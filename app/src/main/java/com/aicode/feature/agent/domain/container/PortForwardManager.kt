package com.aicode.feature.agent.domain.container

import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters

private const val TAG = "PortForwardManager"

/** 端口转发失败原因；[code] 供调用方（插件 host API）透出稳定错误码。 */
class PortForwardException(val code: String, message: String) : Exception(message)

/**
 * 一条端口映射：手机 `127.0.0.1:[localPort]` 对应**服务器视角**的 `[remoteHost]:[remotePort]`。
 * 目标地址在服务器上解析，因此也能映射服务器内网的其他主机。
 */
data class PortForwardInfo(
    val id: String,
    val owner: String,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
    /** 本地容器模式：容器与 App 同在一个网络命名空间，直接可用，没有隧道。 */
    val direct: Boolean
) {
    /** 便于直接打开的 HTTP 地址；非 HTTP 服务请用 `127.0.0.1:[localPort]` 自行拼接。 */
    val url: String get() = "http://127.0.0.1:$localPort"
}

/**
 * 远程端口转发管理：复用现有 SSH 连接把服务器端口映射到手机 loopback，供内置浏览器/插件页面访问。
 *
 * 只绑定 `127.0.0.1`——不对外网或同一 Wi-Fi 暴露服务器端口。转发用独立的 SSH transport，
 * 与 exec/SFTP 隔离，避免长期占用的通道影响命令执行。连接断开或切换执行环境时，
 * 监听由 [connectionState] 监听器统一回收，因此映射不会跨连接残留。
 */
@Singleton
class PortForwardManager @Inject constructor(
    private val sshConnection: RemoteSshConnection,
    private val executionModeHolder: ExecutionModeHolder
) {

    private class Tunnel(
        val info: PortForwardInfo,
        val client: SSHClient?,
        val socket: ServerSocket?,
        val forwarder: LocalPortForwarder?
    )

    private val mutex = Mutex()
    private val tunnels = LinkedHashMap<String, Tunnel>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var tunnelClient: SSHClient? = null

    @Volatile
    private var tunnelConfig: RemoteConnectionConfig? = null

    init {
        scope.launch {
            sshConnection.connectionState.collect { state ->
                if (state != ConnectionState.CONNECTED) closeAll()
            }
        }
    }

    /** 当前映射列表；[owner] 非空时只返回该归属的映射。 */
    suspend fun list(owner: String? = null): List<PortForwardInfo> = mutex.withLock {
        tunnels.values.map { it.info }.filter { owner == null || it.owner == owner }
    }

    /**
     * 建立映射。[requestedLocalPort] 为空时优先占用与 [remotePort] 相同的本地端口，
     * 被占用则顺延；返回的 [PortForwardInfo.localPort] 是**实际**监听端口。
     */
    suspend fun forward(
        owner: String,
        remoteHost: String = "127.0.0.1",
        remotePort: Int,
        requestedLocalPort: Int? = null
    ): PortForwardInfo {
        if (remotePort !in 1..65535) throw PortForwardException("PARAM_INVALID", "port 超出范围: $remotePort")
        if (requestedLocalPort != null && requestedLocalPort !in 1..65535) {
            throw PortForwardException("PARAM_INVALID", "localPort 超出范围: $requestedLocalPort")
        }
        val host = remoteHost.trim().ifEmpty { "127.0.0.1" }
        if (host.any { it.isWhitespace() }) throw PortForwardException("PARAM_INVALID", "host 非法: $remoteHost")

        if (!executionModeHolder.capabilities().portForward) {
            return mutex.withLock {
                checkLimits(owner)
                val info = PortForwardInfo(
                    id = UUID.randomUUID().toString(),
                    owner = owner,
                    localPort = requestedLocalPort ?: remotePort,
                    remoteHost = host,
                    remotePort = remotePort,
                    direct = true
                )
                tunnels[info.id] = Tunnel(info, null, null, null)
                info
            }
        }

        return mutex.withLock {
            checkLimits(owner)
            tunnels.values.firstOrNull {
                !it.info.direct && it.info.owner == owner &&
                    it.info.remoteHost == host && it.info.remotePort == remotePort
            }?.let { return@withLock it.info }

            val client = ensureTunnelClient()
            probeTarget(client, host, remotePort)
            val socket = withContext(Dispatchers.IO) { bindLoopback(requestedLocalPort ?: remotePort) }
            val forwarder = try {
                withContext(Dispatchers.IO) {
                    client.newLocalPortForwarder(
                        Parameters("127.0.0.1", socket.localPort, host, remotePort),
                        socket
                    )
                }
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw PortForwardException("FORWARD_FAILED", "建立转发失败：${e.message.orEmpty()}")
            }

            val info = PortForwardInfo(
                id = UUID.randomUUID().toString(),
                owner = owner,
                localPort = socket.localPort,
                remoteHost = host,
                remotePort = remotePort,
                direct = false
            )
            tunnels[info.id] = Tunnel(info, client, socket, forwarder)
            scope.launch {
                try {
                    forwarder.listen()
                } catch (e: Exception) {
                    if (socket.isClosed) return@launch
                    FileLogger.w(TAG, "端口转发监听中断 ${info.localPort} -> $host:$remotePort", e)
                }
            }
            info
        }
    }

    /** 关闭一条映射；不属于 [owner] 时拒绝。返回是否命中。 */
    suspend fun close(owner: String, id: String): Boolean {
        val tunnel = mutex.withLock { tunnels[id] } ?: return false
        if (tunnel.info.owner != owner) {
            throw PortForwardException("FORBIDDEN", "该端口映射不属于当前插件")
        }
        mutex.withLock { tunnels.remove(id) }
        withContext(Dispatchers.IO) { closeTunnel(tunnel) }
        return true
    }

    private fun checkLimits(owner: String) {
        if (tunnels.size >= MAX_TOTAL) {
            throw PortForwardException("PORT_LIMIT", "端口映射总数已达上限 $MAX_TOTAL")
        }
        if (tunnels.values.count { it.info.owner == owner } >= MAX_PER_OWNER) {
            throw PortForwardException("PORT_LIMIT", "同一插件最多保留 $MAX_PER_OWNER 条端口映射")
        }
    }

    /** 转发用的独立 transport：只在与当前连接配置一致且仍连通时复用。 */
    private suspend fun ensureTunnelClient(): SSHClient = withContext(Dispatchers.IO) {
        val config = sshConnection.config
            ?: throw PortForwardException("SSH_NOT_CONFIGURED", "未配置 SSH 连接")
        if (!sshConnection.isConnected()) {
            throw PortForwardException("SSH_NOT_CONNECTED", "SSH 未连接，请等待连接恢复后重试")
        }
        tunnelClient?.takeIf { tunnelConfig == config && it.isConnected && it.isAuthenticated }
            ?: run {
                runCatching { tunnelClient?.close() }
                sshConnection.newSshClient(config).also {
                    tunnelClient = it
                    tunnelConfig = config
                }
            }
    }

    /**
     * 先开一条到目标的 direct-tcpip 通道再返回：目标未监听或服务器禁用 TCP 转发时
     * 立即报错，而不是等到用户打开页面才发现打不开。
     */
    private suspend fun probeTarget(client: SSHClient, host: String, port: Int) = withContext(Dispatchers.IO) {
        try {
            client.newDirectConnection(host, port).close()
        } catch (e: Exception) {
            val raw = e.message.orEmpty()
            val message = when {
                "administratively prohibited" in raw ->
                    "服务器禁止 TCP 转发（sshd 的 AllowTcpForwarding 为 no），无法映射端口"
                else -> "无法从服务器连接 $host:$port（${raw.ifEmpty { e.javaClass.simpleName }}）"
            }
            throw PortForwardException("TARGET_UNREACHABLE", message)
        }
    }

    private fun bindLoopback(preferred: Int): ServerSocket {
        for (offset in 0 until PORT_SCAN_ATTEMPTS) {
            val port = preferred + offset
            if (port > 65535) break
            val socket = ServerSocket()
            try {
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                return socket
            } catch (_: BindException) {
                runCatching { socket.close() }
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw PortForwardException("PORT_UNAVAILABLE", "无法监听 127.0.0.1:$port（${e.message.orEmpty()}）")
            }
        }
        throw PortForwardException("PORT_UNAVAILABLE", "从 $preferred 起没有可用的本地端口")
    }

    private suspend fun closeAll() {
        val snapshot = mutex.withLock {
            val all = tunnels.values.toList()
            tunnels.clear()
            all
        }
        if (snapshot.isEmpty()) return
        withContext(Dispatchers.IO) {
            snapshot.forEach { closeTunnel(it) }
            runCatching { tunnelClient?.close() }
            tunnelClient = null
            tunnelConfig = null
        }
        FileLogger.i(TAG, "SSH 连接状态变化，已关闭 ${snapshot.size} 条端口映射")
    }

    private fun closeTunnel(tunnel: Tunnel) {
        runCatching { tunnel.forwarder?.close() }
        runCatching { tunnel.socket?.close() }
    }

    private companion object {
        /** 每个归属（插件）最多同时保留的映射数。 */
        const val MAX_PER_OWNER = 8

        /** 全局映射数上限。 */
        const val MAX_TOTAL = 32

        /** 本地端口被占用时向后顺延尝试的次数。 */
        const val PORT_SCAN_ATTEMPTS = 20
    }
}
