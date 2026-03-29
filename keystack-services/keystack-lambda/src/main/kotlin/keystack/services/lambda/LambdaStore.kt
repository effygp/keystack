package keystack.services.lambda

import keystack.state.ServiceStore
import java.util.concurrent.ConcurrentHashMap

class LambdaStore : ServiceStore() {
    val functions = ConcurrentHashMap<String, FunctionConfiguration>()
    // functionName -> qualifier -> FunctionConfiguration
    val versions = ConcurrentHashMap<String, ConcurrentHashMap<String, FunctionConfiguration>>()
    // versionManagerId -> provisionedCount
    val provisionedConfigs = ConcurrentHashMap<String, Int>()
}
