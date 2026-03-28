package keystack.gateway.handlers

import keystack.gateway.Handler
import keystack.gateway.HandlerChain
import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import keystack.protocol.ServiceRouter
import keystack.protocol.model.ServiceModel
import keystack.protocol.parser.JsonRequestParser
import keystack.protocol.parser.QueryRequestParser
import keystack.protocol.serializer.JsonSerializer
import keystack.protocol.serializer.QuerySerializer
import org.slf4j.LoggerFactory

class ServiceDetectionHandler : Handler {
    override suspend fun handle(chain: HandlerChain, context: RequestContext) {
        val result = ServiceRouter.detectService(context.request)
        if (result != null) {
            val (service, operation) = result
            context.serviceName = service.metadata.endpointPrefix
            context.operationName = operation.name
            context.traceContext["serviceModel"] = service
            context.traceContext["operationModel"] = operation
        }
        chain.next(context)
    }
}

class RequestParserHandler : Handler {
    private val jsonParser = JsonRequestParser()
    private val queryParser = QueryRequestParser()
    private val restXmlParser = RestXmlRequestParser()

    override suspend fun handle(chain: HandlerChain, context: RequestContext) {
        val service = context.traceContext["serviceModel"] as? ServiceModel
        val operation = context.traceContext["operationModel"] as? keystack.protocol.model.OperationModel
        
        if (service != null && operation != null) {
            val parser = when (service.metadata.protocol) {
                "json" -> jsonParser
                "query" -> queryParser
                "rest-xml" -> restXmlParser
                else -> null
            }
            
            if (parser != null) {
                try {
                    context.serviceRequest = parser.parse(context.request, service, operation)
                } catch (e: Exception) {
                    context.serviceException = ServiceException("SerializationError", "Failed to parse request: ${e.message}", 400)
                    chain.stop()
                    return
                }
            }
        }
        chain.next(context)
    }
}

class ResponseSerializerHandler : Handler {
    private val jsonSerializer = JsonSerializer()
    private val querySerializer = QuerySerializer()
    private val restXmlSerializer = keystack.protocol.serializer.RestXmlSerializer()

    override suspend fun handle(chain: HandlerChain, context: RequestContext) {
        val service = context.traceContext["serviceModel"] as? ServiceModel
        val operation = context.traceContext["operationModel"] as? keystack.protocol.model.OperationModel
        val call = context.request.call

        if (service != null && operation != null) {
            val serializer = when (service.metadata.protocol) {
                "json" -> jsonSerializer
                "query" -> querySerializer
                "rest-xml" -> restXmlSerializer
                else -> null
            }

            if (serializer != null) {
                if (context.serviceException != null) {
                    serializer.serializeError(context.serviceException!!, service, operation, context.requestId, call)
                } else if (context.serviceResponse != null) {
                    serializer.serialize(context.serviceResponse!!, service, operation, context.requestId, call)
                }
            }
        }
    }
}
