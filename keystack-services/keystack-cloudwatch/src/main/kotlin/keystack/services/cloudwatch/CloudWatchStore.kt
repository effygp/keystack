package keystack.services.cloudwatch

import keystack.state.ServiceStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class CloudWatchStore : ServiceStore() {
    val metricData = ConcurrentHashMap<Metric, MutableList<DataPoint>>()
    val alarms = ConcurrentHashMap<String, MetricAlarm>()
    
    fun putDataPoint(metric: Metric, dataPoint: DataPoint) {
        metricData.computeIfAbsent(metric) { CopyOnWriteArrayList() }.add(dataPoint)
    }
}
