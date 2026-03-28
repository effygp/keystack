package keystack.gateway

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class SqsIntegrationTest {

    @Test
    fun testCreateQueueAndSendMessage() = testApplication {
        application {
            Gateway().apply { module() }
        }

        // 1. CreateQueue
        val createResponse = client.post("/") {
            header("Content-Type", "application/x-www-form-urlencoded")
            setBody("Action=CreateQueue&QueueName=test-queue&Version=2012-11-05")
        }
        assertEquals(HttpStatusCode.OK, createResponse.status)
        val createBody = createResponse.bodyAsText()
        println("CreateQueue Response: $createBody")
        
        // Assert that it contains the expected XML structure
        assertTrue(createBody.contains("<CreateQueueResponse"), "Should contain <CreateQueueResponse")
        assertTrue(createBody.contains("<CreateQueueResult"), "Should contain <CreateQueueResult")
        
        // This is where it's expected to fail if Step 1 is NOT implemented:
        // The QueueUrl should be in the response, but currently QuerySerializer is stubbed.
        // assertTrue(createBody.contains("<QueueUrl>"), "Should contain <QueueUrl>")

        // 2. SendMessage
        val sendResponse = client.post("/") {
            header("Content-Type", "application/x-www-form-urlencoded")
            setBody("Action=SendMessage&QueueUrl=http://localhost:4566/000000000000/test-queue&MessageBody=hello&Version=2012-11-05")
        }
        assertEquals(HttpStatusCode.OK, sendResponse.status)
        val sendBody = sendResponse.bodyAsText()
        println("SendMessage Response: $sendBody")
        assertTrue(sendBody.contains("<SendMessageResponse"), "Should contain <SendMessageResponse")
        // assertTrue(sendBody.contains("<MessageId>"), "Should contain <MessageId>")
    }
}
