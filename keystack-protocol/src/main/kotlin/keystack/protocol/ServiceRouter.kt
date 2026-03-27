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
     * Reference: localstack/aws/protocol/service_router.py lines 360-493
     */
    fun detectService(request: ApplicationRequest): Pair<ServiceModel, OperationModel>? {
        val indicators = extractIndicators(request)
        
        // 1. Try signing name from Authorization header
        indicators.signingName?.let { signingName ->
            ServiceCatalog.findBySigningName(signingName)?.let { service ->
                val operation = resolveOperation(service, request, indicators)
                if (operation != null) return service to operation
            }
        }

        // 2. Try X-Amz-Target header
        indicators.target?.let { target ->
            val targetPrefix = target.substringBefore(".")
            val operationName = target.substringAfter(".")
            
            ServiceCatalog.findByTargetPrefix(targetPrefix)?.let { service ->
                service.operations.values.find { it.name == operationName || it.name?.equals(operationName, ignoreCase = true) == true }?.let { operation ->
                    return service to operation
                }
            }
        }

        // 3. Try host-based routing
        indicators.host?.let { host ->
            val prefix = host.substringBefore(".")
            ServiceCatalog.findByEndpointPrefix(prefix)?.let { service ->
                val operation = resolveOperation(service, request, indicators)
                if (operation != null) return service to operation
            }
        }

        // 4. Try Action parameter (Query protocol)
        indicators.action?.let { action ->
            // We don't have service yet, search all services for this action? 
            // Better: many services use endpoint prefix in host or specific path
            // For now, let's try to match action across all loaded services if we can't find it otherwise
            ServiceCatalog.getAllServices().forEach { service ->
                service.operations.values.find { it.name == action }?.let { operation ->
                    // Basic heuristic: check if protocol matches
                    if (service.metadata.protocol == "query" || service.metadata.protocol == "ec2") {
                        return service to operation
                    }
                }
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
