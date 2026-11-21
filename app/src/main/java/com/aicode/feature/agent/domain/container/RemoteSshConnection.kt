package com.aicode.feature.agent.domain.container

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.explorer.shellQuote
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import com.aicode.feature.credentials.domain.model.GitCredential
import com.aicode.feature.settings.data.repository.DEFAULT_SFTP_CHANNEL_LIMIT
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.RemoteFile
import java.security.MessageDigest
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.userauth.UserAuthException
import com.hierynomus.sshj.common.KeyDecryptionFailedException
import net.schmizz.sshj.userauth.password.PasswordUtils
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RemoteSshConnection"

/** 空闲 SFTP 通道的回收阈值。channel 重建只要一个往返，宁短勿长。 */
private const val SFTP_IDLE_TIMEOUT_MS = 30_000L

/** 空闲通道巡检间隔。 */
private const val SFTP_SWEEP_INTERVAL_MS = 5_000L

/**
 * 共享的 SSH 连接管理器：exec 用一条独立 sshj [SSHClient]（供 [RemoteSshEngine] 执行命令），
 * SFTP 用另一条独立 transport，在其上按需开多条 SFTP channel（见 [acquireSftp]）。两者隔离，
 * SFTP 的 Buffer 溢出不会拖垮命令通道。
 *
 * 连接配置（host/port/username/auth）由调用方在 [connect] 时传入。连接断开后下次 [connect]
 * 重新建立；SFTP 通道按需惰性建立。exec 侧操作串行化（[mutex]），SFTP 通道由租约独占
 * （sshj 的 SFTPClient 非线程安全）。
 */
@Singleton
class RemoteSshConnection @Inject constructor(
    private val hostKeyStore: SshHostKeyStore,
    private val hostKeyVerifier: SshHostKeyVerifier,
    private val privateKeyStore: SshPrivateKeyStore,
    private val containerInstaller: ContainerInstaller,
    private val generalSettingsRepository: GeneralSettingsRepository
) {

    @Volatile
    private var sshClient: SSHClient? = null

    /**
     * SFTP 专用 transport：与 exec 各用一条 SSH 连接。sshj 的 SFTP 有间歇性 Buffer 溢出
     * （hierynomus/sshj#461），共用 transport 时崩溃会一并拖垮 Bash/终端；分开后只影响文件通道。
     *
     * 这条 transport 上按需开多条 SFTP channel（每个 [SFTPClient] 持有独立的 SFTPEngine），
     * 由 [acquireSftp] 以租约方式分配，达到偏好设置里的上限后排队。
     */
    @Volatile
    private var sftpSshClient: SSHClient? = null

    /** 一条可复用的 SFTP channel 及其最近使用时间（毫秒，单调时钟）。 */
    private class SftpChannel(val client: SFTPClient) {
        var lastUsedAt: Long = 0L
    }

    private val mutex = Mutex()
    private val sftpLock = Mutex()
    private val credentialSyncMutex = Mutex()
    private val docsSyncMutex = Mutex()

    // ── SFTP 通道池：状态由 sftpPoolLock 保护，锁内不做 I/O ──
    private val sftpPoolLock = Mutex()
    private val sftpIdle = ArrayDeque<SftpChannel>()
    private val sftpAll = mutableSetOf<SftpChannel>()
    private val sftpWaiters = ArrayDeque<CompletableDeferred<SftpChannel?>>()
    private var sftpCreating = 0
    private val sftpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sftpSweeper: Job? = null

    /** 通道上限，来自「偏好设置」；启动时订阅一次。 */
    @Volatile
    private var sftpChannelLimit: Int = DEFAULT_SFTP_CHANNEL_LIMIT

    init {
        sftpScope.launch {
            generalSettingsRepository.sftpChannelLimitFlow.collectLatest { sftpChannelLimit = it }
        }
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    /** 连接状态流，供 UI 显示指示器、工作区初始化等待连接就绪。 */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** 重连成功后回调，供工作区重新加载。由 [startSupervisor] 注册。 */
    private var onReconnected: (suspend () -> Unit)? = null
    private var supervisorJob: Job? = null

    /** 远程服务器上的真实 home 路径（连接成功后查 $HOME 缓存），供文件工具展开 ~。 */
    @Volatile
    var remoteHome: String? = null
        private set

    /** 当前连接配置快照，供重连与路径映射使用。 */
    @Volatile
    var config: RemoteConnectionConfig? = null
        private set

    @Volatile
    var connectionGeneration: Long = 0L
        private set

    /** 已注入凭证的工作区根（真实路径）；null 表示未注入，远程命令不附加凭证配置。 */
    @Volatile
    var gitCredentialWorkspaceRoot: String? = null
        private set

    suspend fun connect(config: RemoteConnectionConfig) = mutex.withLock {
        if (isConnected() && this.config == config) return@withLock
        disconnectInternal()
        this.config = config
        _connectionState.value = ConnectionState.CONNECTING
        try {
            withContext(Dispatchers.IO) {
                val client = newSshClient(config)
                sshClient = client
                // 查远程真实 home（root 是 /root，普通用户是 /home/xxx），供文件工具展开 ~
                runCatching {
                    val s = client.startSession()
                    val cmd = s.exec("echo \$HOME")
                    remoteHome = java.io.BufferedReader(java.io.InputStreamReader(cmd.inputStream))
                        .readText().trim().ifEmpty { null }
                    s.close()
                }
                // 注册 transport 断开监听：连接断开时即时推状态，不等 supervisor 轮询
                client.transport.setDisconnectListener { reason, info ->
                    FileLogger.w(TAG, "SSH 连接断开: $reason, $info")
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                FileLogger.i(TAG, "SSH 已连接 ${config.host}:${config.port} as ${config.username}")
            }
            _connectionState.value = ConnectionState.CONNECTED
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.FAILED
            throw e
        }
    }

    fun removeHostKey(host: String, port: Int) {
        hostKeyStore.remove(host, port)
    }

    fun savedHostKeys(): Map<String, String> = hostKeyStore.entries()
    /** 无参 connect：用上次保存的 config 重连。 */
    suspend fun connect() {
        val cfg = config ?: throw IllegalStateException("未配置 SSH 连接")
        connect(cfg)
    }

    suspend fun disconnect() = mutex.withLock {
        disconnectInternal()
    }

    private suspend fun disconnectInternal() {
        connectionGeneration++
        // 与 ensureSftpTransport 共用 sftpLock，避免建连/重建与断开交错
        sftpLock.withLock { closeSftpTransport(failWaiters = true) }
        runCatching { sshClient?.disconnect() }
        sshClient = null
        remoteHome = null
        gitCredentialWorkspaceRoot = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** 建立一条 SSH transport 并完成认证与保活（exec、SFTP、端口转发各建一条）。 */
    internal fun newSshClient(
        config: RemoteConnectionConfig,
        onCreated: (SSHClient) -> Unit = {}
    ): SSHClient = SSHClient().apply {
        try {
            onCreated(this)
            addHostKeyVerifier(hostKeyVerifier)
            connect(config.host, config.port)
            when (val auth = config.auth) {
                is RemoteAuth.Password -> authPassword(config.username, auth.password)
                is RemoteAuth.PrivateKey -> {
                    val pem = privateKeyStore.readPem(auth.privateKeyPath)
                    val passwordFinder = auth.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                    authPublickey(config.username, loadKeys(pem, null, passwordFinder))
                }
            }
            // 启用 SSH 心跳保活，防止空闲超时断连
            runCatching {
                connection.keepAlive?.let {
                    it.setKeepAliveInterval(30)
                    it.start()
                }
            }
        } catch (error: Throwable) {
            runCatching { close() }
            throw error
        }
    }

    fun isConnected(): Boolean =
        sshClient?.isConnected == true && sshClient?.isAuthenticated == true

    /** 开一个 exec session 执行命令。调用方负责关闭返回的 Command。 */
    fun startExecSession(command: String): Session.Command {
        val client = sshClient ?: throw IllegalStateException("SSH 未连接")
        val session = client.startSession()
        val cmd = session.exec(command)
        // 立即给远端进程的 stdin 送 EOF：所有调用方只读输出。不关的话，远端按「stdin 是否可读」
        // 决定行为的程序（无路径参数的 rg 等）会把 channel 当待读输入，一直等到命令超时。
        runCatching { cmd.outputStream.close() }
        return cmd
    }

    /**
     * 开 exec 会话但**保留 stdin 可写**，供大内容经 stdin 传输（避免命令行参数超过远端 ARG_MAX）。
     * 调用方负责写入并关闭 [Session.Command.outputStream]、读取输出、关闭 session。
     */
    fun startExecSessionWithStdin(command: String): Session.Command {
        val client = sshClient ?: throw IllegalStateException("SSH 未连接")
        return client.startSession().exec(command)
    }

    /** 开一个新的 Session，供调用方分配 PTY 并启动 shell。调用方负责关闭 Session。 */
    fun startShellSession(): Session {
        val client = sshClient ?: throw IllegalStateException("SSH 未连接")
        return client.startSession()
    }

    /** SFTP 通道租约：持有期间该通道归调用方独占，用完必须 [release]。 */
    internal class SftpLease internal constructor(
        internal val client: SFTPClient,
        private val releaseBlock: suspend (SFTPClient) -> Unit
    ) {
        internal suspend fun release() = releaseBlock(client)
    }

    /** 租用一条 SFTP 通道；无空闲通道且已达上限时挂起等待。 */
    internal suspend fun acquireSftp(): SftpLease =
        SftpLease(obtainSftpChannel().client) { releaseSftpChannel(it) }

    /** 租用通道执行 [block]，无论成败都交还。 */
    internal suspend fun <T> withSftpChannel(block: suspend (SFTPClient) -> T): T {
        val lease = acquireSftp()
        try {
            return block(lease.client)
        } finally {
            // 取消时也要把通道还回池，否则会永久占用一个额度
            withContext(NonCancellable) { lease.release() }
        }
    }

    /** 丢弃一条出问题的 SFTP 通道，其余通道与 transport 保持不变。 */
    internal suspend fun invalidateSftpChannel(client: SFTPClient) {
        val removed = sftpPoolLock.withLock {
            val channel = sftpAll.firstOrNull { it.client === client } ?: return@withLock false
            sftpAll.remove(channel)
            sftpIdle.remove(channel)
            sftpWaiters.removeFirstOrNull()?.complete(null)
            true
        }
        if (removed) runCatching { client.close() }
    }

    private sealed interface AcquireDecision {
        class Reuse(val channel: SftpChannel) : AcquireDecision
        object Create : AcquireDecision
        class Wait(val deferred: CompletableDeferred<SftpChannel?>) : AcquireDecision
    }

    private suspend fun obtainSftpChannel(): SftpChannel {
        while (true) {
            val dead = mutableListOf<SftpChannel>()
            val decision = sftpPoolLock.withLock { decideAcquire(dead) }
            dead.forEach { channel -> runCatching { channel.client.close() } }
            when (decision) {
                is AcquireDecision.Reuse -> return decision.channel
                AcquireDecision.Create -> tryCreateSftpChannel()?.let { return it }
                is AcquireDecision.Wait -> {
                    try {
                        decision.deferred.await()?.let { return it }
                    } catch (e: CancellationException) {
                        // 取消时等待者若已被移交通道，必须把它还回池；否则通道会永久占着额度
                        val handed = if (decision.deferred.isCompleted) {
                            withContext(NonCancellable) {
                                runCatching { decision.deferred.await() }.getOrNull()
                            }
                        } else {
                            null
                        }
                        sftpPoolLock.withLock { sftpWaiters.remove(decision.deferred) }
                        if (handed != null) releaseSftpChannel(handed.client)
                        throw e
                    }
                }
            }
        }
    }

    /** 在 sftpPoolLock 内判定本次是复用、新建还是排队；已关闭的空闲通道丢入 [dead] 待锁外关闭。 */
    private fun decideAcquire(dead: MutableList<SftpChannel>): AcquireDecision {
        while (sftpIdle.isNotEmpty()) {
            val channel = sftpIdle.removeLast()
            if (isSftpChannelOpen(channel.client)) {
                channel.lastUsedAt = System.nanoTime() / 1_000_000
                return AcquireDecision.Reuse(channel)
            }
            sftpAll.remove(channel)
            dead.add(channel)
        }
        if (sftpAll.size + sftpCreating < sftpChannelLimit) {
            sftpCreating++
            return AcquireDecision.Create
        }
        return AcquireDecision.Wait(
            CompletableDeferred<SftpChannel?>().also { sftpWaiters.addLast(it) }
        )
    }

    /** 新建一条 channel 并纳入池；创建期间池被清空或上限被调小时返回 null，由调用方重试。 */
    private suspend fun tryCreateSftpChannel(): SftpChannel? {
        val channel = try {
            val transport = ensureSftpTransport()
            SftpChannel(withContext(Dispatchers.IO) { transport.newSFTPClient() })
        } catch (e: Throwable) {
            sftpPoolLock.withLock {
                sftpCreating--
                sftpWaiters.removeFirstOrNull()?.complete(null)
            }
            throw e
        }
        val accepted = sftpPoolLock.withLock {
            sftpCreating--
            if (sftpAll.size >= sftpChannelLimit) {
                false
            } else {
                channel.lastUsedAt = System.nanoTime() / 1_000_000
                sftpAll.add(channel)
                true
            }
        }
        if (!accepted) {
            runCatching { channel.client.close() }
            return null
        }
        startSftpSweeper()
        return channel
    }

    /** 交还通道：有等待者时直接移交，否则回到空闲队列；超出上限的通道直接关闭。 */
    private suspend fun releaseSftpChannel(client: SFTPClient) {
        var closeAfter = false
        sftpPoolLock.withLock {
            val channel = sftpAll.firstOrNull { it.client === client } ?: return@withLock
            if (!isSftpChannelOpen(client) || sftpAll.size > sftpChannelLimit) {
                sftpAll.remove(channel)
                sftpWaiters.removeFirstOrNull()?.complete(null)
                closeAfter = true
                return@withLock
            }
            channel.lastUsedAt = System.nanoTime() / 1_000_000
            val waiter = sftpWaiters.removeFirstOrNull()
            if (waiter != null) waiter.complete(channel) else sftpIdle.addLast(channel)
        }
        if (closeAfter) runCatching { client.close() }
    }

    private fun isSftpChannelOpen(client: SFTPClient): Boolean =
        runCatching { client.getSFTPEngine().getSubsystem().isOpen }.getOrDefault(false)

    /** 取得可用的 SFTP transport，失效时重建；重建会一并清空其上的通道。 */
    private suspend fun ensureSftpTransport(): SSHClient = sftpLock.withLock {
        val cfg = config ?: throw IllegalStateException("SSH 未配置")
        sftpSshClient
            ?.takeIf { it.isConnected && it.isAuthenticated }
            ?.let { return@withLock it }
        closeSftpTransport(failWaiters = false)
        val client = withContext(Dispatchers.IO) { newSshClient(cfg) }
        sftpSshClient = client
        startSftpSweeper()
        client
    }

    /** 关闭 SFTP transport 及其上所有通道。[failWaiters] 为真时唤醒等待者并报错，否则让其重试。 */
    private suspend fun closeSftpTransport(failWaiters: Boolean) {
        sftpSweeper?.cancel()
        sftpSweeper = null
        val channels = sftpPoolLock.withLock {
            val snapshot = sftpAll.toList()
            sftpAll.clear()
            sftpIdle.clear()
            while (true) {
                val waiter = sftpWaiters.removeFirstOrNull() ?: break
                if (failWaiters) {
                    waiter.completeExceptionally(java.io.IOException("SSH 连接已断开"))
                } else {
                    waiter.complete(null)
                }
            }
            snapshot
        }
        channels.forEach { channel -> runCatching { channel.client.close() } }
        runCatching { sftpSshClient?.disconnect() }
        sftpSshClient = null
    }

    private fun startSftpSweeper() {
        if (sftpSweeper?.isActive == true) return
        sftpSweeper = sftpScope.launch {
            while (isActive) {
                delay(SFTP_SWEEP_INTERVAL_MS)
                sweepIdleSftpChannel()
            }
        }
    }

    /** 每轮最多回收一条通道：超出上限时先关最旧的空闲通道，否则回收空闲超时的，且至少保留一条。 */
    private suspend fun sweepIdleSftpChannel() {
        val now = System.nanoTime() / 1_000_000
        val victim = sftpPoolLock.withLock {
            if (sftpIdle.isEmpty()) return@withLock null
            val overLimit = sftpAll.size > sftpChannelLimit
            if (!overLimit && sftpAll.size <= 1) return@withLock null
            val channel = if (overLimit) {
                sftpIdle.first()
            } else {
                sftpIdle.firstOrNull { now - it.lastUsedAt >= SFTP_IDLE_TIMEOUT_MS } ?: return@withLock null
            }
            sftpIdle.remove(channel)
            sftpAll.remove(channel)
            channel
        } ?: return
        runCatching { victim.client.close() }
    }

    /** 若已配置但未连接，立即尝试重连一次。返回是否最终连通。供 App 回到前台时主动触发。 */
    suspend fun tryReconnectIfDisconnected(): Boolean {
        val cfg = config ?: return false
        if (isConnected()) return true
        _connectionState.value = ConnectionState.CONNECTING
        return runCatching { connect(cfg) }
            .onSuccess {
                FileLogger.i(TAG, "SSH 重连成功（前台触发）")
                runCatching { onReconnected?.invoke() }
            }
            .onFailure {
                FileLogger.w(TAG, "SSH 重连失败（前台触发）", it)
                _connectionState.value = ConnectionState.FAILED
            }
            .isSuccess
    }

    /**
     * 启动连接监督协程：远程模式下定期探活，连接断开则自动重连（指数退避），
     * 重连成功后触发 [onReconnected] 回调（供工作区重新加载）并维护 [connectionState]。
     * 幂等：重复调用不会起多个 supervisor。仅在远程模式下生效。 */
    fun startSupervisor(scope: CoroutineScope, onReconnected: suspend () -> Unit) {
        this.onReconnected = onReconnected
        if (supervisorJob?.isActive == true) return
        supervisorJob = scope.launch {
            var backoffMs = 5000L
            val maxBackoffMs = 30000L
            while (isActive) {
                delay(backoffMs)
                val cfg = config ?: continue
                if (isConnected()) {
                    backoffMs = 15000 // 连接正常，拉长探活间隔（仅兜底，断开有 DisconnectListener 即时通知）
                    continue
                }
                // 连接已断，尝试重连
                tryReconnectIfDisconnected()
                // 重连失败后指数退避
                if (!isConnected()) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
                } else {
                    backoffMs = 15000
                }
            }
        }
    }

    suspend fun stopSupervisor() {
        supervisorJob?.cancelAndJoin()
        supervisorJob = null
        onReconnected = null
    }

    /**
     * 更新 ~/workspace 符号链接指向当前选中工作区的远程路径，让 AI 用 ~/workspace/... 路径时
     * Bash 命令（pwd 等）能直接访问到正确的工作区目录。应在工作区选中/初始化后调用。
     *
     * `~/workspace` 已是真实目录时跳过（工作区根被配成了 `~/workspace` 的情况）：此时 `ln` 会把
     * 链接建到该目录**内部**，形成自引用死循环，破坏整个工作区；跳过时命令链路会回退到真实路径。
     */
    suspend fun updateWorkspaceSymlink(workspacePath: String) {
        val client = sshClient ?: return
        val ws = workspacePath.trimEnd('/')
        if (ws.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val session = client.startSession()
                val cmd = session.exec(
                    "if [ -d ~/workspace ] && [ ! -L ~/workspace ]; then echo skip; " +
                        "else ln -sfn ${shellQuote(ws)} ~/workspace 2>/dev/null; echo done; fi"
                )
                val out = java.io.BufferedReader(java.io.InputStreamReader(cmd.inputStream)).readText().trim()
                session.close()
                if (out == "skip") {
                    FileLogger.w(TAG, "~/workspace 已是真实目录，跳过符号链接（工作区根可能配成了 ~/workspace）")
                }
            }.onFailure { FileLogger.w(TAG, "更新 workspace 符号链接失败: $ws", it) }
        }
    }

    internal class DocsSyncTarget internal constructor(
        internal val config: RemoteConnectionConfig,
        internal val execClient: SSHClient,
        internal val home: String,
        internal val generation: Long
    ) {
        fun sameConnection(other: DocsSyncTarget): Boolean =
            generation == other.generation && execClient === other.execClient && config == other.config && home == other.home
    }

    internal fun captureDocsSyncTarget(): DocsSyncTarget? {
        val generation = connectionGeneration
        val client = sshClient ?: return null
        val cfg = config ?: return null
        val home = remoteHome ?: return null
        val target = DocsSyncTarget(cfg, client, home, generation)
        return target.takeIf { isCurrentDocsTarget(it) }
    }

    private fun isCurrentDocsTarget(target: DocsSyncTarget): Boolean =
        connectionGeneration == target.generation && sshClient === target.execClient &&
            config == target.config && remoteHome == target.home &&
            _connectionState.value == ConnectionState.CONNECTED && isConnected()

    internal suspend fun uploadDocs(docs: BuiltinDocsSync.Snapshot, target: DocsSyncTarget) =
        withContext(Dispatchers.IO) {
            docsSyncMutex.withLock {
                coroutineScope {
                    val context = currentCoroutineContext()
                    val checkActive = {
                        context.ensureActive()
                        if (!isCurrentDocsTarget(target)) {
                            throw CancellationException("SSH docs sync target changed")
                        }
                    }
                    checkActive()
                    val client = AtomicReference<SSHClient?>()
                    var sftp: SFTPClient? = null
                    // sshj 的阻塞读不响应协程取消；取消时关闭任务专属 transport 以唤醒它。
                    val cancellationCloser = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            runCatching { client.get()?.close() }
                        }
                    }
                    try {
                        val connection = newSshClient(target.config) {
                            client.set(it)
                            it.connectTimeout = 15_000
                            it.timeout = 15_000
                            checkActive()
                        }
                        checkActive()
                        val channel = connection.newSFTPClient()
                        sftp = channel
                        checkActive()
                        BuiltinDocsSync(DocsSftpBackend(channel, target.home, checkActive), checkActive)
                            .sync(docs).also {
                                if (it.changed) {
                                    FileLogger.i(TAG, "内置文档增量同步完成：上传 ${it.uploaded}，删除 ${it.deleted}")
                                }
                            }
                    } finally {
                        withContext(NonCancellable) {
                            runCatching { sftp?.close() }
                            cancellationCloser.cancelAndJoin()
                            runCatching { client.get()?.close() }
                        }
                    }
                }
            }
        }

    private class DocsSftpBackend(
        private val client: SFTPClient,
        home: String,
        private val checkActive: () -> Unit
    ) : BuiltinDocsSync.Backend {
        private val root = home.trimEnd('/') + "/.aicode/docs"

        init {
            require(root.startsWith('/') && root.drop(1).split('/').all {
                it.isNotEmpty() && it != "." && it != ".." && it.none { char -> char <= '\u001f' }
            }) { "Invalid SSH home path" }
        }

        override fun type(path: String): BuiltinDocsSync.EntryType? =
            checkedPath(path)?.let { lstat(it) }?.let {
                when (it.type) {
                    FileMode.Type.REGULAR -> BuiltinDocsSync.EntryType.FILE
                    FileMode.Type.DIRECTORY -> BuiltinDocsSync.EntryType.DIRECTORY
                    FileMode.Type.SYMLINK -> BuiltinDocsSync.EntryType.SYMLINK
                    else -> BuiltinDocsSync.EntryType.OTHER
                }
            }

        override fun readFile(path: String): ByteArray {
            val remote = checkNotNull(checkedPath(path))
            val attrs = checkNotNull(lstat(remote))
            check(attrs.type == FileMode.Type.REGULAR && attrs.size in 0..BuiltinDocsSync.MAX_STATE_BYTES.toLong()) {
                "Unsafe built-in docs state file"
            }
            checkActive()
            return client.open(remote, EnumSet.of(OpenMode.READ)).use { file ->
                val bytes = ByteArray(attrs.size.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    checkActive()
                    val count = file.read(offset.toLong(), bytes, offset, minOf(32 * 1024, bytes.size - offset))
                    check(count > 0) { "Incomplete built-in docs state" }
                    offset += count
                }
                checkActive()
                check(file.read(offset.toLong(), ByteArray(1), 0, 1) == -1) { "Built-in docs state changed" }
                bytes
            }
        }

        override fun fileHash(path: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            readChunks(path) { bytes, count -> digest.update(bytes, 0, count) }
            return BuiltinDocsSync.hexDigest(digest.digest())
        }

        private fun readChunks(path: String, consume: (ByteArray, Int) -> Unit) {
            val remote = checkNotNull(checkedPath(path))
            check(lstat(remote)?.type == FileMode.Type.REGULAR) { "Unsafe built-in docs source" }
            checkActive()
            client.open(remote, EnumSet.of(OpenMode.READ)).use { file ->
                val bytes = ByteArray(32 * 1024)
                var offset = 0L
                while (true) {
                    checkActive()
                    val count = file.read(offset, bytes, 0, bytes.size)
                    if (count == -1) break
                    check(count > 0) { "Incomplete built-in docs source" }
                    consume(bytes, count)
                    offset += count
                }
            }
        }

        override fun createDirectory(path: String) {
            val remote = checkNotNull(checkedPath(path, createParents = true))
            checkActive()
            client.mkdir(remote)
            if (path == BuiltinDocsSync.BACKUP_DIR || path.startsWith("${BuiltinDocsSync.BACKUP_DIR}/")) {
                checkActive()
                client.chmod(remote, 448)
            }
        }

        override fun replaceAtomically(path: String, bytes: ByteArray) = replace(path, path) { file ->
            var offset = 0
            while (offset < bytes.size) {
                checkActive()
                val count = minOf(32 * 1024, bytes.size - offset)
                file.write(offset.toLong(), bytes, offset, count)
                offset += count
            }
        }

        override fun backupFile(path: String, backupPath: String, expectedHash: String) = replace(backupPath, path) { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            readChunks(path) { bytes, count ->
                digest.update(bytes, 0, count)
                checkActive()
                file.write(offset, bytes, 0, count)
                offset += count
            }
            check(BuiltinDocsSync.hexDigest(digest.digest()) == expectedHash) { "Built-in docs source changed during backup" }
        }

        override fun cleanupTemporary(path: String) {
            val temporary = checkedPath(BuiltinDocsSync.temporaryPath(path)) ?: return
            val attrs = lstat(temporary) ?: return
            check(attrs.type == FileMode.Type.REGULAR) { "Unsafe built-in docs staging file" }
            checkActive()
            client.rm(temporary)
        }

        private fun replace(path: String, temporaryOwner: String, write: (RemoteFile) -> Unit) {
            val remote = checkNotNull(checkedPath(path))
            check(lstat(remote).let { it == null || it.type == FileMode.Type.REGULAR }) {
                "Unsafe built-in docs destination"
            }
            cleanupTemporary(temporaryOwner)
            val staging = checkNotNull(checkedPath(BuiltinDocsSync.STAGING_DIR))
            if (lstat(staging) == null) {
                checkActive()
                client.mkdir(staging)
            }
            check(lstat(staging)?.type == FileMode.Type.DIRECTORY) { "Unsafe built-in docs staging directory" }
            checkActive()
            client.chmod(staging, 448)
            val temporary = checkNotNull(checkedPath(BuiltinDocsSync.temporaryPath(temporaryOwner)))
            try {
                checkActive()
                client.open(temporary, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).use { file ->
                    checkActive()
                    client.chmod(temporary, 384)
                    write(file)
                }
                checkNotNull(checkedPath(path))
                check(lstat(remote).let { it == null || it.type == FileMode.Type.REGULAR }) {
                    "Unsafe built-in docs destination"
                }
                checkActive()
                client.rename(temporary, remote, EnumSet.of(RenameFlags.ATOMIC, RenameFlags.OVERWRITE))
            } finally {
                runCatching { cleanupTemporary(temporaryOwner) }
            }
        }

        override fun deleteFile(path: String) {
            val remote = checkedPath(path) ?: return
            val attrs = lstat(remote) ?: return
            check(attrs.type == FileMode.Type.REGULAR) { "Unsafe built-in docs removal path" }
            checkActive()
            client.rm(remote)
        }

        private fun checkedPath(path: String, createParents: Boolean = false): String? {
            require(path.isEmpty() || path.split('/').all {
                it.isNotEmpty() && it != "." && it != ".." && it.none { char -> char <= '\u001f' || char == '\\' }
            }) { "Invalid built-in docs path" }
            val remote = if (path.isEmpty()) root else "$root/$path"
            var parent = ""
            for (part in remote.drop(1).split('/').dropLast(1)) {
                parent += "/$part"
                val attrs = lstat(parent)
                if (attrs == null) {
                    if (!createParents) return null
                    checkActive()
                    client.mkdir(parent)
                    check(lstat(parent)?.type == FileMode.Type.DIRECTORY) { "Unsafe built-in docs parent" }
                } else {
                    check(attrs.type == FileMode.Type.DIRECTORY) { "Unsafe built-in docs parent" }
                }
            }
            return remote
        }

        private fun lstat(path: String): FileAttributes? {
            checkActive()
            return try {
                client.lstat(path)
            } catch (error: SFTPException) {
                if (error.statusCode != StatusCode.NO_SUCH_FILE && error.statusCode != StatusCode.NO_SUCH_PATH) throw error
                null
            }
        }
    }

    /**
     * 释放 App 内置资源（子代理定义、面板示例脚本等）到远程 `~/.aicode/<subdir>/`。
     * 仅补齐服务器上缺失的文件、**不覆盖已存在**（保留用户修改），脚本赋可执行位。
     * 与 [uploadDocs] 一样在连接/重连后调用——远程模式下全局 agents/scripts 以远端为准，
     * 内置默认需在远端存在才能「开箱即用」。二进制安全（base64 传输）。
     */
    suspend fun uploadBuiltinFiles(
        destSubdir: String,
        files: Map<String, ByteArray>,
        executable: Boolean = false
    ) {
        if (files.isEmpty()) return
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val destDir = home.trimEnd('/') + "/.aicode/" + destSubdir.trim('/')
        withContext(Dispatchers.IO) {
            runCatching {
                withSftpChannel { sftp ->
                    sftp.mkdirs(destDir)
                    for ((name, bytes) in files) {
                        val dest = "$destDir/$name"
                        if (sftp.statExistence(dest) == null) {
                            writeRemoteFile(sftp, dest, bytes, executable, overwrite = false)
                        }
                    }
                }
                FileLogger.i(TAG, "已释放 ${files.size} 个内置文件到远程 $destDir")
            }.onFailure { FileLogger.w(TAG, "释放内置文件到远程失败: $destSubdir", it) }
        }
    }

    /**
     * 把 App 的 git 凭据注入远程服务器（仅当用户开启「自动注入」时调用）：
     * 写 `~/.aicode/git-credentials`（store 格式）+ `~/.aicode/gitconfig.credential`。
     *
     * 生效机制：[RemoteSshEngine] 执行命令时用 `GIT_CONFIG_*` 环境变量指向 credential 文件，
     * 并经 `includeIf "gitdir:<工作区根>/"` 限定只有工作区根目录下的仓库才加载 store helper。
     * 不接管 `GIT_CONFIG_GLOBAL`——服务器用户自己的全局配置（署名、代理等）照常生效，
     * App 里的 `git config --global` 也仍写用户自己的配置，不会被后续注入覆盖。
     *
     * @param credentials 要注入的凭据列表（每 host 一条）。
     * @param workspaceRoot 远程工作区根路径（AI 的 ~/workspace 映射目录），限定注入范围。
     */
    suspend fun uploadGitCredentialConfig(credentials: List<GitCredential>, workspaceRoot: String) = credentialSyncMutex.withLock {
        val client = sshClient ?: return@withLock
        val home = remoteHome ?: return@withLock
        val aicodeDir = home.trimEnd('/') + "/.aicode"
        val wsRoot = workspaceRoot.trimEnd('/').ifEmpty { return@withLock }
        withContext(Dispatchers.IO) {
            runCatching {
                val creds = credentials.joinToString("\n") { c ->
                    "https://${enc(c.username)}:${enc(c.token)}@${c.host}"
                }.let { if (it.isNotEmpty()) "$it\n" else "" }


                // 上传 credential helper 脚本（本地由 ContainerInstaller 提取），配置为唯一 helper；
                // 服务器上没有 App，用 AICODE_CRED_NO_PROMPT 让未命中时直接空退出而非等待弹窗。
                containerInstaller.extractCredentialHelper()
                val helperScript = java.io.File(containerInstaller.aicodeDir, "git-credential-aicode").readText()
                check(helperScript.isNotBlank()) { "Git credential helper is empty" }
                val remoteHelperScript = helperScript.replaceFirst(
                    "#!/bin/sh\n",
                    "#!/bin/sh\nexport AICODE_CRED_NO_PROMPT=1\n"
                )

                withSftpChannel { sftp ->
                    sftp.mkdirs(aicodeDir)
                    writeRemoteFile(sftp, "$aicodeDir/git-credential-aicode", remoteHelperScript.toByteArray(Charsets.UTF_8), executable = true)
                    writeRemoteFile(sftp, "$aicodeDir/git-credentials", encodeCreds(creds).toByteArray(Charsets.UTF_8))

                    val credentialConfig = buildString {
                        append("[credential]\n")
                        append("    helper = !AICODE_CRED_NO_PROMPT=1 $aicodeDir/git-credential-aicode\n")
                    }
                    writeRemoteFile(sftp, "$aicodeDir/gitconfig.credential", credentialConfig.toByteArray(Charsets.UTF_8))
                }
                gitCredentialWorkspaceRoot = wsRoot
                FileLogger.i(TAG, "已注入 git 凭据到远程 $aicodeDir（限定 $wsRoot/）")
            }.onFailure { FileLogger.w(TAG, "注入 git 凭据到远程失败", it) }
        }
    }

    /**
     * 撤销远程注入：删除 App 管理的凭据与 helper 文件，命令不再附加凭证配置。
     * `~/.aicode/gitconfig` 是旧版实现生成的文件，旧版期间写入其中的用户配置可能只存在于该文件，
     * 这里不作删除。
     */
    suspend fun removeGitCredentialConfig() = credentialSyncMutex.withLock {
        val client = sshClient ?: return@withLock
        val home = remoteHome ?: return@withLock
        val aicodeDir = home.trimEnd('/') + "/.aicode"
        withContext(Dispatchers.IO) {
            runCatching {
                withSftpChannel { sftp ->
                    listOf("gitconfig.credential", "git-credentials", "git-credential-aicode").forEach { name ->
                        val path = "$aicodeDir/$name"
                        if (sftp.statExistence(path) != null) sftp.rm(path)
                    }
                }
                gitCredentialWorkspaceRoot = null
                FileLogger.i(TAG, "已撤销远程 git 凭据注入")
            }.onFailure { FileLogger.w(TAG, "撤销远程 git 凭据注入失败", it) }
        }
    }

    /** 写一个远程文件；调用方必须已持有该通道的租约。 */
    private fun writeRemoteFile(
        client: SFTPClient,
        dest: String,
        bytes: ByteArray,
        executable: Boolean = false,
        overwrite: Boolean = true
    ) {
        val temporary = "$dest.tmp.${UUID.randomUUID()}"
        try {
            client.open(temporary, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).use { file ->
                client.chmod(temporary, if (executable) 448 else 384)
                var offset = 0
                while (offset < bytes.size) {
                    val count = minOf(32 * 1024, bytes.size - offset)
                    file.write(offset.toLong(), bytes, offset, count)
                    offset += count
                }
            }
            check(client.size(temporary) == bytes.size.toLong()) { "Remote file size mismatch" }
            if (overwrite) {
                client.rename(temporary, dest, EnumSet.of(RenameFlags.ATOMIC, RenameFlags.OVERWRITE))
            } else if (client.statExistence(dest) == null) {
                client.rename(temporary, dest)
            }
        } finally {
            runCatching { client.rm(temporary) }
        }
    }

    private fun enc(part: String): String = java.net.URLEncoder.encode(part, "UTF-8")

    /** 与 app 侧一致的编码：base64 后整体反转（属混淆，非加密）。 */
    private fun encodeCreds(plain: String): String =
        android.util.Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP).reversed()
}

/** 远程 SSH 连接状态，供 UI 指示器与工作区初始化时序判断。 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

/** 把 SSH 连接异常翻译成友好提示，供工具层回显给 AI/用户。 */
fun friendlySshError(e: Throwable): String {
    val msg = e.message.orEmpty()
    return when {
        e is KeyDecryptionFailedException ->
            "SSH 认证失败：私钥口令不正确或私钥格式不受支持（$msg）"
        e is UserAuthException ->
            "SSH 认证失败：用户名、密码或密钥未被服务器接受（$msg）"
        "ECONNREFUSED" in msg || "Connection refused" in msg ->
            "无法连接到 SSH 服务器（连接被拒绝），请确认远程服务器已启动且端口正确"
        "UnknownHost" in msg || "unknown host" in msg ->
            "无法连接到 SSH 服务器（未知主机），请检查主机地址"
        "Auth fail" in msg || "authentication" in msg ->
            "SSH 认证失败，请检查用户名和密码"
        "Network is unreachable" in msg ->
            "网络不可达，请检查网络连接"
        "SocketTimeout" in msg || "timed out" in msg ->
            "连接 SSH 服务器超时，请检查网络或服务器状态"
        "SSH 未连接" in msg ->
            "SSH 未连接，请等待连接恢复或在设置中检查配置"
        else -> "SSH 连接失败: ${e.message ?: "未知错误"}"
    }
}

/** 远程 SSH 连接配置。 */
data class RemoteConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: RemoteAuth,
    /** 远程服务器上的工作区根路径（AI 的 ~/workspace 映射到此路径）。 */
    val remoteWorkspacePath: String
)
