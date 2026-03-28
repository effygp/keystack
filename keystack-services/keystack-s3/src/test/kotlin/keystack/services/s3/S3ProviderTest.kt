package keystack.services.s3

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.io.InputStream

class S3ProviderTest {
    private lateinit var provider: S3Provider
    private val context = RequestContext(
        request = mockk(relaxed = true),
        accountId = "123456789012",
        region = "us-east-1"
    )

    @BeforeTest
    fun setup() {
        provider = S3Provider()
    }

    @Test
    fun `test bucket lifecycle`() = runBlocking {
        val bucketName = "test-bucket"
        
        // 1. Create Bucket
        provider.createBucket(context, mapOf("Bucket" to bucketName))
        
        // 2. List Objects (should be empty)
        val listResult = provider.listObjectsV2(context, mapOf("Bucket" to bucketName))
        assertEquals(0, (listResult["Contents"] as List<*>).size)
        
        // 3. Delete Bucket
        provider.deleteBucket(context, mapOf("Bucket" to bucketName))
    }

    @Test
    fun `test object lifecycle`() = runBlocking {
        val bucketName = "obj-test-bucket"
        val key = "hello.txt"
        val content = "Hello S3!"
        
        provider.createBucket(context, mapOf("Bucket" to bucketName))
        
        // 1. Put Object
        val putResult = provider.putObject(context, mapOf(
            "Bucket" to bucketName,
            "Key" to key,
            "Body" to content
        ))
        assertNotNull(putResult["ETag"])
        
        // 2. Get Object
        val getResult = provider.getObject(context, mapOf(
            "Bucket" to bucketName,
            "Key" to key
        ))
        val bodyStream = getResult["Body"] as InputStream
        val bodyContent = bodyStream.bufferedReader().use { it.readText() }
        assertEquals(content, bodyContent)
        assertEquals(content.length.toLong(), getResult["ContentLength"])
        
        // 3. List Objects
        val listResult = provider.listObjectsV2(context, mapOf("Bucket" to bucketName))
        val contents = listResult["Contents"] as List<Map<String, Any?>>
        assertEquals(1, contents.size)
        assertEquals(key, contents[0]["Key"])
        
        // 4. Delete Object
        provider.deleteObject(context, mapOf(
            "Bucket" to bucketName,
            "Key" to key
        ))
        
        // 5. Verify deleted
        val listResult2 = provider.listObjectsV2(context, mapOf("Bucket" to bucketName))
        assertEquals(0, (listResult2["Contents"] as List<*>).size)
        
        provider.deleteBucket(context, mapOf("Bucket" to bucketName))
    }

    @Test
    fun `test delete non-empty bucket fails`() = runBlocking {
        val bucketName = "non-empty-delete"
        provider.createBucket(context, mapOf("Bucket" to bucketName))
        provider.putObject(context, mapOf("Bucket" to bucketName, "Key" to "some-key", "Body" to "data"))
        
        assertFailsWith<ServiceException> {
            provider.deleteBucket(context, mapOf("Bucket" to bucketName))
        }.also {
            assertEquals("BucketNotEmpty", it.code)
        }
        
        // Cleanup
        provider.deleteObject(context, mapOf("Bucket" to bucketName, "Key" to "some-key"))
        provider.deleteBucket(context, mapOf("Bucket" to bucketName))
    }

    @Test
    fun `test get non-existent bucket fails`() = runBlocking {
        assertFailsWith<ServiceException> {
            provider.listObjectsV2(context, mapOf("Bucket" to "no-such-bucket"))
        }.also {
            assertEquals("NoSuchBucket", it.code)
        }
    }
}
