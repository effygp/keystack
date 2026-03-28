package keystack.services.s3

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import keystack.provider.AwsOperation
import keystack.provider.ServiceProvider
import keystack.state.AccountRegionStore
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class S3Provider : ServiceProvider {
    override val serviceName = "s3"
    private val logger = LoggerFactory.getLogger(S3Provider::class.java)
    
    private val stores = AccountRegionStore("s3") { S3Store() }
    private val objectStore = FilesystemS3ObjectStore(Paths.get("data", "s3"))

    override fun onStateReset() {
        GlobalS3Store.reset()
        stores.reset()
    }

    @AwsOperation("CreateBucket")
    fun createBucket(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val bucketName = params["Bucket"] as? String ?: throw ServiceException("InvalidBucketName", "Bucket name is required")
        
        val existingBucket = GlobalS3Store.buckets[bucketName]
        if (existingBucket != null) {
            if (existingBucket.accountId == context.accountId) {
                // In AWS, creating an already owned bucket in the same region is a no-op
                // For simplicity, we just return emptyMap here
                return emptyMap()
            }
            throw ServiceException("BucketAlreadyExists", "The requested bucket name is not available. The bucket namespace is shared by all users of the system. Please select a different name and try again.")
        }
        
        val bucket = S3Bucket(
            name = bucketName,
            region = context.region,
            creationDate = Instant.now(),
            accountId = context.accountId
        )
        
        GlobalS3Store.buckets[bucketName] = bucket
        val store = stores[context.accountId, context.region]
        store.objects[bucketName] = ConcurrentHashMap()
        
        logger.info("Created S3 bucket: {} in region: {} for account: {}", bucketName, context.region, context.accountId)
        return emptyMap()
    }

    @AwsOperation("PutObject")
    fun putObject(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val bucketName = params["Bucket"] as? String ?: throw ServiceException("NoSuchBucket", "Bucket name is required")
        val key = params["Key"] as? String ?: throw ServiceException("InvalidKey", "Key is required")
        val body = params["Body"]
        
        val bucket = GlobalS3Store.buckets[bucketName] ?: throw ServiceException("NoSuchBucket", "The specified bucket does not exist")
        
        val inputStream = when (body) {
            is InputStream -> body
            is ByteArray -> ByteArrayInputStream(body)
            is String -> ByteArrayInputStream(body.toByteArray())
            else -> ByteArrayInputStream(ByteArray(0))
        }
        
        val metadata = objectStore.putObject(bucketName, key, inputStream)
        
        val s3Object = S3Object(
            key = key,
            size = metadata.size,
            etag = metadata.etag,
            lastModified = Instant.now(),
            contentType = params["ContentType"] as? String ?: "application/octet-stream"
        )
        
        val store = stores[bucket.accountId, bucket.region]
        store.objects[bucketName]!![key] = s3Object
        
        logger.debug("Put S3 object: {}/{}", bucketName, key)
        return mapOf("ETag" to metadata.etag)
    }

    @AwsOperation("GetObject")
    fun getObject(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val bucketName = params["Bucket"] as? String ?: throw ServiceException("NoSuchBucket", "Bucket name is required")
        val key = params["Key"] as? String ?: throw ServiceException("NoSuchKey", "Key is required")
        
        val bucket = GlobalS3Store.buckets[bucketName] ?: throw ServiceException("NoSuchBucket", "The specified bucket does not exist")
        val store = stores[bucket.accountId, bucket.region]
        val bucketObjects = store.objects[bucketName] ?: throw ServiceException("NoSuchBucket", "The specified bucket does not exist")
        val s3Object = bucketObjects[key] ?: throw ServiceException("NoSuchKey", "The specified key does not exist")
        
        val inputStream = objectStore.getObject(bucketName, key) ?: throw ServiceException("InternalError", "Failed to retrieve object data")
        
        return mapOf(
            "Body" to inputStream,
            "ContentLength" to s3Object.size,
            "ETag" to s3Object.etag,
            "ContentType" to s3Object.contentType,
            "LastModified" to s3Object.lastModified.toString()
        )
    }

    @AwsOperation("DeleteObject")
    fun deleteObject(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val bucketName = params["Bucket"] as? String ?: throw ServiceException("NoSuchBucket", "Bucket name is required")
        val key = params["Key"] as? String ?: throw ServiceException("NoSuchKey", "Key is required")
        
        val bucket = GlobalS3Store.buckets[bucketName] ?: throw ServiceException("NoSuchBucket", "The specified bucket does not exist")
        val store = stores[bucket.accountId, bucket.region]
        val bucketObjects = store.objects[bucketName] ?: throw ServiceException("NoSuchBucket", "The specified bucket does not exist")
        
        bucketObjects.remove(key)
        objectStore.deleteObject(bucketName, key)
        
        logger.debug("Deleted S3 object: {}/{}", bucketName, key)
        return emptyMap()
    }

    @AwsOperation("DeleteBucket")
    fun deleteBucket(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val bucketName = params["Bucket"] as? String ?: throw ServiceException("NoSuchBucket", "Bucket name is required")
        
        val bucket = GlobalS3Store.buckets[bucketName] ?: throw ServiceException("NoSuchBucket", "The specified bucket does not exist")
        
        // AWS S3 allows bucket deletion only by owner
        if (bucket.accountId != context.accountId) {
             throw ServiceException("AccessDenied", "Access Denied")
        }

        val store = stores[bucket.accountId, bucket.region]
        val bucketObjects = store.objects[bucketName]
        if (bucketObjects != null && bucketObjects.isNotEmpty()) {
            throw ServiceException("BucketNotEmpty", "The bucket you tried to delete is not empty")
        }
        
        GlobalS3Store.buckets.remove(bucketName)
        store.objects.remove(bucketName)
        
        logger.info("Deleted S3 bucket: {}", bucketName)
        return emptyMap()
    }

    @AwsOperation("ListObjectsV2")
    fun listObjectsV2(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val bucketName = params["Bucket"] as? String ?: throw ServiceException("NoSuchBucket", "Bucket name is required")
        val prefix = params["Prefix"] as? String ?: ""
        
        val bucket = GlobalS3Store.buckets[bucketName] ?: throw ServiceException("NoSuchBucket", "The specified bucket does not exist")
        val store = stores[bucket.accountId, bucket.region]
        val bucketObjects = store.objects[bucketName] ?: throw ServiceException("NoSuchBucket", "The specified bucket does not exist")
        
        val contents = bucketObjects.values.asSequence()
            .filter { it.key.startsWith(prefix) }
            .map {
                mapOf(
                    "Key" to it.key,
                    "Size" to it.size,
                    "ETag" to it.etag,
                    "LastModified" to it.lastModified.toString()
                )
            }
            .toList()
            
        return mapOf(
            "Name" to bucketName,
            "Contents" to contents,
            "KeyCount" to contents.size,
            "IsTruncated" to false
        )
    }
}
