package com.aicode.feature.plugin.data

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginManifestParser
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一个已安装插件的宿主侧记录。启停、授权与包摘要只存在这里，由宿主独占写入，
 * 不放进插件包目录，插件包内容无法改动它。
 */
@Serializable
data class PluginRecord(
    val id: String,
    val version: String,
    val enabled: Boolean,
    val installedAt: Long,
    /** 安装来源标识，目前只有 `local`（本地导入）。 */
    val origin: String,
    val archiveSha256: String,
    val manifestSha256: String,
    /** 使用者已授予的能力 ID。 */
    val grants: List<String> = emptyList()
)

@Serializable
private data class RegistryFile(val schema: Int = SCHEMA, val plugins: List<PluginRecord> = emptyList())

private const val SCHEMA = 1

/** 安装记录库：一个 JSON 文件，临时文件加原子重命名落盘，崩溃不会留下写了一半的内容。 */
class PluginRegistryStore(private val file: File) {

    /** 文件不存在返回空。文件损坏或版本不认识时先备份再按空处理，不覆盖原内容。 */
    fun load(): List<PluginRecord> {
        if (!file.isFile) return emptyList()
        return try {
            val parsed = JSON.decodeFromString<RegistryFile>(file.readText())
            if (parsed.schema != SCHEMA) throw IllegalStateException("schema=${parsed.schema}")
            parsed.plugins.filter { record ->
                PluginManifestParser.isValidPluginId(record.id).also { valid ->
                    if (!valid) FileLogger.w(TAG, "忽略 ID 非法的插件记录")
                }
            }.distinctBy { it.id }
        } catch (e: Exception) {
            val backup = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
            FileLogger.e(TAG, "插件安装记录无法读取，已备份为 ${backup.name}", e)
            file.renameTo(backup)
            emptyList()
        }
    }

    /** 失败时抛出异常，由调用方回滚；不回退到非原子写。 */
    fun save(records: List<PluginRecord>) {
        val dir = file.parentFile ?: throw IllegalStateException("registry 路径没有父目录")
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.outputStream().use { out ->
            out.write(JSON.encodeToString(RegistryFile(plugins = records)).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val TAG = "PluginRegistryStore"
        val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
