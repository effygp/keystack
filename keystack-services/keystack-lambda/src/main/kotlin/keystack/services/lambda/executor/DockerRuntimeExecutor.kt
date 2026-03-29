package keystack.services.lambda.executor

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.model.*
import keystack.services.lambda.FunctionConfiguration
import keystack.services.lambda.InvocationResult
import keystack.services.lambda.execution.ExecutorEndpoint
import keystack.services.lambda.execution.ExecutorRouter
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Concrete implementation of RuntimeExecutor that uses Docker containers.
 */
class DockerRuntimeExecutor(
    override val id: String,
    override val functionConfig: FunctionConfiguration,
    private val dockerClient: DockerClient,
    private val keystackHostAddress: String = "host.docker.internal",
    private val keystackPort: Int = 4566
) : RuntimeExecutor {

    private val logger = LoggerFactory.getLogger(DockerRuntimeExecutor::class.java)
    private val containerName = generateContainerName()
    private var hostPort: Int? = null
    private val executorEndpoint = ExecutorEndpoint(id)
    private val objectMapper = ObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    companion object {
        private val pulledImages = ConcurrentHashMap.newKeySet<String>()
        private val imageLocks = ConcurrentHashMap<String, ReentrantLock>()

        private fun getImageLock(imageName: String): ReentrantLock {
            return imageLocks.getOrPut(imageName) { ReentrantLock() }
        }
    }

    override suspend fun start(envVars: Map<String, String>) {
        // Register the executor endpoint for callbacks
        ExecutorRouter.register(id, executorEndpoint)

        // Resolve and pull image
        val imageName = RuntimeImageResolver.getImageForRuntime(functionConfig.runtime)
        ensureImagePulled(imageName)

        val isHotReloading = functionConfig.code?.isHotReloading == true
        val codePath = if (isHotReloading) {
            functionConfig.code?.hotReloadPath!!
        } else {
            functionConfig.code?.getUnzippedCodeLocation()?.toAbsolutePath()?.toString()
                ?: throw IllegalStateException("No code path for function ${functionConfig.functionName}")
        }

        val containerEnvVars = envVars.toMutableMap()
        containerEnvVars["_HANDLER"] = functionConfig.handler
        containerEnvVars["AWS_LAMBDA_FUNCTION_HANDLER"] = functionConfig.handler
        if (isHotReloading) {
            containerEnvVars["KEYSTACK_HOT_RELOADING_PATHS"] = "/var/task"
        }

        logger.debug(
            "Creating container {} for function {} with code path {} (hotReloading={})",
            containerName, functionConfig.functionName, codePath, isHotReloading
        )

        val exposedPort = ExposedPort.tcp(8080)
        val createCmd = dockerClient.createContainerCmd(imageName)
            .withName(containerName)
            .withEnv(containerEnvVars.map { "${it.key}=${it.value}" })
            .withExposedPorts(exposedPort)
            .withHostConfig(
                HostConfig.newHostConfig()
                    .withBinds(Bind(codePath, Volume("/var/task"), AccessMode.ro))
                    .withExtraHosts("host.docker.internal:host-gateway")
                    .withPortBindings(PortBinding(Ports.Binding.bindPort(0), exposedPort))
            )
            .withCmd(functionConfig.handler)

        val container = createCmd.exec()

        dockerClient.startContainerCmd(container.id).exec()

        val inspectResp = dockerClient.inspectContainerCmd(container.id).exec()
        val binding = inspectResp.networkSettings.ports.bindings[exposedPort]?.firstOrNull()
            ?: throw IllegalStateException("Container port 8080 not bound to host")
        hostPort = binding.hostPortSpec.toInt()

        executorEndpoint.containerAddress = "127.0.0.1"
        executorEndpoint.containerPort = hostPort!!

        waitForContainerReady()

        logger.info(
            "Started container {} for function {} (image: {}, port: {})",
            containerName, functionConfig.functionName, imageName, hostPort
        )
    }    override suspend fun stop() {
        try {
            dockerClient.stopContainerCmd(containerName).withTimeout(5).exec()
            dockerClient.removeContainerCmd(containerName).exec()
        } catch (e: Exception) {
            logger.debug("Error stopping container {}: {}", containerName, e.message)
        } finally {
            ExecutorRouter.unregister(id)
            executorEndpoint.shutdown()
        }
    }

    override suspend fun invoke(payload: Map<String, String>): InvocationResult {
        val requestId = payload["invoke-id"] ?: error("Missing invoke-id")
        val timeoutMs = (functionConfig.timeout * 1000).toLong()

        // Send invocation and handle response synchronously
        val port = hostPort ?: throw IllegalStateException("Container port unknown")
        // Standard Lambda RIE endpoint on host
        val url = "http://127.0.0.1:$port/2015-03-31/functions/function/invocations"
        
        // Extract the actual payload string
        val payloadData = payload["payload"] ?: "{}"
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payloadData))
            .timeout(Duration.ofMillis(timeoutMs))
            .build()

        try {
            logger.debug("Sending invocation request {} to container {} at {}", requestId, containerName, url)
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            val isError = response.statusCode() >= 400
            
            // Standard Lambda RIE returns the Request ID in the Lambda-Runtime-Aws-Request-Id header
            val containerRequestId = response.headers().firstValue("Lambda-Runtime-Aws-Request-Id").orElse(requestId)
            
            logger.debug("Received response for {} (container ID: {}) from container {}: status={}", 
                requestId, containerRequestId, containerName, response.statusCode())
            
            // Manually trigger our own endpoint instance
            if (isError) {
                executorEndpoint.onInvocationError(containerRequestId, response.body(), null)
            } else {
                executorEndpoint.onInvocationResponse(containerRequestId, response.body(), null)
            }
            
            // If the request IDs differed, also trigger the original one to avoid hangs
            if (containerRequestId != requestId) {
                if (isError) {
                    executorEndpoint.onInvocationError(requestId, response.body(), null)
                } else {
                    executorEndpoint.onInvocationResponse(requestId, response.body(), null)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to invoke container {} for request {}: {}", containerName, requestId, e.message, e)
            executorEndpoint.onInvocationError(requestId, (e.message ?: "Unknown error").toByteArray(), null)
        }

        // Wait for the result (which we just triggered above)
        return executorEndpoint.waitForResult(requestId, timeoutMs)
    }

    override fun getAddress(): String =
        "localhost"

    override fun getEndpointFromExecutor(): String = keystackHostAddress

    override fun getRuntimeEndpoint(): String =
        "http://$keystackHostAddress:$keystackPort/_keystack_lambda/$id"

    override fun getLogs(): String {
        val logs = StringBuilder()
        try {
            dockerClient.logContainerCmd(containerName)
                .withStdOut(true)
                .withStdErr(true)
                .withTail(100)
                .exec(object : ResultCallback.Adapter<Frame>() {
                    override fun onNext(frame: Frame) {
                        logs.append(String(frame.payload))
                    }
                }).awaitCompletion(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logs.append("Error retrieving logs: ${e.message}")
        }
        return logs.toString()
    }

    private fun generateContainerName(): String {
        val sanitized = functionConfig.functionName.lowercase().replace("_", "-").replace("$", "LATEST")
        return "keystack-lambda-$sanitized-$id"
    }

    private fun ensureImagePulled(imageName: String) {
        if (pulledImages.contains(imageName)) return

        getImageLock(imageName).withLock {
            if (pulledImages.contains(imageName)) return

            logger.info("Pulling image: {}", imageName)
            dockerClient.pullImageCmd(imageName)
                .exec(PullImageResultCallback())
                .awaitCompletion(5, TimeUnit.MINUTES)
            
            pulledImages.add(imageName)
        }
    }

    private suspend fun waitForContainerReady() {
        val port = hostPort ?: throw IllegalStateException("Container port unknown")
        val url = "http://127.0.0.1:$port/2018-06-01/ping"
        
        var attempts = 0
        val maxAttempts = 50
        var delayMs = 100L

        while (attempts < maxAttempts) {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofMillis(500))
                    .build()
                
                val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
                if (response.statusCode() == 200 || response.statusCode() == 404) {
                    return
                }
            } catch (e: Exception) {
                // Ignore and retry
            }
            
            delay(delayMs)
            attempts++
            delayMs = (delayMs * 1.5).toLong().coerceAtMost(1000L)
        }
        
        // Fallback: check if port is open
        try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000)
                return
            }
        } catch (e: Exception) {
            throw IllegalStateException("Container $containerName failed to become ready after $maxAttempts attempts")
        }
    }
}
