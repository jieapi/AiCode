package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.overlay.PluginOverlayException
import com.aicode.feature.plugin.overlay.PluginOverlayManager
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** 宿主 API 的传输来源；来源决定可见能力和模型句柄的生命周期归属。 */
enum class PluginHostApiOrigin {
    PAGE,
    PROCESS,
    OVERLAY
}

/**
 * 页面、插件进程和浮层共用的宿主能力分发器。
 *
 * 各传输桥只负责协议编解码、生命周期和自身的窄权限能力；文件、会话、模型、工具、终端等
 * 公共能力在这里完成方法归一化、参数组装、业务服务调用和错误码转换。
 */
@Singleton
class PluginHostApiDispatcher @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val homeResolver: PathHomeResolver,
    private val commandEngine: CommandEngine,
    private val notifier: PluginNotifier,
    private val terminalRunner: PluginTerminalRunner,
    private val shizukuRunner: PluginShizukuRunner,
    private val tipManager: PluginTipManager,
    private val overlayManager: PluginOverlayManager,
    private val chatService: PluginChatService,
    private val modelService: PluginModelService,
    private val providerService: PluginProviderService,
    private val secretStore: PluginSecretStore,
    private val systemService: PluginSystemService,
    private val introspectionService: PluginIntrospectionService,
    private val toolService: PluginToolService,
    private val permissionService: PluginPermissionService,
    private val httpService: PluginHttpService,
    private val portService: PluginPortService,
    private val workspaceHostService: PluginWorkspaceHostService,
    private val externalSessionHostService: ExternalSessionHostService
) {
    suspend fun dispatch(
        pluginId: String,
        method: String,
        params: JsonObject,
        origin: PluginHostApiOrigin,
        workspaceRoot: () -> String,
        modelOrigin: String = origin.name.lowercase()
    ): JsonElement {
        try {
            return dispatchInternal(pluginId, method, params, origin, workspaceRoot, modelOrigin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PluginHostException) {
            throw e
        } catch (e: PluginProcessException) {
            throw PluginHostException(e.code, e.message)
        } catch (e: PluginOverlayException) {
            throw PluginHostException(e.code, e.message.orEmpty())
        } catch (e: PluginTerminalException) {
            throw PluginHostException(e.code, e.message)
        } catch (e: PluginShizukuException) {
            throw PluginHostException(e.code, e.message.orEmpty())
        } catch (e: PluginTipException) {
            throw PluginHostException(e.code, e.message.orEmpty())
        }
    }

    private suspend fun dispatchInternal(
        pluginId: String,
        method: String,
        params: JsonObject,
        origin: PluginHostApiOrigin,
        workspaceRoot: () -> String,
        modelOrigin: String
    ): JsonElement {
        val name = method.removePrefix("host.")
        if (origin == PluginHostApiOrigin.OVERLAY &&
            name !in setOf("app.isForeground", "permissions.request", "permissions.cancel")
        ) {
            throw PluginHostException("METHOD_UNKNOWN", "method is not available to overlay pages: $method")
        }
        val paths = PluginHostPaths(fileAccess, homeResolver, workspaceRoot, pluginId)
        return when (name) {
            "fs.read" -> paths.read(PluginHostPaths.requireString(params, "path"))
            "fs.write" -> paths.write(
                PluginHostPaths.requireString(params, "path"),
                PluginHostPaths.requireString(params, "content")
            )
            "fs.list" -> paths.list(PluginHostPaths.requireString(params, "path"))
            "fs.delete" -> paths.delete(PluginHostPaths.requireString(params, "path"))
            "fs.stat" -> paths.stat(PluginHostPaths.requireString(params, "path"))
            "fs.mkdir" -> paths.mkdir(PluginHostPaths.requireString(params, "path"))
            "fs.rename" -> paths.rename(
                PluginHostPaths.requireString(params, "from"),
                PluginHostPaths.requireString(params, "to")
            )
            "fs.copy" -> paths.copy(
                PluginHostPaths.requireString(params, "from"),
                PluginHostPaths.requireString(params, "to"),
                (params["overwrite"] as? JsonPrimitive)?.booleanOrNull ?: false
            )
            "fs.append" -> paths.append(
                PluginHostPaths.requireString(params, "path"),
                PluginHostPaths.requireString(params, "content")
            )
            "fs.readBinary" -> paths.readBinary(PluginHostPaths.requireString(params, "path"))
            "fs.writeBinary" -> paths.writeBinary(
                PluginHostPaths.requireString(params, "path"),
                PluginHostPaths.requireString(params, "base64")
            )

            "app.isForeground" -> JsonPrimitive(overlayManager.isAppForeground())
            "app.info" -> systemService.appInfo(pluginId)
            "bash" -> bash(params, workspaceRoot)
            "shizuku.exec" -> shizukuRunner.exec(
                pluginId,
                PluginHostPaths.requireString(params, "command"),
                (params["timeout"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_SHIZUKU_TIMEOUT_SECONDS
            ).toJson()
            "notify" -> processOnly(origin, name) { notifier.notify(pluginId, params) }
            "tip" -> tipManager.show(pluginId, params)

            "overlay.create" -> overlayCreate(pluginId, params)
            "overlay.update" -> overlayManager.update(
                pluginId,
                PluginHostPaths.requireString(params, "overlayId"),
                params
            )
            "overlay.close" -> overlayClose(pluginId, params, origin)
            "overlay.postMessage" -> overlayPostMessage(pluginId, params, origin)

            "terminal.run" -> terminalRun(pluginId, params)
            "terminal.poll" -> terminalPoll(pluginId, params)
            "terminal.stop" -> terminalStop(pluginId, params, close = false)
            "terminal.close" -> terminalStop(pluginId, params, close = true)
            "terminal.write" -> terminalWrite(pluginId, params)
            "terminal.list" -> terminalRunner.list(pluginId)

            "chat.listSessions" -> chatService.listSessions(pluginId, params)
            "chat.createSession" -> chatService.createSession(pluginId, params)
            "chat.listMessages" -> chatService.listMessages(pluginId, params)
            "chat.sendMessage" -> chatService.sendMessage(pluginId, params)
            "chat.setDraft" -> chatService.setDraft(pluginId, params)
            "chat.stop" -> chatService.stop(pluginId, params)
            "chat.openSession" -> chatService.openSession(pluginId, params)
            "chat.getSession" -> chatService.getSession(pluginId, params)
            "chat.renameSession" -> chatService.renameSession(pluginId, params)
            "chat.pinSession" -> chatService.pinSession(pluginId, params)
            "chat.deleteSession" -> chatService.deleteSession(pluginId, params)
            "chat.getRunState" -> chatService.getRunState(pluginId, params)
            "chat.waitForRun" -> chatService.waitForRun(pluginId, params)
            "chat.listApprovals" -> chatService.listApprovals(pluginId, params)
            "chat.resolveToolApproval" -> chatService.resolveToolApproval(pluginId, params)
            "chat.resolveQuestion" -> chatService.resolveQuestion(pluginId, params)
            "chat.resolvePlanApproval" -> chatService.resolvePlanApproval(pluginId, params)

            "model.listProviders" -> modelService.listProviders(pluginId)
            "model.listModels" -> modelService.listModels(pluginId, params)
            "model.create" -> modelService.create(pluginId, params)
            "model.close" -> modelService.close(pluginId, params)
            "model.start" -> modelService.start(pluginId, params, modelOrigin)
            "model.read" -> modelService.read(pluginId, params)
            "model.cancel" -> modelService.cancel(pluginId, params)

            "providers.listModels" -> providerService.listModels(pluginId, params)
            "providers.addModel" -> providerService.addModel(pluginId, params)
            "providers.deleteModel" -> providerService.deleteModel(pluginId, params)
            "providers.setModelContext" -> providerService.setModelContext(pluginId, params)
            "providers.setApiKey" -> providerService.setApiKey(pluginId, params)

            "clipboard.read" -> systemService.clipboardRead(pluginId)
            "clipboard.write" -> systemService.clipboardWrite(
                pluginId,
                PluginHostPaths.requireString(params, "text")
            )
            "share.text" -> systemService.shareText(
                pluginId,
                PluginHostPaths.requireString(params, "text"),
                (params["title"] as? JsonPrimitive)?.contentOrNull
            )
            "ui.openUrl" -> systemService.openUrl(
                pluginId,
                PluginHostPaths.requireString(params, "url")
            )
            "log", "log.write" -> systemService.log(
                pluginId,
                PluginHostPaths.requireString(params, "level"),
                PluginHostPaths.requireString(params, "message"),
                (params["tag"] as? JsonPrimitive)?.contentOrNull
            )

            "secrets.get" -> secretStore.get(pluginId, PluginHostPaths.requireString(params, "key"))
            "secrets.set" -> secretStore.set(
                pluginId,
                PluginHostPaths.requireString(params, "key"),
                PluginHostPaths.requireString(params, "value")
            )
            "secrets.delete" -> secretStore.delete(pluginId, PluginHostPaths.requireString(params, "key"))
            "secrets.list" -> secretStore.list(pluginId)

            "tools.list" -> introspectionService.listTools(
                pluginId,
                includeSchema = (params["includeSchema"] as? JsonPrimitive)?.booleanOrNull ?: false
            )
            "permissions.request" -> permissionService.request(pluginId, params)
            "permissions.cancel" -> permissionService.cancel(pluginId, params)
            "tools.execute" -> toolService.execute(pluginId, params)
            "tools.pending" -> toolService.pending(pluginId, params)
            "tools.resolve" -> toolService.resolve(pluginId, params)
            "mcp.status" -> introspectionService.mcpStatus(pluginId)
            "http.request" -> httpService.request(pluginId, params)

            "ports.forward" -> processOnly(origin, name) { portService.forward(pluginId, params) }
            "ports.close" -> processOnly(origin, name) { portService.close(pluginId, params) }
            "ports.list" -> processOnly(origin, name) { portService.list(pluginId) }
            "workspaces.upsert" -> processOnly(origin, name) { workspaceHostService.upsert(pluginId, params) }
            "workspaces.remove" -> processOnly(origin, name) { workspaceHostService.remove(pluginId, params) }
            "workspaces.set" -> processOnly(origin, name) { workspaceHostService.set(pluginId, params) }
            "sessions.upsert" -> processOnly(origin, name) { externalSessionHostService.upsert(pluginId, params) }
            "sessions.remove" -> processOnly(origin, name) { externalSessionHostService.remove(pluginId, params) }
            "sessions.replaceAll" -> processOnly(origin, name) { externalSessionHostService.replaceAll(pluginId, params) }
            "sessions.setLoading" -> processOnly(origin, name) { externalSessionHostService.setLoading(pluginId, params) }
            "sessions.setError" -> processOnly(origin, name) { externalSessionHostService.setError(pluginId, params) }
            "sessions.setThinking" -> processOnly(origin, name) { externalSessionHostService.setThinking(pluginId, params) }

            else -> throw PluginHostException("METHOD_UNKNOWN", "unknown host method: $method")
        }
    }

    private suspend fun bash(params: JsonObject, workspaceRoot: () -> String): JsonObject {
        val command = PluginHostPaths.requireString(params, "command")
        if (command.isBlank()) throw PluginHostException("PARAM_INVALID", "empty command")
        if (command.length > PluginHostPaths.MAX_COMMAND_CHARS) {
            throw PluginHostException("PARAM_INVALID", "command too long")
        }
        val timeoutSeconds = (params["timeout"] as? JsonPrimitive)?.longOrNull
            ?: PluginHostPaths.DEFAULT_BASH_TIMEOUT_SECONDS
        val timeoutMs = timeoutSeconds.coerceIn(1L, PluginHostPaths.MAX_BASH_TIMEOUT_SECONDS) * 1000L
        val result = commandEngine.runCommandSyncWithExit(command, workspaceRoot(), timeoutMs)
        return buildJsonObject {
            put("output", result.output)
            put("exitCode", result.exitCode)
            put("truncated", result.outputTruncated)
        }
    }

    private suspend fun overlayCreate(pluginId: String, params: JsonObject): JsonElement =
        overlayManager.create(pluginId, (params["surface"] as? JsonPrimitive)?.contentOrNull, params)

    private fun overlayClose(
        pluginId: String,
        params: JsonObject,
        origin: PluginHostApiOrigin
    ): JsonElement {
        val overlayId = (params["overlayId"] as? JsonPrimitive)?.contentOrNull
        val surfaceId = (params["surface"] as? JsonPrimitive)?.contentOrNull
        return when {
            !overlayId.isNullOrBlank() -> overlayManager.close(pluginId, overlayId)
            origin == PluginHostApiOrigin.PROCESS && !surfaceId.isNullOrBlank() ->
                overlayManager.closeBySurface(pluginId, surfaceId)
            else -> throw PluginHostException("PARAM_INVALID", "overlayId is required")
        }
    }

    private fun overlayPostMessage(
        pluginId: String,
        params: JsonObject,
        origin: PluginHostApiOrigin
    ): JsonElement {
        val overlayId = (params["overlayId"] as? JsonPrimitive)?.contentOrNull
        val surfaceId = (params["surface"] as? JsonPrimitive)?.contentOrNull
        val payload = params["payload"] ?: kotlinx.serialization.json.JsonNull
        return when {
            !overlayId.isNullOrBlank() -> overlayManager.postMessage(pluginId, overlayId, payload)
            origin == PluginHostApiOrigin.PROCESS && !surfaceId.isNullOrBlank() ->
                overlayManager.postMessageBySurface(pluginId, surfaceId, payload)
            else -> throw PluginHostException("PARAM_INVALID", "overlayId is required")
        }
    }

    private suspend fun terminalRun(pluginId: String, params: JsonObject): JsonObject {
        val command = PluginHostPaths.requireString(params, "command").trim()
        val title = (params["title"] as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_TERMINAL_TITLE_CHARS)
        val tabId = terminalRunner.start(pluginId, command, title)
        return buildJsonObject {
            put("tabId", tabId)
            put("state", "running")
        }
    }

    private suspend fun terminalPoll(pluginId: String, params: JsonObject): JsonObject {
        val tabId = PluginHostPaths.requireString(params, "tabId")
        val offset = (params["offset"] as? JsonPrimitive)?.longOrNull ?: 0L
        val poll = terminalRunner.poll(pluginId, tabId, offset)
        return buildJsonObject {
            put("output", poll.output)
            put("offset", poll.offset)
            put("state", poll.state)
            poll.exitCode?.let { put("exitCode", it) }
            put("truncated", poll.truncated)
        }
    }

    private suspend fun terminalStop(pluginId: String, params: JsonObject, close: Boolean): JsonObject {
        val tabId = PluginHostPaths.requireString(params, "tabId")
        if (close) terminalRunner.close(pluginId, tabId) else terminalRunner.stop(pluginId, tabId)
        return buildJsonObject { put("ok", true) }
    }

    private suspend fun terminalWrite(pluginId: String, params: JsonObject): JsonObject {
        val tabId = PluginHostPaths.requireString(params, "tabId")
        val text = PluginHostPaths.requireString(params, "text")
        val appendNewline = (params["appendNewline"] as? JsonPrimitive)?.booleanOrNull ?: true
        val written = terminalRunner.write(pluginId, tabId, text, appendNewline)
        return buildJsonObject { put("ok", written) }
    }

    private suspend fun <T> processOnly(
        origin: PluginHostApiOrigin,
        method: String,
        block: suspend () -> T
    ): T {
        if (origin != PluginHostApiOrigin.PROCESS) {
            throw PluginHostException("METHOD_UNKNOWN", "method is not available to this host API: $method")
        }
        return block()
    }

    private fun PluginShizukuResult.toJson(): JsonObject = buildJsonObject {
        put("output", output)
        put("exitCode", exitCode)
        put("truncated", truncated)
    }

    private companion object {
        const val MAX_TERMINAL_TITLE_CHARS = 120
        const val DEFAULT_SHIZUKU_TIMEOUT_SECONDS = 120L
    }
}
