package com.aicode.feature.plugin.data.index

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.plugin.data.PluginStoreSettingsRepository
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PluginIndexClientDownloadCancellationTest {

    @Test
    fun cancellingDownloadClosesHttpConnection() = runBlocking {
        val server = ServerSocket(0)
        val requestChecked = CountDownLatch(1)
        val disconnected = AtomicBoolean(false)
        val serverFailure = AtomicBoolean(false)
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    var headerEnd = 0
                    while (headerEnd != 0x0D0A0D0A) {
                        val byte = input.read()
                        if (byte < 0) throw IOException("Request ended before headers")
                        headerEnd = (headerEnd shl 8) or byte
                    }
                    socket.getOutputStream().apply {
                        write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\nConnection: keep-alive\r\n\r\n"
                                .toByteArray()
                        )
                        write(ByteArray(64 * 1024))
                        flush()
                    }
                    requestChecked.countDown()
                    try {
                        disconnected.set(input.read() < 0)
                    } catch (_: SocketTimeoutException) {
                    } catch (_: IOException) {
                        disconnected.set(true)
                    }
                }
            } catch (_: IOException) {
                serverFailure.set(true)
                requestChecked.countDown()
            } finally {
                server.close()
            }
        }.apply { start() }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = mockk<PluginStoreSettingsRepository>()
        every { settings.downloadMirror } returns flowOf("")
        val client = PluginIndexClient(context, OkHttpClient.Builder().build(), settings)
        val progressReceived = CompletableDeferred<Unit>()
        val download = async(Dispatchers.IO) {
            client.download(
                PluginReleaseAsset(
                    version = "1.0.0",
                    name = "plugin_1.0.0.zip",
                    url = "http://127.0.0.1:${server.localPort}/plugin.zip",
                    size = 1024 * 1024L
                )
            ) { _, _ -> progressReceived.complete(Unit) }
        }

        try {
            withTimeout(5_000) { progressReceived.await() }
            assertTrue("The streamed download should still be active", download.isActive)
            download.cancelAndJoin()
            assertTrue(requestChecked.await(5, TimeUnit.SECONDS))
            assertTrue("Cancellation should close the download socket", disconnected.get())
            assertTrue("The local server should handle the request", !serverFailure.get())
        } finally {
            download.cancel()
            server.close()
            serverThread.join(5_000)
        }
    }
}
