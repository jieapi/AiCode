package com.aicode.feature.plugin.runtime

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.IsolateTerminatedException
import androidx.javascriptengine.JavaScriptException
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.MemoryLimitExceededException
import androidx.javascriptengine.SandboxDeadException
import androidx.javascriptengine.SandboxUnsupportedException
import com.aicode.R
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** 插件运行时错误；[code] 供工具结果与日志使用，message 已本地化。 */
class PluginRuntimeException(message: String, val code: String) : Exception(message)

/**
 * 插件 JavaScript 运行时。
 *
 * 每个插件一个 isolate，共用同一个 [JavaScriptSandbox]（官方限制：每个 App 同时只能有一个
 * sandbox 进程）。插件入口在加载时执行一次，之后通过宿主注入的 `__aicodeInvoke` 调用
 * `globalThis.aicode.handlers[handler]`。参数以转义后的 JS 字符串传入，避免直接拼接代码。
 *
 * 这不是强隔离：isolate 之间是弱安全边界，sandbox 进程崩溃会影响全部插件；具体能否终止
 * 运行中的求值取决于设备 WebView 是否支持 JS_FEATURE_ISOLATE_TERMINATION。
 */
@Singleton
class PluginRuntimeManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private data class IsolateRuntime(
        val key: String,
        val isolate: JavaScriptIsolate,
        val asyncHandlerSupported: Boolean,
        val mutex: Mutex = Mutex()
    )

    private val sandboxMutex = Mutex()
    private val creationMutex = Mutex()
    private val instancesLock = Any()
    private val instances = mutableMapOf<String, IsolateRuntime>()

    @Volatile
    private var sandbox: JavaScriptSandbox? = null

    /** 设备是否支持 JavaScriptEngine；不支持时插件工具不注册，避免模型看到一个必然失败的工具。 */
    fun isSupported(): Boolean = runCatching { JavaScriptSandbox.isSupported() }.getOrDefault(false)

    suspend fun invoke(
        pluginId: String,
        version: String,
        entryFile: File,
        handler: String,
        args: JsonObject
    ): JsonElement {
        val instance = getOrCreate(pluginId, version, entryFile)
        return instance.mutex.withLock {
            val script = "globalThis.__aicodeInvoke(${jsStringLiteral(handler)}, ${jsStringLiteral(args.toString())})"
            val raw = try {
                withTimeout(TIMEOUT_MS) {
                    instance.isolate.evaluateJavaScriptAsync(script).await()
                }
            } catch (e: TimeoutCancellationException) {
                closeInstance(pluginId, instance)
                throw PluginRuntimeException(context.getString(R.string.plugin_runtime_timeout), "PLUGIN_TIMEOUT")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw handleEvaluationFailure(pluginId, instance, e)
            }
            parseResult(raw)
        }
    }

    /** 插件停用、卸载或不再生效时释放它的 isolate。 */
    fun closePlugin(pluginId: String) {
        val instance = synchronized(instancesLock) { instances.remove(pluginId) } ?: return
        runCatching { instance.isolate.close() }
    }

    private suspend fun getOrCreate(pluginId: String, version: String, entryFile: File): IsolateRuntime {
        val key = "$pluginId@$version"
        synchronized(instancesLock) {
            instances[pluginId]?.let { if (it.key == key) return it }
        }
        return creationMutex.withLock {
            synchronized(instancesLock) {
                instances[pluginId]?.let { if (it.key == key) return@withLock it }
            }
            createIsolate(pluginId, key, entryFile)
        }
    }

    private suspend fun createIsolate(pluginId: String, key: String, entryFile: File): IsolateRuntime {
        val sandbox = ensureSandbox()
        val asyncHandlerSupported = sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN)
        val params = IsolateStartupParameters().apply {
            if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
                setMaxHeapSizeBytes(MAX_HEAP_BYTES)
            }
            setMaxEvaluationReturnSizeBytes(MAX_RETURN_BYTES)
        }
        val isolate = try {
            sandbox.createIsolate(params)
        } catch (e: SandboxUnsupportedException) {
            throw PluginRuntimeException(
                context.getString(R.string.plugin_runtime_unsupported),
                "PLUGIN_RUNTIME_UNSUPPORTED"
            )
        }

        try {
            val source = withContext(Dispatchers.IO) { readEntry(entryFile) }
            withTimeout(ENTRY_TIMEOUT_MS) { isolate.evaluateJavaScriptAsync(source).await() }
            withTimeout(ENTRY_TIMEOUT_MS) {
                isolate.evaluateJavaScriptAsync(bootstrapScript(asyncHandlerSupported)).await()
            }
        } catch (e: TimeoutCancellationException) {
            runCatching { isolate.close() }
            throw PluginRuntimeException(
                context.getString(R.string.plugin_runtime_timeout),
                "PLUGIN_TIMEOUT"
            )
        } catch (e: CancellationException) {
            runCatching { isolate.close() }
            throw e
        } catch (e: PluginRuntimeException) {
            runCatching { isolate.close() }
            throw e
        } catch (e: Throwable) {
            runCatching { isolate.close() }
            throw handleEvaluationFailure(pluginId, IsolateRuntime(key, isolate, asyncHandlerSupported), e)
        }

        val created = IsolateRuntime(key, isolate, asyncHandlerSupported)
        val replaced = synchronized(instancesLock) {
            val existing = instances[pluginId]
            if (existing != null && existing.key == key) {
                existing
            } else {
                instances[pluginId] = created
                existing
            }
        }
        if (replaced != null && replaced.key == key) {
            runCatching { isolate.close() }
            return replaced
        }
        replaced?.let { runCatching { it.isolate.close() } }
        return created
    }

    private suspend fun ensureSandbox(): JavaScriptSandbox {
        if (!isSupported()) {
            throw PluginRuntimeException(
                context.getString(R.string.plugin_runtime_unsupported),
                "PLUGIN_RUNTIME_UNSUPPORTED"
            )
        }
        return sandboxMutex.withLock {
            sandbox ?: try {
                withTimeout(SANDBOX_TIMEOUT_MS) {
                    JavaScriptSandbox.createConnectedInstanceAsync(context).await()
                }.also { sandbox = it }
            } catch (e: TimeoutCancellationException) {
                throw PluginRuntimeException(
                    context.getString(R.string.plugin_runtime_failed),
                    "PLUGIN_RUNTIME_FAILED"
                )
            } catch (e: SandboxUnsupportedException) {
                throw PluginRuntimeException(
                    context.getString(R.string.plugin_runtime_unsupported),
                    "PLUGIN_RUNTIME_UNSUPPORTED"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw PluginRuntimeException(
                    context.getString(R.string.plugin_runtime_failed),
                    "PLUGIN_RUNTIME_FAILED"
                )
            }
        }
    }

    private suspend fun handleEvaluationFailure(
        pluginId: String,
        instance: IsolateRuntime,
        error: Throwable
    ): PluginRuntimeException {
        FileLogger.w(TAG, "插件运行时调用失败: $pluginId", error)
        return when (error) {
            is PluginRuntimeException -> error
            is SandboxDeadException -> {
                closeInstance(pluginId, instance)
                resetSandbox()
                PluginRuntimeException(
                    context.getString(R.string.plugin_runtime_terminated),
                    "PLUGIN_RUNTIME_TERMINATED"
                )
            }
            is MemoryLimitExceededException, is IsolateTerminatedException -> {
                closeInstance(pluginId, instance)
                PluginRuntimeException(
                    context.getString(R.string.plugin_runtime_terminated),
                    "PLUGIN_RUNTIME_TERMINATED"
                )
            }
            is JavaScriptException -> PluginRuntimeException(
                error.message ?: context.getString(R.string.plugin_runtime_failed),
                "PLUGIN_HANDLER_ERROR"
            )
            else -> PluginRuntimeException(
                context.getString(R.string.plugin_runtime_failed),
                "PLUGIN_RUNTIME_FAILED"
            )
        }
    }

    private suspend fun resetSandbox() {
        sandboxMutex.withLock {
            runCatching { sandbox?.close() }
            sandbox = null
        }
        synchronized(instancesLock) {
            instances.values.forEach { runCatching { it.isolate.close() } }
            instances.clear()
        }
    }

    private fun closeInstance(pluginId: String, instance: IsolateRuntime) {
        synchronized(instancesLock) {
            if (instances[pluginId] === instance) instances.remove(pluginId)
        }
        runCatching { instance.isolate.close() }
    }

    private fun readEntry(file: File): String {
        if (!file.isFile) {
            throw PluginRuntimeException(context.getString(R.string.plugin_runtime_failed), "PLUGIN_RUNTIME_FAILED")
        }
        if (file.length() > MAX_ENTRY_BYTES) {
            throw PluginRuntimeException(context.getString(R.string.plugin_runtime_failed), "PLUGIN_RUNTIME_FAILED")
        }
        return file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
    }

    private fun bootstrapScript(async: Boolean): String {
        val call = if (async) {
            "const result = await fn(JSON.parse(argsJson));"
        } else {
            """
            const result = fn(JSON.parse(argsJson));
            if (result && typeof result.then === "function") throw new Error("ASYNC_HANDLER_UNSUPPORTED");
            """.trimIndent()
        }
        val declaration = if (async) {
            "globalThis.__aicodeInvoke = async function(name, argsJson) {"
        } else {
            "globalThis.__aicodeInvoke = function(name, argsJson) {"
        }
        return """
            $declaration
              try {
                const api = globalThis.aicode;
                if (!api || !api.handlers) throw new Error("PLUGIN_API_MISSING");
                const fn = api.handlers[name];
                if (typeof fn !== "function") throw new Error("HANDLER_NOT_FOUND");
                $call
                return JSON.stringify({ ok: true, result: result === undefined ? null : result });
              } catch (e) {
                return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
              }
            };
        """.trimIndent()
    }

    private fun parseResult(raw: String?): JsonElement {
        val obj = raw?.let {
            runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
        } ?: throw PluginRuntimeException(
            context.getString(R.string.plugin_invalid_result),
            "PLUGIN_INVALID_RESULT"
        )
        val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (!ok) {
            val message = (obj["error"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.plugin_handler_error)
            throw PluginRuntimeException(message, "PLUGIN_HANDLER_ERROR")
        }
        return obj["result"] ?: JsonNull
    }

    private fun jsStringLiteral(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (c < ' ') {
                    append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    private companion object {
        const val TAG = "PluginRuntime"
        const val TIMEOUT_MS = 30_000L
        const val SANDBOX_TIMEOUT_MS = 15_000L
        const val ENTRY_TIMEOUT_MS = 15_000L
        const val MAX_HEAP_BYTES = 64L * 1024 * 1024
        const val MAX_RETURN_BYTES = 1_000_000
        const val MAX_ENTRY_BYTES = 512L * 1024
    }
}
