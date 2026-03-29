package keystack.services.lambda.execution

import keystack.services.lambda.*
import keystack.services.lambda.executor.RuntimeExecutor
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

class AssignmentException(message: String) : Exception(message)

class AssignmentService(
    /** Factory that creates RuntimeExecutor instances for a given function config */
    private val executorFactory: (id: String, config: FunctionConfiguration) -> RuntimeExecutor,
    /** Max concurrent on-demand container starts (prevents fd exhaustion) */
    private val maxConcurrentStarts: Int = 16,
    private val keepaliveMs: Long = 600_000L,
    private val startupTimeoutMs: Long = 120_000L
) {
    private val logger = LoggerFactory.getLogger(AssignmentService::class.java)

    // versionManagerId → (environmentId → ExecutionEnvironment)
    val environments = ConcurrentHashMap<String, ConcurrentHashMap<String, ExecutionEnvironment>>()

    private val onDemandStartSemaphore = Semaphore(maxConcurrentStarts)

    /**
     * Get an execution environment for the given function version.
     * Tries to reuse a warm container first; creates a new one if none available.
     *
     * Returns the environment in INVOKING state. Caller MUST call release() or stop
     * the environment when done.
     */
    suspend fun getEnvironment(
        versionManagerId: String,
        functionConfig: FunctionConfiguration,
        provisioningType: InitializationType
    ): ExecutionEnvironment {
        val versionEnvs = environments.getOrPut(versionManagerId) { ConcurrentHashMap() }

        // Try to reserve an existing warm container
        val applicableEnvs = versionEnvs.values
            .filter { it.initializationType == provisioningType }
            .toList() // Snapshot to avoid ConcurrentModificationException

        for (env in applicableEnvs) {
            try {
                env.reserve()
                logger.info("WARM START: function={} envId={} idleMs={}",
                    functionConfig.functionName, env.id,
                    Duration.between(env.lastReturned, Instant.now()).toMillis())
                return env // WARM HIT — no cold start!
            } catch (_: InvalidStatusException) {
                // Environment not in READY state, try next
            }
        }

        // No warm container available — cold start
        if (provisioningType == InitializationType.PROVISIONED_CONCURRENCY) {
            throw AssignmentException(
                "No provisioned concurrency environment available for ${functionConfig.functionName}"
            )
        }

        onDemandStartSemaphore.acquire()
        try {
            val environment = startEnvironment(versionManagerId, functionConfig)
            versionEnvs[environment.id] = environment
            environment.reserve()
            logger.info("COLD START: function={} envId={} runtime={}",
                functionConfig.functionName, environment.id, functionConfig.runtime)
            return environment
        } finally {
            onDemandStartSemaphore.release()
        }
    }

    /**
     * Creates and starts a new ExecutionEnvironment (cold start).
     */
    private suspend fun startEnvironment(
        versionManagerId: String,
        functionConfig: FunctionConfiguration
    ): ExecutionEnvironment {
        val envId = generateRuntimeId()
        val executor = executorFactory(envId, functionConfig)

        val environment = ExecutionEnvironment(
            id = envId,
            functionConfig = functionConfig,
            initializationType = InitializationType.ON_DEMAND,
            runtimeExecutor = executor,
            onTimeout = ::onTimeout,
            versionManagerId = versionManagerId,
            startupTimeoutMs = startupTimeoutMs,
            keepaliveMs = keepaliveMs
        )

        try {
            environment.start()
        } catch (e: Exception) {
            throw AssignmentException("Could not start environment: ${e.message}")
        }
        return environment
    }

    /** Callback: remove environment from pool after keepalive expires */
    private fun onTimeout(versionManagerId: String, environmentId: String) {
        environments[versionManagerId]?.remove(environmentId)
        logger.debug("Removed timed-out environment {}", environmentId)
    }

    /** Stop a specific environment and remove it from the pool */
    suspend fun stopEnvironment(environment: ExecutionEnvironment) {
        try {
            environment.stop()
        } catch (e: Exception) {
            logger.debug("Error stopping environment {}: {}", environment.id, e.message)
        }
        environments[environment.versionManagerId]?.remove(environment.id)
    }

    /** Stop all environments for a given version manager (e.g., on function deletion) */
    suspend fun stopEnvironmentsForVersion(versionManagerId: String) {
        val envs = environments.remove(versionManagerId) ?: return
        envs.values.toList().forEach { stopEnvironment(it) }
    }

    /** Stop everything */
    suspend fun stopAll() {
        environments.keys.toList().forEach { stopEnvironmentsForVersion(it) }
    }

    /**
     * Scale provisioned concurrency for a given function version.
     * Pre-warms targetCount containers.
     */
    suspend fun scaleProvisionedConcurrency(
        versionManagerId: String,
        functionConfig: FunctionConfiguration,
        targetCount: Int
    ) {
        val versionEnvs = environments.getOrPut(versionManagerId) { ConcurrentHashMap() }
        
        // Get current provisioned count
        val currentProvisioned = versionEnvs.values.count { it.initializationType == InitializationType.PROVISIONED_CONCURRENCY }
        
        if (targetCount > currentProvisioned) {
            val toStart = targetCount - currentProvisioned
            logger.info("Scaling up provisioned concurrency for {}: current={}, target={}", versionManagerId, currentProvisioned, targetCount)
            
            coroutineScope {
                repeat(toStart) {
                    launch {
                        val envId = generateRuntimeId()
                        val executor = executorFactory(envId, functionConfig)
                        val env = ExecutionEnvironment(
                            id = envId,
                            functionConfig = functionConfig,
                            initializationType = InitializationType.PROVISIONED_CONCURRENCY,
                            runtimeExecutor = executor,
                            onTimeout = ::onTimeout,
                            versionManagerId = versionManagerId,
                            startupTimeoutMs = startupTimeoutMs,
                            keepaliveMs = keepaliveMs
                        )
                        try {
                            env.start()
                            versionEnvs[env.id] = env
                        } catch (e: Exception) {
                            logger.error("Failed to start provisioned environment for {}: {}", versionManagerId, e.message)
                        }
                    }
                }
            }
        } else if (targetCount < currentProvisioned) {
            val toStop = currentProvisioned - targetCount
            logger.info("Scaling down provisioned concurrency for {}: current={}, target={}", versionManagerId, currentProvisioned, targetCount)
            
            val provisionedEnvs = versionEnvs.values
                .filter { it.initializationType == InitializationType.PROVISIONED_CONCURRENCY }
                .take(toStop)
            
            provisionedEnvs.forEach { stopEnvironment(it) }
        }
    }

    private fun generateRuntimeId(): String {
        return (1..32).map { "0123456789abcdef".random() }.joinToString("")
    }
}
