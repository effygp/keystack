package keystack.services.s3

import keystack.state.ServiceStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class S3Bucket(
    val name: String,
    val region: String,
    val creationDate: Instant,
    val accountId: String,
    val tags: MutableMap<String, String> = mutableMapOf()
)

data class S3Object(
    val key: String,
    val size: Long,
    val etag: String,
    val lastModified: Instant,
    val contentType: String,
    val metadata: Map<String, String> = emptyMap()
)

object GlobalS3Store {
    val buckets = ConcurrentHashMap<String, S3Bucket>()
    
    fun reset() {
        buckets.clear()
    }
}

class S3Store : ServiceStore() {
    val objects = ConcurrentHashMap<String, ConcurrentHashMap<String, S3Object>>() // bucket -> key -> object
}
