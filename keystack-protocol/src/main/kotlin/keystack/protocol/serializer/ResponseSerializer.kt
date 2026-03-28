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
        val rootName = "${operation.name}Response"
        val resultName = "${operation.name}Result"
        val xmlNamespace = service.metadata.xmlNamespace?.get("uri") ?: ""
        
        val xmlBuilder = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        xmlBuilder.append("<$rootName xmlns=\"$xmlNamespace\">\n")
        xmlBuilder.append("  <$resultName>\n")
        
        fun serializeValue(value: Any?, builder: StringBuilder, indent: String) {
            when (value) {
                is Map<*, *> -> {
                    value.forEach { (k, v) ->
                        builder.append("$indent  <$k>")
                        if (v is Map<*, *> || v is List<*>) {
                            builder.append("\n")
                            serializeValue(v, builder, "$indent  ")
                            builder.append("$indent  ")
                        } else {
                            builder.append(v ?: "")
                        }
                        builder.append("</$k>\n")
                    }
                }
                is List<*> -> {
                    value.forEach { item ->
                        builder.append("$indent  <member>\n")
                        serializeValue(item, builder, "$indent  ")
                        builder.append("$indent  </member>\n")
                    }
                }
                else -> builder.append(value ?: "")
            }
        }

        if (response is Map<*, *>) {
            serializeValue(response, xmlBuilder, "  ")
        }
        
        xmlBuilder.append("  </$resultName>\n")
        xmlBuilder.append("  <ResponseMetadata>\n")
        xmlBuilder.append("    <RequestId>$requestId</RequestId>\n")
        xmlBuilder.append("  </ResponseMetadata>\n")
        xmlBuilder.append("</$rootName>")
        
        call.respondText(xmlBuilder.toString(), ContentType.Application.Xml, HttpStatusCode.OK)
    }

    override suspend fun serializeError(exception: ServiceException, service: ServiceModel, operation: OperationModel, requestId: String, call: io.ktor.server.application.ApplicationCall) {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                  "<ErrorResponse>\n" +
                  "  <Error>\n" +
                  "    <Type>${exception.type}</Type>\n" +
                  "    <Code>${exception.code}</Code>\n" +
                  "    <Message>${exception.message}</Message>\n" +
                  "  </Error>\n" +
                  "  <RequestId>$requestId</RequestId>\n" +
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
        if (response is Map<*, *>) {
            val body = response["Body"]
            if (body is java.io.InputStream) {
                // S3 GetObject style response
                val contentType = response["ContentType"] as? String ?: "application/octet-stream"
                val contentLength = response["ContentLength"] as? Long
                val etag = response["ETag"] as? String
                val lastModified = response["LastModified"] as? String

                if (contentLength != null) call.response.header(io.ktor.http.HttpHeaders.ContentLength, contentLength.toString())
                if (etag != null) call.response.header(io.ktor.http.HttpHeaders.ETag, etag)
                if (lastModified != null) call.response.header(io.ktor.http.HttpHeaders.LastModified, lastModified)
                
                call.respondOutputStream(ContentType.parse(contentType), HttpStatusCode.OK) {
                    body.copyTo(this)
                }
                return
            }
        }

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
