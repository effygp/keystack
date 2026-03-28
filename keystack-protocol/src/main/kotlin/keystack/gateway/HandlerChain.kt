package keystack.gateway

/**
 * A handler in the gateway pipeline.
 */
typealias Handler = suspend (chain: HandlerChain, context: RequestContext) -> Unit

/**
 * Exception handler to catch and serialize errors.
 */
typealias ExceptionHandler = suspend (context: RequestContext, exception: Throwable) -> Unit

/**
 * Pipeline processing for AWS requests.
 * Reference: localstack/aws/app.py lines 17-89
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
            requestHandlers[index++](this, context)
        }
    }

    suspend fun handle(context: RequestContext) {
        try {
            while (!stopped && index < requestHandlers.size) {
                next(context)
            }

            if (!stopped) {
                responseHandlers.forEach { it(this, context) }
            }
        } catch (e: Throwable) {
            exceptionHandlers.forEach { it(context, e) }
        }
    }
}
