package keystack.services.cloudformation

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import keystack.provider.AwsOperation
import keystack.provider.ServiceProvider
import keystack.provider.ServiceRegistry
import keystack.state.AccountRegionStore
import org.slf4j.LoggerFactory
import java.util.UUID
import java.time.Instant
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CloudFormationProvider(
    private val registry: ServiceRegistry,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : ServiceProvider {
    override val serviceName = "cloudformation"
    private val logger = LoggerFactory.getLogger(CloudFormationProvider::class.java)
    private val stores = AccountRegionStore("cloudformation") { CloudFormationStore() }
    
    private val jsonMapper = ObjectMapper().registerKotlinModule()
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    @AwsOperation("CreateStack")
    suspend fun createStack(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val stackName = params["StackName"] as? String ?: throw ServiceException("MissingParameter", "StackName is required")
        val templateBody = params["TemplateBody"] as? String ?: throw ServiceException("MissingParameter", "TemplateBody is required")
        
        val store = stores[context.accountId, context.region]
        if (store.stacks.containsKey(stackName)) {
            throw ServiceException("AlreadyExistsException", "Stack with name $stackName already exists")
        }
        
        val stackId = "arn:aws:cloudformation:${context.region}:${context.accountId}:stack/$stackName/${UUID.randomUUID()}"
        
        val stack = CloudFormationStack(
            stackName = stackName,
            stackId = stackId,
            stackStatus = "CREATE_IN_PROGRESS",
            template = templateBody
        )
        store.stacks[stackName] = stack
        
        // Launch stack creation in background
        scope.launch {
            try {
                deployStack(context, stack)
                stack.stackStatus = "CREATE_COMPLETE"
                logger.info("Successfully created stack: {}", stackName)
            } catch (e: Exception) {
                stack.stackStatus = "CREATE_FAILED"
                logger.error("Failed to create stack: {}", stackName, e)
            }
        }
        
        return mapOf("StackId" to stackId)
    }

    @AwsOperation("DescribeStacks")
    fun describeStacks(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val stackName = params["StackName"] as? String
        val store = stores[context.accountId, context.region]
        
        val stacksToReturn = if (stackName != null) {
            val stack = store.stacks[stackName] ?: throw ServiceException("ValidationError", "Stack with id $stackName does not exist")
            listOf(stack)
        } else {
            store.stacks.values.toList()
        }
        
        return mapOf("Stacks" to stacksToReturn.map { it.toMap() })
    }

    @AwsOperation("DeleteStack")
    suspend fun deleteStack(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val stackName = params["StackName"] as? String ?: throw ServiceException("MissingParameter", "StackName is required")
        val store = stores[context.accountId, context.region]
        
        val stack = store.stacks[stackName] ?: throw ServiceException("ValidationError", "Stack with name $stackName does not exist")
        
        stack.stackStatus = "DELETE_IN_PROGRESS"
        
        try {
            teardownStack(context, stack)
            store.stacks.remove(stackName)
            logger.info("Successfully deleted stack: {}", stackName)
        } catch (e: Exception) {
            stack.stackStatus = "DELETE_FAILED"
            logger.error("Failed to delete stack: {}", stackName, e)
            throw ServiceException("ValidationError", "Stack deletion failed: ${e.message}")
        }
        
        return emptyMap<String, Any?>()
    }

    @AwsOperation("DescribeStackResources")
    fun describeStackResources(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val stackName = params["StackName"] as? String ?: throw ServiceException("MissingParameter", "StackName is required")
        val store = stores[context.accountId, context.region]
        
        val stack = store.stacks[stackName] ?: throw ServiceException("ValidationError", "Stack with name $stackName does not exist")
        
        return mapOf("StackResources" to stack.resources.map { it.toMap() })
    }

    private suspend fun deployStack(context: RequestContext, stack: CloudFormationStack) {
        val template = parseTemplate(stack.template)
        val resources = template["Resources"] as? Map<String, Any?> ?: emptyMap()
        
        for ((logicalId, resourceDef) in resources) {
            val def = resourceDef as? Map<String, Any?> ?: continue
            val type = def["Type"] as? String ?: continue
            val properties = def["Properties"] as? Map<String, Any?> ?: emptyMap()
            
            val stackResource = StackResource(
                logicalResourceId = logicalId,
                resourceType = type,
                resourceStatus = "CREATE_IN_PROGRESS",
                properties = properties
            )
            stack.resources.add(stackResource)
            
            try {
                val physicalId = deployResource(context, type, properties)
                stackResource.physicalResourceId = physicalId
                stackResource.resourceStatus = "CREATE_COMPLETE"
            } catch (e: Exception) {
                stackResource.resourceStatus = "CREATE_FAILED"
                throw e
            }
        }
    }

    private suspend fun teardownStack(context: RequestContext, stack: CloudFormationStack) {
        // Delete in reverse order
        for (resource in stack.resources.reversed()) {
            resource.resourceStatus = "DELETE_IN_PROGRESS"
            try {
                deleteResource(context, resource.resourceType, resource.physicalResourceId, resource.properties)
                resource.resourceStatus = "DELETE_COMPLETE"
            } catch (e: Exception) {
                resource.resourceStatus = "DELETE_FAILED"
                logger.warn("Failed to delete resource {}: {}", resource.logicalResourceId, e.message)
            }
        }
    }

    private suspend fun deployResource(context: RequestContext, type: String, properties: Map<String, Any?>): String {
        return when (type) {
            "AWS::S3::Bucket" -> {
                val bucketName = properties["BucketName"] as? String ?: "cfn-bucket-${UUID.randomUUID()}"
                callService(context, "s3", "CreateBucket", mapOf("Bucket" to bucketName))
                bucketName
            }
            "AWS::SQS::Queue" -> {
                val queueName = properties["QueueName"] as? String ?: "cfn-queue-${UUID.randomUUID()}"
                val response = callService(context, "sqs", "CreateQueue", mapOf("QueueName" to queueName)) as Map<String, Any?>
                response["QueueUrl"] as String
            }
            else -> {
                logger.warn("Unsupported resource type: {}", type)
                "unsupported-${UUID.randomUUID()}"
            }
        }
    }

    private suspend fun deleteResource(context: RequestContext, type: String, physicalId: String?, properties: Map<String, Any?>) {
        if (physicalId == null) return
        
        when (type) {
            "AWS::S3::Bucket" -> {
                callService(context, "s3", "DeleteBucket", mapOf("Bucket" to physicalId))
            }
            "AWS::SQS::Queue" -> {
                // SQS DeleteQueue uses QueueUrl
                callService(context, "sqs", "DeleteQueue", mapOf("QueueUrl" to physicalId))
            }
        }
    }

    private suspend fun callService(context: RequestContext, service: String, operation: String, params: Map<String, Any?>): Any? {
        val dispatchTable = registry.getDispatchTable(service)
        val dispatcher = dispatchTable[operation] ?: throw Exception("Service $service or operation $operation not found")
        
        val internalContext = context.copy(
            serviceName = service,
            operationName = operation,
            serviceRequest = params,
            isInternalCall = true
        )
        
        return dispatcher.invoke(internalContext)
    }

    private fun parseTemplate(templateBody: String): Map<String, Any?> {
        return try {
            if (templateBody.trim().startsWith("{")) {
                jsonMapper.readValue<Map<String, Any?>>(templateBody)
            } else {
                yamlMapper.readValue<Map<String, Any?>>(templateBody)
            }
        } catch (e: Exception) {
            throw ServiceException("ValidationError", "Template is not a valid JSON or YAML: ${e.message}")
        }
    }

    private fun CloudFormationStack.toMap() = mapOf(
        "StackName" to stackName,
        "StackId" to stackId,
        "StackStatus" to stackStatus,
        "CreationTime" to creationTime.toString(),
        "Outputs" to outputs.map { mapOf("OutputKey" to it.key, "OutputValue" to it.value) }
    )

    private fun StackResource.toMap() = mapOf(
        "LogicalResourceId" to logicalResourceId,
        "PhysicalResourceId" to physicalResourceId,
        "ResourceType" to resourceType,
        "ResourceStatus" to resourceStatus,
        "Timestamp" to timestamp.toString()
    )
}
