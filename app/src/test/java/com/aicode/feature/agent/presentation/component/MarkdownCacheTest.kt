package com.aicode.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MarkdownCacheTest {
    private data class Parsed(val content: String)
    private fun parsed(text: String) = Parsed(text)
    private fun cache(maxEntries: Int = 80, maxChars: Int = 512 * 1024) =
        MarkdownSnapshotCache<Parsed>(maxEntries, maxChars) { it.content }

    @Test
    fun streamUpdatesReplacePreviousSnapshotsWithoutEvictingOtherMessages() {
        val cache = cache(maxEntries = 3, maxChars = 1000)
        val history = parsed("history")
        cache.put(history)
        repeat(30) { index -> cache.put(parsed("growing " + "x".repeat(index)), "reply:content") }
        val latest = parsed("growing " + "x".repeat(30))
        cache.put(latest, "reply:content")

        assertSame(history, cache.get("history"))
        assertSame(latest, cache.get(latest.content))
        assertNull(cache.get("growing x"))
        assertSame(latest, cache.getBestPrefix(latest.content + " more"))
    }

    @Test
    fun contentAndReasoningKeepIndependentLatestSnapshots() {
        val cache = cache()
        val content = parsed("answer")
        val reasoning = parsed("thinking")
        cache.put(content, "reply:content")
        cache.put(reasoning, "reply:reasoning")
        cache.put(parsed("answer continued"), "reply:content")

        assertNull(cache.get("answer"))
        assertSame(reasoning, cache.get("thinking"))
    }

    @Test
    fun finalizedSnapshotDoesNotRemainOwnedByTheStreamingCacheKey() {
        val cache = cache()
        val final = parsed("answer")
        cache.put(final, "reply:content")
        cache.put(final)
        cache.put(parsed("different reply"), "reply:content")

        assertSame(final, cache.get("answer"))
    }

    @Test
    fun streamingTextMatchingHistoryDoesNotTakeItsCachedStateAway() {
        val cache = cache()
        val history = parsed("answer")
        cache.put(history)
        cache.put(history, "reply:content")
        cache.put(parsed("answer more"), "reply:content")

        assertSame(history, cache.get("answer"))
    }

    @Test
    fun identicalTextCanBelongToMultipleStreams() {
        val cache = cache()
        val shared = parsed("same")
        cache.put(shared, "reply:content")
        cache.put(shared, "reply:reasoning")
        cache.put(parsed("content continued"), "reply:content")

        assertSame(shared, cache.get("same"))
        cache.put(parsed("reasoning continued"), "reply:reasoning")
        assertNull(cache.get("same"))
    }

    @Test
    fun trimmedTextSharesAnEntryWithoutConsumingAnotherSlot() {
        val cache = cache(maxEntries = 2)
        val first = parsed("  answer  ")
        val second = parsed("other")
        cache.put(first)
        cache.put(second)

        assertSame(first, cache.get("answer"))
        assertSame(first, cache.get("  answer  "))
        assertSame(second, cache.get("other"))
    }

    @Test
    fun textBudgetEvictsLeastRecentlyUsedParsedState() {
        val cache = cache(maxEntries = 10, maxChars = 10)
        val first = parsed("aaaa")
        val second = parsed("bbbb")
        val third = parsed("cccc")
        cache.put(first)
        cache.put(second)
        assertSame(first, cache.get("aaaa"))
        cache.put(third)

        assertSame(first, cache.get("aaaa"))
        assertNull(cache.get("bbbb"))
        assertSame(third, cache.get("cccc"))
    }

    @Test
    fun oversizedParsedStateIsNotRetained() {
        val cache = cache(maxChars = 8)
        cache.put(parsed("x".repeat(9)))

        assertNull(cache.get("x".repeat(9)))
    }

    @Test
    fun weightedTextCacheUsesBothEntryAndTextBudgets() {
        val cache = TextBudgetCache<String, String>(2, 12) { key, value ->
            key.length.toLong() + value.length
        }
        cache.put("a", "111")
        cache.put("b", "222")
        assertEquals("111", cache["a"])
        cache.put("c", "333")
        assertNull(cache["b"])
        cache.put("d", "x".repeat(10))

        assertNull(cache["a"])
        assertNull(cache["c"])
        assertEquals("x".repeat(10), cache["d"])
        cache.put("oversized", "x".repeat(20))
        assertNull(cache["oversized"])
        assertEquals("x".repeat(10), cache["d"])
    }

    @Test
    fun replacingTextUpdatesTheBudgetInsteadOfCountingOldValue() {
        val cache = TextBudgetCache<String, String>(4, 8) { key, value -> key.length.toLong() + value.length }
        cache.put("a", "12345")
        cache.put("a", "1")
        cache.put("b", "12345")

        assertEquals("1", cache["a"])
        assertEquals("12345", cache["b"])
    }

    @Test
    fun uncachedStreamingPreprocessingMatchesFinalPreprocessing() {
        val text = "thinking: \\(x^2\\), <b>answer</b> &amp; result"

        assertEquals(MarkdownPreprocessor.process(text), MarkdownPreprocessor.process(text, cacheResult = false))
    }
}
