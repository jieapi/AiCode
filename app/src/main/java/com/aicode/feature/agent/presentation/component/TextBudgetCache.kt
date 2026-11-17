package com.aicode.feature.agent.presentation.component

internal class TextBudgetCache<K, V>(
    private val maxEntries: Int,
    private val maxChars: Int,
    private val textSize: (K, V) -> Long
) {
    private class Entry<V>(val value: V, val chars: Long)

    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private var chars = 0L

    @Synchronized
    operator fun get(key: K): V? = entries[key]?.value

    @Synchronized
    fun put(key: K, value: V) {
        entries.remove(key)?.let { chars -= it.chars }
        val size = textSize(key, value)
        if (size > maxChars || maxEntries <= 0) return
        entries[key] = Entry(value, size)
        chars += size
        val iterator = entries.entries.iterator()
        while ((entries.size > maxEntries || chars > maxChars) && iterator.hasNext()) {
            chars -= iterator.next().value.chars
            iterator.remove()
        }
    }
}
