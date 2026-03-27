package keystack.services.sqs

import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

data class MessageAttribute(
    val dataType: String,
    val stringValue: String? = null,
    val binaryValue: ByteArray? = null
)

data class SqsMessage(
    val messageId: String = UUID.randomUUID().toString(),
    val body: String,
    val md5OfBody: String,
    val messageAttributes: Map<String, MessageAttribute> = emptyMap(),
    val systemAttributes: MutableMap<String, String> = mutableMapOf(),
    val created: Instant = Instant.now(),
    var receiveCount: Int = 0,
    var visibilityDeadline: Instant? = null,
    var firstReceived: Instant? = null,
    var lastReceived: Instant? = null,
    val receiptHandles: MutableSet<String> = mutableSetOf(),
    val delaySeconds: Int = 0,
    // FIFO-specific
    val messageGroupId: String? = null,
    val messageDeduplicationId: String? = null,
    val sequenceNumber: String? = null,
) {
    val isVisible: Boolean get() = visibilityDeadline?.isBefore(Instant.now()) ?: true
    val isDelayed: Boolean get() = created.plusSeconds(delaySeconds.toLong()).isAfter(Instant.now())
}

data class QueueAttributes(
    val attributes: MutableMap<String, String> = mutableMapOf()
) {
    var visibilityTimeout: Int
        get() = attributes["VisibilityTimeout"]?.toInt() ?: 30
        set(value) { attributes["VisibilityTimeout"] = value.toString() }

    var delaySeconds: Int
        get() = attributes["DelaySeconds"]?.toInt() ?: 0
        set(value) { attributes["DelaySeconds"] = value.toString() }

    var maximumMessageSize: Int
        get() = attributes["MaximumMessageSize"]?.toInt() ?: 262144
        set(value) { attributes["MaximumMessageSize"] = value.toString() }

    var messageRetentionPeriod: Int
        get() = attributes["MessageRetentionPeriod"]?.toInt() ?: 345600
        set(value) { attributes["MessageRetentionPeriod"] = value.toString() }

    var receiveMessageWaitTimeSeconds: Int
        get() = attributes["ReceiveMessageWaitTimeSeconds"]?.toInt() ?: 0
        set(value) { attributes["ReceiveMessageWaitTimeSeconds"] = value.toString() }
}

sealed class SqsQueue {
    abstract val name: String
    abstract val arn: String
    abstract val url: String
    abstract val attributes: QueueAttributes
    abstract val tags: MutableMap<String, String>
    
    protected val messageChannel = Channel<SqsMessage>(Channel.UNLIMITED)

    abstract fun enqueue(message: SqsMessage)
    abstract suspend fun receive(maxMessages: Int, visibilityTimeout: Int, waitTimeSeconds: Int): List<SqsMessage>
    abstract fun delete(receiptHandle: String): Boolean
    abstract fun purge()
}

class StandardQueue(
    override val name: String,
    override val arn: String,
    override val url: String,
    override val attributes: QueueAttributes = QueueAttributes(),
    override val tags: MutableMap<String, String> = mutableMapOf()
) : SqsQueue() {
    private val messages = ConcurrentHashMap<String, SqsMessage>()
    private val receiptToMessageId = ConcurrentHashMap<String, String>()

    override fun enqueue(message: SqsMessage) {
        messages[message.messageId] = message
        messageChannel.trySend(message)
    }

    override suspend fun receive(maxMessages: Int, visibilityTimeout: Int, waitTimeSeconds: Int): List<SqsMessage> {
        val result = mutableListOf<SqsMessage>()
        
        // Helper to pick visible messages
        fun pickMessages() {
            val now = Instant.now()
            messages.values.asSequence()
                .filter { it.isVisible && !it.isDelayed }
                .take(maxMessages - result.size)
                .forEach { msg ->
                    msg.receiveCount++
                    msg.visibilityDeadline = now.plusSeconds(visibilityTimeout.toLong())
                    val receiptHandle = UUID.randomUUID().toString()
                    msg.receiptHandles.add(receiptHandle)
                    receiptToMessageId[receiptHandle] = msg.messageId
                    if (msg.firstReceived == null) msg.firstReceived = now
                    msg.lastReceived = now
                    result.add(msg)
                }
        }

        pickMessages()

        if (result.isEmpty() && waitTimeSeconds > 0) {
            withTimeoutOrNull(waitTimeSeconds * 1000L) {
                for (msg in messageChannel) {
                    if (msg.isVisible && !msg.isDelayed) {
                        pickMessages()
                        if (result.isNotEmpty()) break
                    }
                }
            }
        }

        return result
    }

    override fun delete(receiptHandle: String): Boolean {
        val messageId = receiptToMessageId.remove(receiptHandle) ?: return false
        messages.remove(messageId)
        return true
    }

    override fun purge() {
        messages.clear()
        receiptToMessageId.clear()
    }
}

class FifoQueue(
    override val name: String,
    override val arn: String,
    override val url: String,
    override val attributes: QueueAttributes = QueueAttributes(),
    override val tags: MutableMap<String, String> = mutableMapOf()
) : SqsQueue() {
    private val messages = mutableListOf<SqsMessage>()
    private val receiptToMessageId = ConcurrentHashMap<String, String>()
    private val deduplicationIds = ConcurrentHashMap<String, Instant>()

    override fun enqueue(message: SqsMessage) {
        if (message.messageDeduplicationId != null) {
            val existing = deduplicationIds[message.messageDeduplicationId]
            if (existing != null && existing.plusSeconds(300).isAfter(Instant.now())) {
                return // Duplicate
            }
            deduplicationIds[message.messageDeduplicationId] = Instant.now()
        }
        synchronized(messages) {
            messages.add(message)
        }
        messageChannel.trySend(message)
    }

    override suspend fun receive(maxMessages: Int, visibilityTimeout: Int, waitTimeSeconds: Int): List<SqsMessage> {
        val result = mutableListOf<SqsMessage>()
        val now = Instant.now()

        synchronized(messages) {
            val iterator = messages.iterator()
            while (iterator.hasNext() && result.size < maxMessages) {
                val msg = iterator.next()
                if (msg.isVisible && !msg.isDelayed) {
                    msg.receiveCount++
                    msg.visibilityDeadline = now.plusSeconds(visibilityTimeout.toLong())
                    val receiptHandle = UUID.randomUUID().toString()
                    msg.receiptHandles.add(receiptHandle)
                    receiptToMessageId[receiptHandle] = msg.messageId
                    if (msg.firstReceived == null) msg.firstReceived = now
                    msg.lastReceived = now
                    result.add(msg)
                }
            }
        }
        
        // Long polling simplified for FIFO for now
        if (result.isEmpty() && waitTimeSeconds > 0) {
             withTimeoutOrNull(waitTimeSeconds * 1000L) {
                for (msg in messageChannel) {
                     synchronized(messages) {
                        if (msg.isVisible && !msg.isDelayed && messages.contains(msg)) {
                             msg.receiveCount++
                             msg.visibilityDeadline = now.plusSeconds(visibilityTimeout.toLong())
                             val receiptHandle = UUID.randomUUID().toString()
                             msg.receiptHandles.add(receiptHandle)
                             receiptToMessageId[receiptHandle] = msg.messageId
                             if (msg.firstReceived == null) msg.firstReceived = now
                             msg.lastReceived = now
                             result.add(msg)
                             return@withTimeoutOrNull
                        }
                     }
                }
            }
        }

        return result
    }

    override fun delete(receiptHandle: String): Boolean {
        val messageId = receiptToMessageId.remove(receiptHandle) ?: return false
        synchronized(messages) {
            messages.removeIf { it.messageId == messageId }
        }
        return true
    }

    override fun purge() {
        synchronized(messages) {
            messages.clear()
        }
        receiptToMessageId.clear()
        deduplicationIds.clear()
    }
}
