package dev.pinkcollab.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A screen-owned LRU bounded by source characters; cached values may be parsed trees. */
internal class TextRenderCache<T>(
    private val maxEntries: Int = 64,
    private val maxCharacters: Int = 128_000,
    private val render: (String) -> T,
) {
    init {
        require(maxEntries > 0 && maxCharacters > 0)
    }

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, T>(16, 0.75f, true)
    private var characters = 0L

    suspend fun get(text: String): T = withContext(Dispatchers.Default) {
        // Serialize cache misses so concurrent requests parse each source only once.
        mutex.withLock {
            entries[text]?.let { return@withLock it }
            val result = render(text)
            currentCoroutineContext().ensureActive()
            val weight = text.length.toLong()
            if (weight <= maxCharacters) {
                entries[text] = result
                characters += weight
                val iterator = entries.entries.iterator()
                while (entries.size > maxEntries || characters > maxCharacters) {
                    val oldest = iterator.next()
                    characters -= oldest.key.length.toLong()
                    iterator.remove()
                }
            }
            result
        }
    }
}
