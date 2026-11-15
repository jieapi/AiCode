package com.aicode.feature.plugin.runtime

import android.content.Context
import com.aicode.core.security.KeystoreCipher
import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 密钥值的加解密入口；生产实现是 [KeystoreSecretCipher]，测试注入可预测的实现。 */
internal interface PluginSecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(value: String): String
}

/** 值经 Android Keystore 的 AES-256-GCM 加密，密钥不可导出，明文不落盘。 */
internal object KeystoreSecretCipher : PluginSecretCipher {
    override fun encrypt(plain: String): String = KeystoreCipher.encryptString(plain)
    override fun decrypt(value: String): String = KeystoreCipher.decryptString(value)
}

@Serializable
private data class SecretFile(
    val schema: Int = SECRET_SCHEMA,
    val values: Map<String, String> = emptyMap()
)

private const val SECRET_SCHEMA = 1

/**
 * 插件私有的加密 key-value 存储，一个插件一个文件（`filesDir/plugin-secrets/<pluginId>.json`）。
 *
 * 值统一经 [PluginSecretCipher] 加密后落盘，因此插件页面、容器内进程和同机的其他插件都只能看到密文；
 * 键名保持明文，[list] 也只返回键名，不返回任何值。写入走临时文件加原子重命名，崩溃不会留下半个 JSON。
 *
 * 方法都是 `suspend` 且切到 IO：Keystore 与文件操作都是阻塞调用，而调用方是桥与插件进程的 RPC
 * handler，可能跑在主线程上。读取不持锁——写入是原子替换，读到的要么是旧文件要么是新文件；
 * 写入用单把锁串行化，避免两个插件同时写各自文件时争用同一临时文件。
 *
 * 稳定错误码：[PluginHostException] 的 `PLUGIN_INACTIVE`、`PARAM_INVALID`、`TOO_LARGE`、`STORAGE_FAILED`。
 */
@Singleton
class PluginSecretStore internal constructor(
    private val rootDir: File,
    private val pluginManager: PluginManager,
    private val cipher: PluginSecretCipher
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        pluginManager: PluginManager
    ) : this(File(context.filesDir, SECRETS_DIR), pluginManager, KeystoreSecretCipher)

    private val writeMutex = Mutex()

    /** 读取一个键；存储文件或键不存在时返回空串。 */
    suspend fun get(pluginId: String, key: String): JsonElement = withContext(Dispatchers.IO) {
        requireActive(pluginId)
        val name = requireKey(key)
        val encrypted = load(fileFor(pluginId))[name]
        buildJsonObject { put("value", encrypted?.let(cipher::decrypt).orEmpty()) }
    }

    /** 写入一个键；已存在则覆盖。 */
    suspend fun set(pluginId: String, key: String, value: String): JsonElement =
        withContext(Dispatchers.IO) {
            requireActive(pluginId)
            val name = requireKey(key)
            if (value.toByteArray(Charsets.UTF_8).size > MAX_VALUE_BYTES) {
                throw PluginHostException("TOO_LARGE", "secret value too large")
            }
            writeMutex.withLock {
                val file = fileFor(pluginId)
                val values = load(file)
                values[name] = cipher.encrypt(value)
                save(file, values)
            }
            buildJsonObject { put("ok", true) }
        }

    /** 删除一个键；键不存在也算成功，不写盘。 */
    suspend fun delete(pluginId: String, key: String): JsonElement = withContext(Dispatchers.IO) {
        requireActive(pluginId)
        val name = requireKey(key)
        writeMutex.withLock {
            val file = fileFor(pluginId)
            val values = load(file)
            if (values.remove(name) != null) save(file, values)
        }
        buildJsonObject { put("ok", true) }
    }

    /** 列出全部键名（按字典序），不返回值。 */
    suspend fun list(pluginId: String): JsonElement = withContext(Dispatchers.IO) {
        requireActive(pluginId)
        val keys = load(fileFor(pluginId)).keys.sorted()
        buildJsonObject { put("keys", JsonArray(keys.map { JsonPrimitive(it) })) }
    }

    private fun requireActive(pluginId: String) {
        pluginManager.ensureLoaded()
        pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    private fun requireKey(key: String): String {
        if (!KEY_PATTERN.matches(key)) throw PluginHostException("PARAM_INVALID", "invalid secret key")
        return key
    }

    private fun fileFor(pluginId: String): File = File(rootDir, "$pluginId.json")

    /** 文件不存在或内容损坏时按空处理；只保留合法键名，手工改坏的文件不会带出非法键。 */
    private fun load(file: File): MutableMap<String, String> {
        if (!file.isFile) return mutableMapOf()
        val parsed = try {
            JSON.decodeFromString<SecretFile>(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            FileLogger.e(TAG, "插件密钥文件无法读取或解析，按空处理: ${file.name}", e)
            return mutableMapOf()
        }
        if (parsed.schema != SECRET_SCHEMA) {
            FileLogger.w(TAG, "插件密钥文件版本不支持，按空处理: ${file.name}")
            return mutableMapOf()
        }
        return parsed.values.filterKeys { KEY_PATTERN.matches(it) }.toMutableMap()
    }

    private fun save(file: File, values: Map<String, String>) {
        val dir = file.parentFile
            ?: throw PluginHostException("STORAGE_FAILED", "secret store directory is unavailable")
        try {
            dir.mkdirs()
            val tmp = File(dir, "${file.name}.tmp")
            tmp.outputStream().use { out ->
                out.write(JSON.encodeToString(SecretFile(values = values)).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            FileLogger.e(TAG, "写入插件密钥失败: ${file.name}", e)
            throw PluginHostException("STORAGE_FAILED", "无法写入密钥存储")
        }
    }

    private companion object {
        const val TAG = "PluginSecretStore"
        const val SECRETS_DIR = "plugin-secrets"
        const val MAX_VALUE_BYTES = 64 * 1024
        val KEY_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
