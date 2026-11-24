package com.aicode.feature.agent.domain.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRequestConcurrencyLimiterTest {
    @Test
    fun zeroMeansUnlimited() = runTest {
        val limiter = ProviderRequestConcurrencyLimiter()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.withPermit("provider-a", maxConcurrentRequests = 0) {
                releaseFirst.await()
            }
        }
        limiter.withPermit("provider-a", maxConcurrentRequests = 0) {
            secondEntered.complete(Unit)
        }

        assertTrue(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        first.await()
    }

    @Test
    fun cancellingQueuedRequestRemovesItFromTheQueue() = runTest {
        val limiter = ProviderRequestConcurrencyLimiter()
        val releaseFirst = CompletableDeferred<Unit>()
        val queued = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.withPermit("provider-a", maxConcurrentRequests = 1) { releaseFirst.await() }
        }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.withPermit("provider-a", maxConcurrentRequests = 1, onQueued = { queued.complete(Unit) }) { }
        }

        queued.await()
        cancelled.cancelAndJoin()
        releaseFirst.complete(Unit)
        first.await()

        var nextEntered = false
        limiter.withPermit("provider-a", maxConcurrentRequests = 1) { nextEntered = true }
        assertTrue(nextEntered)
    }

    @Test
    fun queuesRequestsPerProviderAndAllowsOtherProvidersToProceed() = runTest {
        val limiter = ProviderRequestConcurrencyLimiter()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val secondQueuePosition = CompletableDeferred<Int>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.withPermit("provider-a", maxConcurrentRequests = 1) {
                releaseFirst.await()
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.withPermit(
                "provider-a",
                maxConcurrentRequests = 1,
                onQueued = { secondQueuePosition.complete(it) }
            ) {
                secondEntered.complete(Unit)
            }
        }
        var otherProviderEntered = false
        limiter.withPermit("provider-b", maxConcurrentRequests = 1) {
            otherProviderEntered = true
        }

        assertFalse(secondEntered.isCompleted)
        assertEquals(1, secondQueuePosition.await())
        assertTrue(otherProviderEntered)

        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertTrue(secondEntered.isCompleted)
    }
}
