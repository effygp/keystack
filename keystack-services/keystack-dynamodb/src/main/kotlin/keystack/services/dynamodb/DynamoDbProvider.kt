package keystack.services.dynamodb

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import keystack.provider.AwsOperation
import keystack.provider.ServiceProvider
import keystack.state.AccountRegionStore
import org.slf4j.LoggerFactory

class DynamoDbProvider : ServiceProvider {
    override val serviceName = "dynamodb"
    private val logger = LoggerFactory.getLogger(DynamoDbProvider::class.java)
    
    private val stores = AccountRegionStore("dynamodb") { DynamoDbStore() }

    @AwsOperation("CreateTable")
    fun createTable(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val tableName = params["TableName"] as? String ?: throw ServiceException("MissingParameter", "TableName is required")
        val store = stores[context.accountId, context.region]
        
        if (store.tables.containsKey(tableName)) {
            throw ServiceException("ResourceInUseException", "Table already exists")
        }
        
        val keySchema = params["KeySchema"] as? List<Map<String, String>> ?: emptyList()
        val attributeDefinitions = params["AttributeDefinitions"] as? List<Map<String, String>> ?: emptyList()
        
        val schema = TableSchema(tableName, keySchema, attributeDefinitions)
        store.tables[tableName] = schema
        store.items[tableName] = mutableListOf()
        
        logger.info("Created DynamoDB table: {} in region: {}", tableName, context.region)
        
        return mapOf(
            "TableDescription" to mapOf(
                "TableName" to tableName,
                "TableStatus" to "ACTIVE",
                "CreationDateTime" to schema.creationDateTime
            )
        )
    }

    @AwsOperation("PutItem")
    fun putItem(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val tableName = params["TableName"] as? String ?: throw ServiceException("MissingParameter", "TableName is required")
        val item = params["Item"] as? Map<String, AttributeValue> ?: emptyMap()
        
        val store = stores[context.accountId, context.region]
        val items = store.items[tableName] ?: throw ServiceException("ResourceNotFoundException", "Table not found")
        
        // Simplified PutItem: just add to list (should handle updates based on keys)
        items.add(item)
        
        return emptyMap()
    }

    @AwsOperation("GetItem")
    fun getItem(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val tableName = params["TableName"] as? String ?: throw ServiceException("MissingParameter", "TableName is required")
        val key = params["Key"] as? Map<String, AttributeValue> ?: emptyMap()
        
        val store = stores[context.accountId, context.region]
        val items = store.items[tableName] ?: throw ServiceException("ResourceNotFoundException", "Table not found")
        
        // Simplified lookup: find first match for key
        val result = items.find { item ->
            key.all { (k, v) -> item[k] == v }
        }
        
        return if (result != null) {
            mapOf("Item" to result)
        } else {
            emptyMap()
        }
    }

    @AwsOperation("DescribeTable")
    fun describeTable(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val tableName = params["TableName"] as? String ?: throw ServiceException("MissingParameter", "TableName is required")
        val store = stores[context.accountId, context.region]
        
        val table = store.tables[tableName] ?: throw ServiceException("ResourceNotFoundException", "Table not found")
        
        return mapOf(
            "Table" to mapOf(
                "TableName" to table.tableName,
                "TableStatus" to "ACTIVE",
                "KeySchema" to table.keySchema,
                "AttributeDefinitions" to table.attributeDefinitions
            )
        )
    }
}
