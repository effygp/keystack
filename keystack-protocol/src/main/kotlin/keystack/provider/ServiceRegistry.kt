package keystack.provider

import keystack.gateway.RequestContext
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.slf4j.LoggerFactory
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.callSuspend

private val logger = LoggerFactory.getLogger(ServiceRegistry::class.java)

/**
 * Registry for managing and looking up service providers.
 */
class ServiceRegistry(private val koin: Koin = GlobalContext.get()) {
    private val loadedProviders = mutableMapOf<String, ServiceProvider>()
    private val dispatchTables = mutableMapOf<String, Map<String, OperationDispatcher>>()

    /**
     * Returns the service provider for the given service name.
     * Lazy-initializes the provider on the first call.
     */
    fun getProvider(serviceName: String): ServiceProvider? {
        if (loadedProviders.containsKey(serviceName)) return loadedProviders[serviceName]
        
        val provider = try {
            // Try to resolve the provider from Koin
            koin.getOrNull<ServiceProvider> { org.koin.core.parameter.parametersOf(serviceName) }
                ?: koin.getAll<ServiceProvider>().find { it.serviceName == serviceName }
        } catch (e: Exception) {
            logger.error("Failed to load service provider for: $serviceName", e)
            null
        }

        return provider?.also {
            logger.info("Loaded service provider for: {}", serviceName)
            it.onInit()
            it.onStart()
            loadedProviders[serviceName] = it
        }
    }

    /**
     * Returns the dispatch table for the given service name.
     */
    fun getDispatchTable(serviceName: String): Map<String, OperationDispatcher> {
        return dispatchTables.computeIfAbsent(serviceName) {
            val provider = getProvider(serviceName)
            if (provider != null) {
                buildDispatchTable(provider)
            } else {
                emptyMap()
            }
        }
    }

    /**
     * Builds a dispatch table from @AwsOperation annotations in the provider.
     */
    private fun buildDispatchTable(provider: ServiceProvider): Map<String, OperationDispatcher> {
        val table = mutableMapOf<String, OperationDispatcher>()
        
        provider::class.memberFunctions.forEach { function ->
            val annotation = function.findAnnotation<AwsOperation>()
            if (annotation != null) {
                table[annotation.name] = OperationDispatcher(provider, function, annotation.expandParameters)
                logger.debug("Registered operation: {}.{} -> {}", provider.serviceName, annotation.name, function.name)
            }
        }
        
        return table
    }

    /**
     * Resets the state of all loaded service providers.
     */
    fun resetAll() {
        loadedProviders.values.forEach { provider ->
            try {
                logger.info("Resetting state for service: {}", provider.serviceName)
                provider.onStateReset()
            } catch (e: Exception) {
                logger.error("Failed to reset state for service: ${provider.serviceName}", e)
            }
        }
    }
}

/**
 * Handles the invocation of a service operation handler.
 */
class OperationDispatcher(
    private val provider: ServiceProvider,
    private val function: KFunction<*>,
    private val expandParameters: Boolean = false
) {
    /**
     * Invokes the operation handler with the given request context.
     */
    suspend fun invoke(context: RequestContext): Any? {
        val args = mutableListOf<Any?>(provider, context)
        
        if (expandParameters) {
            val params = context.serviceRequest ?: emptyMap<String, Any?>()
            args.add(params)
        }

        return if (function.isSuspend) {
            function.callSuspend(*args.toTypedArray())
        } else {
            function.call(*args.toTypedArray())
        }
    }
}
