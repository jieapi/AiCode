package com.aicode.feature.plugin.data.index

import com.aicode.feature.plugin.domain.LocalizedText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal val PluginIndexJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = false
}

@Serializable
data class PluginIndexDocument(
    @SerialName("schema_version") val schemaVersion: Int,
    val plugins: List<PluginIndexPlugin> = emptyList()
)

@Serializable
data class PluginIndexPlugin(
    val id: String,
    @Serializable(with = LocalizedTextJsonSerializer::class) val name: LocalizedText,
    @Serializable(with = LocalizedTextJsonSerializer::class) val description: LocalizedText,
    val author: String,
    val repository: String,
    val logo: String? = null,
    val homepage: String? = null,
    val license: String? = null,
    val tags: List<String> = emptyList(),
    val version: String? = null,
    @SerialName("min_app_version") val minAppVersion: String? = null
)

/**
 * 索引里的名称与描述可以写成单一字符串，或语言标签到文本的映射，
 * 与插件清单的本地化文本格式一致；语言标签统一为小写、`-` 分隔。
 */
internal object LocalizedTextJsonSerializer : KSerializer<LocalizedText> {

    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): LocalizedText {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("本地化文本仅支持 JSON")
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!element.isString) throw SerializationException("本地化文本必须是字符串或语言映射")
                LocalizedText.single(element.content.trim())
            }
            is JsonObject -> {
                if (element.isEmpty()) throw SerializationException("本地化文本映射不能为空")
                val values = LinkedHashMap<String, String>()
                element.forEach { (tag, value) ->
                    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw SerializationException("语言映射的值必须是字符串: $tag")
                    val normalized = LocalizedText.normalize(tag)
                    if (values.put(normalized, text.trim()) != null) {
                        throw SerializationException("语言标签重复: $tag")
                    }
                }
                LocalizedText(values)
            }
            else -> throw SerializationException("本地化文本必须是字符串或语言映射")
        }
    }

    override fun serialize(encoder: Encoder, value: LocalizedText) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("本地化文本仅支持 JSON")
        if (value.values.keys == setOf(LocalizedText.DEFAULT_KEY)) {
            output.encodeString(value.values.getValue(LocalizedText.DEFAULT_KEY))
        } else {
            output.encodeJsonElement(JsonObject(value.values.mapValues { JsonPrimitive(it.value) }))
        }
    }
}

object PluginTags {
    const val PROVIDER = "provider"
    const val TOOL = "tool"
    const val SKILL = "skill"
    const val AGENT = "agent"
    const val MCP = "mcp"
    const val UI = "ui"
    const val INTEGRATION = "integration"
    const val OTHER = "other"

    val ALL = setOf(PROVIDER, TOOL, SKILL, AGENT, MCP, UI, INTEGRATION, OTHER)
}
