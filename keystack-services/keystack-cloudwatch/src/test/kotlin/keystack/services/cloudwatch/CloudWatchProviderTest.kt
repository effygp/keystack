package keystack.services.cloudwatch

import keystack.gateway.RequestContext
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.time.Instant

class CloudWatchProviderTest {
    private lateinit var provider: CloudWatchProvider
    private val context = RequestContext(
        request = mockk(relaxed = true),
        accountId = "123456789012",
        region = "us-east-1"
    )

    @BeforeTest
    fun setup() {
        provider = CloudWatchProvider()
    }

    @Test
    fun `test metrics operations`() = runBlocking {
        val namespace = "TestNamespace"
        val metricName = "TestMetric"
        
        // 1. Put Metric Data
        provider.putMetricData(context, mapOf(
            "Namespace" to namespace,
            "MetricData.member.1.MetricName" to metricName,
            "MetricData.member.1.Value" to "10.5",
            "MetricData.member.1.Timestamp" to Instant.now().toString()
        ))
        
        // 2. List Metrics
        val listResult = provider.listMetrics(context, mapOf("Namespace" to namespace))
        val metrics = listResult["Metrics"] as List<Map<String, Any?>>
        assertTrue(metrics.any { it["MetricName"] == metricName })
        
        // 3. Get Metric Data
        val getResult = provider.getMetricData(context, mapOf(
            "StartTime" to Instant.now().minusSeconds(3600).toString(),
            "EndTime" to Instant.now().plusSeconds(3600).toString(),
            "MetricDataQueries.member.1.Id" to "q1",
            "MetricDataQueries.member.1.MetricStat.Metric.Namespace" to namespace,
            "MetricDataQueries.member.1.MetricStat.Metric.MetricName" to metricName,
            "MetricDataQueries.member.1.MetricStat.Period" to "60",
            "MetricDataQueries.member.1.MetricStat.Stat" to "Sum"
        ))
        val results = getResult["MetricDataResults"] as List<Map<String, Any?>>
        assertEquals(1, results.size)
        assertEquals("q1", results[0]["Id"])
    }

    @Test
    fun `test alarm lifecycle`() = runBlocking {
        val alarmName = "test-alarm"
        
        // 1. Put Metric Alarm
        provider.putMetricAlarm(context, mapOf(
            "AlarmName" to alarmName,
            "Namespace" to "AWS/EC2",
            "MetricName" to "CPUUtilization",
            "Period" to "60",
            "EvaluationPeriods" to "1",
            "Threshold" to "80.0",
            "ComparisonOperator" to "GreaterThanThreshold"
        ))
        
        // 2. Describe Alarms
        val describeResult = provider.describeAlarms(context, emptyMap())
        val alarms = describeResult["MetricAlarms"] as List<Map<String, Any?>>
        assertTrue(alarms.any { it["AlarmName"] == alarmName })
    }
}
