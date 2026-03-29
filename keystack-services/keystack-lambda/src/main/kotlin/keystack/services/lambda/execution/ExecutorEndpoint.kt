package keystack.services.lambda.execution

import keystack.services.lambda.InvocationResult
import kotlinx.coroutines.CompletableDeferred
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the HTTP callback endpoint for Lambda container → Keystack communication.
 */
class ExecutorEndpoint(val executorId: String) {
    private val logger = LoggerFactory.getLogger(ExecutorEndpoint::class.java)

    // Maps requestId -> deferred result (the invoking coroutine awaits this)
    private val pendingInvocations = ConcurrentHashMap<String, CompletableDeferred<InvocationResult>>()
    // Maps requestId -> already received result (to handle race conditions)
    private val receivedResults = ConcurrentHashMap<String, InvocationResult>()

    var containerAddress: String? = null
    var containerPort: Int = 9563 // Default RIE invocation port

    /**
     * Called by the invoking code to wait for a container result.
     */
    suspend fun waitForResult(requestId: String, timeoutMs: Long): InvocationResult {
        // Check if we already have the result (race condition fix)
        val earlyResult = receivedResults.remove(requestId)
        if (earlyResult != null) {
            return earlyResult
        }

        val deferred = CompletableDeferred<InvocationResult>()
        pendingInvocations[requestId] = deferred
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                deferred.await()
            }
        } finally {
            pendingInvocations.remove(requestId)
        }
    }

    /** Called when container POSTs invocation response */
    fun onInvocationResponse(requestId: String, payload: ByteArray, logs: String?) {
        val result = InvocationResult(
            requestId = requestId,
            payload = payload,
            isError = false,
            logs = logs
        )
        val deferred = pendingInvocations[requestId]
        if (deferred != null) {
            deferred.complete(result)
        } else {
            receivedResults[requestId] = result
        }
    }

    /** Called when container POSTs invocation error */
    fun onInvocationError(requestId: String, payload: ByteArray, logs: String?) {
        val result = InvocationResult(
            requestId = requestId,
            payload = payload,
            isError = true,
            logs = logs
        )
        val deferred = pendingInvocations[requestId]
        if (deferred != null) {
            deferred.complete(result)
        } else {
            receivedResults[requestId] = result
        }
    }

    /** Called when container POSTs logs */
    fun onLogs(requestId: String, logs: String) {
        logger.debug("Received logs for requestId {}: {}", requestId, logs)
    }

    /** Called when container is ready */
    fun onStatusReady() {
        logger.info("Executor {} is ready", executorId)
    }

    /** Called when container reports an error */
    fun onStatusError(error: String) {
        logger.error("Executor {} reported error: {}", executorId, error)
    }

    fun shutdown() {
        pendingInvocations.values.forEach {
            it.cancel(java.util.concurrent.CancellationException("Executor endpoint shutting down"))
        }
        pendingInvocations.clear()
    }
}

/**
 * Global router that maps executor IDs to their endpoints.
 */
object ExecutorRouter {
    private val endpoints = ConcurrentHashMap<String, ExecutorEndpoint>()

    fun register(executorId: String, endpoint: ExecutorEndpoint) {
        endpoints[executorId] = endpoint
    }

    fun unregister(executorId: String) {
        endpoints.remove(executorId)
    }

    fun getEndpoint(executorId: String): ExecutorEndpoint? = endpoints[executorId]
}
