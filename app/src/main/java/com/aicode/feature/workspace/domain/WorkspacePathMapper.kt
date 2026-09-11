package com.aicode.feature.workspace.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 在「容器内路径」与「宿主真实路径」之间互转，让 AI 只看到 / 只使用容器路径。
 *
 * 背景：容器是 PRoot 以当前 profile 的 rootfs 目录为根（`-r rootfs`）跑起来的，
 * 当前工作区目录又被 bind 成容器内的 [CONTAINER_ROOT]（`~/workspace`，展开为 `$HOME/workspace`）。因此「容器内路径」到「宿主真实文件」有两条确定映射：
 * - `~/workspace[/…]` → 宿主工作区目录（写它即写宿主，且容器内可见——bind mount）；
 * - 其它容器绝对路径 `/etc/…`、`/root/…` → 当前 profile rootfs 目录下对应文件（与终端在容器里看到的完全是同一批文件）。
 *
 * 这样文件类工具（read/write/edit）无需进 PRoot 即可读写整个容器文件系统，与 `execute_command` 看到的一致。
 *
 * **profile 感知**：rootfs 目录随当前选中 profile 变化（内置 Alpine 用 filesDir/rootfs，自定义用 filesDir/rootfs_<id>）。
 * [currentProfile] 缓存自 [ContainerSettingsRepository]，避免同步读 DataStore；启动首帧为内置 Alpine，等同改动前。
 *
 * 用本映射器统一：
 * - 工具入参（AI 给的路径）经 [toHostFile] 落到宿主真实文件；
 * - 工具回显/返回的路径经 [toContainerPath] 还原成容器视角（`~/workspace/…` 或 `/etc/…`），对 AI 只暴露容器路径。
 */
@Singleton
class WorkspacePathMapper @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val containerInstaller: ContainerInstaller,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val pathHomeResolver: PathHomeResolver
) {
    companion object {
        /** AI 看到的工作区根路径。用 `~` 形式让提示词/工具描述更自然，内部用 [resolvedContainerRoot] 展开后匹配。 */
        const val CONTAINER_ROOT = "~/workspace"
        /** AI 配置目录在容器内的路径，绑定到宿主 [ContainerInstaller.aicodeDir]（独立于 rootfs）。 */
        const val AICODE_ROOT = "/root/.aicode"
        private const val TAG = "WorkspacePathMapper"
    }

    /**
     * 当前选中的 profile（缓存，避免同步读 DataStore）。启动首帧为内置 Alpine，等同改动前。
     * profile 切换后由 flow collector 更新；切换瞬间与引擎缓存可能短暂不一致，但 ensureInstalled 与文件工具
     * 调用之间有自然顺序，实际不影响。
     */
    @Volatile
    private var currentProfile: ContainerProfile = ContainerProfile.BUILTIN_ALPINE

    init {
        CoroutineScope(Dispatchers.IO).launch {
            containerSettingsRepository.activeProfileIdFlow.collect { id ->
                currentProfile = resolveProfile(id)
            }
        }
    }

    private suspend fun resolveProfile(id: String): ContainerProfile {
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id }
            ?: profiles.firstOrNull()
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    /** 当前工作区在宿主上的根目录。 */
    private fun hostRoot(): File = File(workspaceRepository.currentPath())

    /** [CONTAINER_ROOT] 展开后的绝对路径（`$HOME/workspace`），供路径匹配使用。home 未就绪时回退 `/root`。 */
    private fun resolvedContainerRoot(): String =
        pathHomeResolver.home().trimEnd('/') + "/workspace"

    /** 容器 rootfs 在宿主上的根目录（容器内 `/` 即此目录，随当前 profile 变化）。 */
    private fun rootfsRoot(): File = containerInstaller.rootfsDirFor(currentProfile)

    /** AI 配置目录在宿主上的根（容器内 `/root/.aicode` 即此目录，独立于 rootfs）。 */
    private fun aicodeRoot(): File = containerInstaller.aicodeDir

    /**
     * 把 AI 提供的路径解析为宿主真实文件。兼容以下写法：
     * - 容器绝对路径 `~/workspace[/…]`（或展开后的 `$HOME/workspace[/…]`）→ 映射到宿主工作区；
     * - 容器绝对路径 `/root/.aicode[/…]` → 映射到宿主 AI 配置目录（skill / mcp.json，独立于 rootfs）；
     * - 其它容器绝对路径 `/etc/…`、`/root/…` → 映射到 rootfs 内对应文件（容器系统文件）；
     * - 相对路径 `src/Main.kt` → 挂到宿主工作区根下；
     *
     * `/root/.aicode` 必须先于通用 `/`→rootfs 规则匹配，否则会落到 rootfs 内的临时副本（升级即丢）。
     */
    fun toHostFile(path: String): File {
        val root = hostRoot()
        val wsRoot = resolvedContainerRoot()
        val p = pathHomeResolver.expandHome(path.trim())
        val file = when {
            p == wsRoot || p == "$wsRoot/" || p == CONTAINER_ROOT || p == "$CONTAINER_ROOT/" -> root
            p.startsWith("$wsRoot/") -> File(root, p.removePrefix("$wsRoot/"))
            p == AICODE_ROOT || p == "$AICODE_ROOT/" -> aicodeRoot()
            p.startsWith("$AICODE_ROOT/") -> File(aicodeRoot(), p.removePrefix("$AICODE_ROOT/"))
            else -> mountedHostFile(p)
                ?: if (p.startsWith("/")) File(rootfsRoot(), p.removePrefix("/")) else File(root, p)
        }
        FileLogger.v(TAG, "toHostFile '$path' -> ${file.absolutePath}")
        return file
    }

    /**
     * 把宿主路径还原为容器路径：
     * - 位于工作区内 → `~/workspace/…`；
     * - 位于 AI 配置目录内 → `/root/.aicode/…`；
     * - 位于 rootfs 内 → 去掉 rootfs 前缀的容器绝对路径（如 `/etc/apk/repositories`）；
     * - 其余原样返回（极少出现）。
     *
     * 工作区在 `filesDir/projects`、AI 配置在 `filesDir/aicode`、rootfs 在 `filesDir/rootfs`，三者互不重叠，
     * 判断顺序无歧义。
     */
    fun toContainerPath(hostPath: String): String {
        val rootPath = hostRoot().absolutePath.replace('\\', '/')
        val aicodePath = aicodeRoot().absolutePath.replace('\\', '/')
        val rootfsPath = rootfsRoot().absolutePath.replace('\\', '/')
        val abs = File(hostPath).absolutePath.replace('\\', '/')
        val raw = hostPath.trim().replace('\\', '/')
        val resolvedWs = resolvedContainerRoot().replace('\\', '/')
        return when {
            abs == rootPath -> CONTAINER_ROOT
            abs.startsWith("$rootPath/") -> CONTAINER_ROOT + "/" + abs.removePrefix("$rootPath/")
            // 展开后的 $HOME/workspace 形式也还原为 ~/workspace（bind mount 路径可能以绝对形式出现）
            raw == resolvedWs || abs == resolvedWs -> CONTAINER_ROOT
            raw.startsWith("$resolvedWs/") -> CONTAINER_ROOT + "/" + raw.removePrefix("$resolvedWs/")
            abs.startsWith("$resolvedWs/") -> CONTAINER_ROOT + "/" + abs.removePrefix("$resolvedWs/")
            abs == aicodePath -> AICODE_ROOT
            abs.startsWith("$aicodePath/") -> AICODE_ROOT + "/" + abs.removePrefix("$aicodePath/")
            abs == rootfsPath -> "/"
            abs.startsWith("$rootfsPath/") -> "/" + abs.removePrefix("$rootfsPath/")
            else -> mountedContainerPath(abs) ?: hostPath
        }
    }

    /**
     * 当前 profile 的额外挂载（[ContainerProfile.extraBindings]，格式 `本地源:容器目标`）解析为
     * (宿主源, 展开并去尾斜杠的容器目标)，按容器目标长度降序（最长前缀优先，避免嵌套挂载歧义）。
     * 文件工具不进 PRoot，若不在此映射，挂载路径会被兜底落到 rootfs 内部，
     * 与容器内 shell（proot `-b` bind mount）看到的不一致。
     */
    private fun extraMounts(): List<Pair<String, String>> =
        currentProfile.extraBindings.mapNotNull { binding ->
            val idx = binding.indexOf(':')
            val src = (if (idx >= 0) binding.substring(0, idx) else binding).trim()
            val dstRaw = if (idx >= 0) binding.substring(idx + 1) else binding
            val dst = pathHomeResolver.expandHome(dstRaw.trim()).trimEnd('/')
            if (src.isEmpty() || dst.isEmpty()) null else src to dst
        }.sortedByDescending { it.second.length }

    /** [p]（已展开的容器路径）落在某额外挂载目标下时，映射到宿主源真实文件；否则 null。 */
    private fun mountedHostFile(p: String): File? {
        for ((src, dst) in extraMounts()) {
            when {
                p == dst || p == "$dst/" -> return File(src)
                p.startsWith("$dst/") -> return File(src, p.removePrefix("$dst/"))
            }
        }
        return null
    }

    /** 宿主 [abs] 落在某额外挂载源下时，还原为容器目标路径；否则 null。 */
    private fun mountedContainerPath(abs: String): String? {
        for ((src, dst) in extraMounts()) {
            val srcAbs = File(src).absolutePath
            when {
                abs == srcAbs -> return dst
                abs.startsWith("$srcAbs/") -> return "$dst/" + abs.removePrefix("$srcAbs/")
            }
        }
        return null
    }
}
