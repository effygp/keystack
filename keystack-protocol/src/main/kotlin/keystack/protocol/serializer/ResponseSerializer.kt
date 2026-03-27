package keystack.protocol.serializer

import io.ktor.server.response.*
import io.ktor.http.*
import keystack.gateway.ServiceException
import keystack.protocol.model.OperationModel
import keystack.protocol.model.ServiceModel
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

interface ResponseSerializer {
    suspend fun serialize(response: Any, service: ServiceModel, operation: OperationModel, requestId: String, call: io.ktor.server.application.ApplicationCall)
    suspend fun serializeError(exception: ServiceException, service: ServiceModel, operation: OperationModel, requestId: String, call: io.ktor.server.application.ApplicationCall)
}

class JsonSerializer : ResponseSerializer {
    private val mapper: ObjectMapper = jacksonObjectMapper()

    override suspend fun serialize(response: Any, service: ServiceModel, operation: OperationModel, requestId: String, call: io.ktor.server.application.ApplicationCall) {
        call.respondText(mapper.writeValueAsString(response), ContentType.Application.Json, HttpStatusCode.OK)
    }

    override suspend fun serializeError(exception: ServiceException, service: ServiceModel, operation: OperationModel, requestId: String, call: io.ktor.server.application.ApplicationCall) {
        val body = mapOf(
            "__type" to exception.code,
            "message" to exception.message
        )
        call.respondText(mapper.writeValueAsString(body), ContentType.Application.Json, HttpStatusCode.fromValue(exception.statusCode))
    }
}

class QuerySerializer : ResponseSerializer {
    private val mapper: XmlMapper = XmlMapper().apply {
        registerKotlinModule()
    }

    override suspend fun serialize(response: Any, service: ServiceModel, operation: OperationModel, requestId: String, call: io.ktor.server.application.ApplicationCall) {
        // Query protocol wraps result in <OperationNameResponse><OperationNameResult>...</OperationNameResult></OperationNameResponse>
        val rootName = "${operation.name}Response"
        val resultName = "${operation.name}Result"
        
        // This is a simplified XML serialization
        val xml = "<$rootName xmlns=\"${service.metadata.xmlNamespace?.get("uri") ?: ""}\">" +
                  "<$resultName>" +
                  // Need a better way to serialize to XML based on shapes
                  "</$resultName>" +
                  "<ResponseMetadata><RequestId>$requestId</RequestId></ResponseMetadata>" +
                  "</$rootName>"
        
        call.respondText(xml, ContentType.Application.Xml, HttpStatusCode.OK)
    }

    override suspend fun serializeError(exception: ServiceException, service: ServiceModel, operation: OperationModel, requestId: String, call: io.ktor.server.application.ApplicationCall) {
        val xml = "<ErrorResponse>" +
                  "<Error>" +
                  "<Type>${exception.type}</Type>" +
                  "<Code>${exception.code}</Code>" +
                  "<Message>${exception.message}</Message>" +
                  "</Error>" +
                  "<RequestId>$requestId</RequestId>" +
                  "</ErrorResponse>"
        call.respondText(xml, ContentType.Application.Xml, HttpStatusCode.fromValue(exception.statusCode))
    }
}

class RestXmlSerializer : ResponseSerializer {
    private val mapper: XmlMapper = XmlMapper().apply {
        registerKotlinModule()
    }

    override suspend fun serialize(response: Any, service: ServiceModel, operation: OperationModel, requestId: String, call: io.ktor.server.application.ApplicationCall) {
        // S3 REST-XML uses different formats depending on the operation
        // For simplicity in MVP, we'll respond with a basic XML structure
        if (response is String) {
            // Raw body like GetObject
            call.respondText(response, ContentType.Application.Xml, HttpStatusCode.OK)
            return
        }
        
        val xml = mapper.writeValueAsString(response)
        call.respondText(xml, ContentType.Application.Xml, HttpStatusCode.OK)
    }

    override suspend fun serializeError(exception: ServiceException, service: ServiceModel, operation: OperationModel, requestId: String, call: io.ktor.server.application.ApplicationCall) {
        // S3 specific error format
        val xml = "<Error>" +
                  "<Code>${exception.code}</Code>" +
                  "<Message>${exception.message}</Message>" +
                  "<RequestId>$requestId</RequestId>" +
                  "</Error>"
        call.respondText(xml, ContentType.Application.Xml, HttpStatusCode.fromValue(exception.statusCode))
    }
}
