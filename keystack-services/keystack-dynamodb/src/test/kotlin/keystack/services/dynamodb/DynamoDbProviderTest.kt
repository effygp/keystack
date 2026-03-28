package keystack.services.dynamodb

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DynamoDbProviderTest {
    private lateinit var provider: DynamoDbProvider
    private val context = RequestContext(
        request = mockk(relaxed = true),
        accountId = "123456789012",
        region = "us-east-1"
    )

    @BeforeTest
    fun setup() {
        provider = DynamoDbProvider()
    }

    @Test
    fun `test table lifecycle`() = runBlocking {
        val tableName = "test-table"
        
        // 1. Create Table
        val createResult = provider.createTable(context, mapOf(
            "TableName" to tableName,
            "KeySchema" to listOf(mapOf("AttributeName" to "pk", "KeyType" to "HASH")),
            "AttributeDefinitions" to listOf(mapOf("AttributeName" to "pk", "AttributeType" to "S"))
        ))
        assertEquals(tableName, (createResult["TableDescription"] as Map<*, *>)["TableName"])
        
        // 2. Describe Table
        val describeResult = provider.describeTable(context, mapOf("TableName" to tableName))
        assertEquals("ACTIVE", (describeResult["Table"] as Map<*, *>)["TableStatus"])
    }

    @Test
    fun `test item operations`() = runBlocking {
        val tableName = "item-test-table"
        provider.createTable(context, mapOf("TableName" to tableName))
        
        val item = mapOf("pk" to AttributeValue(S = "user1"), "name" to AttributeValue(S = "Alice"))
        
        // 1. Put Item
        provider.putItem(context, mapOf(
            "TableName" to tableName,
            "Item" to item
        ))
        
        // 2. Get Item
        val getResult = provider.getItem(context, mapOf(
            "TableName" to tableName,
            "Key" to mapOf("pk" to AttributeValue(S = "user1"))
        ))
        val retrievedItem = getResult["Item"] as Map<String, AttributeValue>
        assertEquals("Alice", retrievedItem["name"]?.S)
    }

    @Test
    fun `test table already exists fails`() = runBlocking {
        val tableName = "duplicate-table"
        provider.createTable(context, mapOf("TableName" to tableName))
        
        assertFailsWith<ServiceException> {
            provider.createTable(context, mapOf("TableName" to tableName))
        }.also {
            assertEquals("ResourceInUseException", it.code)
        }
    }

    @Test
    fun `test get item from non-existent table fails`() = runBlocking {
        assertFailsWith<ServiceException> {
            provider.getItem(context, mapOf("TableName" to "no-table", "Key" to emptyMap<String, Any>()))
        }.also {
            assertEquals("ResourceNotFoundException", it.code)
        }
    }
}
