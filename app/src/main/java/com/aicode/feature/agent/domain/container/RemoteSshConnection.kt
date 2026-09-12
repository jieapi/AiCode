package com.aicode.feature.agent.domain.container

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import com.aicode.feature.credentials.domain.model.GitCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.userauth.UserAuthException
import com.hierynomus.sshj.common.KeyDecryptionFailedException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RemoteSshConnection"

/**
 * 共享的 SSH 连接管理器：持有单个 sshj [SSHClient]，供 [RemoteSshEngine]（exec channel）
 * 与 [RemoteSftpFileAccess]（SFTP channel）复用同一 SSH 连接。
 *
 * 连接配置（host/port/username/auth）由调用方在 [connect] 时传入。连接断开后下次 [connect]
 * 重新建立。所有操作串行化（[mutex]），避免并发导致 sshj 状态错乱。
 */
@Singleton
class RemoteSshConnection @Inject constructor(
    private val hostKeyStore: SshHostKeyStore,
    private val hostKeyVerifier: SshHostKeyVerifier
) {

    @Volatile
    private var sshClient: SSHClient? = null

    @Volatile
    private var sftpClient: SFTPClient? = null

    private val mutex = Mutex()

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

    suspend fun connect(config: RemoteConnectionConfig) = mutex.withLock {
        if (isConnected() && this.config == config) return@withLock
        disconnectInternal()
        this.config = config
        _connectionState.value = ConnectionState.CONNECTING
        try {
            withContext(Dispatchers.IO) {
                val client = SSHClient().apply {
                    addHostKeyVerifier(hostKeyVerifier)
                    connect(config.host, config.port)
                    when (val auth = config.auth) {
                        is RemoteAuth.Password -> authPassword(config.username, auth.password)
                        is RemoteAuth.PrivateKey -> {
                            val keyProvider = if (auth.passphrase != null) {
                                loadKeys(auth.privateKeyPath, auth.passphrase)
                            } else {
                                loadKeys(auth.privateKeyPath)
                            }
                            authPublickey(config.username, keyProvider)
                        }
                    }
                    // 启用 SSH 心跳保活，防止空闲超时断连
                    runCatching {
                        connection.keepAlive?.let {
                            it.setKeepAliveInterval(30)
                            it.start()
                        }
                    }
                }
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

    private fun disconnectInternal() {
        runCatching { sftpClient?.close() }
        runCatching { sshClient?.disconnect() }
        sftpClient = null
        sshClient = null
        _connectionState.value = ConnectionState.DISCONNECTED
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

    /** 获取共享的 SFTP client（惰性创建）。调用方不应关闭它——由 [disconnect] 统一管理。 */
    suspend fun getSftpClient(): SFTPClient = mutex.withLock {
        sftpClient?.takeIf { sshClient?.isConnected == true }?.let { return@withLock it }
        val client = sshClient ?: throw IllegalStateException("SSH 未连接")
        val sftp = withContext(Dispatchers.IO) { client.newSFTPClient() }
        sftpClient = sftp
        sftp
    }

    /** [getSftpClient] 的阻塞版，供非 suspend 调用方（如 [RemoteSftpFileAccess]）使用。 */
    fun getSftpClientBlocking(): SFTPClient = runBlocking { getSftpClient() }

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

    /**
     * 更新 ~/workspace 符号链接指向当前选中工作区的远程路径，让 AI 用 ~/workspace/... 路径时
     * Bash 命令（pwd 等）能直接访问到正确的工作区目录。应在工作区选中/初始化后调用。
     */
    suspend fun updateWorkspaceSymlink(workspacePath: String) {
        val client = sshClient ?: return
        val ws = workspacePath.trimEnd('/')
        if (ws.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val session = client.startSession()
                val cmd = session.exec("ln -sfn '$ws' ~/workspace 2>/dev/null; echo done")
                java.io.BufferedReader(java.io.InputStreamReader(cmd.inputStream)).readText()
                session.close()
            }.onFailure { FileLogger.w(TAG, "更新 workspace 符号链接失败: $ws", it) }
        }
    }

    /**
     * 把内置文档同步到远程 ~/.aicode/docs/，让远程模式下 AI 也能像本地模式一样
     * 查阅 ~/.aicode/docs/ 下的设置说明文档。每次连接/重连后全量覆盖，使 App 升级后
     * 远程文档随之更新。docs 是纯文本，用 exec + printf 写入即可，无需 SFTP。
     *
     * @param docs 相对 docs/ 的路径（可含子目录，如 guide/terminal.md）→ 文件内容。
     */
    suspend fun uploadDocs(docs: Map<String, String>) {
        if (docs.isEmpty()) return
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val destDir = home.trimEnd('/') + "/.aicode/docs"
        withContext(Dispatchers.IO) {
            runCatching {
                // 先建全部子目录并清掉旧 .md：版本间调整文档分类会改变路径，只覆盖不清理会让旧文件残留
                val subDirs = docs.keys.mapNotNull { key ->
                    key.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
                }.distinct()
                val mkdirArgs = (listOf(destDir) + subDirs.map { "$destDir/$it" }).joinToString(" ") { "'$it'" }
                val prepSession = client.startSession()
                prepSession.exec("mkdir -p $mkdirArgs; find '$destDir' -type f -name '*.md' -delete").join()
                prepSession.close()
                for ((name, content) in docs) {
                    val dest = "$destDir/$name"
                    val escaped = content.replace("\\", "\\\\").replace("'", "'\\\"'\"'")
                    val session = client.startSession()
                    session.exec("printf %s '$escaped' > '$dest'").join()
                    session.close()
                }
                FileLogger.i(TAG, "已同步 ${docs.size} 个文档到远程 $destDir")
            }.onFailure { FileLogger.w(TAG, "同步文档到远程失败", it) }
        }
    }

    /**
     * 把 App 的 git 凭据注入远程服务器（仅当用户开启「自动注入」时调用）：
     * 写 `~/.aicode/git-credentials`（store 格式）+ `~/.aicode/gitconfig` + `~/.aicode/gitconfig.credential`。
     *
     * 生效机制：[RemoteSshEngine] 执行命令时注入 `GIT_CONFIG_GLOBAL=$HOME/.aicode/gitconfig`，其中
     * `[include] path=~/.gitconfig` 保留服务器用户自己的全局配置（署名等），`[includeIf "gitdir:<工作区根>/"]`
     * 限定只有工作区根目录下的仓库才加载 store helper——不影响用户在服务器上手动 git，也不影响其它目录的仓库。
     *
     * @param credentials 要注入的凭据列表（每 host 一条）。
     * @param workspaceRoot 远程工作区根路径（AI 的 ~/workspace 映射目录），限定注入范围。
     */
    suspend fun uploadGitCredentialConfig(credentials: List<GitCredential>, workspaceRoot: String) {
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val aicodeDir = home.trimEnd('/') + "/.aicode"
        val wsRoot = workspaceRoot.trimEnd('/').ifEmpty { return }
        withContext(Dispatchers.IO) {
            runCatching {
                val mkdirSession = client.startSession()
                mkdirSession.exec("mkdir -p '$aicodeDir'").join()
                mkdirSession.close()

                val creds = credentials.joinToString("\n") { c ->
                    "https://${enc(c.username)}:${enc(c.token)}@${c.host}"
                }.let { if (it.isNotEmpty()) "$it\n" else "" }
                writeRemoteFile(client, "$aicodeDir/git-credentials", creds)

                val gitconfig = buildString {
                    append("[include]\n")
                    append("    path = ~/.gitconfig\n")
                    append("[includeIf \"gitdir:$wsRoot/\"]\n")
                    append("    path = ~/.aicode/gitconfig.credential\n")
                }
                writeRemoteFile(client, "$aicodeDir/gitconfig", gitconfig)

                val credentialConfig = buildString {
                    append("[credential]\n")
                    append("    helper = store --file=$aicodeDir/git-credentials\n")
                }
                writeRemoteFile(client, "$aicodeDir/gitconfig.credential", credentialConfig)
                FileLogger.i(TAG, "已注入 git 凭据到远程 $aicodeDir（限定 $wsRoot/）")
            }.onFailure { FileLogger.w(TAG, "注入 git 凭据到远程失败", it) }
        }
    }

    /**
     * 撤销远程注入：删除 App 管理的三个配置/凭据文件。
     * `GIT_CONFIG_GLOBAL` 指向不存在的文件时 git 静默跳过，注入即失效，服务器用户配置不受影响。
     */
    suspend fun removeGitCredentialConfig() {
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val aicodeDir = home.trimEnd('/') + "/.aicode"
        withContext(Dispatchers.IO) {
            runCatching {
                val session = client.startSession()
                session.exec("rm -f '$aicodeDir/gitconfig' '$aicodeDir/gitconfig.credential' '$aicodeDir/git-credentials' 2>/dev/null; echo done").join()
                session.close()
                FileLogger.i(TAG, "已撤销远程 git 凭据注入")
            }.onFailure { FileLogger.w(TAG, "撤销远程 git 凭据注入失败", it) }
        }
    }

    /** exec + printf 写一个文本文件到远程（content 为原文，内部转义单引号/反斜杠）。 */
    private suspend fun writeRemoteFile(client: SSHClient, dest: String, content: String) {
        if (content.isEmpty()) {
            // 空内容直接 truncate，避免 printf %s '' 的引号歧义
            val session = client.startSession()
            session.exec("printf '' > '$dest'").join()
            session.close()
            return
        }
        val escaped = content.replace("\\", "\\\\").replace("'", "'\\\"'\"'")
        val session = client.startSession()
        session.exec("printf %s '$escaped' > '$dest'").join()
        session.close()
    }

    private fun enc(part: String): String = java.net.URLEncoder.encode(part, "UTF-8")
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
