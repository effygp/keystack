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
 * Reference: localstack/aws/api/core.py lines 78-151
 */
data class RequestContext(
    val request: ApplicationRequest,     // Ktor request wrapper
    var serviceName: String? = null,     // e.g., "sqs", "s3"
    var operationName: String? = null,   // e.g., "CreateQueue", "PutObject"
    var region: String = "us-east-1",
    var accountId: String = "000000000000",
    var partition: String = "aws",
    val requestId: String = UUID.randomUUID().toString(),
    var serviceRequest: Map<String, Any?>? = null,   // Parsed parameters
    var serviceResponse: Any? = null,                 // Provider response
    var serviceException: ServiceException? = null,   // Error from provider
    var isInternalCall: Boolean = false,
    val traceContext: MutableMap<String, Any> = mutableMapOf()
)
