package keystack.services.sns

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SnsProviderTest {
    private lateinit var provider: SnsProvider
    private val context = RequestContext(
        request = mockk(relaxed = true),
        accountId = "123456789012",
        region = "us-east-1"
    )

    @BeforeTest
    fun setup() {
        provider = SnsProvider()
    }

    @Test
    fun `test topic lifecycle`() = runBlocking {
        val topicName = "test-topic"
        
        // 1. Create Topic
        val createResult = provider.createTopic(context, mapOf("Name" to topicName))
        val topicArn = createResult["TopicArn"] as String
        assertTrue(topicArn.contains(topicName))
        assertTrue(topicArn.contains("123456789012"))
        
        // 2. List Topics
        val listResult = provider.listTopics(context, emptyMap())
        val topics = listResult["Topics"] as List<Map<String, String>>
        assertTrue(topics.any { it["TopicArn"] == topicArn })
    }

    @Test
    fun `test subscription and publish`() = runBlocking {
        val topicName = "pub-test-topic"
        val createResult = provider.createTopic(context, mapOf("Name" to topicName))
        val topicArn = createResult["TopicArn"] as String
        
        // 1. Subscribe
        val subResult = provider.subscribe(context, mapOf(
            "TopicArn" to topicArn,
            "Protocol" to "sqs",
            "Endpoint" to "arn:aws:sqs:us-east-1:123456789012:test-queue"
        ))
        assertNotNull(subResult["SubscriptionArn"])
        
        // 2. Publish
        val pubResult = provider.publish(context, mapOf(
            "TopicArn" to topicArn,
            "Message" to "hello sns"
        ))
        assertNotNull(pubResult["MessageId"])
    }

    @Test
    fun `test publish to non-existent topic fails`() = runBlocking {
        assertFailsWith<ServiceException> {
            provider.publish(context, mapOf("TopicArn" to "arn:aws:sns:us-east-1:123456789012:no-topic", "Message" to "fail"))
        }.also {
            assertEquals("NotFound", it.code)
        }
    }
}
