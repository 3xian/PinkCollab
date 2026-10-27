package dev.pinkcollab.ui

import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SlashCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SessionCommands(val generation: String?, val commands: List<SlashCommand>)

/** Catalogs belong to one runtime, never to a host or a previous session generation. */
internal class SessionCommandLoader(
    private val scope: CoroutineScope,
    private val fetch: suspend (Session) -> List<SlashCommand>,
) {
    private val mutableLoads = MutableStateFlow<Map<SessionKey, LoadState<SessionCommands>>>(emptyMap())
    val state = mutableLoads.asStateFlow()
    private val lock = Any()
    private val jobs = mutableMapOf<SessionKey, Job>()
    private val generations = mutableMapOf<SessionKey, String?>()
    private val versions = mutableMapOf<SessionKey, Long>()
    private var nextVersion = 0L

    fun load(session: Session, force: Boolean = false) {
        val key = SessionKey(session.hostId, session.id)
        val version = synchronized(lock) {
            val changed = key in generations && generations[key] != session.generation
            val current = mutableLoads.value[key]
            if (!changed && (current == LoadState.Loading || (!force && current is LoadState.Ready))) {
                null
            } else {
                generations[key] = session.generation
                mutableLoads.value += key to LoadState.Loading
                (++nextVersion).also { versions[key] = it }
            }
        } ?: return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val catalog = SessionCommands(session.generation, fetch(session))
                synchronized(lock) {
                    if (versions[key] == version) mutableLoads.value += key to LoadState.Ready(catalog)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                synchronized(lock) {
                    if (versions[key] == version) {
                        mutableLoads.value += key to LoadState.Failed(failure.message ?: "Unable to load commands")
                    }
                }
            }
        }
        synchronized(lock) {
            if (versions[key] == version) jobs.put(key, job)?.cancel() else job.cancel()
        }
        job.invokeOnCompletion { synchronized(lock) { if (jobs[key] === job) jobs.remove(key) } }
        job.start()
    }

    fun updateSessions(sessions: Collection<Session>) {
        sessions.forEach { session ->
            val key = SessionKey(session.hostId, session.id)
            val changed = synchronized(lock) {
                key in generations && (!session.runtimeAttached || generations[key] != session.generation)
            }
            if (changed) removeSession(key)
        }
    }

    fun removeSession(key: SessionKey) {
        val job = synchronized(lock) {
            versions.remove(key)
            generations.remove(key)
            mutableLoads.value -= key
            jobs.remove(key)
        }
        job?.cancel()
    }

    fun removeHost(hostId: String) {
        val keys = synchronized(lock) { generations.keys.filter { it.hostId == hostId } }
        keys.forEach(::removeSession)
    }
}
