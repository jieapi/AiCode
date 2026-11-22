package com.aicode.feature.agent.domain.provider

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Gson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException

internal class ResponsesWebSocketConnection(
    client: OkHttpClient,
    request: Request,
    private val connectTimeoutMs: Long
) {
    private sealed interface Incoming {
        data class Event(val value: JsonObject) : Incoming
        data class Failure(val error: IOException) : Incoming
    }

    private val incoming = Channel<Incoming>(256)
    private val gson = Gson()
    private val opened = kotlinx.coroutines.CompletableDeferred<Unit>()
    @Volatile var isOpen = false
        private set
    private val socket: WebSocket = client.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isOpen = true
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { JsonParser.parseString(text).asJsonObject }
                .onSuccess { offer(Incoming.Event(it)) }
                .onFailure { offer(Incoming.Failure(IOException("Invalid WebSocket JSON event", it))) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            offer(Incoming.Failure(IOException("Unexpected binary WebSocket event")))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isOpen = false
            if (!opened.isCompleted) {
                opened.completeExceptionally(WebSocketHandshakeException(code, reason))
            }
            offer(Incoming.Failure(IOException("Responses WebSocket closed ($code): $reason")))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isOpen = false
            val error = if (response != null) {
                WebSocketHandshakeException(response.code, response.message, t)
            } else {
                IOException(t.message ?: "Responses WebSocket failed", t)
            }
            if (!opened.isCompleted) opened.completeExceptionally(error)
            offer(Incoming.Failure(error))
        }
    })

    private fun offer(event: Incoming) {
        if (incoming.trySend(event).isFailure) {
            incoming.close(IOException("Responses WebSocket event queue overflow"))
            socket.cancel()
        }
    }

    suspend fun awaitOpen() {
        if (connectTimeoutMs <= 0L) {
            opened.await()
            return
        }
        try {
            withTimeout(connectTimeoutMs) { opened.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            socket.cancel()
            coroutineContext.ensureActive()
            throw java.net.SocketTimeoutException("Responses WebSocket handshake timed out after ${connectTimeoutMs}ms")
        } catch (e: Exception) {
            socket.cancel()
            throw e
        }
    }

    fun send(payload: Map<String, Any?>) {
        if (!socket.send(gson.toJson(payload))) {
            throw IOException("Responses WebSocket is not available for sending")
        }
    }

    suspend fun nextEvent(timeoutMs: Long): JsonObject {
        val next = try {
            if (timeoutMs > 0) {
                withTimeout(timeoutMs) { incoming.receive() }
            } else {
                incoming.receive()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            coroutineContext.ensureActive()
            throw java.net.SocketTimeoutException("Timed out waiting for a Responses WebSocket event")
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            throw IOException("Responses WebSocket event channel is closed", e)
        }
        return when (next) {
            is Incoming.Event -> next.value
            is Incoming.Failure -> throw next.error
        }
    }

    fun close() {
        socket.close(1000, "run complete")
        incoming.close()
    }

    fun cancel() {
        socket.cancel()
        incoming.close()
    }
}

internal class WebSocketHandshakeException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null
) : IOException("Responses WebSocket handshake failed with HTTP $statusCode: $message", cause)
