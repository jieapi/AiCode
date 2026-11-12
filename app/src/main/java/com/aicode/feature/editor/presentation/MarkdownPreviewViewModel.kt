package com.aicode.feature.editor.presentation

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceResponse
import androidx.lifecycle.ViewModel
import androidx.webkit.WebViewAssetLoader
import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.file.Paths
import javax.inject.Inject

/**
 * Markdown 预览页（WebView 渲染）的后端：组装渲染 HTML，并为其提供图片资源。
 *
 * 预览页资源分两路经 [WebViewAssetLoader] 加载：
 * - `/assets/` 由内置 [WebViewAssetLoader.AssetsPathHandler] 提供渲染所需的 JS/CSS；
 * - `/local/` 由 [LocalAssetPathHandler] 从 [FileAccessProvider] 读取（本地模式直读，远程模式 SFTP 下载），
 *   让 README 这类文档里的相对路径图片在两种执行模式下都能显示。
 *
 * `/local/` 后面的路径是「相对工作区根」的路径（由 [baseUrl] 以被预览文件所在目录为基准拼出），
 * 不把 `~` 放进 URL——`~` 在部分 WebView 版本里会被当作需要特殊处理的字符，导致相对资源解析异常。
 */
@HiltViewModel
class MarkdownPreviewViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider
) : ViewModel() {

    companion object {
        /** [WebViewAssetLoader] 的默认虚拟 host。 */
        const val ASSET_HOST = "appassets.androidplatform.net"

        private const val ASSET_BASE = "https://$ASSET_HOST/assets/plugin-ui/markdown/"
        private const val LOCAL_PREFIX = "/local/"
        private const val TAG = "MarkdownPreview"

        /** 工作区在容器内的根路径，与 [com.aicode.feature.workspace.domain.WorkspacePathMapper.CONTAINER_ROOT] 一致。 */
        private const val WS_ROOT = "~/workspace"

        /** 深色模式下页面底色，与 github-markdown-css 的 dark 主题一致。 */
        private const val BG_DARK = "#0d1117"
        private const val BG_LIGHT = "#ffffff"
    }

    private val template: String by lazy {
        context.assets.open("plugin-ui/markdown/preview.html").bufferedReader().use { it.readText() }
    }

    /** 最近一次渲染使用的深浅色，供 [LocalAssetPathHandler] 渲染跳转到的 Markdown 时沿用。 */
    @Volatile
    private var currentDark: Boolean = false

    val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler(LOCAL_PREFIX, LocalAssetPathHandler())
        .build()

    /** 把 markdown 原文注入模板生成预览 HTML；[dark] 决定套用明/暗两套 GitHub 样式。 */
    suspend fun buildHtml(text: String, dark: Boolean): String = withContext(Dispatchers.IO) {
        buildHtmlInternal(text, dark)
    }

    private fun buildHtmlInternal(text: String, dark: Boolean): String {
        currentDark = dark
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return template
            .replace("__ASSET_BASE__", ASSET_BASE)
            .replace("__MD_CSS__", if (dark) "github-markdown-dark.css" else "github-markdown-light.css")
            .replace("__HL_CSS__", if (dark) "highlight-github-dark.min.css" else "highlight-github.min.css")
            .replace("__BG__", if (dark) BG_DARK else BG_LIGHT)
            .replace("__MD_B64__", b64)
    }

    /**
     * 预览页 baseUrl：以被预览文件所在目录为基准，markdown 里的相对图片路径（如 `docs/a.png`）
     * 会解析到 `/local/<相对工作区根的目录>/docs/a.png`，由 [containerPathOf] 还原成容器路径读取。
     */
    fun baseUrl(path: String): String {
        val dir = relativeDirOf(path)
        val encoded = Uri.encode(dir, "/")
        return if (encoded.isEmpty()) {
            "https://$ASSET_HOST$LOCAL_PREFIX"
        } else {
            "https://$ASSET_HOST$LOCAL_PREFIX$encoded/"
        }
    }

    /**
     * `/local/` 之后的相对路径（相对工作区根）还原为容器路径；解析不出内容时返回 null。
     * 传入的是 WebViewAssetLoader 去掉注册前缀后的 suffix 路径，不带 `/local/`。
     * 归一化把 `..` 收在根内，避免请求跳出工作区。
     */
    fun containerPathOf(relativePath: String): String? {
        val decoded = runCatching { URLDecoder.decode(relativePath, "UTF-8") }.getOrNull() ?: return null
        if (decoded.isEmpty()) return null
        val normalized = runCatching { Paths.get("/$decoded").normalize().toString() }.getOrNull() ?: return null
        val trimmed = normalized.trimStart('/')
        if (trimmed.isEmpty()) return null
        return "$WS_ROOT/$trimmed"
    }

    /** 预览页内某个 URL 指向工作区文件时返回其容器路径；非本地 URL 返回 null。 */
    fun containerPathForUrl(url: Uri): String? {
        if (url.host != ASSET_HOST) return null
        val path = url.path ?: return null
        if (!path.startsWith(LOCAL_PREFIX)) return null
        return containerPathOf(path.removePrefix(LOCAL_PREFIX))
    }

    /** 被预览文件所在目录相对工作区根的路径（无前后斜杠）；不在工作区内时退回工作区根。 */
    private fun relativeDirOf(path: String): String {
        val parent = fileAccess.parentPath(path)?.trimEnd('/') ?: return ""
        return when {
            parent == WS_ROOT -> ""
            parent.startsWith("$WS_ROOT/") -> parent.removePrefix("$WS_ROOT/")
            else -> ""
        }
    }

    private inner class LocalAssetPathHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val containerPath = containerPathOf(path)
            FileLogger.v(TAG, "预览本地资源: $path -> ${containerPath ?: "null"}")
            if (containerPath == null) {
                FileLogger.w(TAG, "预览资源路径无法解析: $path")
                return notFound()
            }
            val bytes = runCatching { fileAccess.readBytes(containerPath) }
                .onFailure { FileLogger.w(TAG, "读取预览资源失败: $containerPath", it) }
                .getOrNull()
            if (bytes == null) return notFound()
            // 预览页里点击本地 Markdown 链接时，直接把目标文件渲染成预览页（浏览器式导航）
            if (isMarkdown(containerPath)) {
                val html = buildHtmlInternal(bytes.toString(Charsets.UTF_8), currentDark)
                return WebResourceResponse(
                    "text/html", "utf-8", 200, "OK", emptyMap(),
                    ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                )
            }
            return WebResourceResponse(mimeOf(containerPath), null, ByteArrayInputStream(bytes))
        }
    }

    private fun isMarkdown(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")

    /** 已命中 `/local/` 但取不到内容：返回 404 而非 null，避免 WebView 当作未拦截而回退去访问网络。 */
    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun mimeOf(path: String): String = when (val ext = path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        // 文本类（含无扩展名的 LICENSE / README 等）按纯文本返回，预览页里能直接显示而不被当成下载
        "txt", "text", "log", "json", "jsonc", "yml", "yaml", "toml", "ini", "conf", "cfg", "properties",
        "xml", "html", "htm", "css", "js", "mjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb",
        "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "swift", "sh", "bash", "zsh", "sql", "gradle",
        "gitignore", "env", "csv" -> "text/plain"
        else -> if (ext.isEmpty()) "text/plain" else "application/octet-stream"
    }
}
