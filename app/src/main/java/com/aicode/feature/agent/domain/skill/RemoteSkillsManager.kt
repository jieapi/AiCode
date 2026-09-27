package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.SshHostKeyVerifier
import com.aicode.feature.settings.data.repository.ExecutionModeRepository
import com.aicode.feature.workspace.domain.RemoteSkillConnection
import com.aicode.feature.workspace.domain.RemoteSkillFileAccess
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** 远程技能加载状态。 */
sealed interface RemoteSkillsState {
    /** 未配置远程 SSH（或未连接）。 */
    data object NotConfigured : RemoteSkillsState

    /** 正在连接 / 扫描。 */
    data object Loading : RemoteSkillsState

    /** 已连上 [host]，[skills] 为远程工作区里的技能。 */
    data class Loaded(val host: String, val skills: List<Skill>) : RemoteSkillsState

    /** 连接或读取失败。 */
    data class Failed(val message: String) : RemoteSkillsState
}

/**
 * 本地模式下管理「远程 SSH 模式」那台服务器技能的入口：按当前远程连接配置建立独立的 SFTP 访问器，
 * 对远程工作区的 `.aicode/skills/` 做扫描与完整 CRUD。连接与访问器由本类持有，UI 只读 [state]。
 */
@Singleton
class RemoteSkillsManager @Inject constructor(
    private val skillRepository: SkillRepository,
    private val executionModeRepository: ExecutionModeRepository,
    private val hostKeyVerifier: SshHostKeyVerifier
) {
    private val _state = MutableStateFlow<RemoteSkillsState>(RemoteSkillsState.NotConfigured)
    val state: StateFlow<RemoteSkillsState> = _state.asStateFlow()

    private var access: RemoteSkillFileAccess? = null
    private var currentHost: String? = null

    /** 远程工作区里的技能根（容器路径，经 provider 映射到远程工作区）。 */
    private val skillsRoot: String = "${WorkspacePathMapper.CONTAINER_ROOT}/.aicode/skills"

    /** 读取当前远程 SSH 配置并（重新）连接、扫描远程技能。 */
    suspend fun connect() {
        _state.value = RemoteSkillsState.Loading
        val settings = executionModeRepository.remoteConnectionFlow.first()
        if (settings == null || settings.host.isBlank()) {
            closeAccess()
            _state.value = RemoteSkillsState.NotConfigured
            return
        }
        try {
            if (access == null || currentHost != settings.host) {
                closeAccess()
                access = RemoteSkillFileAccess(
                    RemoteSkillConnection(
                        host = settings.host,
                        port = settings.port,
                        username = settings.username,
                        password = settings.password,
                        workspaceRoot = settings.remoteWorkspacePath
                    ),
                    hostKeyVerifier
                )
                currentHost = settings.host
            }
            val skills = skillRepository.listSkillsFrom(requireAccess(), skillsRoot)
            _state.value = RemoteSkillsState.Loaded(settings.host, skills)
        } catch (e: Exception) {
            FileLogger.w(TAG, "加载远程技能失败", e)
            _state.value = RemoteSkillsState.Failed(e.message ?: "连接失败")
        }
    }

    /** 重新扫描已连接的远程技能；未连接时退回 [connect]。 */
    suspend fun refresh() {
        val a = access
        val host = currentHost
        if (a == null || host == null) return connect()
        try {
            val skills = skillRepository.listSkillsFrom(a, skillsRoot)
            _state.value = RemoteSkillsState.Loaded(host, skills)
        } catch (e: Exception) {
            FileLogger.w(TAG, "刷新远程技能失败", e)
            _state.value = RemoteSkillsState.Failed(e.message ?: "连接失败")
        }
    }

    suspend fun save(form: SkillForm, originalName: String?): SkillSaveError? {
        val a = requireAccess()
        val existing = skillRepository.listSkillsFrom(a, skillsRoot)
        val error = skillRepository.saveTo(a, skillsRoot, form, originalName, existing)
        if (error == null) refresh()
        return error
    }

    suspend fun delete(name: String): Boolean {
        val a = requireAccess()
        val existing = skillRepository.listSkillsFrom(a, skillsRoot)
        val ok = skillRepository.deleteSkillFrom(a, name, existing)
        if (ok) refresh()
        return ok
    }

    suspend fun importMarkdown(text: String, fallbackName: String): SkillImportReport {
        val a = requireAccess()
        val existing = existingNames(a)
        val report = skillRepository.importMarkdownTo(a, skillsRoot, existing, text, fallbackName)
        refresh()
        return report
    }

    suspend fun importZip(input: InputStream, fallbackName: String): SkillImportReport {
        val a = requireAccess()
        val existing = existingNames(a)
        val report = skillRepository.importZipTo(a, skillsRoot, existing, input, fallbackName)
        refresh()
        return report
    }

    fun disconnect() {
        closeAccess()
        _state.value = RemoteSkillsState.NotConfigured
    }

    private suspend fun existingNames(a: RemoteSkillFileAccess): Set<String> =
        skillRepository.listSkillsFrom(a, skillsRoot).map { it.name.lowercase() }.toSet()

    private fun requireAccess(): RemoteSkillFileAccess =
        access ?: throw IllegalStateException("远程技能未连接")

    private fun closeAccess() {
        runCatching { access?.close() }
        access = null
        currentHost = null
    }

    private companion object {
        const val TAG = "RemoteSkillsManager"
    }
}
