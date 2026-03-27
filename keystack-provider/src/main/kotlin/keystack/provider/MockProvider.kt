package keystack.provider

import keystack.gateway.RequestContext

class MockProvider : ServiceProvider {
    override val serviceName: String = "mock"

    @AwsOperation("Hello")
    fun hello(context: RequestContext): Map<String, String> {
        return mapOf("message" to "Hello, World!")
    }

    @AwsOperation("Echo")
    suspend fun echo(context: RequestContext): Any? {
        return context.serviceRequest
    }
}
