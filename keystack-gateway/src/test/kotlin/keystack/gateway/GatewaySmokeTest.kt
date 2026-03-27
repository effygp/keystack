package keystack.gateway

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.*

class GatewaySmokeTest {

    @Test
    fun testHealthEndpoint() = testApplication {
        application {
            Gateway().apply { module() }
        }
        
        val response = client.get("/_keystack/health")
        assertEquals(HttpStatusCode.OK, response.status)
        
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("running", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun testUnknownRequest() = testApplication {
        application {
            Gateway().apply { module() }
        }
        
        val response = client.get("/some/unknown/path")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
