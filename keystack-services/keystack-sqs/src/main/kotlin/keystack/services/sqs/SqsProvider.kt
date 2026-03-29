package keystack.services.sqs

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import keystack.provider.AwsOperation
import keystack.provider.ServiceProvider
import keystack.state.AccountRegionStore
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import java.util.UUID

class SqsProvider : ServiceProvider {
    override val serviceName = "sqs"
    private val logger = LoggerFactory.getLogger(SqsProvider::class.java)
    
    private val stores = AccountRegionStore("sqs") { SqsStore() }

    @AwsOperation("CreateQueue")
    fun createQueue(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val queueName = params["QueueName"] as? String ?: throw ServiceException("MissingParameter", "QueueName is required")
        val attributes = params.filterKeys { it.startsWith("Attribute") }
        
        val store = stores[context.accountId, context.region]
        
        if (store.queues.containsKey(queueName)) {
            return mapOf("QueueUrl" to store.queues[queueName]!!.url)
        }
        
        val isFifo = queueName.endsWith(".fifo")
        val queueUrl = "http://localhost:4566/${context.accountId}/$queueName"
        val queueArn = "arn:aws:sqs:${context.region}:${context.accountId}:$queueName"
        
        val queue = if (isFifo) {
            FifoQueue(queueName, queueArn, queueUrl)
        } else {
            StandardQueue(queueName, queueArn, queueUrl)
        }
        
        store.queues[queueName] = queue
        logger.info("Created SQS queue: {} in region: {} -> URL: {}", queueName, context.region, queue.url)
        
        return mapOf("QueueUrl" to queue.url)
    }

    @AwsOperation("GetQueueUrl")
    fun getQueueUrl(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val queueName = params["QueueName"] as? String ?: throw ServiceException("MissingParameter", "QueueName is required")
        val store = stores[context.accountId, context.region]
        
        val queue = store.queues[queueName] ?: throw ServiceException("QueueDoesNotExist", "The specified queue does not exist")
        return mapOf("QueueUrl" to queue.url)
    }

    @AwsOperation("SendMessage")
    fun sendMessage(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val queueUrl = params["QueueUrl"] as? String ?: throw ServiceException("MissingParameter", "QueueUrl is required")
        val messageBody = params["MessageBody"] as? String ?: throw ServiceException("MissingParameter", "MessageBody is required")
        
        val queue = findQueueByUrl(queueUrl) ?: throw ServiceException("QueueDoesNotExist", "The specified queue does not exist")
        
        val message = SqsMessage(
            body = messageBody,
            md5OfBody = md5(messageBody)
        )
        
        queue.enqueue(message)
        logger.debug("Sent message to queue: {}", queue.name)
        
        return mapOf(
            "MessageId" to message.messageId,
            "MD5OfMessageBody" to message.md5OfBody
        )
    }

    @AwsOperation("ReceiveMessage")
    suspend fun receiveMessage(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val queueUrl = params["QueueUrl"] as? String ?: throw ServiceException("MissingParameter", "QueueUrl is required")
        val maxMessages = (params["MaxNumberOfMessages"] as? String)?.toInt() ?: 1
        val waitTimeSeconds = (params["WaitTimeSeconds"] as? String)?.toInt() ?: 0
        val visibilityTimeout = (params["VisibilityTimeout"] as? String)?.toInt() ?: 30
        
        val queue = findQueueByUrl(queueUrl) ?: throw ServiceException("QueueDoesNotExist", "The specified queue does not exist")
        
        val messages = queue.receive(maxMessages, visibilityTimeout, waitTimeSeconds)
        
        return mapOf("Messages" to messages.map { it.toMap() })
    }

    @AwsOperation("DeleteMessage")
    fun deleteMessage(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val queueUrl = params["QueueUrl"] as? String ?: throw ServiceException("MissingParameter", "QueueUrl is required")
        val receiptHandle = params["ReceiptHandle"] as? String ?: throw ServiceException("MissingParameter", "ReceiptHandle is required")
        
        val queue = findQueueByUrl(queueUrl) ?: throw ServiceException("QueueDoesNotExist", "The specified queue does not exist")
        
        if (queue.delete(receiptHandle)) {
            return emptyMap()
        } else {
            throw ServiceException("ReceiptHandleIsInvalid", "The receipt handle is not valid for this queue")
        }
    }

    private fun findQueueByUrl(url: String): SqsQueue? {
        val parts = url.split("/")
        if (parts.size < 5) return null
        val accountId = parts[3]
        val queueName = parts[4]
        
        listOf("us-east-1", "us-west-2", "eu-central-1", "ap-southeast-1").forEach { region ->
            val store = stores[accountId, region]
            store.queues[queueName]?.let { return it }
        }
        return null
    }

    private fun md5(input: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun SqsMessage.toMap() = mapOf(
        "MessageId" to messageId,
        "Body" to body,
        "MD5OfBody" to md5OfBody,
        "ReceiptHandle" to receiptHandles.firstOrNull()
    )
}
