package keystack.services.cloudwatch

import java.time.Instant
import java.util.UUID

data class Dimension(val name: String, val value: String)

data class Metric(
    val namespace: String,
    val metricName: String,
    val dimensions: Set<Dimension> = emptySet()
)

data class DataPoint(
    val timestamp: Instant,
    val value: Double? = null,
    val statisticValues: StatisticSet? = null,
    val unit: String? = null
)

data class StatisticSet(
    val sampleCount: Double,
    val sum: Double,
    val minimum: Double,
    val maximum: Double
)

enum class AlarmState {
    OK, ALARM, INSUFFICIENT_DATA
}

data class MetricAlarm(
    val alarmName: String,
    val alarmArn: String,
    val alarmDescription: String? = null,
    val metricName: String,
    val namespace: String,
    val statistic: String? = null,
    val extendedStatistic: String? = null,
    val dimensions: Set<Dimension> = emptySet(),
    val period: Int,
    val unit: String? = null,
    val evaluationPeriods: Int,
    val threshold: Double? = null,
    val comparisonOperator: String,
    var stateValue: AlarmState = AlarmState.INSUFFICIENT_DATA,
    var stateReason: String? = null,
    var stateUpdatedTimestamp: Instant = Instant.now()
)

fun parseDimensions(params: Map<String, Any?>, prefix: String): Set<Dimension> {
    val dimensions = mutableSetOf<Dimension>()
    var i = 1
    while (true) {
        val name = params["$prefix.member.$i.Name"] as? String ?: break
        val value = params["$prefix.member.$i.Value"] as? String ?: break
        dimensions.add(Dimension(name, value))
        i++
    }
    // Also support non-member flat structure if needed, but Query protocol usually uses .member.
    return dimensions
}
