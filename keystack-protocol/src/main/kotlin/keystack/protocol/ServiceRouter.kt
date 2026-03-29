package keystack.protocol

import keystack.protocol.model.OperationModel
import keystack.protocol.model.ServiceCatalog
import keystack.protocol.model.ServiceModel
import org.slf4j.LoggerFactory
import io.ktor.server.request.*
import io.ktor.http.*

data class ServiceIndicators(
    val signingName: String? = null,
    val target: String? = null,
    val action: String? = null,
    val host: String? = null,
    val path: String? = null
)

object ServiceRouter {
    private val logger = LoggerFactory.getLogger(ServiceRouter::class.java)

    /**
     * Detects the AWS service and operation from the incoming request.
     */
    suspend fun detectService(request: ApplicationRequest): Pair<ServiceModel, OperationModel>? {
        val indicators = extractIndicators(request)
        logger.debug("Detecting service with indicators: {}", indicators)
        
        // Try signing name from Authorization header
        indicators.signingName?.let { signingName ->
            ServiceCatalog.findBySigningName(signingName)?.let { service ->
                val operation = resolveOperation(service, request, indicators)
                if (operation != null) return service to operation
            }
        }

        // Try X-Amz-Target header
        indicators.target?.let { target ->
            val targetPrefix = target.substringBefore(".")
            val operationName = target.substringAfter(".")
            
            ServiceCatalog.findByTargetPrefix(targetPrefix)?.let { service ->
                service.operations.values.find { it.name == operationName || it.name?.equals(operationName, ignoreCase = true) == true }?.let { operation ->
                    return service to operation
                }
            }
        }

        // Try host-based routing
        indicators.host?.let { host ->
            val prefix = host.substringBefore(".")
            ServiceCatalog.findByEndpointPrefix(prefix)?.let { service ->
                val operation = resolveOperation(service, request, indicators)
                if (operation != null) return service to operation
            }
        }

        // Try Action parameter (Query protocol)
        var action = indicators.action
        if (action == null && request.httpMethod == HttpMethod.Post && request.contentType().match(ContentType.Application.FormUrlEncoded)) {
            try {
                val body = request.call.receiveText()
                logger.debug("Body for action extraction: {}", body)
                if (body.contains("Action=")) {
                    action = body.split("&")
                        .find { it.startsWith("Action=") }
                        ?.substringAfter("Action=")
                        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    logger.debug("Extracted action from body: {}", action)
                }
            } catch (e: Exception) {
                logger.error("Error reading body for action extraction", e)
            }
        }

        action?.let { act ->
            ServiceCatalog.getAllServices().forEach { service ->
                service.operations.values.find { it.name == act }?.let { operation ->
                    if (service.metadata.protocol == "query" || service.metadata.protocol == "ec2") {
                        logger.debug("Detected service {} from action {}", service.metadata.serviceFullName, act)
                        return service to operation
                    }
                }
            }
        }

        // Path-based heuristics (S3 bucket paths, SQS queue URLs)
        val path = indicators.path ?: "/"
        val pathParts = path.split("/").filter { it.isNotEmpty() }
        if (pathParts.size >= 2 && pathParts[0].all { it.isDigit() } && pathParts[0].length == 12) {
            ServiceCatalog.getService("sqs")?.let { service ->
                val operation = resolveOperation(service, request, indicators)
                if (operation != null) return service to operation
            }
        }

        return null
    }

    private fun extractIndicators(request: ApplicationRequest): ServiceIndicators {
        val authHeader = request.headers["Authorization"]
        val signingName = authHeader?.let {
            if (it.startsWith("AWS4-")) {
                // AWS4-HMAC-SHA256 Credential=AKID/date/region/SERVICE/aws4_request
                val credentialPart = it.substringAfter("Credential=", "")
                val parts = credentialPart.split("/")
                if (parts.size >= 4) parts[3] else null
            } else null
        }

        val target = request.headers["X-Amz-Target"]
        val action = request.queryParameters["Action"] ?: request.headers["X-Amz-Action"]
        
        return ServiceIndicators(
            signingName = signingName,
            target = target,
            action = action,
            host = request.host(),
            path = request.path()
        )
    }

    private fun resolveOperation(service: ServiceModel, request: ApplicationRequest, indicators: ServiceIndicators): OperationModel? {
        // If target header has it
        indicators.target?.let { target ->
            val operationName = target.substringAfter(".")
            service.operations.values.find { it.name == operationName }?.let { return it }
        }

        // If Action param has it
        indicators.action?.let { action ->
            service.operations.values.find { it.name == action }?.let { return it }
        }

        // Heuristics based on HTTP method and path for Rest-JSON/Rest-XML
        // This part is complex and needs better mapping. 
        // For SQS/DynamoDB (JSON/Query), the above is usually enough.
        
        return null
    }
}
