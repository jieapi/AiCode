package com.aicode.feature.workspace.domain

class AgentConfigParseCache<T> {
    private data class Entry<T>(val revision: String, val value: T, val bytes: Long)
    private val entries = LinkedHashMap<String, Entry<T>>(16, 0.75f, true)
    private var retainedBytes = 0L

    @Synchronized
    fun get(snapshot: AgentConfigSnapshot, parse: (Map<String, String>) -> T): T {
        entries[snapshot.identity]?.takeIf { it.revision == snapshot.revision }?.let { return it.value }
        entries.remove(snapshot.identity)?.let { retainedBytes -= it.bytes }
        val parsed = parse(snapshot.files)
        val bytes = snapshot.files.entries.sumOf { (path, text) -> (path.length.toLong() + text.length) * 2 + 512 }
        if (bytes > MAX_BYTES) return parsed
        entries[snapshot.identity] = Entry(snapshot.revision, parsed, bytes)
        retainedBytes += bytes
        val iterator = entries.entries.iterator()
        while ((entries.size > 16 || retainedBytes > MAX_BYTES) && iterator.hasNext()) {
            retainedBytes -= iterator.next().value.bytes
            iterator.remove()
        }
        return parsed
    }

    private companion object {
        const val MAX_BYTES = 16L * 1024 * 1024
    }
}
