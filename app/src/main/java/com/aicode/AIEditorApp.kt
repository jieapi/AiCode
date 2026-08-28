package com.aicode

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory
import com.aicode.core.net.AppProxy
import com.aicode.core.util.AILogger
import com.aicode.core.util.FileLogger
import net.schmizz.sshj.common.SecurityUtils
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.LanguageSettingsRepository
import com.aicode.feature.settings.data.repository.LogSettingsRepository
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.terminal.domain.KeepaliveWorker
import com.aicode.feature.terminal.domain.TerminalKeepaliveService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AIEditorApp : Application(), Configuration.Provider {

    companion object {
        const val TAG = "AIEditorApp"
        const val LANG_PREFS = "language_prefs_sync"
        const val LANG_KEY = "language_tag"
        /** 崩溃堆栈经 Intent extra 传递，超出 Binder 上限会抛异常，需截断。 */
        const val MAX_CRASH_STACK_CHARS = 50_000
        private const val CRASH_PREFS = "crash_state"
        private const val KEY_CRASH_UI_SHOWING = "crash_ui_showing"

        /** 当前导航路由（MainActivity 写入），崩溃时随报告带出，用于定位崩溃页面。 */
        @Volatile
        var currentRoute: String? = null

        /** 错误页关闭后清除落盘标志，允许下一次崩溃再次进入错误页。 */
        fun resetCrashUiFlag(context: android.content.Context) {
            context.getSharedPreferences(CRASH_PREFS, android.content.Context.MODE_PRIVATE)
                .edit().remove(KEY_CRASH_UI_SHOWING).apply()
        }
    }

    override fun attachBaseContext(base: android.content.Context) {
        // 最早入口：在任何 Hilt 注入/业务初始化之前就绪日志与崩溃落盘。
        // 启动早期（如 Hilt 注入链实例化 @Singleton 工具）的崩溃若发生在 FileLogger 初始化之前
        // 会不留任何痕迹，故把日志与全局崩溃处理器提到 attachBaseContext 最前。
        FileLogger.init(base)
        AILogger.init(base)
        installCrashHandler()
        // 全局代理入口：必须在任何 Hilt 注入 / OkHttpClient 构建之前设置，
        // 使 App 侧全部 HTTP 链路（对话、MCP、更新检查等）按需走全局/提供商代理。
        // 用 base 而非下文才定义的局部 context：attachBaseContext 阶段 base 即合法 Context。
        AppProxy.applyGlobal(base)
        val tag = base.getSharedPreferences(LANG_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(LANG_KEY, null)
        val context = if (tag.isNullOrBlank()) {
            base
        } else {
            val config = android.content.res.Configuration(base.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(tag))
            base.createConfigurationContext(config)
        }
        super.attachBaseContext(context)
    }

    /** Hilt 字段注入：在 [onCreate] 的 super 调用后即可用。 */
    @Inject
    lateinit var logSettings: LogSettingsRepository

    /** 后台保活开关持久化。 */
    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    /** WorkManager Worker 工厂：使 @HiltWorker（KeepaliveWorker）能注入依赖。 */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** MCP 生命周期总管：启动即连接已配置的远程 server。 */
    @Inject
    lateinit var mcpManager: McpManager

    /** MCP 配置仓库：启动即监听 mcp.json 外部直接编辑，改动数秒内刷新列表并触发重连。 */
    @Inject
    lateinit var mcpConfigRepository: com.aicode.feature.agent.domain.mcp.McpConfigRepository

    /** 工具授权规则仓库：启动即监听 permissions.json 外部直接编辑，改动数秒内刷新规则。 */
    @Inject
    lateinit var permissionRulesRepository: com.aicode.feature.agent.domain.permission.PermissionRulesRepository

    /** 技能配置仓库：启动即监听技能目录与 skills.json 外部变更，改动数秒内刷新技能列表。 */
    @Inject
    lateinit var skillConfigRepository: com.aicode.feature.agent.domain.skill.SkillConfigRepository

    /** 旧 Room git 凭据一次性迁移器：启动即把旧表数据写入 git-credentials 文件后删表（真源已迁到文件）。 */
    @Inject
    lateinit var legacyCredentialMigrator: com.aicode.feature.credentials.data.LegacyCredentialMigrator

    /** 三端 git 缺凭据的统一弹窗桥：监听容器内 credential helper 经文件 IPC 发来的未登录请求，
     *  暴露 StateFlow 供全局弹窗回填后回喂 git。必须在主线程启动（FileObserver 绑定主 Looper）。 */
    @Inject
    lateinit var credentialRequestBridge: com.aicode.feature.credentials.data.CredentialRequestBridge

    /** 执行模式仓库（本地 PRoot / 远程 SSH）。 */
    @Inject
    lateinit var executionModeRepository: com.aicode.feature.settings.data.repository.ExecutionModeRepository

    /** 应用语言偏好仓库：持久化用户选择的语言，供 attachBaseContext 同步读取。 */
    @Inject
    lateinit var languageSettings: LanguageSettingsRepository

    /** 执行模式同步缓存：启动时从 DataStore 读首帧注入 DI。 */
    @Inject
    lateinit var executionModeHolder: com.aicode.feature.settings.data.repository.ExecutionModeHolder

    /** 远程 SSH 连接管理器：远程模式下启动即用配置建立连接。 */
    @Inject
    lateinit var remoteSshConnection: com.aicode.feature.agent.domain.container.RemoteSshConnection

    /** 工作区仓库：SSH 重连成功后重新加载工作区。 */
    @Inject
    lateinit var workspaceRepository: com.aicode.feature.workspace.data.repository.WorkspaceRepository

    /** 连接与同步仓库：注入以在启动早期创建 @Singleton 实例，其内部跟随当前工作区自动连接/断开挂载。 */
    @Inject
    lateinit var remoteRepository: com.aicode.feature.workspace.domain.repository.RemoteRepository

    /** 模型元数据服务：启动即异步刷新 models.dev 目录（24h 缓存，失败静默，兜底内置数据）。 */
    @Inject
    lateinit var modelMetadataService: ModelMetadataService

    /** 提供商级代理注册表：启动即构建 provider→代理 索引，供 AppProxy 按 target host 分派。 */
    @Inject
    lateinit var providerProxyRegistry: com.aicode.feature.settings.data.repository.ProviderProxyRegistry

    /** 长驻作用域：持续把持久化的日志等级同步到 FileLogger。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // 把提供商级代理注册表挂到 AppProxy（applyGlobal 已在 attachBaseContext 完成），
        // 此后按目标 host 分派 provider 专属代理；无 provider 配置时回退全局代理。
        AppProxy.registerProviderProxyRegistry(providerProxyRegistry)
        registerBouncyCastle()
        createNotificationChannels()
        // 主线程启动凭据请求监听（FileObserver 必须主线程创建与 startWatching），
        // 监听容器内 credential helper 写来的 cred-req-* → 全局弹窗回填 → 回喂 git 续跑。
        credentialRequestBridge.start()
        // 启动即把最新的内置指南手册提取到私有配置目录
        appScope.launch {
            ContainerInstaller.extractDocs(this@AIEditorApp)
        }
        // 启动即把内置提示词全量释放到 ~/.aicode/prompts/（覆盖式，随 App 升级更新）；
        // 用户自定义覆盖放在 ~/.aicode/prompts.custom/，同名即覆盖，不被升级覆盖。
        appScope.launch {
            ContainerInstaller.extractPrompts(this@AIEditorApp)
        }
        // 启动即释放套餐余量示例脚本等内置脚本到 ~/.aicode/scripts/
        appScope.launch {
            ContainerInstaller.extractScripts(this@AIEditorApp)
        }
        // 启动即把旧 Room git 凭据一次性迁移到 git-credentials 文件（真源已迁到文件，删表由迁移器完成）。
        appScope.launch {
            legacyCredentialMigrator.migrateIfNeeded()
        }
        // 启动即异步刷新 models.dev 模型元数据（24h 缓存；失败静默，resolve 兜底内置 assets 数据）。
        appScope.launch {
            modelMetadataService.refreshFromNetworkIfStale()
        }
        // 启动即加载持久化等级，并随设置页改动实时生效（唯一同步点）。
        appScope.launch {
            logSettings.levelFlow.collectLatest { FileLogger.setMinLevel(it) }
        }
        // 启动即同步从 DataStore 读首帧执行模式缓存到 ExecutionModeHolder，供 DI @Provides 同步读取。
        // 异步读首帧模式写入 ExecutionModeHolder。委托层（DelegatingCommandEngine/DelegatingFileAccess）
        // 每次方法调用时才按 holder.currentMode() 转发，不依赖注入时机，故 holder 晚几毫秒写入无妨——
        // 首次命令/文件操作一定在 UI 启动之后，那时 holder 早已就绪。
        // SSH 连接放后台，失败不阻塞 UI（连接失败时首次命令会触发 ensureInstalled 重试）。
        appScope.launch {
            val mode = executionModeRepository.executionModeFlow.first()
            executionModeHolder.setMode(mode)
            if (mode == com.aicode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH) {
                executionModeRepository.remoteConnectionFlow.first()?.let { settings ->
                    runCatching {
                        remoteSshConnection.connect(
                            com.aicode.feature.agent.domain.container.RemoteConnectionConfig(
                                host = settings.host,
                                port = settings.port,
                                username = settings.username,
                                auth = com.aicode.feature.workspace.domain.remote.RemoteAuth.Password(settings.password),
                                remoteWorkspacePath = settings.remoteWorkspacePath
                            )
                        )
                        // 连接成功后同步内置文档到远程 ~/.aicode/docs/，供 AI 查阅。
                        syncDocsToRemote()
                    }.onFailure { FileLogger.e(TAG, "启动时 SSH 连接失败，将在首次命令时重试", it) }
                }
                // 启动 SSH 连接监督：定期探活、断线自动重连、重连成功后重新加载工作区与同步文档。
                remoteSshConnection.startSupervisor(appScope) {
                    runCatching { workspaceRepository.initialize() }
                        .onFailure { FileLogger.w(TAG, "SSH 重连后重新加载工作区失败", it) }
                    syncDocsToRemote()
                }
            }
        }
        // 连接与同步的「跟随当前工作区」由 RemoteRepository 内部监听工作区变化自动执行（启动注入即就绪）：
        // 工作区就绪/切换时，自动断开非当前工作区的挂载，连接当前工作区里勾了「应用启动时自动连接」的挂载。
        // 后台保活常驻通知的唯一反应器：监听开关，启停 TerminalKeepaliveService 的常驻模式。
        // 既覆盖设置页实时切换，也覆盖冷启动恢复。仅在「由开变关」时发 disable，
        // 避免为关闭而凭空拉起从未开过的 Service。
        appScope.launch {
            var last: Boolean? = null
            keepaliveSettings.enabledFlow.distinctUntilChanged().collect { enabled ->
                if (enabled) {
                    TerminalKeepaliveService.enablePersistent(this@AIEditorApp)
                    // WorkManager 兜底：周期检查服务存活，被杀后自动拉起。
                    KeepaliveWorker.schedule(this@AIEditorApp)
                } else if (last == true) {
                    TerminalKeepaliveService.disablePersistent(this@AIEditorApp)
                    KeepaliveWorker.cancel(this@AIEditorApp)
                }
                last = enabled
            }
        }
        // 连接已配置的 MCP server，把其工具注册进 ToolRegistry（内部自有 scope，失败不影响启动）。
        mcpManager.start()
        // 启动即监听 mcp.json / permissions.json 的外部直接编辑：改动数秒内刷新设置页列表并重连。
        appScope.launch { mcpConfigRepository.startWatching() }
        appScope.launch { permissionRulesRepository.startWatching() }
        appScope.launch { skillConfigRepository.startWatching() }
        // 语言切换由 MainActivity 的 attachBaseContext + recreate() 统一管理。
        // MainActivity 继承 ComponentActivity（非 AppCompatActivity），
        // AppCompatDelegate.setApplicationLocales 的自动 recreate 不生效，
        // 且两者同时设置 locale 会竞争导致偶发语言错乱。
    }

    /** HiltWorkerFactory：@HiltWorker 的 Worker 经此工厂创建，才能注入 Repository。 */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * 读取 assets/docs 下所有内置文档，通过 SSH exec 同步到远程 ~/.aicode/docs/。
     * 远程模式下 AI 查阅 ~/.aicode/docs/ 的设置说明文档时，需要这些文件存在于远程服务器。
     * 连接成功与重连成功后调用，保证远程文档随 App 升级更新。失败仅记日志，不阻断流程。
     */
    private suspend fun syncDocsToRemote() {
        runCatching {
            val names = assets.list("docs") ?: return@runCatching
            val docs = linkedMapOf<String, String>()
            for (name in names) {
                val content = assets.open("docs/$name").bufferedReader().use { it.readText() }
                docs[name] = content
            }
            remoteSshConnection.uploadDocs(docs)
        }.onFailure { FileLogger.w(TAG, "同步内置文档到远程失败", it) }
    }

    /** 注册完整版 BouncyCastle 取代 Android 自带的裁剪版。
     *  sshj 0.38.0 用 X25519 做密钥交换，Android 自带的 BC provider 不含 X25519 算法，
     *  需先移除裁剪版再注册 bcprov-jdk18on（sshj 传递依赖）的完整版，并告诉 sshj 使用它。
     *  必须在任何 sshj 调用之前完成。 */
    private fun registerBouncyCastle() {
        // 先移除 Android 自带的裁剪版 BC，再注册完整版 bcprov-jdk18on。
        // 用 addProvider 而非 insertProviderAt(…, 1)：BC 只需存在于 Provider 列表中供 sshj
        // 通过 SecurityUtils.setSecurityProvider("BC") 按名查到即可，无需排到最高优先级。
        // 若抬到第 1 位，会抢占 OkHttp/Conscrypt 初始化默认 SSLContext 时的 KeyStore 查找，
        // BC 注册了 BKS 类型却没有配套默认 truststore，导致抛 KeyStoreException: BKS not found
        // （表现为检测更新等 HTTPS 请求崩溃）。放末尾让系统自带 provider 继续负责 TLS。
        java.security.Security.removeProvider("BC")
        java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        SecurityUtils.setSecurityProvider("BC")
    }

    /**
     * 捕获未处理异常：落盘后拉起全屏错误页（展示崩溃详情、支持复制），
     * 随即杀掉进程让系统重启并恢复错误页。
     *
     * 必须杀进程：主线程崩溃后其 Looper 已死，不杀进程则错误页窗口虽出现
     * 但 onCreate 永不执行（白屏），最终被系统当 ANR 杀掉。杀掉后 AMS 检测到
     * 进程死在 Activity 创建途中，会自动重启进程并恢复 CrashActivity，
     * 新进程主线程健康，错误页才能正常渲染。
     *
     * 防递归：错误页自身崩溃时落盘标志仍在，直接交回系统默认处理器，避免无限重启。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            FileLogger.e("CRASH", "线程 ${thread.name} 未捕获异常", throwable)
            // 崩溃前同步 flush 缓冲日志，避免最后一段（含本行错误）留在内存中丢失。
            FileLogger.flushSync()
            val prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
            if (prefs.getBoolean(KEY_CRASH_UI_SHOWING, false)) {
                // 错误页自身崩溃：交回系统默认处理器，避免无限重启循环
                previous?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            prefs.edit().putBoolean(KEY_CRASH_UI_SHOWING, true).apply()
            try {
                startActivity(
                    Intent(this, CrashActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(CrashActivity.EXTRA_THREAD_NAME, thread.name)
                        putExtra(CrashActivity.EXTRA_STACK, stackTraceOf(throwable))
                        putExtra(CrashActivity.EXTRA_SCREEN, currentRoute)
                    }
                )
            } catch (t: Throwable) {
                // 错误页启动失败（如系统限制）：交回默认处理器，不让崩溃被吞掉
                FileLogger.e(TAG, "启动崩溃错误页失败", t)
                prefs.edit().remove(KEY_CRASH_UI_SHOWING).apply()
                previous?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            Process.killProcess(Process.myPid())
        }
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        val text = sw.toString()
        return if (text.length > MAX_CRASH_STACK_CHARS) {
            text.take(MAX_CRASH_STACK_CHARS) + "\n...[truncated]"
        } else {
            text
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                "terminal_service",
                getString(R.string.notification_channel_terminal_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_terminal_service_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)

            // Agent 完成通知：需要弹窗+声音，区别于 terminal_service 的静默常驻通知。
            val agentChannel = NotificationChannel(
                "agent_complete",
                getString(R.string.notification_channel_agent_complete),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_agent_complete_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(agentChannel)
        }
    }
}
