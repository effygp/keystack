package keystack.services.lambda

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import keystack.provider.AwsOperation
import keystack.provider.ServiceProvider
import keystack.state.AccountRegionStore
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.time.Instant

class LambdaProvider : ServiceProvider {
    override val serviceName = "lambda"
    private val logger = LoggerFactory.getLogger(LambdaProvider::class.java)
    
    private val stores = AccountRegionStore("lambda") { LambdaStore() }
    private val codeManager = CodeManager(Paths.get("data", "lambda"))
    private val lambdaService = LambdaService()

    @AwsOperation("CreateFunction")
    fun createFunction(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val functionName = params["FunctionName"] as? String ?: throw ServiceException("InvalidParameterValueException", "FunctionName is required")
        val runtime = params["Runtime"] as? String ?: "python3.9"
        val handler = params["Handler"] as? String ?: "index.handler"
        val role = params["Role"] as? String ?: "arn:aws:iam::000000000000:role/lambda-role"
        val timeout = (params["Timeout"] as? Number)?.toInt() ?: 3
        val memorySize = (params["MemorySize"] as? Number)?.toInt() ?: 128
        @Suppress("UNCHECKED_CAST")
        val environment = (params["Environment"] as? Map<String, Any?>)?.get("Variables") as? Map<String, String>
        
        val store = stores[context.accountId, context.region]
        val arn = "arn:aws:lambda:${context.region}:${context.accountId}:function:$functionName"
        
        val codeParams = params["Code"] as? Map<String, Any?>
        val zipFile = codeParams?.get("ZipFile") as? String
        val s3Key = codeParams?.get("S3Key") as? String
        val imageUri = codeParams?.get("ImageUri") as? String
        
        val functionCode = when {
            zipFile != null -> codeManager.storeCode(functionName, zipFile)
            s3Key?.startsWith("file://") == true -> codeManager.storeHotReloadCode(functionName, s3Key.removePrefix("file://"))
            imageUri?.startsWith("file://") == true -> codeManager.storeHotReloadCode(functionName, imageUri.removePrefix("file://"))
            else -> null
        }

        val config = FunctionConfiguration(
            functionName = functionName,
            functionArn = arn,
            runtime = runtime,
            handler = handler,
            role = role,
            timeout = timeout,
            memorySize = memorySize,
            environment = environment,
            code = functionCode,
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
    suspend fun invoke(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val functionName = params["FunctionName"] as? String ?: throw ServiceException("ResourceNotFoundException", "Function not found")
        val store = stores[context.accountId, context.region]
        
        val config = store.functions[functionName] ?: throw ServiceException("ResourceNotFoundException", "Function not found: $functionName")

        val payloadString = (params["Payload"] ?: context.serviceRequest?.get("Payload") ?: "{}").toString()
        val payload = payloadString.toByteArray()
        
        logger.info("Invoking Lambda function: {} with payload: {}", functionName, payloadString)

        if (config.code != null) {
            val result = lambdaService.invoke(config, payload)
            return mapOf(
                "StatusCode" to if (result.isError) 500 else 200,
                "Payload" to (result.payload?.let { String(it) } ?: "{}"),
                "FunctionError" to if (result.isError) "Unhandled" else null
            ).filterValues { it != null }
        }

        return mapOf(
            "StatusCode" to 200,
            "Payload" to payloadString
        )
    }

    override fun onStop() {
        runBlocking {
            lambdaService.stop()
        }
    }

    @AwsOperation("DeleteFunction")
    fun deleteFunction(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val functionName = params["FunctionName"] as? String ?: throw ServiceException("ResourceNotFoundException", "FunctionName is required")
        val store = stores[context.accountId, context.region]
        val config = store.functions.remove(functionName)
        codeManager.deleteCode(functionName)
        
        if (config != null) {
            val qualifiers = listOf("\$LATEST")
            qualifiers.forEach { qualifier ->
                val versionManagerId = "${config.functionArn}:$qualifier"
                store.provisionedConfigs.remove(versionManagerId)
                runBlocking {
                    lambdaService.assignmentService.stopEnvironmentsForVersion(versionManagerId)
                }
            }
        }
        
        logger.info("Deleted Lambda function: {}", functionName)
        return emptyMap()
    }

    @AwsOperation("PutProvisionedConcurrencyConfig")
    suspend fun putProvisionedConcurrencyConfig(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val functionName = params["FunctionName"] as String
        val qualifier = params["Qualifier"] as? String ?: "\$LATEST"
        val provisionedConcurrentExecutions = (params["ProvisionedConcurrentExecutions"] as? Number)?.toInt() 
            ?: (params["ProvisionedConcurrentExecutions"] as? String)?.toInt()
            ?: throw ServiceException("InvalidParameterValueException", "ProvisionedConcurrentExecutions is required")
        
        val store = stores[context.accountId, context.region]
        val config = store.functions[functionName] ?: throw ServiceException("ResourceNotFoundException", "Function not found")
        
        val versionManagerId = "${config.functionArn}:$qualifier"
        lambdaService.assignmentService.scaleProvisionedConcurrency(versionManagerId, config, provisionedConcurrentExecutions)
        
        store.provisionedConfigs[versionManagerId] = provisionedConcurrentExecutions
        
        return mapOf(
            "RequestedProvisionedConcurrentExecutions" to provisionedConcurrentExecutions,
            "AllocatedProvisionedConcurrentExecutions" to provisionedConcurrentExecutions,
            "Status" to "READY"
        )
    }

    @AwsOperation("GetProvisionedConcurrencyConfig")
    fun getProvisionedConcurrencyConfig(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val functionName = params["FunctionName"] as String
        val qualifier = params["Qualifier"] as? String ?: "\$LATEST"
        
        val store = stores[context.accountId, context.region]
        val config = store.functions[functionName] ?: throw ServiceException("ResourceNotFoundException", "Function not found")
        
        val versionManagerId = "${config.functionArn}:$qualifier"
        val provisionedCount = store.provisionedConfigs[versionManagerId] 
            ?: throw ServiceException("ResourceNotFoundException", "Provisioned concurrency config not found")
            
        return mapOf(
            "RequestedProvisionedConcurrentExecutions" to provisionedCount,
            "AllocatedProvisionedConcurrentExecutions" to provisionedCount,
            "Status" to "READY"
        )
    }

    @AwsOperation("DeleteProvisionedConcurrencyConfig")
    suspend fun deleteProvisionedConcurrencyConfig(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val functionName = params["FunctionName"] as String
        val qualifier = params["Qualifier"] as? String ?: "\$LATEST"
        
        val store = stores[context.accountId, context.region]
        val config = store.functions[functionName] ?: throw ServiceException("ResourceNotFoundException", "Function not found")
        
        val versionManagerId = "${config.functionArn}:$qualifier"
        store.provisionedConfigs.remove(versionManagerId)
        
        lambdaService.assignmentService.scaleProvisionedConcurrency(versionManagerId, config, 0)
        
        return emptyMap()
    }

    @AwsOperation("ListFunctions")
    fun listFunctions(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val store = stores[context.accountId, context.region]
        return mapOf("Functions" to store.functions.values.toList())
    }
}
