package com.aicode.feature.agent.domain.provider

import com.aicode.feature.settings.domain.model.DEFAULT_PROVIDER_MAX_CONCURRENT_REQUESTS
import kotlinx.coroutines.CompletableDeferred
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.ArrayDeque

@Singleton
class ProviderRequestConcurrencyLimiter @Inject constructor() {
    private val lock = Any()
    private val gates = mutableMapOf<String, Gate>()

    suspend fun <T> withPermit(
        providerId: String,
        maxConcurrentRequests: Int = DEFAULT_PROVIDER_MAX_CONCURRENT_REQUESTS,
        onQueued: suspend (Int) -> Unit = {},
        onAdmitted: suspend () -> Unit = {},
        block: suspend () -> T
    ): T {
        if (providerId.isBlank() || maxConcurrentRequests <= 0) return block()

        val (gate, waiter, requestsAhead) = synchronized(lock) {
            val current = gates[providerId]
            val selected = if (current == null || current.users == 0 && current.limit != maxConcurrentRequests) {
                Gate(maxConcurrentRequests).also { gates[providerId] = it }
            } else {
                current
            }
            selected.users++
            if (selected.activeRequests < selected.limit && selected.waiters.isEmpty()) {
                selected.activeRequests++
                Triple(selected, null, 0)
            } else {
                val queued = Waiter().also(selected.waiters::addLast)
                Triple(selected, queued, selected.activeRequests + selected.waiters.size - 1)
            }
        }

        var hasPermit = waiter == null
        return try {
            if (waiter != null) {
                try {
                    onQueued(requestsAhead)
                    waiter.admitted.await()
                    hasPermit = true
                } catch (failure: Throwable) {
                    synchronized(lock) {
                        if (waiter.granted) hasPermit = true else gate.waiters.remove(waiter)
                    }
                    throw failure
                }
                onAdmitted()
            }
            block()
        } finally {
            var next: Waiter? = null
            synchronized(lock) {
                if (hasPermit) {
                    gate.activeRequests--
                    next = gate.waiters.removeFirstOrNull()
                    next?.let {
                        it.granted = true
                        gate.activeRequests++
                    }
                } else {
                    waiter?.let(gate.waiters::remove)
                }
                gate.users--
                if (gate.users == 0) gates.remove(providerId, gate)
            }
            next?.admitted?.complete(Unit)
        }
    }

    private class Gate(val limit: Int) {
        var activeRequests: Int = 0
        var users: Int = 0
        val waiters = ArrayDeque<Waiter>()
    }

    private class Waiter {
        val admitted = CompletableDeferred<Unit>()
        var granted: Boolean = false
    }
}
