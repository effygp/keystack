package keystack.state

/**
 * Interface for elements that can be visited by a [StateVisitor].
 */
interface StateVisitable {
    fun acceptVisitor(visitor: StateVisitor)
}

/**
 * Visitor interface for traversing the state of all services.
 */
interface StateVisitor {
    fun visit(store: AccountRegionStore<out ServiceStore>)
}
