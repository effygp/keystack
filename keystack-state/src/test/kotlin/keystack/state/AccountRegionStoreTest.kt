package keystack.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class TestStore : ServiceStore() {
    var data: String = ""
}

class AccountRegionStoreTest {

    @Test
    fun `should create and retrieve store`() {
        val store = AccountRegionStore("test") { TestStore() }
        val s1 = store["123456789012", "us-east-1"]
        
        assertEquals("test", s1.serviceName)
        assertEquals("123456789012", s1.accountId)
        assertEquals("us-east-1", s1.regionName)
        
        val s2 = store["123456789012", "us-east-1"]
        assertSame(s1, s2)
    }

    @Test
    fun `should isolate different accounts and regions`() {
        val store = AccountRegionStore("test") { TestStore() }
        val s1 = store["111111111111", "us-east-1"]
        val s2 = store["222222222222", "us-east-1"]
        val s3 = store["111111111111", "us-west-2"]
        
        assertNotSame(s1, s2)
        assertNotSame(s1, s3)
        assertNotSame(s2, s3)
    }

    @Test
    fun `should reset all stores`() {
        val store = AccountRegionStore("test") { TestStore() }
        val s1 = store["111111111111", "us-east-1"]
        s1.data = "dirty"
        
        store.reset()
        
        val s2 = store["111111111111", "us-east-1"]
        assertNotSame(s1, s2)
        assertEquals("", s2.data)
    }

    @Test
    fun `should iterate over all stores`() {
        val store = AccountRegionStore("test") { TestStore() }
        store["1", "r1"]
        store["1", "r2"]
        store["2", "r1"]
        
        var count = 0
        store.forEach { _, _, _ -> count++ }
        assertEquals(3, count)
    }
}
