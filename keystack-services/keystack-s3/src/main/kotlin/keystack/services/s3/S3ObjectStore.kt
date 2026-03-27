package keystack.services.s3

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.slf4j.LoggerFactory

data class StorageMetadata(
    val etag: String,
    val size: Long
)

/**
 * Handles the actual streaming of bytes to the local disk.
 */
class FilesystemS3ObjectStore(private val baseDir: Path) {
    private val logger = LoggerFactory.getLogger(FilesystemS3ObjectStore::class.java)

    init {
        if (!Files.exists(baseDir)) {
            Files.createDirectories(baseDir)
        }
    }

    fun putObject(bucket: String, key: String, data: InputStream): StorageMetadata {
        val bucketDir = baseDir.resolve(bucket)
        if (!Files.exists(bucketDir)) {
            Files.createDirectories(bucketDir)
        }
        
        // Simple hash for key to avoid deep nesting issues
        val keyHash = md5(key)
        val targetPath = bucketDir.resolve(keyHash)
        
        Files.copy(data, targetPath, StandardCopyOption.REPLACE_EXISTING)
        
        val size = Files.size(targetPath)
        val etag = calculateEtag(targetPath)
        
        logger.debug("Stored S3 object: {}/{} at {}", bucket, key, targetPath)
        return StorageMetadata(etag, size)
    }

    fun getObject(bucket: String, key: String): InputStream? {
        val keyHash = md5(key)
        val targetPath = baseDir.resolve(bucket).resolve(keyHash)
        return if (Files.exists(targetPath)) {
            Files.newInputStream(targetPath)
        } else null
    }

    fun deleteObject(bucket: String, key: String) {
        val keyHash = md5(key)
        val targetPath = baseDir.resolve(bucket).resolve(keyHash)
        Files.deleteIfExists(targetPath)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun calculateEtag(path: Path): String {
        val md = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(8192)
        Files.newInputStream(path).use { input ->
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                md.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        return "\"" + md.digest().joinToString("") { "%02x".format(it) } + "\""
    }
}
