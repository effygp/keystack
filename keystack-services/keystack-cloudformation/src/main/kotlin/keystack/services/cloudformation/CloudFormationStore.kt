package keystack.services.cloudformation

import keystack.state.ServiceStore
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant

class CloudFormationStore : ServiceStore() {
    val stacks = ConcurrentHashMap<String, CloudFormationStack>()
}

data class CloudFormationStack(
    val stackName: String,
    val stackId: String,
    var stackStatus: String,
    val creationTime: Instant = Instant.now(),
    var lastUpdatedTime: Instant? = null,
    val resources: MutableList<StackResource> = mutableListOf(),
    val parameters: Map<String, String> = emptyMap(),
    val outputs: Map<String, String> = emptyMap(),
    val template: String
)

data class StackResource(
    val logicalResourceId: String,
    var physicalResourceId: String? = null,
    val resourceType: String,
    var resourceStatus: String,
    val timestamp: Instant = Instant.now(),
    val properties: Map<String, Any?> = emptyMap()
)
