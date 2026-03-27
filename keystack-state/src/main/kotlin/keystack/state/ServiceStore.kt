package keystack.state

/**
 * Base class for service-specific state stores.
 */
abstract class ServiceStore {
    lateinit var serviceName: String
    lateinit var accountId: String
    lateinit var regionName: String
}
