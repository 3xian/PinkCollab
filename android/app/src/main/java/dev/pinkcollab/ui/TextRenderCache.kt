package dev.pinkcollab.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A screen-owned LRU. Text keys also invalidate edited messages without retaining every revision. */
internal class TextRenderCache<T : CharSequence>(
    private val maxEntries: Int = 64,
    private val maxCharacters: Int = 256_000,
    private val render: (String) -> T,
) {
    init {
        require(maxEntries > 0 && maxCharacters > 0)
    }

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, T>(16, 0.75f, true)
    private var characters = 0L

    suspend fun get(text: String): T = withContext(Dispatchers.Default) {
        // Markwon's parser/renderer instance is shared by visible rows; serialize its use.
        mutex.withLock {
            entries[text]?.let { return@withLock it }
            val result = render(text)
            currentCoroutineContext().ensureActive()
            val weight = text.length.toLong() + result.length
            if (weight <= maxCharacters) {
                entries[text] = result
                characters += weight
                val iterator = entries.entries.iterator()
                while (entries.size > maxEntries || characters > maxCharacters) {
                    val oldest = iterator.next()
                    characters -= oldest.key.length.toLong() + oldest.value.length
                    iterator.remove()
                }
            }
            result
        }
    }
}
