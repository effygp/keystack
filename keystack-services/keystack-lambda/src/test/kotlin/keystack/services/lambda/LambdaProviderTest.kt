package keystack.services.lambda

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LambdaProviderTest {
    private lateinit var provider: LambdaProvider
    private val context = RequestContext(
        request = mockk(relaxed = true),
        accountId = "123456789012",
        region = "us-east-1"
    )

    @BeforeTest
    fun setup() {
        provider = LambdaProvider()
    }

    @Test
    fun `test function lifecycle`() = runBlocking {
        val functionName = "test-function"
        
        // 1. Create Function
        val createResult = provider.createFunction(context, mapOf(
            "FunctionName" to functionName,
            "Runtime" to "python3.9",
            "Handler" to "index.handler"
        ))
        assertEquals(functionName, createResult["FunctionName"])
        assertNotNull(createResult["FunctionArn"])
        
        // 2. Get Function
        val getResult = provider.getFunction(context, mapOf("FunctionName" to functionName))
        val config = getResult["Configuration"] as FunctionConfiguration
        assertEquals(functionName, config.functionName)
        
        // 3. List Functions
        val listResult = provider.listFunctions(context, emptyMap())
        val functions = listResult["Functions"] as List<FunctionConfiguration>
        assertTrue(functions.any { it.functionName == functionName })
        
        // 4. Delete Function
        provider.deleteFunction(context, mapOf("FunctionName" to functionName))
    }

    @Test
    fun `test function invocation`() = runBlocking {
        val functionName = "invoke-test"
        provider.createFunction(context, mapOf("FunctionName" to functionName))
        
        val inputPayload = "{\"key\": \"value\"}"
        val invokeResult = provider.invoke(context, mapOf(
            "FunctionName" to functionName,
            "Payload" to inputPayload
        ))
        
        assertEquals(200, invokeResult["StatusCode"])
        assertEquals(inputPayload, invokeResult["Payload"])
    }

    @Test
    fun `test function code storage`() = runBlocking {
        val functionName = "code-test"
        val zipBase64 = "UEsDBAoAAAAAALhufVwgMDo2BgAAAAYAAAAIABwAaW5kZXgucHlVVAkAAxxZyWkaWclpdXgLAAEE9QEAAAQUAAAAaGVsbG8KUEsBAh4DCgAAAAAAuG59XCAwOjYGAAAABgAAAAgAGAAAAAAAAQAAAKSBAAAAAGluZGV4LnB5VVQFAAMcWclpdXgLAAEE9QEAAAQUAAAAUEsFBgAAAAABAAEATgAAAEgAAAAAAA=="
        
        // 1. Create Function with code
        provider.createFunction(context, mapOf(
            "FunctionName" to functionName,
            "Code" to mapOf("ZipFile" to zipBase64)
        ))
        
        // 2. Get Function and verify code exists
        val getResult = provider.getFunction(context, mapOf("FunctionName" to functionName))
        val config = getResult["Configuration"] as FunctionConfiguration
        assertNotNull(config.code)
        assertTrue(config.code!!.codeSize > 0)
        assertNotNull(config.code!!.zipFilePath)
        
        // 3. Delete Function
        provider.deleteFunction(context, mapOf("FunctionName" to functionName))
    }

    @Test
    fun `test get non-existent function fails`() = runBlocking {
        assertFailsWith<ServiceException> {
            provider.getFunction(context, mapOf("FunctionName" to "no-func"))
        }.also {
            assertEquals("ResourceNotFoundException", it.code)
        }
    }
}
