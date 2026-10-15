package dev.pinkcollab.ui

import dev.pinkcollab.data.ModelCatalog
import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionResourceLoaderTest {
    private val session = Session("session", "host", "/work", "Work", SessionStatus.Idle, "Ready",
        false, null, "", "", false)
    private val key = SessionKey("host", "session")

    @Test fun switching_focus_clears_old_failure_before_returning_to_the_session() = runTest {
        var calls = 0
        val actions = object : SessionResourceActions {
            override fun hasDetail(key: SessionKey) = false
            override suspend fun detail(session: Session) {
                if (++calls == 1) throw IllegalStateException("Snapshot timed out")
                awaitCancellation()
            }
            override suspend fun models(session: Session) = ModelCatalog(emptyList(), emptyList())
        }
        val loader = SessionResourceLoader(backgroundScope, actions, {})
        loader.loadDetail(session)
        runCurrent()
        assertTrue(loader.detailLoads.value[key] is LoadState.Failed)

        loader.loadDetail(session.copy(id = "another"))
        assertFalse(key in loader.detailLoads.value)
        runCurrent()
        loader.loadDetail(session, force = true)
        assertEquals(mapOf(key to LoadState.Loading), loader.detailLoads.value)
    }

    @Test fun switching_focus_cancels_old_timeout_without_reporting_an_error() = runTest {
        val errors = mutableListOf<String>()
        var cancelled = false
        val actions = object : SessionResourceActions {
            override fun hasDetail(key: SessionKey) = false
            override suspend fun detail(session: Session) {
                if (session.id != "session") return
                try {
                    kotlinx.coroutines.delay(15_000)
                    throw IllegalStateException("Snapshot timed out")
                } finally {
                    cancelled = true
                }
            }
            override suspend fun models(session: Session) = ModelCatalog(emptyList(), emptyList())
        }
        val loader = SessionResourceLoader(backgroundScope, actions, errors::add)
        loader.loadDetail(session)
        runCurrent()
        loader.loadDetail(session.copy(id = "another"))
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertTrue(cancelled)
        assertTrue(errors.isEmpty())
        assertFalse(key in loader.detailLoads.value)
    }

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

    @Test fun same_host_sessions_share_models_but_keep_their_own_active_model_fallback() = runTest {
        val first = CompletableDeferred<ModelCatalog>()
        val currentA = ModelInfo("provider", "a", "A")
        val currentB = ModelInfo("provider", "b", "B")
        val originalModels = listOf(currentA, currentB)
        val updatedModels = originalModels + ModelInfo("provider", "new", "New")
        val refreshedModels = updatedModels + ModelInfo("provider", "refresh", "Refresh")
        val remoteModels = listOf(ModelInfo("remote", "remote", "Remote"))
        val calls = mutableListOf<String>()
        val actions = object : SessionResourceActions {
            override fun hasDetail(key: SessionKey) = false
            override suspend fun detail(session: Session) = Unit
            override suspend fun models(session: Session): ModelCatalog {
                calls += session.id
                return when (session.id) {
                    "session" -> first.await()
                    "other" -> ModelCatalog(
                        if (calls.count { it == "other" } == 1) updatedModels else refreshedModels,
                        listOf("off", "high"),
                    )
                    else -> ModelCatalog(remoteModels, listOf("remote"))
                }
            }
        }
        val loader = SessionResourceLoader(backgroundScope, actions, {})
        val sibling = session.copy(id = "other", generation = "g-other")
        val siblingKey = SessionKey("host", "other")
        val remote = session.copy(id = "remote", hostId = "other-host")
        val remoteKey = SessionKey("other-host", "remote")

        loader.loadModels(session)
        runCurrent()
        loader.loadModels(sibling)
        loader.loadModels(sibling, force = true)
        runCurrent()
        assertEquals(listOf("session", "other"), calls)
        assertEquals(LoadState.Loading, loader.modelLoads.value[key])
        val siblingCatalog = (loader.modelLoads.value[siblingKey] as LoadState.Ready).value
        assertEquals(listOf("off", "high"), thinkingLevelsForModel(siblingCatalog, currentB, currentB))
        assertEquals(emptyList<String>(), thinkingLevelsForModel(siblingCatalog, currentA, currentB))

        // The older request finishes last without replacing the newer host list.
        first.complete(ModelCatalog(originalModels, listOf("off", "low")))
        runCurrent()
        val catalogA = (loader.modelLoads.value[key] as LoadState.Ready).value
        val catalogB = (loader.modelLoads.value[siblingKey] as LoadState.Ready).value
        assertEquals(updatedModels, catalogA.models)
        assertSame(catalogA.models, catalogB.models)
        assertEquals(listOf("off", "low"), thinkingLevelsForModel(catalogA, currentA, currentA))
        assertEquals(listOf("off", "high"), thinkingLevelsForModel(catalogB, currentB, currentB))
        assertEquals(emptyList<String>(), thinkingLevelsForModel(catalogA, currentB, currentA))

        loader.loadModels(session)
        loader.loadModels(sibling)
        loader.loadModels(remote)
        runCurrent()
        assertEquals(listOf("session", "other", "remote"), calls)
        assertEquals(remoteModels, (loader.modelLoads.value[remoteKey] as LoadState.Ready).value.models)
        assertEquals(updatedModels, (loader.modelLoads.value[key] as LoadState.Ready).value.models)

        loader.loadModels(sibling, force = true)
        runCurrent()
        val refreshedA = (loader.modelLoads.value[key] as LoadState.Ready).value
        val refreshedB = (loader.modelLoads.value[siblingKey] as LoadState.Ready).value
        assertEquals(listOf("session", "other", "remote", "other"), calls)
        assertEquals(refreshedModels, refreshedA.models)
        assertSame(refreshedA.models, refreshedB.models)
        assertEquals(listOf("off", "low"), thinkingLevelsForModel(refreshedA, currentA, currentA))
        assertEquals(listOf("off", "high"), thinkingLevelsForModel(refreshedB, currentB, currentB))
        assertEquals(remoteModels, (loader.modelLoads.value[remoteKey] as LoadState.Ready).value.models)

        loader.removeHost("host")
        assertFalse(key in loader.modelLoads.value)
        assertFalse(siblingKey in loader.modelLoads.value)
        assertEquals(remoteModels, (loader.modelLoads.value[remoteKey] as LoadState.Ready).value.models)
    }

    @Test fun replacing_runtime_discards_its_fallback_and_failure_does_not_restore_it() = runTest {
        val finishReplacement = CompletableDeferred<Unit>()
        val errors = mutableListOf<String>()
        var calls = 0
        val actions = object : SessionResourceActions {
            override fun hasDetail(key: SessionKey) = false
            override suspend fun detail(session: Session) = Unit
            override suspend fun models(session: Session): ModelCatalog {
                calls++
                if (calls == 2) {
                    finishReplacement.await()
                    throw IllegalStateException("New runtime unavailable")
                }
                return ModelCatalog(emptyList(), listOf("runtime-$calls"))
            }
        }
        val loader = SessionResourceLoader(backgroundScope, actions, errors::add)
        loader.loadModels(session.copy(generation = "old"))
        runCurrent()
        loader.loadModels(session.copy(generation = "new"))
        assertEquals(LoadState.Loading, loader.modelLoads.value[key])
        runCurrent()
        finishReplacement.complete(Unit)
        runCurrent()
        assertEquals(LoadState.Failed("New runtime unavailable"), loader.modelLoads.value[key])
        assertTrue(errors.isEmpty())

        loader.loadModels(session.copy(generation = "new"))
        runCurrent()
        assertEquals(3, calls)
        assertEquals(listOf("runtime-3"), (loader.modelLoads.value[key] as LoadState.Ready).value.thinkingLevels)
    }

    @Test fun replaced_runtime_request_is_cancelled_and_late_response_cannot_overwrite_models_or_levels() = runTest {
        val obsolete = CompletableDeferred<ModelCatalog>()
        var cancelled = false
        val errors = mutableListOf<String>()
        val fresh = ModelCatalog(listOf(ModelInfo("provider", "fresh", "Fresh")), listOf("high"))
        val actions = object : SessionResourceActions {
            override fun hasDetail(key: SessionKey) = false
            override suspend fun detail(session: Session) = Unit
            override suspend fun models(session: Session): ModelCatalog {
                if (session.generation == "new") return fresh
                try {
                    // Simulate an RPC result arriving after cancellation.
                    return withContext(NonCancellable) { obsolete.await() }
                } finally {
                    cancelled = true
                }
            }
        }
        val loader = SessionResourceLoader(backgroundScope, actions, errors::add)
        loader.loadModels(session.copy(generation = "old"))
        runCurrent()
        loader.loadModels(session.copy(generation = "new"))
        runCurrent()
        assertEquals(LoadState.Ready(fresh), loader.modelLoads.value[key])
        obsolete.complete(ModelCatalog(listOf(ModelInfo("provider", "obsolete", "Obsolete")), listOf("low")))
        runCurrent()
        assertTrue(cancelled)
        assertEquals(LoadState.Ready(fresh), loader.modelLoads.value[key])
        assertTrue(errors.isEmpty())
    }

    @Test fun sibling_failure_is_retryable_without_borrowing_another_sessions_fallback() = runTest {
        val sibling = session.copy(id = "other")
        val siblingKey = SessionKey("host", "other")
        val model = ModelInfo("provider", "model", "Model")
        val errors = mutableListOf<String>()
        var siblingCalls = 0
        val actions = object : SessionResourceActions {
            override fun hasDetail(key: SessionKey) = false
            override suspend fun detail(session: Session) = Unit
            override suspend fun models(session: Session): ModelCatalog {
                if (session.id == sibling.id && ++siblingCalls == 1) error("Sibling unavailable")
                return ModelCatalog(listOf(model), if (session.id == sibling.id) listOf("high") else listOf("low"))
            }
        }
        val loader = SessionResourceLoader(backgroundScope, actions, errors::add)
        loader.loadModels(session)
        runCurrent()
        loader.loadModels(sibling)
        runCurrent()
        assertEquals(LoadState.Failed("Sibling unavailable"), loader.modelLoads.value[siblingKey])
        assertEquals(listOf("low"), (loader.modelLoads.value[key] as LoadState.Ready).value.thinkingLevels)
        assertTrue(errors.isEmpty())
        loader.loadModels(sibling)
        runCurrent()
        assertEquals(2, siblingCalls)
        assertEquals(listOf("high"), (loader.modelLoads.value[siblingKey] as LoadState.Ready).value.thinkingLevels)
        assertEquals(listOf("low"), (loader.modelLoads.value[key] as LoadState.Ready).value.thinkingLevels)
    }
}
