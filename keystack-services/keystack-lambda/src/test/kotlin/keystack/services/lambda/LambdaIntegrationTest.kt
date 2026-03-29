package keystack.services.lambda

import keystack.gateway.RequestContext
import kotlinx.coroutines.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.Base64
import java.nio.file.Paths
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.api.model.Container
import org.slf4j.LoggerFactory

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LambdaIntegrationTest {
    private lateinit var provider: LambdaProvider
    private val context = RequestContext(
        request = io.mockk.mockk(relaxed = true),
        accountId = "000000000000",
        region = "us-east-1"
    )

    @BeforeEach
    fun setup() {
        provider = LambdaProvider()
    }

    @AfterEach
    fun teardown() {
        provider.onStop()
    }

    @Test
    fun `test T1-T2 cold start and warm reuse`() = runBlocking {
        // Simple echo function in Python
        val pythonCode = """
            def handler(event, context):
                return event
        """.trimIndent()
        // Create a ZIP in memory and base64 encode it
        val zipBase64 = createZipBase64(mapOf("index.py" to pythonCode))

        val functionName = "integration-test-func"
        provider.createFunction(context, mapOf(
            "FunctionName" to functionName,
            "Runtime" to "python3.12",
            "Handler" to "index.handler",
            "Timeout" to 30,
            "Code" to mapOf("ZipFile" to zipBase64)
        ))

        // First Invoke (Cold Start)
        val payload1 = "{\"msg\": \"hello cold\"}"
        val result1 = provider.invoke(context, mapOf("FunctionName" to functionName, "Payload" to payload1))
        assertEquals(200, result1["StatusCode"]) { "First invoke failed: ${result1["Payload"]}" }
        assertEquals(payload1, result1["Payload"])

        // Second Invoke (Warm Reuse)
        val payload2 = "{\"msg\": \"hello warm\"}"
        val result2 = provider.invoke(context, mapOf("FunctionName" to functionName, "Payload" to payload2))
        assertEquals(200, result2["StatusCode"]) { "Second invoke failed: ${result2["Payload"]}" }
        assertEquals(payload2, result2["Payload"])
    }

    @Test
    fun `test T4 concurrent invocations`() = runBlocking {
        val pythonCode = "def handler(event, context): return event"
        val zipBase64 = createZipBase64(mapOf("index.py" to pythonCode))
        val functionName = "concurrent-func"
        provider.createFunction(context, mapOf(
            "FunctionName" to functionName,
            "Runtime" to "python3.12",
            "Handler" to "index.handler",
            "Timeout" to 30,
            "Code" to mapOf("ZipFile" to zipBase64)
        ))

        val jobs = (1..3).map { i ->
            async {
                val payload = "{\"id\": $i}"
                val result = provider.invoke(context, mapOf("FunctionName" to functionName, "Payload" to payload))
                assertEquals(200, result["StatusCode"])
                assertEquals(payload, result["Payload"])
            }
        }
        jobs.awaitAll()
    }

    @Test
    fun `test T5 provisioned concurrency`() = runBlocking {
        val pythonCode = "def handler(event, context): return event"
        val zipBase64 = createZipBase64(mapOf("index.py" to pythonCode))
        val functionName = "provisioned-func"
        provider.createFunction(context, mapOf(
            "FunctionName" to functionName,
            "Runtime" to "python3.12",
            "Handler" to "index.handler",
            "Timeout" to 30,
            "Code" to mapOf("ZipFile" to zipBase64)
        ))

        // Provision 1 container
        provider.putProvisionedConcurrencyConfig(context, mapOf(
            "FunctionName" to functionName,
            "Qualifier" to "\$LATEST",
            "ProvisionedConcurrentExecutions" to 1
        ))

        // Invoke - should be warm
        val payload = "{\"msg\": \"warm\"}"
        val result = provider.invoke(context, mapOf("FunctionName" to functionName, "Payload" to payload))
        assertEquals(200, result["StatusCode"])
        assertEquals(payload, result["Payload"])

        provider.deleteProvisionedConcurrencyConfig(context, mapOf(
            "FunctionName" to functionName,
            "Qualifier" to "\$LATEST"
        ))
    }

    @Test
    fun `test T6 function deletion cleanup`() = runBlocking {
        val pythonCode = "def handler(event, context): return event"
        val zipBase64 = createZipBase64(mapOf("index.py" to pythonCode))
        val functionName = "delete-cleanup-func"
        provider.createFunction(context, mapOf(
            "FunctionName" to functionName,
            "Runtime" to "python3.12",
            "Handler" to "index.handler",
            "Code" to mapOf("ZipFile" to zipBase64)
        ))

        // Create a warm container
        provider.invoke(context, mapOf("FunctionName" to functionName, "Payload" to "{}"))

        // Delete function
        provider.deleteFunction(context, mapOf("FunctionName" to functionName))
        
        // Code directory should be gone
        val storagePath = Paths.get("data", "lambda", functionName)
        assertFalse(java.nio.file.Files.exists(storagePath))
    }

    @Test
    fun `test T7 timeout enforcement`() = runBlocking {
        val pythonCode = """
            import time
            def handler(event, context):
                time.sleep(10)
                return event
        """.trimIndent()
        val zipBase64 = createZipBase64(mapOf("index.py" to pythonCode))
        val functionName = "timeout-func"
        provider.createFunction(context, mapOf(
            "FunctionName" to functionName,
            "Runtime" to "python3.12",
            "Handler" to "index.handler",
            "Timeout" to 2, // 2 seconds timeout
            "Code" to mapOf("ZipFile" to zipBase64)
        ))

        val result = provider.invoke(context, mapOf("FunctionName" to functionName, "Payload" to "{}"))
        assertEquals(500, result["StatusCode"])
        assertTrue(result["Payload"].toString().contains("timed out") || result.containsKey("FunctionError"))
    }

    @Test
    fun `test T8 environment variables`() = runBlocking {
        val pythonCode = """
            import os
            def handler(event, context):
                return {"val": os.environ.get("MY_VAR")}
        """.trimIndent()
        val zipBase64 = createZipBase64(mapOf("index.py" to pythonCode))
        val functionName = "env-var-func"
        provider.createFunction(context, mapOf(
            "FunctionName" to functionName,
            "Runtime" to "python3.12",
            "Handler" to "index.handler",
            "Environment" to mapOf("Variables" to mapOf("MY_VAR" to "hello-keystack")),
            "Code" to mapOf("ZipFile" to zipBase64)
        ))

        val result = provider.invoke(context, mapOf("FunctionName" to functionName, "Payload" to "{}"))
        assertEquals(200, result["StatusCode"])
        val payload = result["Payload"].toString()
        assertTrue(payload.contains("hello-keystack")) { "Payload should contain environment variable value but was: $payload" }
    }

    // Helper to create a base64 ZIP
    private fun createZipBase64(files: Map<String, String>): String {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            files.forEach { (name, content) ->
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }
}
