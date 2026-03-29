package keystack.services.lambda

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import java.security.MessageDigest

class CodeManager(private val storagePath: Path) {
    init {
        Files.createDirectories(storagePath)
    }

    fun storeCode(functionName: String, zipBase64: String): FunctionCode {
        val functionDir = storagePath.resolve(functionName)
        Files.createDirectories(functionDir)

        val zipPath = functionDir.resolve("function.zip")
        val zipBytes = Base64.getDecoder().decode(zipBase64)
        Files.write(zipPath, zipBytes)

        val codePath = functionDir.resolve("code")
        if (Files.exists(codePath)) {
            codePath.toFile().deleteRecursively()
        }
        Files.createDirectories(codePath)
        extractZip(zipBytes, codePath)

        return FunctionCode(
            zipFilePath = zipPath,
            codeSha256 = sha256Hex(zipBytes),
            codeSize = zipBytes.size.toLong()
        )
    }

    /**
     * Stores a reference to a host path for hot reloading.
     */
    fun storeHotReloadCode(functionName: String, hostPath: String): FunctionCode {
        return FunctionCode(
            zipFilePath = null,
            isHotReloading = true,
            hotReloadPath = hostPath
        )
    }

    fun deleteCode(functionName: String) {
        val functionDir = storagePath.resolve(functionName)
        if (Files.exists(functionDir)) {
            functionDir.toFile().deleteRecursively()
        }
    }

    private fun extractZip(zipBytes: ByteArray, target: Path) {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zipInputStream ->
            var entry = zipInputStream.nextEntry
            while (entry != null) {
                val filePath = target.resolve(entry.name)
                if (entry.isDirectory) {
                    Files.createDirectories(filePath)
                } else {
                    Files.createDirectories(filePath.parent)
                    Files.copy(zipInputStream, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
