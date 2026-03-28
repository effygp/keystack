package keystack.services.sns

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import keystack.provider.AwsOperation
import keystack.provider.ServiceProvider
import keystack.state.AccountRegionStore
import org.slf4j.LoggerFactory
import java.util.UUID

class SnsProvider : ServiceProvider {
    override val serviceName = "sns"
    private val logger = LoggerFactory.getLogger(SnsProvider::class.java)
    
    private val stores = AccountRegionStore("sns") { SnsStore() }

    @AwsOperation("CreateTopic")
    fun createTopic(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val topicName = params["Name"] as? String ?: throw ServiceException("MissingParameter", "Topic name is required")
        val store = stores[context.accountId, context.region]
        
        val topicArn = "arn:aws:sns:${context.region}:${context.accountId}:$topicName"
        
        if (store.topics.containsKey(topicArn)) {
            return mapOf("TopicArn" to topicArn)
        }
        
        val topic = SnsTopic(topicArn, topicName)
        store.topics[topicArn] = topic
        
        logger.info("Created SNS topic: {} in region: {}", topicName, context.region)
        
        return mapOf("TopicArn" to topicArn)
    }

    @AwsOperation("Subscribe")
    fun subscribe(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val topicArn = params["TopicArn"] as? String ?: throw ServiceException("MissingParameter", "TopicArn is required")
        val protocol = params["Protocol"] as? String ?: throw ServiceException("MissingParameter", "Protocol is required")
        val endpoint = params["Endpoint"] as? String ?: throw ServiceException("MissingParameter", "Endpoint is required")
        
        val store = stores[context.accountId, context.region]
        val topic = store.topics[topicArn] ?: throw ServiceException("NotFound", "Topic not found")
        
        val subscriptionArn = "$topicArn:${UUID.randomUUID()}"
        val subscription = SnsSubscription(subscriptionArn, topicArn, protocol, endpoint)
        
        topic.subscriptions.add(subscription)
        
        logger.info("Subscribed {} to topic: {} with protocol: {}", endpoint, topicArn, protocol)
        
        return mapOf("SubscriptionArn" to subscriptionArn)
    }

    @AwsOperation("Publish")
    fun publish(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val topicArn = params["TopicArn"] as? String
        val targetArn = params["TargetArn"] as? String
        val message = params["Message"] as? String ?: throw ServiceException("MissingParameter", "Message is required")
        
        val finalTopicArn = topicArn ?: targetArn ?: throw ServiceException("MissingParameter", "TopicArn or TargetArn is required")
        
        val store = stores[context.accountId, context.region]
        val topic = store.topics[finalTopicArn] ?: throw ServiceException("NotFound", "Topic not found")
        
        logger.info("Publishing message to topic: {}", finalTopicArn)
        
        topic.subscriptions.forEach { sub ->
            try {
                deliverMessage(sub, message)
            } catch (e: Exception) {
                logger.error("Failed to deliver message to subscription: {}", sub.arn, e)
            }
        }
        
        return mapOf("MessageId" to UUID.randomUUID().toString())
    }

    private fun deliverMessage(subscription: SnsSubscription, message: String) {
        logger.debug("Delivering SNS message to {}: {}", subscription.protocol, subscription.endpoint)
    }

    @AwsOperation("ListTopics")
    fun listTopics(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val store = stores[context.accountId, context.region]
        return mapOf(
            "Topics" to store.topics.values.map { mapOf("TopicArn" to it.arn) }
        )
    }
}
