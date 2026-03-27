package keystack.provider

/**
 * Annotation to mark a method as an AWS operation handler.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AwsOperation(
    val name: String,
    val expandParameters: Boolean = false
)
