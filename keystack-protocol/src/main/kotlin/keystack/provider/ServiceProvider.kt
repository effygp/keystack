package keystack.provider

/**
 * Interface that all AWS service providers must implement.
 */
interface ServiceProvider {
    /**
     * The name of the AWS service (e.g., "sqs", "s3").
     */
    val serviceName: String

    /**
     * Lifecycle hook: Called when the service is initialized.
     */
    fun onInit() {}

    /**
     * Lifecycle hook: Called when the service starts.
     */
    fun onStart() {}

    /**
     * Lifecycle hook: Called when the service stops.
     */
    fun onStop() {}

    /**
     * Lifecycle hook: Called when the service state is reset.
     */
    fun onStateReset() {}
}
