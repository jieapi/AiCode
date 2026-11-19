package com.aicode.feature.plugin.data

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginManifestParser
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一个已安装插件的宿主侧记录。启停与包摘要只存在这里，由宿主独占写入，
 * 不放进插件包目录，插件包内容无法改动它。
 */
@Serializable
data class PluginRecord(
    val id: String,
    val version: String,
    val enabled: Boolean,
    val installedAt: Long,
    /** 安装来源标识：`local`（本地导入）或 `index`（在线索引）。 */
    val origin: String,
    val archiveSha256: String,
    val manifestSha256: String,
    /** 在线安装绑定的索引来源；本地导入为 null。 */
    val sourceId: String? = null,
    val sourceName: String? = null,
    val indexUrl: String? = null,
    val repository: String? = null,
    val official: Boolean = false,
    /** 依赖是否已在本容器准备完成。 */
    val dependenciesReady: Boolean = false,
    /** 依赖输入与运行环境的指纹；旧版本中这里保存的是依赖文件 sha256。 */
    val dependenciesHash: String? = null,
    /** 依赖缓存的完整指纹，缺省时回退使用 [dependenciesHash]。 */
    val dependencyFingerprint: String? = null,
    /** 来源类型：package 或 development。 */
    val sourceKind: String = SOURCE_PACKAGE,
    /** development 来源在 ~/.aicode/plugin-dev 下的目录名。 */
    val devDirName: String? = null,
    /** 每次安装独立生成，区分同版本、同清单甚至相同 ZIP 的重装。 */
    val installRevision: String? = null
)

@Serializable
private data class RegistryFile(val schema: Int = SCHEMA, val plugins: List<PluginRecord> = emptyList())

@Serializable
internal data class PluginInstallTransaction(val id: String, val revision: String, val hadPrevious: Boolean)

private const val SCHEMA = 6
const val SOURCE_PACKAGE = "package"
const val SOURCE_DEVELOPMENT = "development"

/** 安装记录库：一个 JSON 文件，临时文件加原子重命名落盘，崩溃不会留下写了一半的内容。 */
class PluginRegistryStore(private val file: File) {

    @Volatile
    private var lastLoadFailed = false

    /** 最近一次 [load] 是否因文件损坏或版本不认识而失败；调用方据此避免误删安装目录。 */
    val loadFailed: Boolean get() = lastLoadFailed

    /** 文件不存在返回空。文件损坏或版本不认识时先备份再按空处理，不覆盖原内容。 */
    fun load(): List<PluginRecord> {
        lastLoadFailed = false
        if (!file.isFile) return emptyList()
        return try {
            val parsed = JSON.decodeFromString<RegistryFile>(file.readText())
            if (parsed.schema !in 1..SCHEMA) throw IllegalStateException("schema=${parsed.schema}")
            parsed.plugins.filter { record ->
                PluginManifestParser.isValidPluginId(record.id).also { valid ->
                    if (!valid) FileLogger.w(TAG, "忽略 ID 非法的插件记录")
                }
            }.distinctBy { it.id }
        } catch (e: Exception) {
            lastLoadFailed = true
            val backup = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
            FileLogger.e(TAG, "插件安装记录无法读取，已备份为 ${backup.name}", e)
            file.renameTo(backup)
            emptyList()
        }
    }

    /** 失败时抛出异常，由调用方回滚；不回退到非原子写。 */
    fun save(records: List<PluginRecord>) {
        writeAtomically(file, JSON.encodeToString(RegistryFile(plugins = records)))
    }

    // 与 registry 同在宿主私有目录，不能用插件安装目录里的标记决定是否提交。
    private val transactionFile = File(file.parentFile, "${file.name}.install")

    internal fun saveInstallTransaction(transaction: PluginInstallTransaction) {
        writeAtomically(transactionFile, JSON.encodeToString(transaction))
    }

    internal fun loadInstallTransaction(): PluginInstallTransaction? {
        return try {
            val text = String(Files.readAllBytes(transactionFile.toPath()), Charsets.UTF_8)
            JSON.decodeFromString<PluginInstallTransaction>(text).also {
                if (!PluginManifestParser.isValidPluginId(it.id) || it.revision.isBlank()) {
                    throw IOException("安装事务无效")
                }
            }
        } catch (_: NoSuchFileException) {
            null
        } catch (e: Exception) {
            throw IOException("无法读取安装事务", e)
        }
    }

    internal fun clearInstallTransaction() {
        Files.deleteIfExists(transactionFile.toPath())
    }

    private fun writeAtomically(target: File, text: String) {
        val dir = target.parentFile ?: throw IOException("registry 路径没有父目录")
        dir.mkdirs()
        val tmp = File(dir, "${target.name}.tmp")
        tmp.outputStream().use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val TAG = "PluginRegistryStore"
        val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
