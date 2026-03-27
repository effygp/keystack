package keystack.services.dynamodb

import keystack.state.ServiceStore
import java.util.concurrent.ConcurrentHashMap

data class AttributeValue(
    val S: String? = null,
    val N: String? = null,
    val B: String? = null,
    val BOOL: Boolean? = null,
    val NULL: Boolean? = null,
    val M: Map<String, AttributeValue>? = null,
    val L: List<AttributeValue>? = null,
    val SS: List<String>? = null,
    val NS: List<String>? = null,
    val BS: List<String>? = null
)

data class TableSchema(
    val tableName: String,
    val keySchema: List<Map<String, String>>,
    val attributeDefinitions: List<Map<String, String>>,
    val creationDateTime: Long = System.currentTimeMillis()
)

class DynamoDbStore : ServiceStore() {
    val tables = ConcurrentHashMap<String, TableSchema>()
    val items = ConcurrentHashMap<String, MutableList<Map<String, AttributeValue>>>()
}
