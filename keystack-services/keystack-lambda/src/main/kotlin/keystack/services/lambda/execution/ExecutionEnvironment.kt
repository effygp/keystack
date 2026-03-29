package keystack.services.lambda.execution

import keystack.services.lambda.*
import keystack.services.lambda.executor.RuntimeExecutor
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class InvalidStatusException(message: String) : Exception(message)
class EnvironmentStartupTimeoutException(message: String) : Exception(message)

class ExecutionEnvironment(
    val id: String,
    val functionConfig: FunctionConfiguration,
    val initializationType: InitializationType,
    private val runtimeExecutor: RuntimeExecutor,
    private val onTimeout: (versionManagerId: String, environmentId: String) -> Unit,
    val versionManagerId: String,
    private val startupTimeoutMs: Long = 20_000L,
    private val keepaliveMs: Long = 600_000L
) {
    private val logger = LoggerFactory.getLogger(ExecutionEnvironment::class.java)
    private val statusLock = ReentrantLock()

    var status: RuntimeStatus = RuntimeStatus.INACTIVE
        private set
    var lastReturned: Instant = Instant.MIN
        private set

    private var startupJob: Job? = null
    private var keepaliveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // --- Lifecycle ---

    suspend fun start() {
        statusLock.withLock {
            if (status != RuntimeStatus.INACTIVE) {
                throw InvalidStatusException(
                    "Environment $id can only be started when INACTIVE. Current: $status"
                )
            }
            status = RuntimeStatus.STARTING
        }

        // Start a coroutine-based startup timeout
        startupJob = scope.launch {
            delay(startupTimeoutMs)
            timedOut()
        }

        try {
            val envVars = buildEnvironmentVariables()
            runtimeExecutor.start(envVars)

            statusLock.withLock {
                status = RuntimeStatus.READY
            }
        } catch (e: Exception) {
            if (status == RuntimeStatus.STARTUP_TIMED_OUT) {
                throw EnvironmentStartupTimeoutException(
                    "Environment $id timed out during startup."
                )
            }
            errored()
            throw e
        } finally {
            startupJob?.cancel()
            startupJob = null
        }
    }

    suspend fun stop() {
        statusLock.withLock {
            if (status in listOf(RuntimeStatus.INACTIVE, RuntimeStatus.STOPPED)) {
                throw InvalidStatusException(
                    "Environment $id cannot be stopped when INACTIVE or STOPPED. Current: $status"
                )
            }
            status = RuntimeStatus.STOPPED
        }
        runtimeExecutor.stop()
        keepaliveJob?.cancel()
        scope.cancel()
    }

    // --- Reserve / Release (the core of warm container reuse) ---

    fun reserve() {
        statusLock.withLock {
            if (status != RuntimeStatus.READY) {
                throw InvalidStatusException(
                    "Environment $id can only be reserved when READY. Current: $status"
                )
            }
            status = RuntimeStatus.INVOKING
        }
        keepaliveJob?.cancel()
    }

    fun release() {
        lastReturned = Instant.now()
        statusLock.withLock {
            if (status != RuntimeStatus.INVOKING) {
                throw InvalidStatusException(
                    "Environment $id can only be released when INVOKING. Current: $status"
                )
            }
            status = RuntimeStatus.READY
        }

        // Start keepalive timer — if no new invocation arrives, tear down
        if (initializationType == InitializationType.ON_DEMAND) {
            keepaliveJob = scope.launch {
                delay(keepaliveMs)
                keepalivePassed()
            }
        }
        // Provisioned concurrency environments never time out
    }

    // --- Invocation ---

    suspend fun invoke(invocation: Invocation): InvocationResult {
        assert(status == RuntimeStatus.INVOKING)
        val invokePayload = mapOf(
            "invoke-id" to invocation.requestId,
            "invoked-function-arn" to invocation.invokedArn,
            "payload" to String(invocation.payload)
        )
        return runtimeExecutor.invoke(invokePayload)
    }

    // --- Timeout handlers ---

    private fun keepalivePassed() {
        logger.debug("Environment {} keepalive expired. Stopping.", id)
        statusLock.withLock {
            if (status != RuntimeStatus.READY) {
                logger.debug("Keepalive passed but status is {}. Aborting.", status)
                return
            }
            status = RuntimeStatus.TIMING_OUT
        }
        runBlocking { stop() }
        onTimeout(versionManagerId, id)
    }

    private fun timedOut() {
        logger.warn("Environment {} timed out during startup.", id)
        statusLock.withLock {
            if (status != RuntimeStatus.STARTING) return
            status = RuntimeStatus.STARTUP_TIMED_OUT
        }
        runBlocking { runtimeExecutor.stop() }
    }

    private fun errored() {
        statusLock.withLock {
            if (status != RuntimeStatus.STARTING) return
            status = RuntimeStatus.STARTUP_FAILED
        }
        runBlocking { runtimeExecutor.stop() }
    }

    // --- Environment variable construction ---

    private fun buildEnvironmentVariables(): Map<String, String> {
        val vars = mutableMapOf(
            "AWS_DEFAULT_REGION" to "us-east-1", // Use context region if available
            "AWS_REGION" to "us-east-1",
            "AWS_LAMBDA_FUNCTION_NAME" to functionConfig.functionName,
            "AWS_LAMBDA_FUNCTION_MEMORY_SIZE" to functionConfig.memorySize.toString(),
            "AWS_LAMBDA_FUNCTION_VERSION" to functionConfig.version,
            "AWS_LAMBDA_FUNCTION_TIMEOUT" to functionConfig.timeout.toString(),
            "AWS_LAMBDA_INITIALIZATION_TYPE" to initializationType.value,
            "AWS_LAMBDA_LOG_GROUP_NAME" to "/aws/lambda/${functionConfig.functionName}",
            "AWS_LAMBDA_LOG_STREAM_NAME" to "${java.time.LocalDate.now()}/[\$LATEST]${id}",
            "LAMBDA_TASK_ROOT" to "/var/task",
            "LAMBDA_RUNTIME_DIR" to "/var/runtime",
            "AWS_ENDPOINT_URL" to "http://host.docker.internal:4566",
            "TZ" to ":UTC",
            // Keystack-specific
            "KEYSTACK_RUNTIME_ID" to id,
            "KEYSTACK_RUNTIME_ENDPOINT" to runtimeExecutor.getRuntimeEndpoint()
        )
        if (functionConfig.handler.isNotBlank()) {
            vars["_HANDLER"] = functionConfig.handler
        }
        if (functionConfig.runtime.isNotBlank()) {
            vars["AWS_EXECUTION_ENV"] = "AWS_Lambda_rapid"
        }
        // User-defined env vars override everything
        functionConfig.environment?.let { vars.putAll(it) }
        return vars
    }

    fun getLogs(): String = runtimeExecutor.getLogs()
}
