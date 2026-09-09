package com.aicode.feature.agent.domain.container

import android.content.ContextWrapper
import com.aicode.feature.settings.data.repository.ExecutionMode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 重置容器时 rootfs 的删除行为。
 *
 * 回归点：重置曾在主线程递归删除 rootfs，装满工具的容器有十万级 inode，界面冻结几十秒直到 ANR。
 * 现在删除在 IO 线程进行并回报进度；标记文件先删，中途进程被杀不会留下「标记有效、内容残缺」的容器。
 */
class ContainerInstallerResetTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "installer-test-${System.nanoTime()}")
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val cacheDir = File(root, "cache").apply { mkdirs() }
    private val context = FakeContext(filesDir, cacheDir)
    private val osDetector = ContainerOsDetector(context)
    private val installer = ContainerInstaller(context, osDetector)

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun resetRootfs_内置容器_返回时目录已彻底删除() = runTest {
        val rootfs = fakeRootfs(File(filesDir, "rootfs"), ".installed")

        installer.resetRootfs(ContainerProfile.BUILTIN_ALPINE)

        assertFalse("重置返回时 rootfs 必须已删完", rootfs.exists())
        assertFalse(installer.isInstalled())
    }

    @Test
    fun resetRootfs_回报已删条目数() = runTest {
        fakeRootfs(File(filesDir, "rootfs"), ".installed")
        val reported = mutableListOf<Int>()

        installer.resetRootfs(ContainerProfile.BUILTIN_ALPINE) { reported += it }

        // rootfs 本身 + usr + usr/bin + busybox；安装标记在删除前先删，不计入
        assertEquals(4, reported.last())
    }

    @Test
    fun resetRootfs_自定义容器_只删自己的目录并清系统缓存() = runTest {
        val custom = localProfile("custom-1")
        val customRootfs = fakeRootfs(File(filesDir, "rootfs_custom-1"), ".installed_custom")
        val builtinRootfs = fakeRootfs(File(filesDir, "rootfs"), ".installed")
        osDetector.cacheOs("custom-1", "ubuntu")

        installer.resetRootfs(custom)

        assertFalse(customRootfs.exists())
        assertTrue("不能连带动到内置 rootfs", builtinRootfs.exists())
        assertNull("rootfs 已删，系统识别缓存要一起失效", osDetector.cachedOs("custom-1"))
    }

    @Test
    fun resetRootfs_远程SSH容器_不碰同名本地目录() = runTest {
        val remote = ContainerProfile(
            id = "custom-2",
            name = "远程",
            rootfsSource = RootfsSource.RemoteSsh("conn-1", "/home/u/workspace"),
            shellPath = null,
            isBuiltin = false,
            mode = ExecutionMode.REMOTE_SSH
        )
        val strayDir = fakeRootfs(File(filesDir, "rootfs_custom-2"), ".installed_custom")

        installer.resetRootfs(remote)

        assertTrue("远程 profile 无本地 rootfs，不应删任何本地目录", strayDir.exists())
    }

    @Test
    fun resetRootfs_目录不存在时静默返回() = runTest {
        installer.resetRootfs(localProfile("custom-3"))

        assertFalse(File(filesDir, "rootfs_custom-3").exists())
    }

    @Test
    fun resetRootfs_不跟随符号链接删到链接目标() = runTest {
        val outside = File(root, "outside").apply { mkdirs() }
        val keep = File(outside, "important.txt").apply { writeText("keep me") }
        val rootfs = fakeRootfs(File(filesDir, "rootfs"), ".installed")
        Files.createSymbolicLink(File(rootfs, "mnt").toPath(), outside.toPath())

        installer.resetRootfs(ContainerProfile.BUILTIN_ALPINE)

        assertFalse(rootfs.exists())
        assertTrue("符号链接指向的目录不能被跟着删掉", outside.exists())
        assertEquals("keep me", keep.readText())
    }

    @Test
    fun prootTmpDirFor_按容器隔离_重置内置不影响自定义容器的临时目录() = runTest {
        val custom = localProfile("custom-4")
        fakeRootfs(File(filesDir, "rootfs_custom-4"), ".installed_custom")
        File(filesDir, "rootfs_custom-4/tmp").mkdirs()
        fakeRootfs(File(filesDir, "rootfs"), ".installed")
        File(filesDir, "rootfs/tmp").mkdirs()

        installer.resetRootfs(ContainerProfile.BUILTIN_ALPINE)

        // PROOT_TMP_DIR 曾固定取内置 rootfs 的 tmp，重置内置容器会让所有容器报 can't canonicalize。
        val customTmp = installer.prootTmpDirFor(custom)
        assertEquals(File(filesDir, "rootfs_custom-4/tmp"), customTmp)
        assertTrue("自定义容器的 PROOT_TMP_DIR 不能随内置容器重置而消失", customTmp.isDirectory)
    }

    private fun localProfile(id: String) = ContainerProfile(
        id = id,
        name = id,
        rootfsSource = RootfsSource.LocalFile("file:///tmp/$id.tar.gz"),
        shellPath = null,
        isBuiltin = false
    )

    /** 造一个有层级的假 rootfs，含安装标记。 */
    private fun fakeRootfs(dir: File, marker: String): File {
        File(dir, "usr/bin").mkdirs()
        File(dir, "usr/bin/busybox").writeText("bin")
        File(dir, marker).writeText("alpine-3.21.3-v6")
        return dir
    }

    private class FakeContext(private val files: File, private val cache: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
    }
}
