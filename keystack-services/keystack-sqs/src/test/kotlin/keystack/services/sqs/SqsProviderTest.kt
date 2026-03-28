package keystack.services.sqs

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SqsProviderTest {
    private lateinit var provider: SqsProvider
    private val context = RequestContext(
        request = mockk(relaxed = true),
        accountId = "123456789012",
        region = "us-east-1"
    )

    @BeforeTest
    fun setup() {
        provider = SqsProvider()
    }

    @Test
    fun `test create queue`() = runBlocking {
        val params = mapOf("QueueName" to "test-queue")
        val result = provider.createQueue(context, params)
        
        val queueUrl = result["QueueUrl"] as String
        assertTrue(queueUrl.contains("test-queue"), "QueueUrl should contain 'test-queue'")
        assertTrue(queueUrl.contains("123456789012"), "QueueUrl should contain '123456789012'")
    }

    @Test
    fun `test get queue url`() = runBlocking {
        provider.createQueue(context, mapOf("QueueName" to "test-queue"))
        
        val result = provider.getQueueUrl(context, mapOf("QueueName" to "test-queue"))
        val queueUrl = result["QueueUrl"] as String
        assertTrue(queueUrl.contains("test-queue"))
    }

    @Test
    fun `test send and receive message`() = runBlocking {
        val createResult = provider.createQueue(context, mapOf("QueueName" to "test-queue"))
        val queueUrl = createResult["QueueUrl"] as String
        
        val sendResult = provider.sendMessage(context, mapOf(
            "QueueUrl" to queueUrl,
            "MessageBody" to "hello world"
        ))
        assertNotNull(sendResult["MessageId"])
        
        val receiveResult = provider.receiveMessage(context, mapOf("QueueUrl" to queueUrl))
        val messages = receiveResult["Messages"] as List<Map<String, Any?>>
        
        assertEquals(1, messages.size)
        assertEquals("hello world", messages[0]["Body"])
    }

    @Test
    fun `test delete message`() = runBlocking {
        val createResult = provider.createQueue(context, mapOf("QueueName" to "test-queue"))
        val queueUrl = createResult["QueueUrl"] as String
        
        provider.sendMessage(context, mapOf("QueueUrl" to queueUrl, "MessageBody" to "to be deleted"))
        
        val receiveResult = provider.receiveMessage(context, mapOf("QueueUrl" to queueUrl))
        val messages = receiveResult["Messages"] as List<Map<String, Any?>>
        val receiptHandle = messages[0]["ReceiptHandle"] as String
        
        provider.deleteMessage(context, mapOf(
            "QueueUrl" to queueUrl,
            "ReceiptHandle" to receiptHandle
        ))
        
        // Try to receive again, should be empty
        val receiveResult2 = provider.receiveMessage(context, mapOf("QueueUrl" to queueUrl, "WaitTimeSeconds" to "0"))
        val messages2 = receiveResult2["Messages"] as List<Map<String, Any?>>
        assertEquals(0, messages2.size)
    }

    @Test
    fun `test queue does not exist`() = runBlocking {
        assertFailsWith<ServiceException> {
            provider.getQueueUrl(context, mapOf("QueueName" to "non-existent"))
        }.also {
            assertEquals("QueueDoesNotExist", it.code)
        }
    }

    @Test
    fun `test standard queue best effort ordering`() = runBlocking {
        val createResult = provider.createQueue(context, mapOf("QueueName" to "ordering-queue"))
        val queueUrl = createResult["QueueUrl"] as String
        
        // Send 10 messages
        val messagesSent = (1..10).map { i -> "msg-$i" }
        for (body in messagesSent) {
            provider.sendMessage(context, mapOf(
                "QueueUrl" to queueUrl,
                "MessageBody" to body
            ))
        }
        
        // Receive 10 messages (can be in multiple calls, but let's try 10 at once)
        val receiveResult = provider.receiveMessage(context, mapOf(
            "QueueUrl" to queueUrl,
            "MaxNumberOfMessages" to "10"
        ))
        val messagesReceived = receiveResult["Messages"] as List<Map<String, Any?>>
        
        assertEquals(10, messagesReceived.size)
        for (i in 0..9) {
            assertEquals(messagesSent[i], messagesReceived[i]["Body"])
        }
    }
}
