package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.toolApprovalDataStore by preferencesDataStore(name = "tool_approval_prefs")

/**
 * 持久化各工具的「审批开关」。默认开启（= 需要审批弹窗）；关闭后该工具自动放行（跳过弹窗，
 * 但 DENY 规则仍生效，AUTO/PLAN 模式判定也不受影响）。MCP 工具用统一 key "mcp" 控制
 * 所有动态注册的 MCP 工具（工具名形如 mcp__server__tool）。
 */
@Singleton
class ToolApprovalSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        /** MCP 工具的统一开关 key。 */
        const val MCP_KEY = "mcp"

        /** git 写命令按子命令分组的开关 key。 */
        const val GIT_COMMIT_KEY = "git_commit"
        const val GIT_PUSH_KEY = "git_push"
        const val GIT_PULL_KEY = "git_pull"
        const val GIT_BRANCH_KEY = "git_branch"
        const val GIT_OTHER_KEY = "git_other"

        /** git 分支/标签/切换/合并类子命令前缀。 */
        private val GIT_BRANCH_PREFIXES = listOf(
            "git branch", "git tag", "git checkout", "git switch", "git restore",
            "git merge", "git rebase", "git reset", "git cherry-pick", "git stash"
        )

        fun key(toolName: String): String = if (toolName.startsWith("mcp__")) MCP_KEY else toolName

        /** 前缀精确匹配（token 边界）：`git commit` 命中 `git commit -m`，不命中 `git commitx`。 */
        private fun matchesPrefix(prefix: String, command: String): Boolean =
            command == prefix || command.startsWith("$prefix ")
    }

    /**
     * Bash 命令 → 审批开关 key：git 写命令按子命令分组（commit/push/pull/分支标签/其它），
     * 非 git 命令归 "Bash"。git 只读命令（status/log/diff 等）在内置白名单自动放行，不走到这里。
     */
    fun bashSwitchKey(command: String): String {
        val trimmed = command.trimStart()
        if (!trimmed.startsWith("git ")) return "Bash"
        return when {
            matchesPrefix("git commit", trimmed) -> GIT_COMMIT_KEY
            matchesPrefix("git push", trimmed) -> GIT_PUSH_KEY
            matchesPrefix("git pull", trimmed) -> GIT_PULL_KEY
            GIT_BRANCH_PREFIXES.any { matchesPrefix(it, trimmed) } -> GIT_BRANCH_KEY
            else -> GIT_OTHER_KEY
        }
    }

    /** 指定工具当前审批开关流；未设置时默认开启（需要审批）。 */
    fun isApprovalEnabled(toolName: String): Flow<Boolean> =
        context.toolApprovalDataStore.data.map { it[booleanPreferencesKey(key(toolName))] ?: true }

    /** 写入指定工具的审批开关。 */
    suspend fun setApprovalEnabled(toolName: String, enabled: Boolean) {
        context.toolApprovalDataStore.edit { it[booleanPreferencesKey(key(toolName))] = enabled }
    }

    /** 读取一次当前值（冷启动恢复用）。 */
    suspend fun isEnabledOnce(toolName: String): Boolean = isApprovalEnabled(toolName).first()
}
