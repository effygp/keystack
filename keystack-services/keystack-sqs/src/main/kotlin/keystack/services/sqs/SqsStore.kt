package keystack.services.sqs

import keystack.state.ServiceStore
import java.util.concurrent.ConcurrentHashMap

class SqsStore : ServiceStore() {
    val queues = ConcurrentHashMap<String, SqsQueue>()
}
