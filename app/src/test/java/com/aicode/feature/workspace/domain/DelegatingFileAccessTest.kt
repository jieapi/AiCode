package com.aicode.feature.workspace.domain

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class DelegatingFileAccessTest {
    @Test
    fun pathResolutionAndBoundedReadsFollowCurrentMode() {
        val mode = ExecutionModeHolder()
        val local = mockk<LocalFileAccess>()
        val remote = mockk<RemoteSftpFileAccess>()
        val access = DelegatingFileAccess(mode, local, remote)
        every { local.resolvePath("link/..", false) } returns "/local/parent"
        every { remote.resolvePath("link/..", false) } returns "/remote/parent"
        every { local.readBytes("file", 3) } returns byteArrayOf(1)
        every { remote.readBytes("file", 3) } returns byteArrayOf(2)
        assertEquals("/local/parent", access.resolvePath("link/..", false))
        assertEquals(listOf<Byte>(1), access.readBytes("file", 3).toList())
        mode.setMode(ExecutionMode.REMOTE_SSH)
        assertEquals("/remote/parent", access.resolvePath("link/..", false))
        assertEquals(listOf<Byte>(2), access.readBytes("file", 3).toList())
        verify(exactly = 0) { local.readBytes(any()) }
        verify(exactly = 0) { remote.readBytes(any()) }
    }
}
