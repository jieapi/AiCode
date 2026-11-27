package com.aicode.feature.agent.domain.container

import android.content.Context
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContainerRuntimeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun containerProfile_legacyJsonWithoutRuntime_defaultsToProot() {
        val raw = """
            {
              "id": "custom-1",
              "name": "Debian",
              "rootfsSource": {"type": "com.aicode.feature.agent.domain.container.RootfsSource.LocalFile", "uri": "file:///tmp/debian.tar.gz"},
              "shellPath": "/bin/bash",
              "isBuiltin": false
            }
        """.trimIndent()

        val decoded = json.decodeFromString(ContainerProfile.serializer(), raw)
        assertEquals(ContainerRuntime.PROOT, decoded.runtime)
    }

    @Test
    fun containerProfile_withTawcrootRuntime_roundTrips() {
        val profile = ContainerProfile(
            id = "custom-tawc",
            name = "Arch",
            rootfsSource = RootfsSource.LocalFile("file:///tmp/arch.tar.gz"),
            shellPath = "/bin/bash",
            isBuiltin = false,
            runtime = ContainerRuntime.TAWCROOT
        )

        val encoded = json.encodeToString(ContainerProfile.serializer(), profile)
        val decoded = json.decodeFromString(ContainerProfile.serializer(), encoded)
        assertEquals(ContainerRuntime.TAWCROOT, decoded.runtime)
    }

    @Test
    fun resolveEffectiveRuntime_withoutTawcrootBinaryOrQ_fallsBackToProot() {
        val rootfs = tempFolder.newFolder("rootfs")
        val aicodeDir = tempFolder.newFolder("aicode")
        val nativeDir = tempFolder.newFolder("native")
        val prootBin = File(nativeDir, "libproot.so").apply { writeText("") }
        val loader = File(nativeDir, "libproot-loader.so").apply { writeText("") }
        val loader32 = File(nativeDir, "libproot-loader32.so").apply { writeText("") }

        val context = mockk<Context>(relaxed = true)
        val installer = mockk<ContainerInstaller>(relaxed = true)
        val detector = mockk<ContainerOsDetector>(relaxed = true)
        val settingsRepo = mockk<ContainerSettingsRepository>(relaxed = true)
        val homeResolver = mockk<PathHomeResolver>(relaxed = true)

        every { settingsRepo.activeProfileFlow } returns flowOf(ContainerProfile.BUILTIN_ALPINE)
        every { installer.rootfsDirFor(any()) } returns rootfs
        every { installer.prootTmpDirFor(any()) } returns File(rootfs, "tmp")
        every { installer.aicodeDir } returns aicodeDir
        every { installer.prootBin } returns prootBin
        every { installer.prootLoader } returns loader
        every { installer.prootLoader32 } returns loader32
        every { installer.tawcrootBin } returns File(nativeDir, "libtawcroot.so")

        val engine = LinuxContainerEngine(context, installer, detector, settingsRepo, homeResolver)
        val tawcProfile = ContainerProfile.BUILTIN_ALPINE.copy(runtime = ContainerRuntime.TAWCROOT)

        assertEquals(ContainerRuntime.PROOT, engine.resolveEffectiveRuntime(tawcProfile))

        val invocation = engine.buildProotInvocation("echo ok", projectPath = null)
        assertEquals(prootBin.absolutePath, invocation.executable)
        assertTrue(invocation.argv.contains("--link2symlink"))
        assertTrue(invocation.env.containsKey("PROOT_LOADER"))
        assertFalse(invocation.argv.contains("--"))
    }
}
