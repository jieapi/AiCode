package com.aicode.feature.agent.domain.container

import android.content.Context
import android.system.Os
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 负责把打进 assets 的 Alpine rootfs 与 PRoot 二进制安装到 App 私有目录。
 *
 * 自动根据设备架构（ARM / x86）加载对应资源。targetSdk 锁定 28，数据目录文件才可执行（见 build.gradle.kts）。
 */
@Singleton
class ContainerInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerOsDetector: ContainerOsDetector
) {
    companion object {
        private const val TAG = "ContainerInstaller"
        @Volatile private var docsExtractedSession = false

        /** 从 assets 提取文档到 ~/.aicode/docs (内置使用指导) */
        fun extractDocs(context: Context) {
            if (docsExtractedSession) return
            val destDir = File(File(context.filesDir, "aicode"), "docs")
            destDir.mkdirs()
            runCatching {
                val docs = context.assets.list("docs") ?: return
                for (doc in docs) {
                    val destFile = File(destDir, doc)
                    context.assets.open("docs/$doc").use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                docsExtractedSession = true
            }.onFailure {
                FileLogger.w(TAG, "提取内置文档失败: ${it.message}", it)
            }
        }

        /**
         * 从 assets 提取内置提示词到 ~/.aicode/prompts/，每次启动全量覆盖，使 App 升级后提示词随之更新。
         *
         * 用户自定义覆盖放在 ~/.aicode/prompts.custom/（同名即覆盖），本方法不触碰该目录，
         * 故用户重写的片段不会被升级覆盖。参见 [com.aicode.feature.agent.domain.prompt.SystemPromptProvider]。
         */
        fun extractPrompts(context: Context) {
            val destDir = File(File(context.filesDir, "aicode"), "prompts")
            destDir.mkdirs()
            runCatching {
                extractPromptsRecursive(context, "prompts", destDir)
            }.onFailure {
                FileLogger.w(TAG, "提取内置提示词失败: ${it.message}", it)
            }
        }

        /** 递归复制 assets 下的提示词目录到目标目录，支持子目录（如 prompts/agent/）。 */
        private fun extractPromptsRecursive(context: Context, assetDir: String, destDir: File) {
            val entries = context.assets.list(assetDir) ?: return
            destDir.mkdirs()
            for (entry in entries) {
                val assetPath = "$assetDir/$entry"
                val destFile = File(destDir, entry)
                try {
                    context.assets.open(assetPath).use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: IOException) {
                    // 目录项：assets.open 对目录抛 IOException，递归处理
                    extractPromptsRecursive(context, assetPath, destFile)
                }
            }
        }

        /**
         * 从 assets 提取内置脚本（如套餐余量 demo_balance.py）到 ~/.aicode/scripts/。
         * 若文件已存在则不覆盖，以保留用户的修改。
         */
        fun extractScripts(context: Context) {
            val destDir = File(File(context.filesDir, "aicode"), "scripts")
            destDir.mkdirs()
            runCatching {
                val entries = context.assets.list("aicode/scripts") ?: return@runCatching
                for (entry in entries) {
                    val destFile = File(destDir, entry)
                    if (!destFile.exists()) {
                        context.assets.open("aicode/scripts/$entry").use { input ->
                            destFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        destFile.setExecutable(true, false)
                    }
                }
            }.onFailure {
                FileLogger.w(TAG, "提取内置脚本失败: ${it.message}", it)
            }
        }

        /**
         * 从 assets 提取自定义 git credential helper 到 ~/.aicode/git-credential-aicode 并赋可执行位。
         *
         * 经 [LinuxContainerEngine] 的 -b 绑定即容器内 /root/.aicode/git-credential-aicode，
         * 由容器初始化菜单（provision.sh）在 `.gitconfig` 里登记为第二个 credential.helper，
         * 排在 `store` 之后兜底未登录（双保险）。helper 详行为见 assets/aicode/git-credential-aicode。
         *
         * 启动即提取、独立于 provisioning 成败：provisioning 失败时 git 没装上，helper 配置不存在也无所谓；
         * 一旦 git 装好且配置登记，helper 立即可用。提取失败仅告警不抛（helper 缺席仅导致未登录时无弹窗，
         * git 仍能裸跑报认证失败，不致命）。
         */
        fun extractCredentialHelper(context: Context) {
            val dest = File(File(context.filesDir, "aicode"), "git-credential-aicode")
            runCatching {
                dest.parentFile?.mkdirs()
                context.assets.open("aicode/git-credential-aicode").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                // 对所有用户赋可执行位（proot 进程以 App uid 运行，参照 [copyAsset] 的 0o111 模式）。
                if (!dest.setExecutable(true, false)) {
                    FileLogger.w(TAG, "setExecutable 返回 false: ${dest.absolutePath}")
                }
            }.onFailure {
                FileLogger.w(TAG, "提取 git credential helper 失败: ${it.message}", it)
            }
        }

        /**
         * 从 assets 提取容器初始化依赖安装脚本到 ~/.aicode/provision.sh 并赋可执行位。
         *
         * 经 [LinuxContainerEngine] 的 -b 绑定即容器内 /root/.aicode/provision.sh，由
         * 首次进入终端的初始化菜单以 `sh` 执行——脚本按包管理器
         * （apk/apt-get/dnf/yum/pacman）安装基础包，包清单维护在脚本内而非代码里。
         * 启动即提取、每次覆盖写（脚本随 App 版本更新）。提取失败仅告警不抛：
         * 缺脚本时初始化菜单不会出现，用户可手动安装基础工具。
         */
        fun extractProvisionScript(context: Context) {
            val dest = File(File(context.filesDir, "aicode"), "provision.sh")
            runCatching {
                dest.parentFile?.mkdirs()
                context.assets.open("aicode/provision.sh").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                if (!dest.setExecutable(true, false)) {
                    FileLogger.w(TAG, "setExecutable 返回 false: ${dest.absolutePath}")
                }
            }.onFailure {
                FileLogger.w(TAG, "提取 provision 脚本失败: ${it.message}", it)
            }
        }

        /**
         * 与 assets 里 alpine-rootfs 版本对应的 apk 分支，用于拼镜像源地址。
         * 固定 v3.21：与 [INSTALL_VERSION]（alpine-3.21.3）一致；该版本 apk-tools 2.14 在 proot 下可靠。
         */
        /**
         * 安装版本。换 rootfs / proot 或改安装逻辑时 +1，触发重新解压。
         * 与 assets 里实际放的 Alpine 版本保持一致以便排查。
         */
        private const val INSTALL_VERSION = "alpine-3.21.3-v6"
    }

    /** assets 内的架构特定目录 */
    val ASSET_DIR: String
        get() {
            // 优先按设备 ABI 选对应镜像目录。
            // 但 release 包按 flavor 拆分后，单架构包只含一套镜像（armsolo→arm、x86solo→x86）：
            // 若该套不在 assets 里（例如 armsolo 包被装到只报 x86 的设备），下面会 fallback
            // 到实际存在的那套，避免 open() 直接崩溃——proot 能否真正运行由设备 ABI 决定，
            // 但至少 asset 查找层不会挂。
            val preferX86 = android.os.Build.SUPPORTED_ABIS.any { it.contains("x86") }
            val first = if (preferX86) "container/x86" else "container/arm"
            val fallback = if (preferX86) "container/arm" else "container/x86"
            return if (assetExists(first)) first else fallback
        }

    /** 轻量探测某 asset 路径是否被打进当前 APK（用于 [ASSET_DIR] 的 fallback 判断） */
    private fun assetExists(path: String): Boolean =
        context.assets.list(path.substringBeforeLast('/'))?.any { it == path.substringAfterLast('/') } == true

    val ASSET_PROOT: String get() = "$ASSET_DIR/proot"
    // Termux proot 的 loader 分离（靠 PROOT_LOADER 定位），且动态依赖 libtalloc / libandroid-shmem。
    val ASSET_LOADER: String get() = "$ASSET_DIR/loader"
    val ASSET_LOADER32: String get() = "$ASSET_DIR/loader32"
    val ASSET_LIBTALLOC: String get() = "$ASSET_DIR/libtalloc.so.2"
    val ASSET_LIBSHMEM: String get() = "$ASSET_DIR/libandroid-shmem.so"
    // 故意用中性的 .bin 后缀：AGP 的 asset 合并会把 .tar.gz/.tgz 当归档自动解压并改名，
    // 导致运行时 open("...tar.gz") 找不到文件。.bin 让它当普通二进制原样打包。
    val ASSET_ROOTFS: String get() = "$ASSET_DIR/alpine-rootfs.bin"

    /** rootfs 解压根目录 */
    val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    /**
     * AI 配置数据根目录（skill 指令 + MCP 配置），固定在 app 私有 filesDir。
     *
     * 刻意**独立于 [rootfsDir]**：rootfs 在容器版本升级时会被整体删除重装（见 [installRootfsIfNeed]），
     * 而本目录承载用户数据，必须跨升级保留。它由 [com.aicode.feature.agent.domain.container.LinuxContainerEngine]
     * 绑定到容器内 `/root/.aicode`，故 AI / 终端看到的 `/root/.aicode` 实际落在这里。
     */
    val aicodeDir: File
        get() = File(context.filesDir, "aicode")

    /** PRoot 可执行文件路径（Termux 构建，含 statx，动态链接 libtalloc/libandroid-shmem） */
    val prootBin: File
        get() = File(context.filesDir, "container/bin/proot")

    /** PRoot 的 64/32 位 loader（Termux proot loader 分离，由 PROOT_LOADER/_32 指向）。 */
    val prootLoader: File
        get() = File(context.filesDir, "container/bin/loader")
    val prootLoader32: File
        get() = File(context.filesDir, "container/bin/loader32")

    /** proot 的动态依赖库目录（libtalloc.so.2 / libandroid-shmem.so），由 LD_LIBRARY_PATH 指向。 */
    val prootLibDir: File
        get() = File(context.filesDir, "container/lib")

    /**
     * PRoot 在 Android 上必须的临时目录（Android 没有 /tmp）。
     * 放在 rootfs 的 /tmp（宿主 filesDir/rootfs/tmp）：cache 目录会被系统清理（清缓存后 proot
     * 找不到临时目录报 can't canonicalize），files 目录稳定；rootfs 重装后自带 /tmp 会重建，无需额外处理。
     */
    val prootTmpDir: File
        get() = File(rootfsDir, "tmp")

    /** 标记文件，内容是已安装的版本号 */
    private val installedMarker: File
        get() = File(rootfsDir, ".installed")

    /** 检查 rootfs 与 proot 是否已按当前版本安装就绪 */
    fun isInstalled(): Boolean {
        if (!prootBin.exists() || !rootfsDir.isDirectory) return false
        val marker = installedMarker
        return marker.exists() && marker.readText().trim() == INSTALL_VERSION
    }

    /**
     * 若未安装（或版本不匹配）则从 assets 解压安装。幂等，可在每次执行命令前调用。
     *
     * [onProgress] 在真正解压/部署的各阶段被回调以更新 [ContainerInitState]；已安装的快路径不会调用。
     */
    suspend fun installRootfsIfNeed(
        onProgress: (ContainerInitState) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext

        FileLogger.i(TAG, "开始安装容器 rootfs（版本 $INSTALL_VERSION）")

        // 版本不匹配时清掉旧的，保证干净安装
        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
        rootfsDir.mkdirs()

        onProgress(ContainerInitState.DeployingProot)
        installProot()
        extractRootfs(onProgress)
        configureResolvConf()
        prootTmpDir.mkdirs()

        installedMarker.writeText(INSTALL_VERSION)
        FileLogger.i(TAG, "容器 rootfs 安装完成")
    }

    /**
     * 按 [profile] 返回 rootfs 目录：内置仍是 [rootfsDir]（不动），自定义本地镜像用 filesDir/rootfs_<id>。
     * 远程 SSH profile 无本地 rootfs，返回一个占位目录（不会被使用/创建）。
     * 目录隔离——内置与自定义互不共享、互不删除，切回内置时其 rootfs 原封不动。
     */
    fun rootfsDirFor(profile: ContainerProfile): File =
        if (profile.isBuiltin) rootfsDir
        else File(context.filesDir, "rootfs_${profile.id}")

    /** 自定义镜像的已安装标记（独立于内置 .installed，避免混淆）。 */
    private fun customInstalledMarker(profile: ContainerProfile): File =
        File(rootfsDirFor(profile), ".installed_custom")

    /** 按 [profile] 判断是否已安装就绪：内置走现有版本校验，自定义本地看目录与标记，远程 SSH 恒就绪。 */
    fun isInstalledFor(profile: ContainerProfile): Boolean =
        when {
            profile.isBuiltin -> isInstalled()
            profile.rootfsSource is RootfsSource.RemoteSsh -> true
            else -> prootBin.exists() && rootfsDirFor(profile).isDirectory && customInstalledMarker(profile).exists()
        }

    /**
     * 按 [profile] 解压安装 rootfs。内置走 assets 全流程（proot/resolv/apk 源）；自定义本地镜像只解压
     * tar.gz + 装 proot + 写 DNS（不写 apk 源）。两者都不自动装包——基础工具由进入终端时的
     * 初始化菜单（assets/aicode/provision.sh）引导用户选择安装。远程 SSH profile 无本地 rootfs，直接返回
     * （命令执行走 [RemoteSshEngine]，不需本地 rootfs）。
     */
    suspend fun installRootfsIfNeed(
        profile: ContainerProfile,
        onProgress: (ContainerInitState) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (isInstalledFor(profile)) return@withContext

        // rootfs 将被重新解压，容器系统可能变化，清掉旧的识别缓存让下次运行重新检测。
        containerOsDetector.clear(profile.id)

        if (profile.isBuiltin) {
            installRootfsIfNeed(onProgress)
            return@withContext
        }

        // 远程 SSH profile：无本地 rootfs 可解压，视为就绪。
        if (profile.rootfsSource is RootfsSource.RemoteSsh) return@withContext

        val dest = rootfsDirFor(profile)
        FileLogger.i(TAG, "安装自定义容器 rootfs：${profile.id} -> ${dest.absolutePath}")
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()

        onProgress(ContainerInitState.DeployingProot)
        installProot()
        when (val src = profile.rootfsSource) {
            is RootfsSource.Asset -> context.assets.open("${ASSET_DIR}/${src.path}").use {
                extractRootfsTo(dest, it, CompressedFormat.GZIP, onProgress)
            }
            is RootfsSource.LocalFile -> {
                val uri = android.net.Uri.parse(src.uri)
                val format = if (src.uri.endsWith(".xz") || src.uri.endsWith(".txz"))
                    CompressedFormat.XZ else CompressedFormat.GZIP
                context.contentResolver.openInputStream(uri)?.use {
                    extractRootfsTo(dest, it, format, onProgress)
                } ?: FileLogger.w(TAG, "打开导入的 rootfs uri 失败: ${src.uri}")
            }
            is RootfsSource.RemoteSsh -> { /* 无本地 rootfs，上面已提前 return */ }
        }
        configureResolvConf(dest)
        prootTmpDir.mkdirs()
        repairRootfsCompatibility(dest)
        customInstalledMarker(profile).writeText("custom")
        FileLogger.i(TAG, "自定义容器 rootfs 安装完成：${profile.id}")
    }

    /**
     * rootfs 兼容性巡检（幂等，失败静默）：修复已知的 Ubuntu 25.10+ 布局问题。
     * 所有容器统一调用，作用面由内部存在性检测收敛——非命中容器零改动。
     */
    fun repairRootfsCompatibility(rootfs: File) {
        fixCoreutilsPermissions(rootfs)
        fixNodeGlobEntry(rootfs)
    }

    private fun fixCoreutilsPermissions(rootfs: File) {
        val coreutilsDir = File(rootfs, "usr/lib/cargo/bin/coreutils")
        if (!coreutilsDir.isDirectory) return
        val target = PosixFilePermissions.fromString("rwxr-xr-x")
        var fixed = 0
        fun fix(path: File) {
            runCatching {
                val p = path.toPath()
                if (Files.getPosixFilePermissions(p) != target) {
                    Files.setPosixFilePermissions(p, target)
                    fixed++
                }
            }.onFailure { FileLogger.w(TAG, "修复 coreutils 权限失败: ${path.absolutePath}", it) }
        }
        fix(File(rootfs, "usr/lib/cargo"))
        fix(File(rootfs, "usr/lib/cargo/bin"))
        fix(coreutilsDir)
        coreutilsDir.listFiles()?.forEach { fix(it) }
        if (fixed > 0) {
            FileLogger.i(TAG, "已修复容器 coreutils 权限: $fixed 个文件（${coreutilsDir.absolutePath}）")
        }
    }

    /**
     * 修复 Ubuntu 26.04 仓库打包 bug：node-glob 10.3.6 用 `dist/cjs/src -> .` 自引用软链做打包
     * 拍平，node 22+ 的 realpath 判定 ELOOP，npm 启动即报 MODULE_NOT_FOUND（与 PRoot 无关，
     * 原生 Ubuntu 26.04 同样中招）。真机布局下的修复：把 package.json 入口从 dist/cjs/src/
     * 改指真实打平文件所在 dist/cjs/。幂等：入口已正常则不动。
     */
    private fun fixNodeGlobEntry(rootfs: File) {
        val candidates = listOf(
            File(rootfs, "usr/share/nodejs/glob/package.json"),
            File(rootfs, "usr/share/node_modules/glob/package.json")
        )
        for (f in candidates) {
            if (!f.isFile) continue
            runCatching {
                val text = f.readText()
                if (text.contains("dist/cjs/src/")) {
                    f.writeText(text.replace("dist/cjs/src/", "dist/cjs/"))
                    FileLogger.i(TAG, "已修复 node-glob 入口: ${f.absolutePath}")
                }
            }.onFailure { FileLogger.w(TAG, "修复 node-glob 入口失败: ${f.absolutePath}", it) }
        }
    }

    /** 删除自定义 profile 的 rootfs 目录（删 profile 时调用）。内置 rootfs 不可删，远程 SSH 无 rootfs 可删。 */
    fun deleteCustomRootfs(profile: ContainerProfile) {
        // profile 被删除或换镜像，缓存随之作废（重新运行时重新检测）。
        containerOsDetector.clear(profile.id)
        if (profile.isBuiltin) return
        if (profile.rootfsSource is RootfsSource.RemoteSsh) return
        rootfsDirFor(profile).deleteRecursively()
    }

    /**
     * 重置内置 Alpine 容器：删除其 rootfs 目录（含 .installed / .provisioned 标记），
     * 下次 [ensureInstalled] 会重新解压；进入终端时由初始化菜单重新引导安装。供内置镜像「重置」按钮调用。
     */
    fun resetBuiltinRootfs() {
        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
    }

    /** 按 [profile] 统一重置 rootfs：内置走 [resetBuiltinRootfs]，自定义本地删其 rootfs 目录，远程 SSH 无本地数据不操作。 */
    fun resetRootfs(profile: ContainerProfile) {
        if (profile.isBuiltin) {
            resetBuiltinRootfs()
            return
        }
        deleteCustomRootfs(profile)
    }

    init {
        CoroutineScope(Dispatchers.IO).launch {
            extractDocs(context)
            extractCredentialHelper(context)
            extractProvisionScript(context)
        }
    }

    /** 从 assets 提取文档到 ~/.aicode/docs (内置使用指导) */
    fun extractDocs() = extractDocs(context)

    /** 从 assets 提取 git credential helper 到 ~/.aicode/git-credential-aicode 并赋可执行位。 */
    fun extractCredentialHelper() = extractCredentialHelper(context)

    /** 从 assets 提取容器初始化依赖安装脚本到 ~/.aicode/provision.sh 并赋可执行位。 */
    fun extractProvisionScript() = extractProvisionScript(context)

    /** 从 assets 复制 proot 全套（二进制 + loader + 动态依赖库）到私有目录并赋权限 */
    private fun installProot() {
        // 二进制与 loader：需可执行
        copyAsset(ASSET_PROOT, prootBin, executable = true)
        copyAsset(ASSET_LOADER, prootLoader, executable = true)
        copyAsset(ASSET_LOADER32, prootLoader32, executable = true)
        // 动态依赖库：放到 lib 目录，由 LD_LIBRARY_PATH 指向；可读即可（给可执行位无害）
        copyAsset(ASSET_LIBTALLOC, File(prootLibDir, "libtalloc.so.2"), executable = true)
        copyAsset(ASSET_LIBSHMEM, File(prootLibDir, "libandroid-shmem.so"), executable = true)
    }

    /** 把单个 asset 复制到目标文件，按需赋「对所有用户」的可执行位（proot 进程以 App uid 运行）。
     *  幂等：内容与 assets 一致则跳过——proot 常被运行中的容器进程占用，直接覆写会 ETXTBSY；
     *  不一致（App 升级换了新二进制）先删后写：unlink 运行中的可执行文件是安全的，新文件写新 inode 不与旧进程冲突。 */
    private fun copyAsset(assetPath: String, dest: File, executable: Boolean) {
        dest.parentFile?.mkdirs()
        if (assetContentEquals(assetPath, dest)) return
        dest.delete()
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        if (executable && !dest.setExecutable(true, false)) {
            FileLogger.w(TAG, "setExecutable 返回 false: ${dest.absolutePath}")
        }
    }

    /** asset 与目标文件字节是否一致（读失败按不一致处理）。 */
    private fun assetContentEquals(assetPath: String, dest: File): Boolean {
        if (!dest.isFile) return false
        return try {
            context.assets.open(assetPath).use { input ->
                dest.inputStream().use { output -> input.readBytes().contentEquals(output.readBytes()) }
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 解压 alpine-minirootfs.tar.gz，正确处理目录/文件/符号链接/硬链接与权限位 */
    private fun extractRootfs(onProgress: (ContainerInitState) -> Unit) {
        context.assets.open(ASSET_ROOTFS).use { rawIn ->
            extractRootfsTo(rootfsDir, rawIn, CompressedFormat.GZIP, onProgress)
        }
    }

    /** 镜像压缩格式：内置 Alpine 用 gzip，用户导入的可能是 gzip 或 xz。 */
    enum class CompressedFormat { GZIP, XZ }

    /**
     * 把 tar.gz / tar.xz 流解压到 [destDir]，正确处理目录/文件/符号链接/硬链接与权限位。
     * 内置 Alpine（[extractRootfs] 传 assets 流）与用户自定义镜像（[installRootfsIfNeed] 传 content uri 流）共用。
     */
    fun extractRootfsTo(
        destDir: File,
        input: java.io.InputStream,
        format: CompressedFormat = CompressedFormat.GZIP,
        onProgress: (ContainerInitState) -> Unit = {}
    ) {
        var processed = 0
        val decompressed = when (format) {
            CompressedFormat.GZIP -> GZIPInputStream(input)
            CompressedFormat.XZ -> XZCompressorInputStream(input)
        }
        decompressed.use { decompIn ->
            TarArchiveInputStream(decompIn).use { tarIn ->
                var entry: TarArchiveEntry? = tarIn.nextEntry
                while (entry != null) {
                    extractEntry(destDir, tarIn, entry)
                    processed++
                    onProgress(ContainerInitState.ExtractingRootfs(processed))
                    entry = tarIn.nextEntry
                }
            }
        }
    }

    private fun extractEntry(
        destDir: File,
        tarIn: TarArchiveInputStream,
        entry: TarArchiveEntry
    ) {
        val outFile = File(destDir, entry.name)

        // 防 zip-slip：确保解压目标落在 destDir 内
        val canonicalRoot = destDir.canonicalPath
        if (!outFile.canonicalPath.startsWith(canonicalRoot + File.separator) &&
            outFile.canonicalPath != canonicalRoot
        ) {
            FileLogger.w(TAG, "跳过越界条目: ${entry.name}")
            return
        }

        when {
            entry.isDirectory -> outFile.mkdirs()

            entry.isSymbolicLink -> {
                outFile.parentFile?.mkdirs()
                // symlink 的目标可能是相对/绝对路径，原样创建（在容器内由 proot 解析）
                if (outFile.exists()) outFile.delete()
                runCatching { Os.symlink(entry.linkName, outFile.absolutePath) }
                    .onFailure { FileLogger.w(TAG, "symlink 失败 ${entry.name} -> ${entry.linkName}: ${it.message}") }
            }

            entry.isLink -> {
                // 硬链接：linkName 指向 tar 内已解压的另一文件
                outFile.parentFile?.mkdirs()
                val target = File(destDir, entry.linkName)
                if (outFile.exists()) outFile.delete()
                runCatching { Os.link(target.absolutePath, outFile.absolutePath) }
                    .onFailure {
                        // 退化为复制，保证文件存在
                        FileLogger.w(TAG, "hardlink 失败 ${entry.name} -> ${entry.linkName}，改为复制: ${it.message}")
                        runCatching { target.copyTo(outFile, overwrite = true) }
                    }
            }

            entry.isFile -> {
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { tarIn.copyTo(it) }
                applyMode(outFile, entry.mode)
            }

            else -> FileLogger.d(TAG, "忽略不支持的条目类型: ${entry.name}")
        }
    }

    /** 按 tar entry 的 mode 设置可执行位（owner 有 x 位则对所有人开放执行） */
    private fun applyMode(file: File, mode: Int) {
        val ownerExecutable = (mode and 0b001_000_000) != 0 // 0100
        if (ownerExecutable) {
            file.setExecutable(true, false)
        }
        file.setReadable(true, false)
        // owner 写位
        if ((mode and 0b010_000_000) != 0) file.setWritable(true, false)
    }

    /**
     * 写入容器内 DNS，否则 apk/npm 等联网操作会因无法解析域名而失败。
     * 用阿里云公共 DNS：国内解析更快/更稳，8.8.8.8 在部分网络环境会被拦截。
     * 已是有效配置（普通文件且含 nameserver）则不动，尊重容器内已有设置；
     * 空文件或坏链（Ubuntu/Debian base 常把 resolv.conf 链到 systemd-resolved 的 stub，PRoot 下不可用）先删再写。
     */
    private fun configureResolvConf(rootfs: File = rootfsDir) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        val conf = File(etc, "resolv.conf")
        if (conf.isFile) {
            runCatching {
                if (conf.readText().contains("nameserver")) return
            }
        }
        conf.delete()
        conf.writeText("nameserver 223.5.5.5\nnameserver 223.6.6.6\n")
    }
}
