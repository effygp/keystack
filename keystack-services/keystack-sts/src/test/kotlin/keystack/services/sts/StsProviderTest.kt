package keystack.services.sts

import keystack.gateway.RequestContext
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class StsProviderTest {
    private lateinit var provider: StsProvider
    private val context = RequestContext(
        request = mockk(relaxed = true),
        accountId = "123456789012",
        region = "us-east-1"
    )

    @BeforeTest
    fun setup() {
        provider = StsProvider()
    }

    @Test
    fun `test get caller identity`() = runBlocking {
        val result = provider.getCallerIdentity(context, emptyMap())
        assertEquals("123456789012", result["Account"])
        assertEquals("arn:aws:iam::123456789012:root", result["Arn"])
    }

    @Test
    fun `test assume role`() = runBlocking {
        val roleArn = "arn:aws:iam::123456789012:role/test-role"
        val result = provider.assumeRole(context, mapOf(
            "RoleArn" to roleArn,
            "RoleSessionName" to "test-session"
        ))
        
        val assumedUser = result["AssumedRoleUser"] as Map<*, *>
        assertTrue((assumedUser["Arn"] as String).startsWith(roleArn))
        
        val credentials = result["Credentials"] as Map<*, *>
        assertNotNull(credentials["AccessKeyId"])
        assertNotNull(credentials["SecretAccessKey"])
        assertNotNull(credentials["SessionToken"])
    }
}
