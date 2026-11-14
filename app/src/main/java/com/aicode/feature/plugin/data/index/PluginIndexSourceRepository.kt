package com.aicode.feature.plugin.data.index

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private val Context.pluginIndexDataStore by preferencesDataStore(
    name = "plugin_index_sources",
    corruptionHandler = preferencesCorruptionHandler
)

/** 一个索引来源。身份由规范化地址派生，改显示名称不会改变来源身份。 */
data class PluginIndexSource(
    val id: String,
    val name: String,
    val indexUrl: String,
    val official: Boolean,
    val enabled: Boolean = true,
    val lastFetchedAt: Long = 0
) {
    val rootUrl: String get() = indexUrl.substringBeforeLast('/') + "/"
}

object PluginIndexDefaults {
    const val OFFICIAL_URL = "https://raw.githubusercontent.com/jieapi/aicode-plugins/main/index.json"
    const val OFFICIAL_NAME = "AiCode Plugins"
    const val OFFICIAL_ID = "aicode.official"

    val official = PluginIndexSource(
        id = OFFICIAL_ID,
        name = OFFICIAL_NAME,
        indexUrl = OFFICIAL_URL,
        official = true
    )
}

@Serializable
private data class StoredSource(
    val id: String,
    val name: String,
    val indexUrl: String,
    val enabled: Boolean = true,
    val lastFetchedAt: Long = 0
)

@Serializable
private data class StoredSources(val sources: List<StoredSource> = emptyList())

/** 自定义索引来源的持久化；官方来源永远由宿主常量提供，不能被自定义来源顶替。 */
@Singleton
class PluginIndexSourceRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    val sources: Flow<List<PluginIndexSource>> = context.pluginIndexDataStore.data.map { preferences ->
        val stored = preferences[KEY]?.let { raw ->
            runCatching { PluginIndexJson.decodeFromString<StoredSources>(raw) }.getOrNull()
        } ?: StoredSources()
        buildList {
            add(
                PluginIndexDefaults.official.copy(
                    lastFetchedAt = stored.sources.firstOrNull { it.id == PluginIndexDefaults.OFFICIAL_ID }?.lastFetchedAt ?: 0
                )
            )
            stored.sources
                .filter { it.id != PluginIndexDefaults.OFFICIAL_ID }
                .map { it.toModel() }
                .forEach(::add)
        }
    }

    suspend fun add(name: String, indexUrl: String): Result<PluginIndexSource> = runCatching {
        val normalizedName = name.trim().ifEmpty { throw IllegalArgumentException("name") }
        val normalizedUrl = normalizeIndexUrl(indexUrl)
        val id = sourceId(normalizedUrl)
        val current = sources.first()
        if (current.any { it.indexUrl == normalizedUrl }) throw IllegalArgumentException("duplicate")
        val source = PluginIndexSource(id = id, name = normalizedName, indexUrl = normalizedUrl, official = false)
        updateStored { stored ->
            stored.copy(sources = stored.sources.filterNot { it.id == id } + source.toStored())
        }
        source
    }

    suspend fun update(id: String, name: String, indexUrl: String, enabled: Boolean): Result<Unit> = runCatching {
        if (id == PluginIndexDefaults.OFFICIAL_ID) throw IllegalArgumentException("official")
        val normalizedName = name.trim().ifEmpty { throw IllegalArgumentException("name") }
        val normalizedUrl = normalizeIndexUrl(indexUrl)
        val newId = sourceId(normalizedUrl)
        if (newId != id) throw IllegalArgumentException("url-changed")
        updateStored { stored ->
            stored.copy(
                sources = stored.sources.map { source ->
                    if (source.id == id) {
                        source.copy(name = normalizedName, indexUrl = normalizedUrl, enabled = enabled)
                    } else {
                        source
                    }
                }
            )
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        if (id == PluginIndexDefaults.OFFICIAL_ID) return
        updateStored { stored ->
            stored.copy(sources = stored.sources.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
    }

    suspend fun remove(id: String) {
        if (id == PluginIndexDefaults.OFFICIAL_ID) return
        updateStored { stored -> stored.copy(sources = stored.sources.filterNot { it.id == id }) }
    }

    /** 记录最近一次成功拉取时间，仅用于离线展示。 */
    suspend fun markFetched(id: String, fetchedAt: Long) {
        updateStored { stored ->
            val exists = stored.sources.any { it.id == id }
            if (!exists) {
                stored.copy(
                    sources = stored.sources + StoredSource(
                        id = id,
                        name = PluginIndexDefaults.OFFICIAL_NAME,
                        indexUrl = PluginIndexDefaults.OFFICIAL_URL,
                        lastFetchedAt = fetchedAt
                    )
                )
            } else {
                stored.copy(
                    sources = stored.sources.map {
                        if (it.id == id) it.copy(lastFetchedAt = fetchedAt) else it
                    }
                )
            }
        }
    }

    private suspend fun updateStored(block: (StoredSources) -> StoredSources) {
        context.pluginIndexDataStore.edit { preferences ->
            val current = preferences[KEY]?.let { raw ->
                runCatching { PluginIndexJson.decodeFromString<StoredSources>(raw) }.getOrNull()
            } ?: StoredSources()
            preferences[KEY] = PluginIndexJson.encodeToString(block(current))
        }
    }

    private fun StoredSource.toModel() = PluginIndexSource(
        id = id,
        name = name,
        indexUrl = indexUrl,
        official = false,
        enabled = enabled,
        lastFetchedAt = lastFetchedAt
    )

    private fun PluginIndexSource.toStored() = StoredSource(
        id = id,
        name = name,
        indexUrl = indexUrl,
        enabled = enabled,
        lastFetchedAt = lastFetchedAt
    )

    private companion object {
        val KEY = stringPreferencesKey("sources_json")

        fun normalizeIndexUrl(raw: String): String {
            val text = raw.trim()
            val uri = URI(text)
            require(uri.scheme == "https") { "scheme" }
            require(uri.host != null) { "host" }
            require(uri.userInfo == null) { "userInfo" }
            require(!text.contains('#')) { "fragment" }
            return text
        }

        fun sourceId(indexUrl: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(indexUrl.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(24)
        }
    }
}
