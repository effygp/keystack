package keystack.gateway

/**
 * A handler in the gateway pipeline.
 */
interface Handler {
    suspend fun handle(chain: HandlerChain, context: RequestContext)
}

/**
 * Exception handler to catch and serialize errors.
 */
typealias ExceptionHandler = suspend (context: RequestContext, exception: Throwable) -> Unit

/**
 * Pipeline processing for AWS requests.
 */
class HandlerChain(
    private val requestHandlers: List<Handler>,
    private val responseHandlers: List<Handler>,
    private val exceptionHandlers: List<ExceptionHandler>
) {
    private var stopped = false
    private var index = 0

    fun stop() {
        stopped = true
    }

    suspend fun next(context: RequestContext) {
        if (!stopped && index < requestHandlers.size) {
            requestHandlers[index++].handle(this, context)
        }
    }

    suspend fun handle(context: RequestContext) {
        try {
            next(context)

            if (!stopped) {
                responseHandlers.forEach { it.handle(this, context) }
            }
        } catch (e: Throwable) {
            exceptionHandlers.forEach { it(context, e) }
        }
    }
}
