package keystack.services.cloudwatch

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import keystack.provider.AwsOperation
import keystack.provider.ServiceProvider
import keystack.state.AccountRegionStore
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*

class CloudWatchProvider : ServiceProvider {
    override val serviceName = "cloudwatch"
    private val logger = LoggerFactory.getLogger(CloudWatchProvider::class.java)
    
    private val stores = AccountRegionStore("cloudwatch") { CloudWatchStore() }

    @AwsOperation("PutMetricData")
    fun putMetricData(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val namespace = params["Namespace"] as? String ?: throw ServiceException("MissingParameter", "Namespace is required")
        val store = stores[context.accountId, context.region]
        
        var i = 1
        while (true) {
            val prefix = "MetricData.member.$i"
            val metricName = params["$prefix.MetricName"] as? String ?: break
            
            val dimensions = parseDimensions(params, "$prefix.Dimensions")
            val metric = Metric(namespace, metricName, dimensions)
            
            val timestampStr = params["$prefix.Timestamp"] as? String
            val timestamp = if (timestampStr != null) Instant.parse(timestampStr) else Instant.now()
            
            val value = (params["$prefix.Value"] as? String)?.toDouble()
            val unit = params["$prefix.Unit"] as? String
            
            val statsPrefix = "$prefix.StatisticValues"
            val statisticValues = if (params["$statsPrefix.SampleCount"] != null) {
                StatisticSet(
                    sampleCount = (params["$statsPrefix.SampleCount"] as String).toDouble(),
                    sum = (params["$statsPrefix.Sum"] as String).toDouble(),
                    minimum = (params["$statsPrefix.Minimum"] as String).toDouble(),
                    maximum = (params["$statsPrefix.Maximum"] as String).toDouble()
                )
            } else null
            
            if (value != null || statisticValues != null) {
                val dataPoint = DataPoint(timestamp, value, statisticValues, unit)
                store.putDataPoint(metric, dataPoint)
            }
            
            i++
        }
        
        return emptyMap()
    }

    @AwsOperation("ListMetrics")
    fun listMetrics(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val namespace = params["Namespace"] as? String
        val metricName = params["MetricName"] as? String
        val store = stores[context.accountId, context.region]
        
        val metrics = store.metricData.keys().asSequence()
            .filter { namespace == null || it.namespace == namespace }
            .filter { metricName == null || it.metricName == metricName }
            .map { metric ->
                mapOf(
                    "Namespace" to metric.namespace,
                    "MetricName" to metric.metricName,
                    "Dimensions" to metric.dimensions.map { mapOf("Name" to it.name, "Value" to it.value) }
                )
            }
            .toList()
            
        return mapOf("Metrics" to metrics)
    }

    @AwsOperation("GetMetricData")
    fun getMetricData(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val startTime = Instant.parse(params["StartTime"] as String)
        val endTime = Instant.parse(params["EndTime"] as String)
        val store = stores[context.accountId, context.region]
        
        val results = mutableListOf<Map<String, Any?>>()
        
        var i = 1
        while (true) {
            val prefix = "MetricDataQueries.member.$i"
            val id = params["$prefix.Id"] as? String ?: break
            val metricStatPrefix = "$prefix.MetricStat"
            
            if (params["$metricStatPrefix.Metric.MetricName"] != null) {
                val msNamespace = params["$metricStatPrefix.Metric.Namespace"] as String
                val msMetricName = params["$metricStatPrefix.Metric.MetricName"] as String
                val msDimensions = parseDimensions(params, "$metricStatPrefix.Metric.Dimensions")
                val period = (params["$metricStatPrefix.Period"] as String).toInt()
                val stat = params["$metricStatPrefix.Stat"] as String // e.g., Sum, Average
                
                val metric = Metric(msNamespace, msMetricName, msDimensions)
                val dataPoints = store.metricData[metric] ?: emptyList()
                
                val filtered = dataPoints.filter { it.timestamp.isAfter(startTime) && it.timestamp.isBefore(endTime) }
                
                val timestamps = mutableListOf<String>()
                val values = mutableListOf<Double>()
                
                filtered.forEach { dp ->
                    timestamps.add(DateTimeFormatter.ISO_INSTANT.format(dp.timestamp))
                    values.add(dp.value ?: 0.0)
                }
                
                results.add(mapOf(
                    "Id" to id,
                    "Timestamps" to timestamps,
                    "Values" to values,
                    "StatusCode" to "Complete"
                ))
            }
            i++
        }
        
        return mapOf("MetricDataResults" to results)
    }

    @AwsOperation("PutMetricAlarm")
    fun putMetricAlarm(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val alarmName = params["AlarmName"] as String
        val namespace = params["Namespace"] as String
        val metricName = params["MetricName"] as String
        val period = (params["Period"] as String).toInt()
        val evaluationPeriods = (params["EvaluationPeriods"] as String).toInt()
        val comparisonOperator = params["ComparisonOperator"] as String
        val threshold = (params["Threshold"] as? String)?.toDouble()
        
        val dimensions = parseDimensions(params, "Dimensions")
        
        val alarmArn = "arn:aws:cloudwatch:${context.region}:${context.accountId}:alarm:$alarmName"
        
        val alarm = MetricAlarm(
            alarmName = alarmName,
            alarmArn = alarmArn,
            alarmDescription = params["AlarmDescription"] as? String,
            metricName = metricName,
            namespace = namespace,
            statistic = params["Statistic"] as? String,
            dimensions = dimensions,
            period = period,
            evaluationPeriods = evaluationPeriods,
            threshold = threshold,
            comparisonOperator = comparisonOperator
        )
        
        val store = stores[context.accountId, context.region]
        store.alarms[alarmName] = alarm
        
        return emptyMap()
    }

    @AwsOperation("DescribeAlarms")
    fun describeAlarms(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val store = stores[context.accountId, context.region]
        val alarms = store.alarms.values.map { alarm ->
            mapOf(
                "AlarmName" to alarm.alarmName,
                "AlarmArn" to alarm.alarmArn,
                "AlarmDescription" to alarm.alarmDescription,
                "MetricName" to alarm.metricName,
                "Namespace" to alarm.namespace,
                "Statistic" to alarm.statistic,
                "Dimensions" to alarm.dimensions.map { mapOf("Name" to it.name, "Value" to it.value) },
                "Period" to alarm.period,
                "EvaluationPeriods" to alarm.evaluationPeriods,
                "Threshold" to alarm.threshold,
                "ComparisonOperator" to alarm.comparisonOperator,
                "StateValue" to alarm.stateValue.name,
                "StateReason" to alarm.stateReason,
                "StateUpdatedTimestamp" to DateTimeFormatter.ISO_INSTANT.format(alarm.stateUpdatedTimestamp)
            )
        }
        
        return mapOf("MetricAlarms" to alarms)
    }
}
