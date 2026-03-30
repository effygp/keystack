package keystack.gateway

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class SqsJsonIntegrationTest {

    @BeforeTest
    fun setup() {
        try {
            keystack.provider.initKeystack()
        } catch (e: Exception) {
            // Ignore if already started
        }
    }

    @Test
    fun `test create queue with json protocol`() = testApplication {
        application {
            Gateway().apply { module() }
        }

        // CreateQueue with JSON protocol (as modern AWS CLI does)
        val createResponse = client.post("/") {
            header("Content-Type", "application/x-amz-json-1.0")
            header("X-Amz-Target", "AmazonSQS.CreateQueue")
            setBody("{\"QueueName\": \"test-queue-json\"}")
        }
        
        val createBody = createResponse.bodyAsText()
        assertEquals(HttpStatusCode.OK, createResponse.status, "Status should be OK but was ${createResponse.status}. Body: $createBody")
        
        // Assert that it contains JSON structure
        assertTrue(createBody.startsWith("{"), "Should start with { for JSON. Body: $createBody")
        assertTrue(createBody.contains("\"QueueUrl\""), "Should contain QueueUrl in JSON. Body: $createBody")
        assertTrue(createBody.contains("test-queue-json"), "Should contain queue name in URL. Body: $createBody")
    }

    @Test
    fun `test send message with json protocol`() = testApplication {
        application {
            Gateway().apply { module() }
        }

        // SendMessage with JSON protocol
        val sendResponse = client.post("/") {
            header("Content-Type", "application/x-amz-json-1.0")
            header("X-Amz-Target", "AmazonSQS.SendMessage")
            setBody("{\"QueueUrl\": \"http://localhost:4566/000000000000/test-queue-json\", \"MessageBody\": \"hello json\"}")
        }
        
        val sendBody = sendResponse.bodyAsText()
        assertEquals(HttpStatusCode.OK, sendResponse.status, "Status should be OK but was ${sendResponse.status}. Body: $sendBody")
        
        assertTrue(sendBody.startsWith("{"), "Should start with { for JSON. Body: $sendBody")
        assertTrue(sendBody.contains("\"MessageId\""), "Should contain MessageId in JSON. Body: $sendBody")
    }
}
