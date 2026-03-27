package keystack.state

import java.util.concurrent.ConcurrentHashMap

/**
 * Hierarchical store for service states scoped by account and region.
 *
 * @param T the type of the service store.
 * @param serviceName the name of the service.
 * @param storeFactory a function to create a new instance of the service store.
 */
class AccountRegionStore<T : ServiceStore>(
    val serviceName: String,
    private val storeFactory: () -> T
) {
    // accountId -> regionName -> store instance
    private val accounts = ConcurrentHashMap<String, ConcurrentHashMap<String, T>>()

    /**
     * Gets or creates the store for the specified account and region.
     */
    operator fun get(accountId: String, region: String): T {
        val regions = accounts.computeIfAbsent(accountId) { ConcurrentHashMap() }
        return regions.computeIfAbsent(region) {
            storeFactory().apply {
                this.serviceName = this@AccountRegionStore.serviceName
                this.accountId = accountId
                this.regionName = region
            }
        }
    }

    /**
     * Clears all stores.
     */
    fun reset() {
        accounts.clear()
    }

    /**
     * Iterates over all stores.
     */
    fun forEach(action: (accountId: String, region: String, store: T) -> Unit) {
        accounts.forEach { (accountId, regions) ->
            regions.forEach { (region, store) ->
                action(accountId, region, store)
            }
        }
    }

    /**
     * Returns all account IDs currently in the store.
     */
    fun getAccountIds(): Set<String> = accounts.keys

    /**
     * Returns all regions for a specific account.
     */
    fun getRegions(accountId: String): Set<String> = accounts[accountId]?.keys ?: emptySet()
}
