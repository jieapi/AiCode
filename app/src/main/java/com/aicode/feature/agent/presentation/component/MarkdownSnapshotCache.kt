package com.aicode.feature.agent.presentation.component

internal class MarkdownSnapshotCache<V>(
    private val maxEntries: Int,
    private val maxChars: Int,
    private val content: (V) -> String
) {
    private class Entry<V>(
        val value: V,
        var historical: Boolean,
        val streamOwners: MutableSet<String>,
        val chars: Long
    )
    private val entries = LinkedHashMap<String, Entry<V>>(16, 0.75f, true)
    private var chars = 0L

    fun get(text: String): V? = entries[text.trim()]?.value

    fun getBestPrefix(text: String): V? {
        get(text)?.let { return it }
        var bestMatch: V? = null
        var bestLength = 0
        for ((_, entry) in entries) {
            val candidate = content(entry.value)
            if (candidate.length in (bestLength + 1)..text.length && text.startsWith(candidate)) {
                bestMatch = entry.value
                bestLength = candidate.length
            }
        }
        return bestMatch
    }

    fun put(value: V, streamKey: String? = null) {
        val text = content(value)
        val key = text.trim()
        val cached = entries[key]
        if (cached?.value === value &&
            (streamKey == null && cached.historical || streamKey != null && streamKey in cached.streamOwners)) return
        if (streamKey != null) {
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key != key && entry.value.streamOwners.remove(streamKey) &&
                    !entry.value.historical && entry.value.streamOwners.isEmpty()) {
                    chars -= entry.value.chars
                    iterator.remove()
                }
            }
        }
        if (cached != null) {
            if (streamKey == null) {
                cached.historical = true
                cached.streamOwners.clear()
            } else if (!cached.historical) {
                cached.streamOwners += streamKey
            }
            return
        }
        val size = text.length.toLong() + if (key === text) 0L else key.length.toLong()
        if (size > maxChars || maxEntries <= 0) return
        entries[key] = Entry(value, streamKey == null, mutableSetOf<String>().apply {
            if (streamKey != null) add(streamKey)
        }, size)
        chars += size
        val iterator = entries.entries.iterator()
        while ((entries.size > maxEntries || chars > maxChars) && iterator.hasNext()) {
            val entry = iterator.next()
            chars -= entry.value.chars
            iterator.remove()
        }
    }
}
