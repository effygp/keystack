package keystack.protocol.parser

import io.ktor.server.request.*
import io.ktor.util.*
import keystack.protocol.model.OperationModel
import keystack.protocol.model.ServiceModel
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URLDecoder

interface RequestParser {
    suspend fun parse(request: ApplicationRequest, service: ServiceModel, operation: OperationModel): Map<String, Any?>
}

class JsonRequestParser : RequestParser {
    private val mapper: ObjectMapper = jacksonObjectMapper()

    override suspend fun parse(request: ApplicationRequest, service: ServiceModel, operation: OperationModel): Map<String, Any?> {
        val body = request.call.receiveText()
        if (body.isEmpty()) return emptyMap()
        return mapper.readValue(body)
    }
}

class QueryRequestParser : RequestParser {
    override suspend fun parse(request: ApplicationRequest, service: ServiceModel, operation: OperationModel): Map<String, Any?> {
        val params = request.queryParameters.toMap().toMutableMap()
        
        // Also parse form body if it's a POST
        if (request.httpMethod == io.ktor.http.HttpMethod.Post) {
            val body = request.call.receiveText()
            if (body.isNotEmpty()) {
                val formParams = parseQueryString(body)
                formParams.forEach { (k, v) ->
                    params[k] = v
                }
            }
        }
        
        // AWS Query protocol uses flat keys with dots for nesting (e.g. Attribute.1.Name)
        // For MVP, we can just return the flat map, or implement a nested structure builder
        return params.mapValues { it.value.firstOrNull() }
    }

    private fun parseQueryString(query: String): Map<String, List<String>> {
        return query.split("&").filter { it.isNotEmpty() }.map {
            val parts = it.split("=")
            val name = URLDecoder.decode(parts[0], "UTF-8")
            val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
            name to value
        }.groupBy({ it.first }, { it.second })
    }
}
