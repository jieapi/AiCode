package com.aicode.feature.agent.presentation.component

import org.jsoup.parser.Parser
import java.util.Base64

/**
 * 在把文本交给 Markdown 渲染器前做的轻量预处理：
 *
 * 1. 抽取 LaTeX 数学（`$$...$$` 块级、`$...$` 行内），编码为特殊 scheme 的 Markdown 图片链接，
 *    交由 [MathImageTransformer] 用 jlatexmath 渲染。
 * 2. 解码 HTML 实体，并让内嵌 HTML 标签以字面文本展示——聊天流不渲染 HTML，
 *    也不做 HTML→Markdown 的语法映射，标签原样可见即可（见 [processNormal] 的 ZWSP 处理）。
 *
 * 所有处理都跳过围栏代码块与行内代码，避免破坏代码样例里的 `$` / `<` 等字符。
 */
internal object MarkdownPreprocessor {

    // 进程级结果缓存：process 会在 item 每次滚入视口时被调（上层只有 remember(text)，滚出
    // 被 dispose 后 remember 归零）。含数学定界符的文本要跑正则，快速 fling 时反复重跑拖慢主线程。
    // 这里按原文做进程级 LRU，同一段只处理一次。
    private val cache = TextBudgetCache<String, String>(256, 512 * 1024) { key, value ->
        key.length.toLong() + value.length
    }

    fun process(raw: String, cacheResult: Boolean = true): String {
        if (raw.isEmpty()) return raw
        // 无需处理的快速路径：既没有 HTML 标签、没有 $ 或 \( / \[ 数学定界符，也没有 & 实体
        if (!raw.contains('<') && !raw.contains('$') && !raw.contains('\\') && !raw.contains('&')) return raw

        if (cacheResult) cache[raw]?.let { return it }

        val sb = StringBuilder(raw.length + 32)
        for (seg in splitPreservingCode(raw)) {
            when (seg.kind) {
                SegmentKind.NORMAL -> sb.append(processNormal(seg.text))
                // 行内代码也会被渲染器按 AST 遍历（CODE_SPAN 的子节点），HTML_TAG 同样会被丢弃，
                // 故与普通文本一致做同一个 `<` 中和；围栏代码块走原文渲染，保持原样。
                SegmentKind.INLINE_CODE -> sb.append(neutralizeHtmlOpenBracket(seg.text))
                SegmentKind.FENCE -> sb.append(seg.text)
            }
        }
        val result = sb.toString()
        if (cacheResult) cache.put(raw, result)
        return result
    }

    private enum class SegmentKind { NORMAL, INLINE_CODE, FENCE }

    private data class Segment(val kind: SegmentKind, val text: String)

    /** 扫描文本，把围栏代码块与行内代码切成 isCode=true 的段原样保留，其余为普通文本段。 */
    private fun splitPreservingCode(text: String): List<Segment> {
        val result = ArrayList<Segment>()
        val normal = StringBuilder()
        val n = text.length
        var i = 0
        var atLineStart = true

        fun flushNormal() {
            if (normal.isNotEmpty()) {
                result.add(Segment(SegmentKind.NORMAL, normal.toString()))
                normal.clear()
            }
        }

        while (i < n) {
            val c = text[i]

            // 围栏代码块：行首连续 >=3 个 ` 或 ~
            if (atLineStart && (c == '`' || c == '~')) {
                var j = i
                while (j < n && text[j] == c) j++
                if (j - i >= 3) {
                    val fenceLen = j - i
                    var lineEnd = j
                    while (lineEnd < n && text[lineEnd] != '\n') lineEnd++
                    var end = n
                    var lineStart = if (lineEnd < n) lineEnd + 1 else n
                    var k = lineStart
                    while (k <= n) {
                        if (k == n || text[k] == '\n') {
                            var p = lineStart
                            while (p < k && text[p] == ' ') p++
                            var q = p
                            while (q < k && text[q] == c) q++
                            if (q - p >= fenceLen) {
                                var rest = q
                                while (rest < k && text[rest] == ' ') rest++
                                if (rest == k) { end = k; break }
                            }
                            lineStart = k + 1
                        }
                        k++
                    }
                    flushNormal()
                    result.add(Segment(SegmentKind.FENCE, text.substring(i, end)))
                    i = end
                    atLineStart = i < n && text[i] == '\n'
                    continue
                }
            }

            // 行内代码：同长度 ` 序列包裹，且不跨行
            if (c == '`') {
                var j = i
                while (j < n && text[j] == '`') j++
                val tickLen = j - i
                var k = j
                var found = -1
                while (k < n && text[k] != '\n') {
                    if (text[k] == '`') {
                        var m = k
                        while (m < n && text[m] == '`') m++
                        if (m - k == tickLen) { found = m; break }
                        k = m
                    } else k++
                }
                if (found >= 0) {
                    flushNormal()
                    result.add(Segment(SegmentKind.INLINE_CODE, text.substring(i, found)))
                    i = found
                    atLineStart = false
                    continue
                }
            }

            normal.append(c)
            atLineStart = c == '\n'
            i++
        }
        flushNormal()
        return result
    }

    private fun processNormal(input: String): String {
        var s = extractMath(input)
        s = Parser.unescapeEntities(s, false)
        return neutralizeHtmlOpenBracket(s)
    }

    /**
     * 聊天流不渲染 HTML：在 `<` 后插入零宽空格（U+200B），标签就不会被解析成 HTML_TAG 节点。
     * 渲染器对 HTML_TAG 没有处理分支（直接丢弃），插字符后 `<` 退化为普通 LT 标记、会照原样显示，
     * 于是 `<b>` 就能以字面文本 `<b>` 呈现。用 ZWSP 而非 U+2060，后者在部分机型字体里会渲染成豆腐块。
     */
    private fun neutralizeHtmlOpenBracket(s: String): String = s.replace("<", "<\u200B")

    // ---------------------------------------------------------------------------------------------
    // 数学公式
    // ---------------------------------------------------------------------------------------------

    private val BLOCK_MATH_DOLLAR = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL)
    private val BLOCK_MATH_BRACKET = Regex("""\\\[(.+?)\\\]""", RegexOption.DOT_MATCHES_ALL)

    // 行内：定界符内侧首尾非空白、内容不含换行与 $，规避 "$5 ... $10" 这类货币误命中（非空白锚点）
    private val INLINE_MATH_DOLLAR = Regex("""\$(?=\S)([^\n$]*?\S)\$""")
    // LaTeX 标准行内定界符 \( ... \)
    private val INLINE_MATH_PAREN = Regex("""\\\((.+?)\\\)""", RegexOption.DOT_MATCHES_ALL)

    private fun extractMath(input: String): String {
        var s = input
        if (s.contains("$$")) {
            s = BLOCK_MATH_DOLLAR.replace(s) { m ->
                "\n\n" + encodeMathLink(m.groupValues[1].trim(), block = true) + "\n\n"
            }
        }
        if (s.contains("\\[")) {
            s = BLOCK_MATH_BRACKET.replace(s) { m ->
                "\n\n" + encodeMathLink(m.groupValues[1].trim(), block = true) + "\n\n"
            }
        }
        if (s.contains("\\(")) {
            s = INLINE_MATH_PAREN.replace(s) { m ->
                encodeMathLink(m.groupValues[1].trim(), block = false)
            }
        }
        if (s.contains('$')) {
            s = INLINE_MATH_DOLLAR.replace(s) { m ->
                encodeMathLink(m.groupValues[1].trim(), block = false)
            }
        }
        return s
    }

    // ---------------------------------------------------------------------------------------------
    // 数学链接编解码（供 MathImageTransformer 使用）
    // ---------------------------------------------------------------------------------------------

    const val MATH_BLOCK_SCHEME = "aicode-math-block://"
    const val MATH_INLINE_SCHEME = "aicode-math-inline://"

    fun encodeMathLink(latex: String, block: Boolean): String {
        val enc = Base64.getUrlEncoder().withoutPadding().encodeToString(latex.toByteArray(Charsets.UTF_8))
        return "![](${if (block) MATH_BLOCK_SCHEME else MATH_INLINE_SCHEME}$enc)"
    }

    /** 解析数学链接；非数学链接返回 null。返回 (latex, isBlock)。 */
    fun decodeMathLink(link: String): Pair<String, Boolean>? {
        val block = link.startsWith(MATH_BLOCK_SCHEME)
        val inline = link.startsWith(MATH_INLINE_SCHEME)
        if (!block && !inline) return null
        val enc = link.removePrefix(if (block) MATH_BLOCK_SCHEME else MATH_INLINE_SCHEME)
        return try {
            val latex = String(Base64.getUrlDecoder().decode(enc), Charsets.UTF_8)
            latex to block
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
