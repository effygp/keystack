package keystack.services.lambda.executor

import keystack.services.lambda.FunctionConfiguration
import keystack.services.lambda.InvocationResult

/**
 * Abstract contract for Lambda runtime executors.
 * Implementations manage the lifecycle of a single execution environment
 * (e.g., a Docker container, a local process).
 */
interface RuntimeExecutor {
    /** Unique ID of this executor instance */
    val id: String

    /** The function configuration this executor runs */
    val functionConfig: FunctionConfiguration

    /**
     * Start the execution environment with the given environment variables.
     * Blocks until the environment is ready to receive invocations.
     */
    suspend fun start(envVars: Map<String, String>)

    /**
     * Stop and clean up the execution environment.
     */
    suspend fun stop()

    /**
     * Send an invocation payload to the running environment and return the result.
     */
    suspend fun invoke(payload: Map<String, String>): InvocationResult

    /**
     * Get the IP address or hostname this executor is reachable at.
     */
    fun getAddress(): String

    /**
     * Get the address of Keystack from the executor's perspective
     * (for the container to call back to the host).
     */
    fun getEndpointFromExecutor(): String

    /**
     * Get the callback URL for the runtime API endpoint.
     */
    fun getRuntimeEndpoint(): String

    /**
     * Retrieve logs from the execution environment.
     */
    fun getLogs(): String

    companion object {
        /**
         * Prepare a function version for execution (pull images, extract code, etc.).
         * Called once when a function version is created or updated.
         */
        suspend fun prepareVersion(functionConfig: FunctionConfiguration) {
            // Default no-op; overridden by implementations
        }

        /**
         * Clean up resources for a function version (remove prebuilt images, etc.).
         */
        suspend fun cleanupVersion(functionConfig: FunctionConfiguration) {
            // Default no-op; overridden by implementations
        }
    }
}
