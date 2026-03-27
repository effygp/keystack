package keystack.protocol.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.InputStream
import org.slf4j.LoggerFactory

@JsonIgnoreProperties(ignoreUnknown = true)
data class ServiceMetadata(
    val apiVersion: String? = null,
    val endpointPrefix: String? = null,
    val protocol: String? = null,
    val serviceFullName: String? = null,
    val signatureVersion: String? = null,
    val signingName: String? = null,
    val targetPrefix: String? = null,
    val jsonVersion: String? = null,
    val uid: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HttpBinding(
    val method: String? = null,
    val requestUri: String? = null,
    val responseCode: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ShapeRef(
    val shape: String,
    val location: String? = null,
    val locationName: String? = null,
    val queryName: String? = null,
    val xmlNamespace: Map<String, String>? = null,
    val streaming: Boolean = false,
    val payload: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OperationModel(
    val name: String? = null,
    val http: HttpBinding? = null,
    val input: ShapeRef? = null,
    val output: ShapeRef? = null,
    val errors: List<ShapeRef>? = null,
    val documentation: String? = null,
    val authtype: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ServiceModel(
    val version: String? = null,
    val metadata: ServiceMetadata,
    val operations: Map<String, OperationModel>,
    val shapes: Map<String, Map<String, Any>>
)

object ServiceCatalog {
    private val logger = LoggerFactory.getLogger(ServiceCatalog::class.java)
    private val mapper: ObjectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    
    private val services = mutableMapOf<String, ServiceModel>()
    private val signingNameIndex = mutableMapOf<String, ServiceModel>()
    private val targetPrefixIndex = mutableMapOf<String, ServiceModel>()
    private val endpointPrefixIndex = mutableMapOf<String, ServiceModel>()

    init {
        loadBundledModels()
    }

    private fun loadBundledModels() {
        listOf("sqs.json", "dynamodb.json", "s3.json").forEach { resourceName ->
            try {
                val inputStream: InputStream? = javaClass.classLoader.getResourceAsStream("models/$resourceName")
                if (inputStream != null) {
                    val service: ServiceModel = mapper.readValue(inputStream)
                    registerService(service)
                    logger.debug("Loaded service model for ${service.metadata.serviceFullName}")
                } else {
                    logger.warn("Service model resource not found: models/$resourceName")
                }
            } catch (e: Exception) {
                logger.error("Failed to load service model: models/$resourceName", e)
            }
        }
    }

    fun registerService(service: ServiceModel) {
        val name = service.metadata.endpointPrefix ?: service.metadata.signingName ?: "unknown"
        services[name] = service
        
        service.metadata.signingName?.let { signingNameIndex[it] = service }
        service.metadata.targetPrefix?.let { targetPrefixIndex[it] = service }
        service.metadata.endpointPrefix?.let { endpointPrefixIndex[it] = service }
    }

    fun getService(name: String): ServiceModel? = services[name]
    fun findBySigningName(signingName: String): ServiceModel? = signingNameIndex[signingName]
    fun findByTargetPrefix(targetPrefix: String): ServiceModel? = targetPrefixIndex[targetPrefix]
    fun findByEndpointPrefix(prefix: String): ServiceModel? = endpointPrefixIndex[prefix]
    fun getAllServices(): Collection<ServiceModel> = services.values
}
