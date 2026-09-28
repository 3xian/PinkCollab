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

    @Test fun cached_models_survive_reopen_but_refresh_and_runtime_change_reload() = runTest {
        var calls = 0
        val actions = object : SessionResourceActions {
            override fun hasDetail(key: SessionKey) = false
            override suspend fun detail(session: Session) = Unit
            override suspend fun models(session: Session): ModelCatalog {
                calls++
                return ModelCatalog(emptyList(), listOf("version-$calls"))
            }
        }
        val loader = SessionResourceLoader(backgroundScope, actions, {})
        val firstRuntime = session.copy(generation = "generation-1")

        loader.loadModels(firstRuntime)
        runCurrent()
        loader.loadModels(firstRuntime) // Reopening the picker uses the cached catalog.
        runCurrent()
        assertEquals(1, calls)
        assertEquals(listOf("version-1"), (loader.modelLoads.value[key] as LoadState.Ready).value.thinkingLevels)

        loader.loadModels(firstRuntime, force = true) // Explicit refresh.
        runCurrent()
        assertEquals(2, calls)
        assertEquals(listOf("version-2"), (loader.modelLoads.value[key] as LoadState.Ready).value.thinkingLevels)

        loader.loadModels(firstRuntime.copy(generation = "generation-2"))
        runCurrent()
        assertEquals(3, calls)
        assertEquals(listOf("version-3"), (loader.modelLoads.value[key] as LoadState.Ready).value.thinkingLevels)
    }

    @Test fun failed_refresh_keeps_cached_models_and_reports_error() = runTest {
        var calls = 0
        val finishRefresh = CompletableDeferred<Unit>()
        val errors = mutableListOf<String>()
        val catalog = ModelCatalog(emptyList(), listOf("high"))
        val actions = object : SessionResourceActions {
            override fun hasDetail(key: SessionKey) = false
            override suspend fun detail(session: Session) = Unit
            override suspend fun models(session: Session): ModelCatalog {
                calls++
                if (calls > 1) {
                    finishRefresh.await()
                    throw IllegalStateException("Refresh failed")
                }
                return catalog
            }
        }
        val loader = SessionResourceLoader(backgroundScope, actions, errors::add)

        loader.loadModels(session)
        runCurrent()
        loader.loadModels(session, force = true)
        runCurrent()
        assertEquals(LoadState.Ready(catalog, refreshing = true), loader.modelLoads.value[key])
        finishRefresh.complete(Unit)
        runCurrent()

        assertEquals(LoadState.Ready(catalog), loader.modelLoads.value[key])
        assertEquals(listOf("Refresh failed"), errors)
        loader.loadModels(session)
        runCurrent()
        assertEquals(2, calls)
    }

    @Test fun forgetting_host_clears_pending_model_load_and_allows_fresh_load() = runTest {
        val first = CompletableDeferred<ModelCatalog>()
        var calls = 0
        val actions = object : SessionResourceActions {
            override fun hasDetail(key: SessionKey) = false
            override suspend fun detail(session: Session) = Unit
            override suspend fun models(session: Session): ModelCatalog {
                calls++
                return if (calls == 1) first.await() else ModelCatalog(emptyList(), listOf("fresh"))
            }
        }
        val loader = SessionResourceLoader(backgroundScope, actions, {})
        loader.loadModels(session)
        runCurrent()
        loader.removeHost("host")
        runCurrent()
        assertEquals(null, loader.modelLoads.value[key])

        loader.loadModels(session)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(listOf("fresh"), (loader.modelLoads.value[key] as LoadState.Ready).value.thinkingLevels)
    }
}
