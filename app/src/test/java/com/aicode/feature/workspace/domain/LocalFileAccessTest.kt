package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import kotlin.io.FileAlreadyExistsException
import kotlin.io.NoSuchFileException

/**
 * LocalFileAccess：以真实临时目录（TemporaryFolder）为宿主的 java.io.File 文件操作。
 * pathMapper 是 mockk：toHostFile 一律映射到 tmp 根下（路径字符串原样拼进临时目录），
 * toContainerPath 原样返回，聚焦文件行为本身。
 */
class LocalFileAccessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pathMapper: WorkspacePathMapper = mockk()

    private fun newAccess(): LocalFileAccess {
        every { pathMapper.toHostFile(any()) } answers { File(tmp.root, firstArg<String>()) }
        every { pathMapper.toContainerPath(any()) } answers { firstArg<String>() }
        every { pathMapper.resolveFile(any(), any()) } answers { File(tmp.root, firstArg<String>()) }
        every { pathMapper.resolveHostFile(any(), any()) } answers { firstArg<File>() }
        return LocalFileAccess(pathMapper)
    }

    private fun linkedAccess(): LocalFileAccess {
        val workspace: WorkspaceRepository = mockk()
        val installer: ContainerInstaller = mockk()
        val settings: ContainerSettingsRepository = mockk()
        val home: PathHomeResolver = mockk()
        every { workspace.currentPath() } returns tmp.root.absolutePath
        every { installer.aicodeDir } returns File(tmp.root, "global")
        every { installer.rootfsDirFor(any()) } returns File(tmp.root, "rootfs")
        every { settings.activeProfileFlow } returns emptyFlow()
        every { home.home() } returns "/root"
        every { home.expandHome(any()) } answers {
            firstArg<String>().replaceFirst("~/", "/root/")
        }
        return LocalFileAccess(WorkspacePathMapper(workspace, installer, settings, home))
    }

    @Test
    fun absoluteDirectoryLink_readsAndWritesTarget() {
        val access = linkedAccess()
        val target = tmp.newFolder("target")
        File(target, "probe.txt").writeText("original")
        val link = File(tmp.root, "link").toPath()
        Files.createSymbolicLink(link, File("/root/workspace/target").toPath())
        assertEquals("original", access.readFile("link/probe.txt"))
        access.writeFile("link/probe.txt", "changed", overwrite = true)
        assertEquals("changed", File(target, "probe.txt").readText())
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(listOf("probe.txt"), access.listFiles("link").map { it.name })
    }

    @Test
    fun absoluteFileLink_writePreservesLinkAndUpdatesTarget() {
        val access = linkedAccess()
        val target = tmp.newFile("target.txt").apply { writeText("original") }
        val link = File(tmp.root, "link.txt").toPath()
        Files.createSymbolicLink(link, File("/root/workspace/target.txt").toPath())
        access.writeBytes("link.txt", "updated".toByteArray(), overwrite = true)
        assertEquals("updated", target.readText())
        assertTrue(Files.isSymbolicLink(link))
    }

    @Test
    fun absoluteFileLink_writeFileUpdatesTargetAndCleansTempFiles() {
        val access = linkedAccess()
        val target = tmp.newFile("target.txt").apply { writeText("original") }
        val link = File(tmp.root, "link.txt").toPath()
        val linkTarget = File("/root/workspace/target.txt").toPath()
        Files.createSymbolicLink(link, linkTarget)

        access.writeFile("link.txt", "updated", overwrite = true)

        assertEquals("updated", target.readText())
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(linkTarget, Files.readSymbolicLink(link))
        val leftovers = tmp.root.listFiles().orEmpty().map { it.name }
            .filter { it.startsWith(".aicode-tmp-") }
        assertEquals(emptyList<String>(), leftovers)
    }

    @Test
    fun absoluteDirectoryLink_fingerprintTracksAtomicWrite() {
        val access = linkedAccess()
        val target = tmp.newFolder("target")
        File(target, "probe.txt").writeText("original")
        val link = File(tmp.root, "link").toPath()
        val linkTarget = File("/root/workspace/target").toPath()
        Files.createSymbolicLink(link, linkTarget)

        val beforeLinked = access.fingerprint("link/probe.txt")
        val beforeDirect = access.fingerprint("target/probe.txt")
        assertNotNull(beforeLinked)
        assertEquals(beforeDirect, beforeLinked)

        access.writeFile("link/probe.txt", "updated", overwrite = true)

        val afterLinked = access.fingerprint("link/probe.txt")
        val afterDirect = access.fingerprint("target/probe.txt")
        assertNotNull(afterLinked)
        assertNotEquals(beforeLinked, afterLinked)
        assertNotEquals(beforeDirect, afterDirect)
        assertEquals(afterDirect, afterLinked)
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(linkTarget, Files.readSymbolicLink(link))
    }

    @Test
    fun deleteRecursively_directoryLink_preservesTargetContents() {
        val access = linkedAccess()
        val target = tmp.newFolder("target")
        File(target, "keep.txt").writeText("keep")
        val link = File(tmp.root, "link").toPath()
        Files.createSymbolicLink(link, File("target").toPath())
        access.deleteRecursively("link")
        assertFalse(Files.isSymbolicLink(link))
        assertEquals("keep", File(target, "keep.txt").readText())
    }

    @Test
    fun deleteRecursively_nestedDirectoryLink_preservesExternalTarget() {
        val access = linkedAccess()
        val target = tmp.newFolder("target")
        File(target, "keep.txt").writeText("keep")
        val directory = tmp.newFolder("remove")
        Files.createSymbolicLink(File(directory, "link").toPath(), File("../target").toPath())
        access.deleteRecursively("remove")
        assertFalse(directory.exists())
        assertEquals("keep", File(target, "keep.txt").readText())
    }

    @Test
    fun renameAndMove_absoluteLink_preserveLinkAndTarget() {
        val access = linkedAccess()
        val target = tmp.newFile("target.txt").apply { writeText("keep") }
        Files.createSymbolicLink(File(tmp.root, "link").toPath(), File("/root/workspace/target.txt").toPath())
        access.rename("link", "renamed")
        access.move("renamed", "moved", overwrite = false)
        assertTrue(Files.isSymbolicLink(File(tmp.root, "moved").toPath()))
        assertEquals("keep", target.readText())
        assertEquals("keep", access.readFile("moved"))
    }

    @Test
    fun danglingLink_canBeRenamedAndDeleted() {
        val access = linkedAccess()
        Files.createSymbolicLink(File(tmp.root, "link").toPath(), File("/missing").toPath())
        access.rename("link", "renamed")
        access.delete("renamed")
        assertFalse(Files.isSymbolicLink(File(tmp.root, "renamed").toPath()))
    }

    @Test
    fun listing_linkCycle_keepsEntriesButReadingReportsLoop() {
        val access = linkedAccess()
        Files.createSymbolicLink(File(tmp.root, "a").toPath(), File("b").toPath())
        Files.createSymbolicLink(File(tmp.root, "b").toPath(), File("a").toPath())
        assertEquals(setOf("a", "b"), access.listFiles(".").map { it.name }.toSet())
        assertTrue(access.listFilesRecursive(".", maxDepth = 8).isEmpty())
        assertThrows(FileSystemLoopException::class.java) { access.readFile("a") }
    }

    @Test
    fun recursiveListing_directoryLinkToAncestor_stopsAtAncestor() {
        val access = linkedAccess()
        val directory = tmp.newFolder("directory")
        File(directory, "probe.txt").writeText("test")
        Files.createSymbolicLink(File(directory, "back").toPath(), File("/root/workspace").toPath())
        assertEquals(listOf("directory/probe.txt"), access.listFilesRecursive(".", maxDepth = 8))
    }

    // ---------- readFile / writeFile ----------

    @Test
    fun writeFile_createsFileAndReadBack() {
        newAccess().writeFile("a.txt", "hello", overwrite = true)
        assertEquals("hello", File(tmp.root, "a.txt").readText())
        assertEquals("hello", newAccess().readFile("a.txt"))
    }

    @Test
    fun writeFile_usesProvidedEncoding() {
        newAccess().writeFile("cn.txt", "中文内容", overwrite = true, encoding = Charsets.UTF_8)
        assertEquals("中文内容", newAccess().readFile("cn.txt"))
    }

    @Test
    fun writeFile_overwriteTrue_replacesExisting() {
        newAccess().writeFile("a.txt", "v1", overwrite = true)
        newAccess().writeFile("a.txt", "v2", overwrite = true)
        assertEquals("v2", newAccess().readFile("a.txt"))
    }

    @Test
    fun writeFile_overwriteFalse_existingFile_throws() {
        newAccess().writeFile("a.txt", "v1", overwrite = true)
        assertThrows(FileAlreadyExistsException::class.java) {
            newAccess().writeFile("a.txt", "v2", overwrite = false)
        }
    }

    @Test
    fun writeFile_createsParentDirectories() {
        newAccess().writeFile("sub/dir/a.txt", "deep", overwrite = true)
        assertTrue(File(tmp.root, "sub/dir/a.txt").isFile)
    }

    @Test
    fun readFile_missingFile_throws() {
        assertThrows(NoSuchFileException::class.java) {
            newAccess().readFile("missing.txt")
        }
    }

    // ---------- readLines ----------

    @Test
    fun readLines_iteratesAllLines_includingEmpty() {
        newAccess().writeFile("lines.txt", "line1\n\nline3\n", overwrite = true)
        assertEquals(listOf("line1", "", "line3"), newAccess().readLines("lines.txt").toList())
    }

    @Test
    fun readLines_sequenceCanIterateMultipleTimes() {
        // 实现是惰性 sequence（每次迭代重新打开 reader），验证可重复迭代
        newAccess().writeFile("lines.txt", "a\nb\n", overwrite = true)
        val seq = newAccess().readLines("lines.txt")
        assertEquals(listOf("a", "b"), seq.toList())
        assertEquals(listOf("a", "b"), seq.toList())
    }

    @Test
    fun readLines_missingFile_throws() {
        assertThrows(NoSuchFileException::class.java) {
            newAccess().readLines("missing.txt")
        }
    }

    // ---------- 元数据：exists / isDirectory / isFile / fileSize / lastModified ----------

    @Test
    fun exists_isDirectory_isFile() {
        val access = newAccess()
        assertFalse(access.exists("nope"))
        access.writeFile("f.txt", "x", overwrite = true)
        assertTrue(access.exists("f.txt"))
        assertTrue(access.isFile("f.txt"))
        assertFalse(access.isDirectory("f.txt"))
        assertTrue(access.isDirectory("."))
        assertFalse(access.isFile("."))
    }

    @Test
    fun fileSizeAndLastModified_reflectRealFile() {
        val access = newAccess()
        access.writeFile("f.txt", "12345", overwrite = true)
        assertEquals(5L, access.fileSize("f.txt"))
        assertTrue(access.lastModified("f.txt") > 0)
    }

    // ---------- listFiles / permissions ----------

    @Test
    fun listFiles_returnsFileEntries() {
        val access = newAccess()
        access.writeFile("a.txt", "data", overwrite = true)
        tmp.newFolder("sub")
        tmp.newFile("b.txt")

        val entries = access.listFiles(".")
        assertEquals(3, entries.size)

        val a = entries.first { it.name == "a.txt" }
        assertEquals(4L, a.size)
        assertFalse(a.isDirectory)
        assertNotNull(a.localFile)
        val expectedFilePerm = if (a.localFile?.canExecute() == true) "rwx" else "rw-"
        assertEquals(expectedFilePerm, a.permissions)

        val sub = entries.first { it.name == "sub" }
        assertTrue(sub.isDirectory)
        assertEquals("rwx", sub.permissions)

        val b = entries.first { it.name == "b.txt" }
        assertEquals(0L, b.size)
    }

    @Test
    fun listFiles_missingDir_returnsEmpty() {
        assertEquals(emptyList<FileEntry>(), newAccess().listFiles("missing"))
    }

    @Test
    fun permissions_fileAndDirectory() {
        val access = newAccess()
        access.writeFile("f.txt", "x", overwrite = true)
        val expectedFilePerm = if (File(tmp.root, "f.txt").canExecute()) "rwx" else "rw-"
        assertEquals(expectedFilePerm, access.permissions("f.txt"))
        // 目录恒带 x 位
        assertEquals("rwx", access.permissions("."))
    }

    // ---------- readBytes / writeBytes ----------

    @Test
    fun readBytesAndWriteBytes_roundTrip() {
        val access = newAccess()
        val bytes = byteArrayOf(1, 2, 3, -1, 0)
        access.writeBytes("bin.dat", bytes, overwrite = true)
        assertTrue(bytes.contentEquals(access.readBytes("bin.dat")))
    }

    @Test
    fun writeBytes_overwriteFalse_existing_throws() {
        val access = newAccess()
        access.writeBytes("bin.dat", byteArrayOf(1), overwrite = true)
        assertThrows(FileAlreadyExistsException::class.java) {
            access.writeBytes("bin.dat", byteArrayOf(2), overwrite = false)
        }
    }

    @Test
    fun boundedRead_acceptsExactLimitAndRejectsSparseOversizedFile() {
        val access = newAccess()
        tmp.newFile("small.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(access.readBytes("small.bin", 3)))
        tmp.newFile("empty.bin")
        assertEquals(0, access.readBytes("empty.bin", 0).size)
        val large = tmp.newFile("large.bin")
        RandomAccessFile(large, "rw").use { it.setLength(256L * 1024 * 1024) }
        assertThrows(FileReadTooLargeException::class.java) {
            access.readBytes("large.bin", 8L * 1024 * 1024)
        }
    }

    @Test
    fun boundedStreamRead_stopsAfterLimitPlusOneEvenWhenSourceKeepsGrowing() {
        var readBytes = 0L
        val stream = object : InputStream() {
            override fun read(): Int = error("expected buffered read")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                readBytes += length
                return length
            }
        }
        assertThrows(FileReadTooLargeException::class.java) { stream.readBytesBounded(8192) }
        assertEquals(8193L, readBytes)
    }

    @Test
    fun resolvePath_matchesBackendLinkAndParentSemantics() {
        val access = linkedAccess()
        tmp.newFolder("target", "nested")
        Files.createSymbolicLink(File(tmp.root, "link").toPath(), File("target/nested").toPath())
        assertEquals(File(tmp.root, "target").path, access.resolvePath("link/.."))
        assertEquals(File(tmp.root, "link").path, access.resolvePath("link", followFinalLink = false))
        assertEquals(File(tmp.root, "target/nested").path, access.resolvePath("link/.", followFinalLink = false))
        assertEquals(tmp.root.path, access.resolvePath("/root//workspace/."))
        assertEquals(File(tmp.root, "target/new/file").path, access.resolvePath("link/../new/file"))
    }

    // ---------- fingerprint ----------

    @Test
    fun fingerprint_missingFile_returnsNull() {
        assertEquals(null, newAccess().fingerprint("missing.txt"))
    }

    @Test
    fun fingerprint_directory_returnsNull() {
        // 目录没有「内容版本」可言，返回 null 让并发校验放行
        assertEquals(null, newAccess().fingerprint("."))
    }

    @Test
    fun fingerprint_isStableForSameContent_andChangesWithContent() {
        val access = newAccess()
        access.writeFile("f.txt", "same", overwrite = true)
        val first = access.fingerprint("f.txt")
        assertNotNull(first)
        assertTrue("指纹应带算法前缀，便于将来换算法时不会误判为相等", first!!.startsWith("sha256:"))
        assertEquals("内容未变时指纹必须稳定", first, access.fingerprint("f.txt"))

        access.writeFile("f.txt", "different", overwrite = true)
        assertNotEquals("内容变化后指纹必须不同", first, access.fingerprint("f.txt"))
    }

    @Test
    fun fingerprint_isEncodingAgnostic_hashesRawBytes() {
        // 指纹按原始字节计算，与调用方用哪种编码解码无关；不同字节必须得到不同指纹。
        val access = newAccess()
        access.writeBytes("bin.dat", byteArrayOf(1, 2, 3, -1, 0), overwrite = true)
        val byBytes = access.fingerprint("bin.dat")
        assertNotNull(byBytes)

        access.writeBytes("bin2.dat", byteArrayOf(1, 2, 3, -1, 1), overwrite = true)
        assertNotEquals("仅末字节不同也必须产生不同指纹", byBytes, access.fingerprint("bin2.dat"))
    }

    // ---------- 原子写 ----------

    @Test
    fun writeFile_leavesNoTempFileBehind() {
        val access = newAccess()
        access.writeFile("f.txt", "content", overwrite = true)
        access.writeBytes("b.dat", byteArrayOf(1, 2, 3), overwrite = true)

        val leftovers = tmp.root.listFiles().orEmpty().map { it.name }
            .filter { it.startsWith(".aicode-tmp-") }
        assertEquals("原子写不应留下临时文件，实际=$leftovers", emptyList<String>(), leftovers)
    }

    @Test
    fun writeFile_overwrite_replacesContentExactly() {
        // 原子替换路径（delete + rename）必须产出与直接写完全一致的结果
        val access = newAccess()
        access.writeFile("f.txt", "longer-old-content", overwrite = true)
        access.writeFile("f.txt", "s", overwrite = true)
        assertEquals("s", access.readFile("f.txt"))
        assertEquals(1L, access.fileSize("f.txt"))
    }

    @Test
    fun writeBytes_overwriteFalse_doesNotTouchExistingFile() {
        val access = newAccess()
        access.writeBytes("b.dat", byteArrayOf(9), overwrite = true)
        assertThrows(FileAlreadyExistsException::class.java) {
            access.writeBytes("b.dat", byteArrayOf(1, 2), overwrite = false)
        }
        assertTrue("被拒绝的写入不得改动原文件", byteArrayOf(9).contentEquals(access.readBytes("b.dat")))
    }

    @Test
    fun moveReplacing_movesWhenTargetIsClear() {
        val tmpFile = tmp.newFile("tmp.part")
        val target = File(tmp.root, "target.txt")

        moveReplacing(
            exists = { false },
            delete = { error("目标不存在时不应调用 delete") },
            move = { from, to -> from.renameTo(to) },
            tmp = tmpFile,
            target = target
        )

        assertTrue("替换后目标应存在", target.isFile)
        assertFalse("临时文件应被消费掉", tmpFile.exists())
    }

    @Test
    fun moveReplacing_failsFastWhenTargetCannotBeCleared() {
        val tmpFile = tmp.newFile("tmp.part")
        val target = tmp.newFile("target.txt")
        var moveAttempted = false

        assertThrows(java.io.IOException::class.java) {
            moveReplacing(
                exists = { true },
                delete = { false }, // 目标删不掉
                move = { _, _ -> moveAttempted = true; true },
                tmp = tmpFile,
                target = target
            )
        }

        assertFalse("删不掉目标时不得继续改名（否则会破坏目标）", moveAttempted)
        assertFalse("失败路径必须清掉临时文件", tmpFile.exists())
    }

    @Test
    fun moveReplacing_cleansTempWhenMoveFails() {
        val tmpFile = tmp.newFile("tmp.part")
        val target = File(tmp.root, "target.txt")

        assertThrows(java.io.IOException::class.java) {
            moveReplacing(
                exists = { false },
                delete = { true },
                move = { _, _ -> false }, // 改名失败
                tmp = tmpFile,
                target = target
            )
        }

        assertFalse("改名失败时必须清掉临时文件", tmpFile.exists())
    }

    // ---------- delete / rename / copy / move / mkdirs ----------

    @Test
    fun delete_removesFile() {
        val access = newAccess()
        access.writeFile("f.txt", "x", overwrite = true)
        assertTrue(access.exists("f.txt"))
        access.delete("f.txt")
        assertFalse(access.exists("f.txt"))
    }

    @Test
    fun deleteRecursively_removesTree() {
        val access = newAccess()
        access.writeFile("dir/a.txt", "x", overwrite = true)
        access.writeFile("dir/sub/b.txt", "y", overwrite = true)
        access.deleteRecursively("dir")
        assertFalse(access.exists("dir"))
    }

    @Test
    fun deleteRecursively_missing_silent() {
        newAccess().deleteRecursively("missing")
    }

    @Test
    fun rename_movesFile() {
        val access = newAccess()
        access.writeFile("src.txt", "x", overwrite = true)
        access.rename("src.txt", "dst.txt")
        assertFalse(access.exists("src.txt"))
        assertEquals("x", access.readFile("dst.txt"))
    }

    @Test
    fun rename_missingSource_throws() {
        assertThrows(NoSuchFileException::class.java) {
            newAccess().rename("missing", "dst.txt")
        }
    }

    @Test
    fun rename_targetExists_throws() {
        val access = newAccess()
        access.writeFile("src.txt", "x", overwrite = true)
        access.writeFile("dst.txt", "y", overwrite = true)
        assertThrows(FileAlreadyExistsException::class.java) {
            access.rename("src.txt", "dst.txt")
        }
    }

    @Test
    fun copy_fileToNewPath() {
        val access = newAccess()
        access.writeFile("src.txt", "content", overwrite = true)
        access.copy("src.txt", "dst.txt", overwrite = false)
        assertEquals("content", access.readFile("dst.txt"))
        assertEquals("content", access.readFile("src.txt"))
    }

    @Test
    fun copy_missingSource_throws() {
        assertThrows(NoSuchFileException::class.java) {
            newAccess().copy("missing", "dst.txt", overwrite = true)
        }
    }

    @Test
    fun copy_targetExistsWithoutOverwrite_throws() {
        val access = newAccess()
        access.writeFile("src.txt", "x", overwrite = true)
        access.writeFile("dst.txt", "y", overwrite = true)
        assertThrows(FileAlreadyExistsException::class.java) {
            access.copy("src.txt", "dst.txt", overwrite = false)
        }
    }

    @Test
    fun copy_samePathOverwrite_preservesSource() {
        val access = newAccess()
        access.writeFile("src.txt", "content", overwrite = true)
        assertThrows(java.io.IOException::class.java) {
            access.copy("src.txt", "./src.txt", overwrite = true)
        }
        assertEquals("content", access.readFile("src.txt"))
    }

    @Test
    fun move_samePathOverwrite_preservesSource() {
        val access = newAccess()
        access.writeFile("src.txt", "content", overwrite = true)
        assertThrows(java.io.IOException::class.java) {
            access.move("src.txt", "./src.txt", overwrite = true)
        }
        assertEquals("content", access.readFile("src.txt"))
    }

    @Test
    fun copy_directoryIntoDescendant_preservesSource() {
        val access = newAccess()
        access.writeFile("dir/file.txt", "content", overwrite = true)
        assertThrows(java.io.IOException::class.java) {
            access.copy("dir", "dir/child", overwrite = true)
        }
        assertEquals("content", access.readFile("dir/file.txt"))
        assertFalse(access.exists("dir/child"))
    }

    @Test
    fun move_directoryIntoDescendant_preservesSource() {
        val access = newAccess()
        access.writeFile("dir/file.txt", "content", overwrite = true)
        assertThrows(java.io.IOException::class.java) {
            access.move("dir", "dir/child", overwrite = true)
        }
        assertEquals("content", access.readFile("dir/file.txt"))
        assertFalse(access.exists("dir/child"))
    }

    @Test
    fun copy_directoryToSiblingWithSharedPrefix_succeeds() {
        val access = newAccess()
        access.writeFile("dir/file.txt", "content", overwrite = true)
        access.copy("dir", "directory", overwrite = false)
        assertEquals("content", access.readFile("directory/file.txt"))
    }

    @Test
    fun move_file() {
        val access = newAccess()
        access.writeFile("src.txt", "x", overwrite = true)
        access.move("src.txt", "dst.txt", overwrite = false)
        assertFalse(access.exists("src.txt"))
        assertEquals("x", access.readFile("dst.txt"))
    }

    @Test
    fun mkdirs_createsNestedDirectories() {
        newAccess().mkdirs("a/b/c")
        assertTrue(File(tmp.root, "a/b/c").isDirectory)
    }

    // ---------- 路径回显 ----------

    @Test
    fun parentPath_returnsContainerPathOfParent() {
        val access = newAccess()
        assertEquals(tmp.root.absolutePath, access.parentPath("child.txt"))
    }

    @Test
    fun toDisplayPath_returnsContainerPath() {
        val access = newAccess()
        assertEquals(File(tmp.root, "f.txt").absolutePath, access.toDisplayPath("f.txt"))
    }

    @Test
    fun copyToLocal_returnsHostFile() {
        val access = newAccess()
        access.writeFile("f.txt", "x", overwrite = true)
        assertEquals(File(tmp.root, "f.txt"), access.copyToLocal("f.txt"))
    }
}
