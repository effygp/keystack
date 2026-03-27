package keystack.services.lambda

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import keystack.provider.AwsOperation
import keystack.provider.ServiceProvider
import keystack.state.AccountRegionStore
import org.slf4j.LoggerFactory
import java.time.Instant

class LambdaProvider : ServiceProvider {
    override val serviceName = "lambda"
    private val logger = LoggerFactory.getLogger(LambdaProvider::class.java)
    
    private val stores = AccountRegionStore("lambda") { LambdaStore() }

    @AwsOperation("CreateFunction")
    fun createFunction(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val functionName = params["FunctionName"] as? String ?: throw ServiceException("InvalidParameterValueException", "FunctionName is required")
        val runtime = params["Runtime"] as? String ?: "python3.9"
        val handler = params["Handler"] as? String ?: "index.handler"
        val role = params["Role"] as? String ?: "arn:aws:iam::000000000000:role/lambda-role"
        
        val store = stores[context.accountId, context.region]
        val arn = "arn:aws:lambda:${context.region}:${context.accountId}:function:$functionName"
        
        val config = FunctionConfiguration(
            functionName = functionName,
            functionArn = arn,
            runtime = runtime,
            handler = handler,
            role = role,
            lastModified = Instant.now().toString()
        )
        
        store.functions[functionName] = config
        logger.info("Created Lambda function: {} in region: {}", functionName, context.region)
        
        return mapOf(
            "FunctionName" to functionName,
            "FunctionArn" to arn,
            "Runtime" to runtime,
            "Handler" to handler
        )
    }

    @AwsOperation("GetFunction")
    fun getFunction(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val functionName = params["FunctionName"] as? String ?: throw ServiceException("ResourceNotFoundException", "FunctionName is required")
        val store = stores[context.accountId, context.region]
        val config = store.functions[functionName] ?: throw ServiceException("ResourceNotFoundException", "Function not found")
        
        return mapOf("Configuration" to config)
    }

    @AwsOperation("Invoke")
    fun invoke(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val functionName = params["FunctionName"] as? String ?: throw ServiceException("ResourceNotFoundException", "Function not found")
        val store = stores[context.accountId, context.region]
        
        if (!store.functions.containsKey(functionName)) {
            throw ServiceException("ResourceNotFoundException", "Function not found: $functionName")
        }

        val payload = context.serviceRequest?.get("Payload") ?: "{}"
        logger.info("Invoking Lambda function: {} with payload: {}", functionName, payload)

        // Mock response for Phase 9 MVP
        return mapOf(
            "StatusCode" to 200,
            "Payload" to "{\"status\": \"success\", \"message\": \"Mock response from Keystack Lambda\"}"
        )
    }

    @AwsOperation("DeleteFunction")
    fun deleteFunction(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val functionName = params["FunctionName"] as? String ?: throw ServiceException("ResourceNotFoundException", "FunctionName is required")
        val store = stores[context.accountId, context.region]
        store.functions.remove(functionName)
        logger.info("Deleted Lambda function: {}", functionName)
        return emptyMap()
    }

    @AwsOperation("ListFunctions")
    fun listFunctions(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val store = stores[context.accountId, context.region]
        return mapOf("Functions" to store.functions.values.toList())
    }
}
