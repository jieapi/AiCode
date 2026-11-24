package com.aicode.feature.credentials.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.credentials.data.CredentialInjectSettingsRepository
import com.aicode.feature.credentials.data.remote.GitHubDeviceFlowClient
import com.aicode.feature.credentials.data.remote.GitHubTokenPollResult
import com.aicode.feature.credentials.domain.model.GitCredential
import com.aicode.feature.credentials.domain.repository.CredentialRepository
import com.aicode.feature.git.domain.GitRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 快速登录（设备码流）界面状态。 */
sealed interface QuickLoginUiState {
    data object Idle : QuickLoginUiState
    data object RequestingCode : QuickLoginUiState
    data class Awaiting(val userCode: String, val verificationUri: String) : QuickLoginUiState
    data class Success(val username: String, val needsInstall: Boolean) : QuickLoginUiState
    data class Failed(val message: String) : QuickLoginUiState
}

/**
 * 凭据页 UI 编排：凭据 CRUD + 提交署名(user.name/email) 配置。
 *
 * 提交署名读写走 `git config`（[GitRepository.setUserIdentity] / [getUserName] / [getUserEmail]）：
 * **优先项目级**（当前工作区 ~/workspace/.git/config），无则退全局（持久挂载 /root/.aicode/.gitconfig）。
 * UI 与终端敲 `git config user.name` 读到的是同一份署名——优先项目级、无则退全局，无两套源头竞争。
 */
@HiltViewModel
class CredentialViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val gitRepository: GitRepository,
    private val injectSettings: CredentialInjectSettingsRepository,
    private val remoteSshConnection: RemoteSshConnection,
    private val workspaceRepository: WorkspaceRepository,
    private val gitHubDeviceFlow: GitHubDeviceFlowClient,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private companion object {
        const val TAG = "CredentialViewModel"

        /** slow_down 后每次增加的轮询间隔（秒）。 */
        const val SLOW_DOWN_STEP_SECONDS = 5

        /** 快速登录写入凭据的固定主机。 */
        const val GITHUB_HOST = "github.com"

        /** 轮询阶段需立即终止的协议错误码；其余（含网络异常）按瞬时错误继续重试到设备码过期。 */
        val TERMINAL_POLL_ERRORS = setOf(
            "incorrect_client_credentials",
            "device_flow_disabled",
            "unsupported_grant_type",
            "incorrect_device_code"
        )

        /** 拿到令牌后拉取用户名的重试次数与间隔，避开瞬时断网。 */
        const val USER_FETCH_ATTEMPTS = 5
        const val USER_FETCH_RETRY_DELAY_MS = 3000L
    }

    data class UiState(
        val credentials: List<GitCredential> = emptyList(),
        /** 容器 git config 当前 user.name，作编辑框初值。 */
        val userName: String = "",
        /** 容器 git config 当前 user.email，作编辑框初值。 */
        val userEmail: String = "",
        /** 容器 `git config --global` 实际 user.name，回显「git 实际值」（与 userName 同源）。 */
        val globalUserName: String = "",
        /** 容器 git config 当前 remote.origin.url，作仓库地址编辑框初值。 */
        val repoUrl: String = "",
        /** 远程模式下「自动注入凭证到远程服务器」开关状态（按服务器独立记忆）。 */
        val autoInjectEnabled: Boolean = false,
        /** 远程模式下该服务器是否首次进入（未询问过），UI 据此弹确认框。 */
        val showAutoInjectPrompt: Boolean = false,
        /** 当前是否远程模式（决定开关是否显示）。 */
        val isRemote: Boolean = false,
        val toast: String? = null
    )

    // 非反应式来源的状态：git config 读出的署名、仓库地址、注入开关、toast。credentials 走文件仓储 Flow。
    private val _extra = MutableStateFlow(Extra())
    private data class Extra(
        val userName: String = "",
        val userEmail: String = "",
        val globalUserName: String = "",
        val repoUrl: String = "",
        val autoInjectEnabled: Boolean = false,
        val showAutoInjectPrompt: Boolean = false,
        val isRemote: Boolean = false,
        val toast: String? = null
    )

    val state: StateFlow<UiState> = combine(credentialRepository.getAll(), _extra) { creds, extra ->
        UiState(
            credentials = creds,
            userName = extra.userName,
            userEmail = extra.userEmail,
            globalUserName = extra.globalUserName,
            repoUrl = extra.repoUrl,
            autoInjectEnabled = extra.autoInjectEnabled,
            showAutoInjectPrompt = extra.showAutoInjectPrompt,
            isRemote = extra.isRemote,
            toast = extra.toast
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private val _quickLogin = MutableStateFlow<QuickLoginUiState>(QuickLoginUiState.Idle)
    val quickLogin: StateFlow<QuickLoginUiState> = _quickLogin.asStateFlow()

    /** 设备码轮询协程；取消或重开时替换。 */
    private var pollJob: Job? = null

    init {
        refreshIdentity()
        refreshAutoInjectState()
    }

    /** 从容器 git config 读取当前署名与仓库地址刷新 UI（编辑框初值 + 实际值回显）。可重入，进凭据页时调一次兜住终端改动。 */
    fun refreshIdentity() {
        viewModelScope.launch {
            runCatching {
                _extra.update {
                    it.copy(
                        userName = gitRepository.getUserName(),
                        userEmail = gitRepository.getUserEmail(),
                        globalUserName = gitRepository.getUserName(),
                        repoUrl = gitRepository.getRepoUrl()
                    )
                }
            }.onFailure { FileLogger.w(TAG, "读取 git 全局署名失败: ${it.message}") }
        }
    }

    fun saveCredential(credential: GitCredential) {
        viewModelScope.launch {
            try {
                credentialRepository.save(credential)
                syncInjectionIfEnabled() // 凭据变更后重新同步到已开启注入的远程服务器
                toast(context.getString(R.string.credential_toast_saved))
            } catch (e: Exception) {
                FileLogger.e(TAG, "保存凭据失败", e)
                toast(context.getString(R.string.credential_toast_save_failed, e.message))
            }
        }
    }

    fun deleteCredential(id: String) {
        viewModelScope.launch {
            credentialRepository.delete(id)
            syncInjectionIfEnabled() // 删凭据后从远程注入文件移除，否则远程仍能用旧凭据
            toast(context.getString(R.string.credential_toast_deleted))
        }
    }

    // ---------- 远程自动注入开关 ----------

    /** 当前远程服务器标识（host:port 哈希）；本地模式/未连接时为 null。 */
    private fun currentServerKey(): String? {
        val cfg = remoteSshConnection.config ?: return null
        return injectSettings.serverKey(cfg.host, cfg.port)
    }

    /** 按当前服务器刷新开关状态与「是否首次询问」。进入凭据页/连接变化时调用。 */
    fun refreshAutoInjectState() {
        val key = currentServerKey()
        _extra.update {
            it.copy(
                isRemote = key != null,
                autoInjectEnabled = key?.let { k -> injectSettings.isAutoInjectEnabled(k) } ?: false,
                showAutoInjectPrompt = key?.let { k -> !injectSettings.hasAsked(k) } ?: false
            )
        }
    }

    /** 用户切换开关：持久化 + 立即注入/撤销。 */
    fun setAutoInject(enabled: Boolean) {
        viewModelScope.launch {
            val key = currentServerKey() ?: return@launch
            val applied = applyInjection(enabled)
            injectSettings.setAutoInject(key, enabled && applied)
            if (enabled && !applied) {
                toast(context.getString(R.string.credential_toast_auto_inject_failed))
            }
            refreshAutoInjectState()
        }
    }

    /** 首次询问弹窗选择后调用：标记已询问 + 应用选择。 */
    fun dismissAutoInjectPrompt(enabled: Boolean) {
        viewModelScope.launch {
            val key = currentServerKey() ?: return@launch
            injectSettings.markAsked(key)
            val applied = applyInjection(enabled)
            injectSettings.setAutoInject(key, enabled && applied)
            if (enabled && !applied) {
                toast(context.getString(R.string.credential_toast_auto_inject_failed))
            }
            refreshAutoInjectState()
        }
    }

    /** 开启时上传远程注入配置（凭据 + includeIf 限定的 gitconfig），关闭时撤销。 */
    private suspend fun applyInjection(enabled: Boolean): Boolean {
        if (!enabled) {
            return remoteSshConnection.removeGitCredentialConfig()
        }
        val creds = credentialRepository.getAll().first()
        val workspaceRoot = workspaceRepository.currentPath()
            .takeIf { it.isNotBlank() && it != "/" }
            ?: remoteSshConnection.config?.remoteWorkspacePath.orEmpty()
        return remoteSshConnection.uploadGitCredentialConfig(creds, workspaceRoot)
    }

    /** 凭据增删后若当前服务器已开启注入，重新同步到远程。 */
    private suspend fun syncInjectionIfEnabled() {
        val key = currentServerKey() ?: return
        if (injectSettings.isAutoInjectEnabled(key) && !applyInjection(true)) {
            FileLogger.w(TAG, "凭据已变更，但远程注入同步失败")
        }
    }

    /** 保存提交署名与仓库地址：跑 `git config` 写入对应 key 真源 + 刷新回显。UI 与命令行同一文件。 */
    fun saveUserIdentity(name: String, email: String, repoUrl: String) {
        viewModelScope.launch {
            try {
                gitRepository.setUserIdentity(name, email)
                gitRepository.setRepoUrl(repoUrl)
                refreshIdentity()
                toast(context.getString(R.string.credential_toast_identity_saved))
            } catch (e: Exception) {
                FileLogger.e(TAG, "保存署名失败", e)
                toast(context.getString(R.string.credential_toast_save_failed, e.message))
            }
        }
    }

    fun consumeToast() = _extra.update { it.copy(toast = null) }

    private fun toast(msg: String) = _extra.update { it.copy(toast = msg) }

    // ---------- 快速登录（GitHub 设备码流） ----------

    /** 发起 GitHub 快速登录：申请设备码后进入等待授权态并开始轮询。 */
    fun startGitHubLogin() {
        pollJob?.cancel()
        _quickLogin.value = QuickLoginUiState.RequestingCode
        pollJob = viewModelScope.launch {
            val device = try {
                gitHubDeviceFlow.requestDeviceCode()
            } catch (e: Exception) {
                FileLogger.e(TAG, "申请 GitHub 设备码失败", e)
                _quickLogin.value = QuickLoginUiState.Failed(mapDeviceCodeError(e.message))
                return@launch
            }
            _quickLogin.value = QuickLoginUiState.Awaiting(device.userCode, device.verificationUri)

            var interval = device.intervalSeconds.coerceAtLeast(1)
            val deadline = System.currentTimeMillis() + device.expiresInSeconds * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(interval * 1000L)
                // 用户去浏览器授权时 App 可能被切到后台、瞬时断网：异常一律视为瞬时不可用，
                // 继续轮询直到设备码过期，不因此终止流程。
                val result = runCatching { gitHubDeviceFlow.pollAccessToken(device.deviceCode) }.getOrNull()
                when (result) {
                    null -> Unit
                    is GitHubTokenPollResult.Success -> {
                        onGitHubToken(result.accessToken)
                        return@launch
                    }
                    GitHubTokenPollResult.Pending -> Unit
                    GitHubTokenPollResult.SlowDown -> interval += SLOW_DOWN_STEP_SECONDS
                    GitHubTokenPollResult.Expired -> {
                        _quickLogin.value = QuickLoginUiState.Failed(context.getString(R.string.credential_device_expired))
                        return@launch
                    }
                    GitHubTokenPollResult.Denied -> {
                        _quickLogin.value = QuickLoginUiState.Failed(context.getString(R.string.credential_device_denied))
                        return@launch
                    }
                    is GitHubTokenPollResult.Error -> {
                        // 仅明确的协议错误才终止；未知错误（含服务端 5xx）按瞬时错误继续轮询。
                        if (result.code in TERMINAL_POLL_ERRORS) {
                            _quickLogin.value = QuickLoginUiState.Failed(result.code)
                            return@launch
                        }
                    }
                }
            }
            _quickLogin.value = QuickLoginUiState.Failed(context.getString(R.string.credential_device_expired))
        }
    }

    /** 拿到 access token 后拉取用户名并写入 github.com 凭据。 */
    private suspend fun onGitHubToken(accessToken: String) {
        val login = fetchLoginWithRetry(accessToken)
        if (login == null) {
            FileLogger.e(TAG, "GitHub 登录后拉取用户名失败")
            _quickLogin.value = QuickLoginUiState.Failed(context.getString(R.string.credential_device_unknown_error))
            return
        }
        try {
            credentialRepository.save(
                GitCredential(id = GITHUB_HOST, host = GITHUB_HOST, username = login, token = accessToken)
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "GitHub 登录后写入凭据失败", e)
            _quickLogin.value = QuickLoginUiState.Failed(e.message ?: context.getString(R.string.credential_device_unknown_error))
            return
        }
        // 仅授权未安装时令牌无仓库访问权：检测失败时按已安装处理，避免误报打扰。
        val installed = runCatching { gitHubDeviceFlow.isAppInstalled(accessToken) }.getOrDefault(true)
        runCatching { syncInjectionIfEnabled() }
        _quickLogin.value = QuickLoginUiState.Success(login, needsInstall = !installed)
    }

    /** 拉取用户名：网络抖动时重试若干次，避免刚拿到令牌就因瞬时断网判失败。 */
    private suspend fun fetchLoginWithRetry(accessToken: String): String? {
        repeat(USER_FETCH_ATTEMPTS) { attempt ->
            runCatching { gitHubDeviceFlow.fetchUserLogin(accessToken) }.getOrNull()?.let { return it }
            if (attempt < USER_FETCH_ATTEMPTS - 1) delay(USER_FETCH_RETRY_DELAY_MS)
        }
        return null
    }

    /** 取消快速登录：停止轮询并复位状态。 */
    fun cancelQuickLogin() {
        pollJob?.cancel()
        pollJob = null
        _quickLogin.value = QuickLoginUiState.Idle
    }

    /** 消费成功态：收起设备码弹层并复位（凭据列表由仓储 Flow 自动刷新）。 */
    fun consumeQuickLoginResult() {
        if (_quickLogin.value is QuickLoginUiState.Success) _quickLogin.value = QuickLoginUiState.Idle
    }

    /** 把设备码申请阶段返回的错误码映射为用户可读文案。 */
    private fun mapDeviceCodeError(code: String?): String = when (code) {
        "device_flow_disabled" -> context.getString(R.string.credential_device_flow_disabled)
        "incorrect_client_credentials" -> context.getString(R.string.credential_device_bad_client)
        else -> code ?: context.getString(R.string.credential_device_unknown_error)
    }
}
