package keystack.services.lambda

import keystack.state.ServiceStore
import java.util.concurrent.ConcurrentHashMap

data class FunctionConfiguration(
    val functionName: String,
    val functionArn: String,
    val runtime: String,
    val handler: String,
    val role: String,
    val description: String? = null,
    val timeout: Int = 3,
    val memorySize: Int = 128,
    val lastModified: String,
    val codeSize: Long = 0,
    val environment: Map<String, String>? = null
)

class LambdaStore : ServiceStore() {
    val functions = ConcurrentHashMap<String, FunctionConfiguration>()
}
