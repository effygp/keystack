package keystack.gateway

import io.ktor.server.request.*
import java.util.*

/**
 * Standard AWS Service Exception
 */
open class ServiceException(
    val code: String,
    override val message: String,
    val statusCode: Int = 400,
    val type: String = "Sender"
) : RuntimeException(message)

/**
 * Per-request context object.
 */
data class RequestContext(
    val request: ApplicationRequest,
    var serviceName: String? = null,
    var operationName: String? = null,
    var region: String = "us-east-1",
    var accountId: String = "000000000000",
    var partition: String = "aws",
    var protocol: String? = null,
    val requestId: String = UUID.randomUUID().toString(),
    var serviceRequest: Map<String, Any?>? = null,
    var serviceResponse: Any? = null,
    var serviceException: ServiceException? = null,
    var isInternalCall: Boolean = false,
    val traceContext: MutableMap<String, Any> = mutableMapOf()
)
