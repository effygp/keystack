package keystack.services.cloudformation

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import keystack.provider.ServiceRegistry
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CloudFormationProviderTest {
    private lateinit var provider: CloudFormationProvider
    private val registry = mockk<ServiceRegistry>(relaxed = true)
    private val context = RequestContext(
        request = mockk(relaxed = true),
        accountId = "123456789012",
        region = "us-east-1"
    )

    @BeforeTest
    fun setup() {
        provider = CloudFormationProvider(registry)
        
        // Mock registry to return mock dispatchers for internal service calls
        val mockDispatcher = mockk<keystack.provider.OperationDispatcher>(relaxed = true)
        io.mockk.coEvery { registry.getDispatchTable(any()) } returns mapOf(
            "CreateBucket" to mockDispatcher,
            "DeleteBucket" to mockDispatcher,
            "CreateQueue" to mockDispatcher,
            "DeleteQueue" to mockDispatcher
        )
    }

    @Test
    fun `test stack lifecycle`() = runBlocking {
        val stackName = "test-stack"
        val template = """
            {
              "Resources": {
                "MyBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {
                    "BucketName": "my-test-bucket"
                  }
                }
              }
            }
        """.trimIndent()
        
        // 1. Create Stack
        val createResult = provider.createStack(context, mapOf(
            "StackName" to stackName,
            "TemplateBody" to template
        ))
        assertNotNull(createResult["StackId"])
        
        // 2. Describe Stacks (Assert CREATE_IN_PROGRESS immediately)
        var describeResult = provider.describeStacks(context, mapOf("StackName" to stackName))
        var stacks = describeResult["Stacks"] as List<Map<String, Any?>>
        assertEquals("CREATE_IN_PROGRESS", stacks[0]["StackStatus"])
        
        // Poll for CREATE_COMPLETE
        var attempts = 0
        while (attempts < 10 && stacks[0]["StackStatus"] != "CREATE_COMPLETE") {
            kotlinx.coroutines.delay(100)
            describeResult = provider.describeStacks(context, mapOf("StackName" to stackName))
            stacks = describeResult["Stacks"] as List<Map<String, Any?>>
            attempts++
        }
        assertEquals("CREATE_COMPLETE", stacks[0]["StackStatus"])
        
        // 3. Describe Resources
        val resourcesResult = provider.describeStackResources(context, mapOf("StackName" to stackName))
        val resources = resourcesResult["StackResources"] as List<Map<String, Any?>>
        assertTrue(resources.any { it["LogicalResourceId"] == "MyBucket" })
        
        // 4. Delete Stack
        provider.deleteStack(context, mapOf("StackName" to stackName))
        
        // 5. Verify deleted
        val describeResult2 = provider.describeStacks(context, emptyMap())
        val stacks2 = describeResult2["Stacks"] as List<Map<String, Any?>>
        assertFalse(stacks2.any { it["StackName"] == stackName })
    }

    @Test
    fun `test create duplicate stack fails`() = runBlocking {
        val stackName = "dup-stack"
        val template = "{\"Resources\": {}}"
        provider.createStack(context, mapOf("StackName" to stackName, "TemplateBody" to template))
        
        assertFailsWith<ServiceException> {
            provider.createStack(context, mapOf("StackName" to stackName, "TemplateBody" to template))
        }.also {
            assertEquals("AlreadyExistsException", it.code)
        }
    }
}
