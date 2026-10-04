package com.aicode.feature.backup.domain

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 备份编排器：从各数据源采集快照并打包，或反向解包还原。
 *
 * 导出：按 [BackupOptions] 采集 → 流式序列化（metadata.json + 各 *.jsonl）→ tar.gz 压缩 → 口令非空则分块 AES-GCM 加密。
 * 导入：口令非空则先流式解密 → 解 tar.gz → 校验 schemaVersion → 分批合并写入各数据源。
 * 全程流式，内存峰值与数据总量解耦（只持有当前分页批次）。
 */
interface BackupManager {
    /**
     * 流式生成备份写入 [output]（调用方负责打开与关闭输出流）。
     * password 为 null 或空时不加密，输出明文 tar.gz。
     */
    suspend fun export(password: CharArray?, options: BackupOptions, output: OutputStream)

    /**
     * 导出单个会话（无密码 tar.gz）：只含该会话 + 关联消息 + todo，可由 [import] 直接还原。
     * @param output 调用方负责打开与关闭输出流
     */
    suspend fun exportSession(sessionId: String, output: OutputStream)

    /**
     * 流式解包并还原备份文件。
     * @param input 调用方负责打开与关闭输入流
     * @param password 备份未加密时传 null 或空；加密文件必须提供正确口令。
     * @param selectedWorkspaces 非 null 时只恢复这些工作区的文件（导入前由 [previewImport] 勾选）；
     *   null 表示全量恢复（备份无工作区数据或旧调用）。
     * @return 还原统计（各数据段条目数）；口令错误/格式不符/版本过高时返回失败。
     */
    suspend fun import(
        input: InputStream,
        password: CharArray?,
        selectedWorkspaces: Set<String>? = null
    ): Result<RestoreStats>

    /**
     * 导入前预览：校验口令/格式/版本，并读出备份中包含的工作区列表（名称 + 文件数）。
     * 有工作区数据时 UI 应弹出勾选确认（直接覆盖警告）；无数据时导入无需弹窗。
     * @param input 调用方负责打开与关闭输入流
     */
    suspend fun previewImport(input: InputStream, password: CharArray?): Result<ImportPreview>

    /** 准备经过完整解密校验的压缩包，调用方负责在预览和恢复结束后删除临时文件。 */
    suspend fun prepareImport(input: InputStream, password: CharArray?): File
}

/** 导入预览结果：备份中的工作区列表（供勾选）。 */
data class ImportPreview(
    val workspaces: List<WorkspaceBackupMeta>
) {
    val hasWorkspaceData: Boolean get() = workspaces.isNotEmpty()
}

/** 导出数据范围选项；未勾选的段在快照中保持空值，导入时跳过。 */
data class BackupOptions(
    val providers: Boolean = true,
    val remoteConnections: Boolean = true,
    val chatHistory: Boolean = true,
    val mcpServers: Boolean = true,
    val permissionRules: Boolean = true,
    val appSettings: Boolean = true,
    val workspaceFiles: Boolean = false
)

data class RestoreStats(
    val providers: Int = 0,
    val remoteConnections: Int = 0,
    val remoteMounts: Int = 0,
    val chatSessions: Int = 0,
    val agentMessages: Int = 0,
    val todoItems: Int = 0,
    val mcpServers: Int = 0,
    val globalPermissionRules: Int = 0,
    val workspaceFiles: Int = 0
) {
    operator fun plus(other: RestoreStats) = RestoreStats(
        providers = providers + other.providers,
        remoteConnections = remoteConnections + other.remoteConnections,
        remoteMounts = remoteMounts + other.remoteMounts,
        chatSessions = chatSessions + other.chatSessions,
        agentMessages = agentMessages + other.agentMessages,
        todoItems = todoItems + other.todoItems,
        mcpServers = mcpServers + other.mcpServers,
        globalPermissionRules = globalPermissionRules + other.globalPermissionRules,
        workspaceFiles = workspaceFiles + other.workspaceFiles
    )
}
