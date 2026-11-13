package com.aicode.feature.plugin.overlay

import android.content.Context
import android.os.Build
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.plugin.data.PluginOverlayPermissionRepository
import com.aicode.feature.plugin.data.PluginOverlayPositionRepository
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginOverlayDefinition
import com.aicode.feature.plugin.domain.PluginOverlaySurface
import com.aicode.feature.plugin.domain.PluginStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.justRun
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], manifest = Config.NONE, application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PluginOverlayManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun replacingAPluginRecreatesItsOpenSurfaceWithTheNewPageAndSize() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        ShadowSettings.setCanDrawOverlays(true)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val plugins = MutableStateFlow(listOf(plugin("1.0.0", 64, "ball.html")))
        val pluginManager = mockk<PluginManager>()
        every { pluginManager.plugins } returns plugins
        justRun { pluginManager.ensureLoaded() }
        val permissions = mockk<PluginOverlayPermissionRepository>()
        coEvery { permissions.isGranted("test.approval") } returns true
        val positions = mockk<PluginOverlayPositionRepository>(relaxed = true)
        coEvery { positions.get(any(), any()) } returns null
        val foreground = mockk<AppForegroundMonitor>()
        every { foreground.isForeground } returns MutableStateFlow(false)
        every { foreground.current() } returns false
        val manager = PluginOverlayManager(context, pluginManager, permissions, positions, foreground)
        try {
            manager.create("test.approval", "ball", buildJsonObject {
                put("x", 40)
                put("y", 50)
                put("visibilityPolicy", "background")
            })
            val windows = ReflectionHelpers.getField<ConcurrentHashMap<String, PluginOverlayWindow>>(manager, "windows")
            val old = windows.getValue("test.approval:ball")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val params = ReflectionHelpers.getField<WindowManager.LayoutParams>(old, "layoutParams")
                assertFalse(params.canPlayMoveAnimation())
            }
            plugins.value = listOf(plugin("1.1.1", 48, "updated.html"))
            manager.reloadPlugin("test.approval")
            runCurrent()
            val current = windows.getValue("test.approval:ball")
            assertNotSame(old, current)
            assertEquals(48 to 48, current.sizeDp())
            assertEquals("40", current.describe().getValue("x").toString())
            assertEquals("50", current.describe().getValue("y").toString())
            assertEquals("background", current.visibilityPolicy())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val params = ReflectionHelpers.getField<WindowManager.LayoutParams>(current, "layoutParams")
                assertFalse(params.canPlayMoveAnimation())
            }
            val webView = (current.getChildAt(0) as FrameLayout).getChildAt(0) as WebView
            assertEquals(
                "https://appassets.androidplatform.net/plugin/updated.html?v=1.1.1",
                shadowOf(webView).lastLoadedUrl
            )

            plugins.value = listOf(plugin("1.1.1", 48, "updated.html").copy(content = PluginContent()))
            manager.reloadPlugin("test.approval")
            runCurrent()
            assertFalse(manager.hasOverlays())
        } finally {
            manager.closeAll()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Dispatchers.resetMain()
        }
    }

    private fun plugin(version: String, size: Int, page: String): InstalledPlugin {
        val dir = tmp.root
        java.io.File(dir, page).writeText("<!doctype html><html></html>")
        val surface = PluginOverlaySurface("test.approval", "ball", page, dir, version, size, size, true)
        return InstalledPlugin(
            record = PluginRecord("test.approval", version, true, 1L, "local", "archive", "manifest"),
            status = PluginStatus.OK,
            manifest = mockk(),
            content = PluginContent(overlay = PluginOverlayDefinition("test.approval", "Approval", listOf(surface)))
        )
    }
}
