package dev.pinkcollab.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairedHostRegistryTest {
    @Test fun rename_persists_without_reconnecting_and_clears_when_it_matches_the_gateway_name() = runBlocking {
        val original = PairedHost(Host("h", "Desktop", "", "", ""), "https://h", "credential", "client")
        val store = object : PairedHostStore {
            var saved = listOf(original)
            override suspend fun read() = saved
            override suspend fun save(hosts: List<PairedHost>) { saved = hosts }
        }
        val state = MutableStateFlow(AppState())
        var connects = 0
        val registry = PairedHostRegistry(store, state, { connects++ }, {})
        registry.initialize()
        registry.rename("h", "  Office  ")
        assertEquals(1, connects)
        assertEquals("Office", state.value.hosts.getValue("h").paired.localName)
        assertEquals("Office", store.saved.single().localName)
        registry.rename("h", "Desktop")
        assertNull(state.value.hosts.getValue("h").paired.localName)
        assertNull(store.saved.single().localName)
        assertEquals(1, connects)
        registry.rename("h", "   ")
        assertEquals("Desktop", state.value.hosts.getValue("h").paired.displayName)
    }
}
