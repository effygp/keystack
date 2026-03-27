package keystack.services.sns

import keystack.state.ServiceStore
import java.util.concurrent.ConcurrentHashMap

data class SnsTopic(
    val arn: String,
    val name: String,
    val subscriptions: MutableList<SnsSubscription> = mutableListOf()
)

data class SnsSubscription(
    val arn: String,
    val topicArn: String,
    val protocol: String,
    val endpoint: String
)

class SnsStore : ServiceStore() {
    val topics = ConcurrentHashMap<String, SnsTopic>()
}
