package keystack.services.lambda

import keystack.services.lambda.execution.AssignmentService
import keystack.services.lambda.executor.DockerRuntimeExecutor
import com.github.dockerjava.core.DockerClientBuilder
import org.slf4j.LoggerFactory

class LambdaService {
    private val logger = LoggerFactory.getLogger(LambdaService::class.java)
    private val dockerClient = DockerClientBuilder.getInstance().build()

    // Configuration from environment variables
    private val keepaliveMs = System.getenv("KEYSTACK_LAMBDA_KEEPALIVE_MS")?.toLongOrNull() ?: 600_000L
    private val startupTimeoutMs = System.getenv("KEYSTACK_LAMBDA_STARTUP_TIMEOUT_MS")?.toLongOrNull() ?: 20_000L
    private val maxConcurrentStarts = System.getenv("KEYSTACK_LAMBDA_MAX_CONCURRENT_STARTS")?.toIntOrNull() ?: 16

    val assignmentService = AssignmentService(
        executorFactory = { id, config ->
            DockerRuntimeExecutor(
                id = id,
                functionConfig = config,
                dockerClient = dockerClient
            )
        },
        keepaliveMs = keepaliveMs,
        startupTimeoutMs = startupTimeoutMs,
        maxConcurrentStarts = maxConcurrentStarts
    )

    suspend fun invoke(
        functionConfig: FunctionConfiguration,
        payload: ByteArray,
        invocationType: String = "RequestResponse"
    ): InvocationResult {
        val versionManagerId = "${functionConfig.functionArn}:${functionConfig.version}"

        val invocation = Invocation(
            payload = payload,
            invokedArn = functionConfig.functionArn,
            invocationType = invocationType
        )

        val environment = assignmentService.getEnvironment(
            versionManagerId = versionManagerId,
            functionConfig = functionConfig,
            provisioningType = InitializationType.ON_DEMAND
        )

        return try {
            val result = environment.invoke(invocation)
            environment.release() // Return to pool as READY (warm)
            result
        } catch (e: Exception) {
            logger.error("Invocation failed for {}: {}", functionConfig.functionName, e.message)
            assignmentService.stopEnvironment(environment)
            InvocationResult(
                requestId = invocation.requestId,
                payload = e.message?.toByteArray(),
                isError = true
            )
        }
    }

    suspend fun stop() {
        try {
            assignmentService.stopAll()
        } catch (e: Exception) {
            logger.error("Error stopping assignment service: {}", e.message)
        } finally {
            try {
                dockerClient.close()
            } catch (e: Exception) {
                logger.error("Error closing docker client: {}", e.message)
            }
        }
    }
}
