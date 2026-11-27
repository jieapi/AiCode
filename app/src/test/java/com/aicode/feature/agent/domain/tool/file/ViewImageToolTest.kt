package com.aicode.feature.agent.domain.tool.file

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewImageToolTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun viewImage_attachesPngFromRemoteCopy() = runTest {
        val copy = temporaryFolder.newFile("aicode_remote_image.copy")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        copy.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()

        val result = viewImage(copy)
        assertTrue(result.toString(), result is ToolResult.Success)
        val image = (result as ToolResult.Success).images.single()
        assertEquals("image/png", image.mimeType)
        assertArrayEquals(copy.readBytes(), Base64.decode(image.base64Data, Base64.DEFAULT))
    }

    @Test
    fun viewImage_rejectsNonImageFromRemoteCopy() = runTest {
        val copy = temporaryFolder.newFile("aicode_remote_image.copy").apply { writeText("not an image") }

        val result = viewImage(copy)
        assertTrue(result.toString(), result is ToolResult.Error)
        assertEquals("UNSUPPORTED_IMAGE", (result as ToolResult.Error).code)
    }

    private suspend fun viewImage(copy: File): ToolResult {
        val path = "~/workspace/image.png"
        val provider = AIProviderConfig(
            id = "provider",
            name = "Vision provider",
            type = ProviderType.OPENAI,
            apiKey = "test-key",
            baseUrl = "",
            defaultModel = "vision-model"
        )
        val tool = ViewImageTool(
            fileAccess = mockk<FileAccessProvider> {
                every { copyToLocal(path) } returns copy
                every { exists(path) } returns true
                every { isFile(path) } returns true
                every { fileSize(path) } returns copy.length()
                every { toDisplayPath(path) } returns path
            },
            visionSessionStore = mockk(),
            aiProviderRepository = mockk<AIProviderRepository> {
                coEvery { getProviderById(provider.id) } returns provider
            },
            defaultModelSettingsRepository = mockk<DefaultModelSettingsRepository> {
                coEvery { getDefaultProviderId() } returns provider.id
                coEvery { getDefaultModel() } returns provider.defaultModel
            },
            generalSettingsRepository = mockk(),
            visionModelSettingsRepository = mockk(),
            modelMetadataService = mockk<ModelMetadataService> {
                coEvery { resolve(provider.id, provider.type, provider.defaultModel) } returns
                    ModelMetadata(id = provider.defaultModel, contextTokens = 4096, supportsVision = true)
            },
            sessionUseCase = mockk(),
            llmCallRecordDao = mockk(),
            openAIApi = mockk(),
            anthropicApi = mockk(),
            geminiApi = mockk(),
            requestConcurrencyLimiter = mockk(),
            appContext = RuntimeEnvironment.getApplication()
        )
        return tool.executeWithContext(
            mapOf("images" to JsonArray(listOf(JsonPrimitive(path)))),
            AgentContext(
                currentFile = null,
                selectedCode = null,
                projectRoot = "/root/workspace",
                language = null
            )
        )
    }
}
