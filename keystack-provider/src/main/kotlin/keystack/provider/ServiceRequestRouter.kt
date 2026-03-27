package keystack.provider

import keystack.gateway.Handler
import keystack.gateway.HandlerChain
import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(ServiceRequestRouter::class.java)

/**
 * Handler that routes AWS service requests to their respective service providers.
 */
class ServiceRequestRouter(private val registry: ServiceRegistry) : Handler {
    override suspend fun handle(chain: HandlerChain, context: RequestContext) {
        val serviceName = context.serviceName
        val operationName = context.operationName

        if (serviceName == null || operationName == null) {
            // Service not identified yet, skip this handler
            chain.next(context)
            return
        }

        val dispatchTable = registry.getDispatchTable(serviceName)
        val dispatcher = dispatchTable[operationName]

        if (dispatcher == null) {
            logger.warn("Operation not implemented: {}.{}", serviceName, operationName)
            // Operation not implemented, set a service exception
            context.serviceException = ServiceException(
                code = "NotImplemented",
                message = "The operation $operationName for service $serviceName is not implemented yet.",
                statusCode = 501
            )
            // We don't stop the chain here, because the response serializer handler needs to serialize the error
            chain.next(context)
            return
        }

        try {
            logger.debug("Dispatching to: {}.{}", serviceName, operationName)
            context.serviceResponse = dispatcher.invoke(context)
        } catch (e: ServiceException) {
            logger.error("Service error during operation: {}.{}", serviceName, operationName, e)
            context.serviceException = e
        } catch (e: Exception) {
            logger.error("Unexpected error during operation: {}.{}", serviceName, operationName, e)
            context.serviceException = ServiceException(
                code = "InternalError",
                message = "An unexpected error occurred during the $operationName operation for service $serviceName.",
                statusCode = 500
            )
        }
        
        // After dispatching (successfully or with exception), continue to response serialization
        chain.next(context)
    }
}
