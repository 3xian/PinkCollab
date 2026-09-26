package dev.pinkcollab.ui

import dev.pinkcollab.data.ModelCatalog
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionResourceLoaderTest {
    private val session = Session("session", "host", "/work", "Work", SessionStatus.Idle, "Ready",
        false, null, "", "", false)
    private val key = SessionKey("host", "session")

    @Test fun newer_detail_request_wins_and_cancels_the_previous_one() = runTest {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val actions = object : SessionResourceActions {
            override fun hasDetail(key: SessionKey) = false
            override suspend fun detail(session: Session) {
                calls += session.id
                if (calls.size == 1) first.await() else second.await()
            }
            override suspend fun models(session: Session) = ModelCatalog(emptyList(), emptyList())
        }
        val loader = SessionResourceLoader(backgroundScope, actions, errors::add)
        loader.loadDetail(session)
        runCurrent()
        loader.loadDetail(session, force = true)
        runCurrent()
        assertEquals(2, calls.size)
        second.complete(Unit)
        runCurrent()
        first.complete(Unit)
        runCurrent()
        assertEquals(LoadState.Ready(Unit), loader.detailLoads.value[key])
        assertTrue(errors.isEmpty())
    }

    @Test fun duplicate_model_load_is_suppressed_while_pending() = runTest {
        val result = CompletableDeferred<ModelCatalog>()
        var calls = 0
        val actions = object : SessionResourceActions {
            override fun hasDetail(key: SessionKey) = false
            override suspend fun detail(session: Session) = Unit
            override suspend fun models(session: Session): ModelCatalog {
                calls++
                return result.await()
            }
        }
        val loader = SessionResourceLoader(backgroundScope, actions, {})
        loader.loadModels(session)
        runCurrent()
        loader.loadModels(session)
        runCurrent()
        assertEquals(1, calls)
        val catalog = ModelCatalog(emptyList(), listOf("medium"))
        result.complete(catalog)
        runCurrent()
        assertEquals(LoadState.Ready(catalog), loader.modelLoads.value[key])
        loader.loadModels(session, force = false)
        runCurrent()
        assertEquals(1, calls)
    }
}
