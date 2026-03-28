package keystack.services.iam

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class IamProviderTest {
    private lateinit var provider: IamProvider
    private val context = RequestContext(
        request = mockk(relaxed = true),
        accountId = "123456789012",
        region = "us-east-1"
    )

    @BeforeTest
    fun setup() {
        provider = IamProvider()
    }

    @Test
    fun `test role lifecycle`() = runBlocking {
        val roleName = "test-role"
        val policyDoc = "{\"Statement\": []}"
        
        // 1. Create Role
        val createResult = provider.createRole(context, mapOf(
            "RoleName" to roleName,
            "AssumeRolePolicyDocument" to policyDoc
        ))
        val role = createResult["Role"] as Map<String, Any?>
        assertEquals(roleName, role["RoleName"])
        
        // 2. Get Role
        val getResult = provider.getRole(context, mapOf("RoleName" to roleName))
        assertNotNull(getResult["Role"])
        
        // 3. List Roles
        val listResult = provider.listRoles(context, emptyMap())
        val roles = listResult["Roles"] as List<Map<String, Any?>>
        assertTrue(roles.any { it["RoleName"] == roleName })
        
        // 4. Delete Role
        provider.deleteRole(context, mapOf("RoleName" to roleName))
    }

    @Test
    fun `test policy operations`() = runBlocking {
        val policyName = "test-policy"
        val policyDoc = "{\"Statement\": []}"
        
        // 1. Create Policy
        val createResult = provider.createPolicy(context, mapOf(
            "PolicyName" to policyName,
            "PolicyDocument" to policyDoc
        ))
        val policy = createResult["Policy"] as Map<String, Any?>
        assertEquals(policyName, policy["PolicyName"])
        val policyArn = policy["Arn"] as String
        
        // 2. Attach Policy to Role
        val roleName = "attach-test-role"
        provider.createRole(context, mapOf("RoleName" to roleName, "AssumeRolePolicyDocument" to "{}"))
        
        provider.attachRolePolicy(context, mapOf(
            "RoleName" to roleName,
            "PolicyArn" to policyArn
        ))
    }

    @Test
    fun `test get non-existent role fails`() = runBlocking {
        assertFailsWith<ServiceException> {
            provider.getRole(context, mapOf("RoleName" to "no-role"))
        }.also {
            assertEquals("NoSuchEntity", it.code)
        }
    }
}
