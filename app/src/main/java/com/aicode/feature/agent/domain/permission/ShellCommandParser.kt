package com.aicode.feature.agent.domain.permission

/**
 * shell 命令的安全静态分析器——授权系统的安全核心。
 *
 * 职责：把一条命令行拆成「顶层段」并分词，同时判断这条命令是否**可被静态判定**。
 * 自动放行只在「可判定 且 每一段都命中 ALLOW 规则」时发生；任何无法静态判定的构造都把命令
 * 降级为「必须弹窗、不可记忆」，避免授权被绕过。
 *
 * 安全约定（务必维持）：
 * - 引号感知：单/双引号内的 `&& || | ;` 等不视为分隔符，不会被错误拆段。
 * - 不可静态判定（[Analysis.analyzable] = false）的触发条件：
 *     · 命令替换 `$(...)`、反引号 `` `...` ``（双引号内仍生效，单引号内不生效）；
 *     · 子shell/分组括号 `(` `)`、进程替换 `<(` `>(`（均以裸 `(` 命中）；
 *     · 输出重定向到**绝对路径**（`> /...`、`>> /...`，含引号包裹的绝对路径）——可绕过 cwd 写到工作区外；
 *       但 `/dev/null`、`/dev/stdout`、`/dev/stderr` 仅丢弃输出或映射回当前进程 stdio，不落盘，视为安全；
 *     · 相对输出目标含变量、通配符、家目录展开或越出 cwd 的 `..` 路径；
 *     · 未闭合的引号。
 *   普通相对重定向视为落在工作区内。fd 复制/关闭（`2>&1`、`>&-`）视为安全。输入重定向 `<` 不触发（读取风险较低）。
 *   重定向算子、fd 前缀（`2>`）、重定向目标与 fd 复制目标不计入分段 token，避免污染命令前缀或触发 `rm` 路径误判。
 * - 匹配按**字面首 token**：`/usr/bin/node` 不会被 `node` 规则命中（宁可多问，不误放）。
 * - 段首的环境赋值（`FOO=bar cmd`）在取程序名/匹配时被跳过，使 `FOO=bar git ...` 仍按 `git` 处理。
 */
object ShellCommandParser {

    /**
     * @param segments 顶层每一段的 token 列表（已去引号、按空白分词；重定向算子与目标不入 token）。
     * @param analyzable 是否可静态判定；false 表示永不自动放行/记忆，只能单次放行。
     * @param blockers 触发「不可静态判定」的具体构造（如 `命令替换 $(...)`、`子 shell 括号`）。
     *   供授权弹窗与拦截原因回显：不告诉 AI 是哪一段出的问题，它只能反复整条重试。
     * @param hasUnsafeOutputRedirect 命令是否包含非空设备/非 fd 复制的实际文件输出重定向（含引号外、
     *   命令替换及反引号内部），用于 AUTO 模式下结合不可静态判定构造做保守拦截。
     */
    data class Analysis(
        val segments: List<List<String>>,
        val analyzable: Boolean,
        val blockers: List<String> = emptyList(),
        val hasUnsafeOutputRedirect: Boolean = false
    )

    private const val NONE = 0
    private const val SINGLE = 1
    private const val DOUBLE = 2

    private const val REDIRECT_NONE = 0
    private const val REDIRECT_INPUT = 1
    private const val REDIRECT_OUTPUT = 2

    private data class SubstFrame(val outerQuote: Int, var parenDepth: Int = 0)

    /** 仅丢弃输出或映射回当前进程 stdio 的安全设备路径：不落盘、不越出工作区。 */
    private val SAFE_ABSOLUTE_REDIRECT_TARGETS = setOf(
        "/dev/null",
        "/dev/stdout",
        "/dev/stderr"
    )

    /** 段首环境赋值形如 `NAME=value`。 */
    private val ENV_ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")

    fun analyze(command: String): Analysis {
        val segments = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        val token = StringBuilder()
        var tokenStarted = false
        var tokenHasQuoteOrEscape = false
        // 触发「不可静态判定」的构造清单：去重后随 Analysis 返回，供上层解释拦截原因。
        val blockers = mutableListOf<String>()
        fun block(reason: String) {
            if (reason !in blockers) blockers.add(reason)
        }
        var hasUnsafeOutputRedirect = false
        var quote = NONE
        // 命令替换 `$(...)` 嵌套栈：保存外层引号与内层括号深度，既正确配对嵌套引号，也不把配对括号误记为子 shell。
        val substStack = ArrayDeque<SubstFrame>()
        fun inSubst(): Boolean = substStack.isNotEmpty()
        // 顶层重定向状态：待目标 token 判定是否安全/绝对路径，且不把重定向目标放进命令参数 token。
        var redirectMode = REDIRECT_NONE
        // 命令替换 `$(...)` 内部的输出重定向状态：独立跟踪重定向目标，避免受外层 token 累积干扰。
        var subRedirectActive = false
        var subRedirectDepth = 0
        var subRedirectStarted = false
        val subRedirectBuf = StringBuilder()

        var i = 0
        val n = command.length

        fun recordOutputRedirectTarget(target: String) {
            if (target in SAFE_ABSOLUTE_REDIRECT_TARGETS) return
            if (target.startsWith("/")) {
                hasUnsafeOutputRedirect = true
                block("输出重定向到绝对路径 $target")
                return
            }
            if (target.contains('$') || target.contains('`') || target.startsWith('~') ||
                target.contains('*') || target.contains('?') || target.contains('[')) {
                hasUnsafeOutputRedirect = true
                block("输出重定向到动态路径 $target")
                return
            }
            var depth = 0
            for (part in target.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> {
                        depth--
                        if (depth < 0) {
                            hasUnsafeOutputRedirect = true
                            block("输出重定向可能越出工作区 $target")
                            return
                        }
                    }
                    else -> depth++
                }
            }
        }

        fun finishSubRedirect() {
            if (!subRedirectActive) return
            if (subRedirectStarted) {
                recordOutputRedirectTarget(subRedirectBuf.toString())
            } else {
                hasUnsafeOutputRedirect = true
            }
            subRedirectActive = false
            subRedirectStarted = false
            subRedirectBuf.setLength(0)
        }

        fun flushToken() {
            if (tokenStarted) {
                val t = token.toString()
                when (redirectMode) {
                    REDIRECT_NONE -> current.add(t)
                    REDIRECT_INPUT -> Unit
                    REDIRECT_OUTPUT -> recordOutputRedirectTarget(t)
                }
                redirectMode = REDIRECT_NONE
                token.setLength(0)
                tokenStarted = false
                tokenHasQuoteOrEscape = false
            }
        }

        fun flushBeforeRedirectOperator() {
            // 紧邻重定向符的未引用纯数字前缀（如 `2>/dev/null`、`2>&1` 中的 `2`）属于 fd 编号，不是命令参数。
            if (redirectMode == REDIRECT_NONE && tokenStarted && !tokenHasQuoteOrEscape && token.all { it in '0'..'9' }) {
                token.setLength(0)
                tokenStarted = false
                tokenHasQuoteOrEscape = false
            } else {
                flushToken()
            }
        }

        fun flushSegment() {
            flushToken()
            if (redirectMode == REDIRECT_OUTPUT) {
                hasUnsafeOutputRedirect = true
            }
            redirectMode = REDIRECT_NONE
            if (current.isNotEmpty()) {
                segments.add(current)
                current = mutableListOf()
            }
        }

        /**
         * 判断 `>&` / `<&` 之后是否为 fd 复制或关闭（如 `2>&1`、`>&2`、`2>&-`），
         * 若是则返回消费后的下标；否则按文件重定向（如 `>& /dev/null`）返回 -1。
         */
        fun consumeFdDupTargetEnd(startAfterAmp: Int): Int {
            var k = startAfterAmp
            while (k < n && (command[k] == ' ' || command[k] == '\t')) k++
            if (k >= n) return -1
            if (command[k] == '-') {
                k++
            } else if (command[k] in '0'..'9') {
                while (k < n && command[k] in '0'..'9') k++
            } else {
                return -1
            }
            val atBoundary = k >= n || command[k].isWhitespace() ||
                command[k] == ';' || command[k] == '&' || command[k] == '|' ||
                command[k] == '>' || command[k] == '<' ||
                command[k] == '(' || command[k] == ')'
            return if (atBoundary) k else -1
        }

        fun consumeBacktick(inDoubleQuote: Boolean) {
            block("反引号命令替换")
            token.append('`')
            tokenStarted = true
            if (subRedirectActive) {
                subRedirectBuf.append('`')
                subRedirectStarted = true
            }
            var j = i + 1
            val inner = StringBuilder()
            while (j < n) {
                val bc = command[j]
                if (bc == '`') {
                    token.append('`')
                    if (subRedirectActive) subRedirectBuf.append('`')
                    j++
                    break
                } else if (bc == '\\' && j + 1 < n) {
                    val nx = command[j + 1]
                    token.append(bc).append(nx)
                    if (subRedirectActive) subRedirectBuf.append(bc).append(nx)
                    if (nx == '$' || nx == '`' || nx == '\\' || (inDoubleQuote && nx == '"')) {
                        inner.append(nx)
                    } else if (nx != '\n') {
                        inner.append(bc).append(nx)
                    }
                    j += 2
                } else {
                    token.append(bc)
                    if (subRedirectActive) subRedirectBuf.append(bc)
                    inner.append(bc)
                    j++
                }
            }
            if (inner.isNotEmpty()) {
                val sub = analyze(inner.toString())
                sub.blockers.forEach { block(it) }
                if (sub.hasUnsafeOutputRedirect) hasUnsafeOutputRedirect = true
            }
            i = j
        }

        while (i < n) {
            val c = command[i]
            when (quote) {
                SINGLE -> {
                    if (c == '\'') {
                        quote = NONE
                        tokenStarted = true
                        tokenHasQuoteOrEscape = true
                        if (subRedirectActive) subRedirectStarted = true
                    } else {
                        token.append(c)
                        tokenStarted = true
                        if (subRedirectActive) {
                            subRedirectBuf.append(c)
                            subRedirectStarted = true
                        }
                    }
                    i++
                }
                DOUBLE -> {
                    when {
                        c == '"' -> {
                            quote = NONE
                            tokenStarted = true
                            tokenHasQuoteOrEscape = true
                            if (subRedirectActive) subRedirectStarted = true
                            i++
                        }
                        c == '\\' && i + 1 < n -> {
                            val nx = command[i + 1]
                            tokenHasQuoteOrEscape = true
                            when (nx) {
                                '"', '\\', '$', '`' -> {
                                    token.append(nx)
                                    tokenStarted = true
                                    if (subRedirectActive) {
                                        subRedirectBuf.append(nx)
                                        subRedirectStarted = true
                                    }
                                }
                                '\n' -> Unit
                                else -> {
                                    token.append('\\').append(nx)
                                    tokenStarted = true
                                    if (subRedirectActive) {
                                        subRedirectBuf.append('\\').append(nx)
                                        subRedirectStarted = true
                                    }
                                }
                            }
                            i += 2
                        }
                        // 双引号内命令替换/反引号仍会执行 → 不可静态判定。
                        c == '`' -> {
                            tokenHasQuoteOrEscape = true
                            consumeBacktick(inDoubleQuote = true)
                        }
                        c == '$' && i + 1 < n && command[i + 1] == '(' -> {
                            block("命令替换 \$(...)")
                            substStack.addLast(SubstFrame(outerQuote = DOUBLE))
                            quote = NONE
                            token.append('$').append('(')
                            tokenStarted = true
                            if (subRedirectActive) {
                                subRedirectBuf.append('$').append('(')
                                subRedirectStarted = true
                            }
                            i += 2
                        }
                        else -> {
                            token.append(c)
                            tokenStarted = true
                            if (subRedirectActive) {
                                subRedirectBuf.append(c)
                                subRedirectStarted = true
                            }
                            i++
                        }
                    }
                }
                else -> { // 引号外
                    when {
                        c == '\'' -> {
                            quote = SINGLE
                            tokenStarted = true
                            tokenHasQuoteOrEscape = true
                            if (subRedirectActive) subRedirectStarted = true
                            i++
                        }
                        c == '"' -> {
                            quote = DOUBLE
                            tokenStarted = true
                            tokenHasQuoteOrEscape = true
                            if (subRedirectActive) subRedirectStarted = true
                            i++
                        }
                        c == '\\' -> {
                            tokenHasQuoteOrEscape = true
                            // 行末续行 `\<newline>`：整体跳过；其余转义取下一字符字面值。
                            if (i + 1 < n) {
                                val nx = command[i + 1]
                                if (nx != '\n') {
                                    token.append(nx)
                                    tokenStarted = true
                                    if (subRedirectActive) {
                                        subRedirectBuf.append(nx)
                                        subRedirectStarted = true
                                    }
                                }
                                i += 2
                            } else {
                                i++
                            }
                        }
                        c == '$' && i + 1 < n && command[i + 1] == '(' -> {
                            block("命令替换 \$(...)")
                            substStack.addLast(SubstFrame(outerQuote = NONE))
                            token.append('$').append('(')
                            tokenStarted = true
                            if (subRedirectActive) {
                                subRedirectBuf.append('$').append('(')
                                subRedirectStarted = true
                            }
                            i += 2
                        }
                        c == '`' -> {
                            tokenHasQuoteOrEscape = true
                            consumeBacktick(inDoubleQuote = false)
                        }
                        // 分组括号 / 子 shell / 进程替换 `<( >(`（均含裸 `(`）→ 不可静态判定。
                        c == '(' -> {
                            if (inSubst()) {
                                substStack.last().parenDepth++
                                token.append(c)
                                tokenStarted = true
                                if (subRedirectActive) {
                                    subRedirectBuf.append(c)
                                    subRedirectStarted = true
                                }
                            } else {
                                flushSegment()
                                block("子 shell 或分组括号")
                            }
                            i++
                        }
                        c == ')' -> {
                            if (inSubst()) {
                                val top = substStack.last()
                                if (top.parenDepth > 0) {
                                    top.parenDepth--
                                    token.append(c)
                                    tokenStarted = true
                                    if (subRedirectActive) {
                                        subRedirectBuf.append(c)
                                        subRedirectStarted = true
                                    }
                                } else {
                                    if (subRedirectActive && substStack.size == subRedirectDepth) {
                                        finishSubRedirect()
                                    }
                                    substStack.removeLast()
                                    quote = top.outerQuote
                                    token.append(c)
                                    tokenStarted = true
                                    if (subRedirectActive) {
                                        subRedirectBuf.append(c)
                                        subRedirectStarted = true
                                    }
                                }
                            } else {
                                flushSegment()
                                block("子 shell 或分组括号")
                            }
                            i++
                        }
                        // 顶层段分隔符（在 $(...) 内仅作文本保留）。
                        c == '\n' || c == ';' -> {
                            if (inSubst()) {
                                if (subRedirectActive && substStack.size == subRedirectDepth) finishSubRedirect()
                                token.append(c)
                                tokenStarted = true
                            } else {
                                flushSegment()
                            }
                            i++
                        }
                        c == '&' -> {
                            if (i + 1 < n && command[i + 1] == '>') {
                                val opLen = if (i + 2 < n && command[i + 2] == '>') 3 else 2
                                if (inSubst()) {
                                    if (subRedirectActive && substStack.size == subRedirectDepth) finishSubRedirect()
                                    token.append(command, i, i + opLen)
                                    tokenStarted = true
                                    subRedirectActive = true
                                    subRedirectDepth = substStack.size
                                    subRedirectStarted = false
                                    subRedirectBuf.setLength(0)
                                } else {
                                    flushToken()
                                    redirectMode = REDIRECT_OUTPUT
                                }
                                i += opLen
                            } else {
                                val opLen = if (i + 1 < n && command[i + 1] == '&') 2 else 1
                                if (inSubst()) {
                                    if (subRedirectActive && substStack.size == subRedirectDepth) finishSubRedirect()
                                    token.append(command, i, i + opLen)
                                    tokenStarted = true
                                } else {
                                    flushSegment()
                                }
                                i += opLen
                            }
                        }
                        c == '|' -> {
                            val opLen = if (i + 1 < n && command[i + 1] == '|') 2 else 1
                            if (inSubst()) {
                                if (subRedirectActive && substStack.size == subRedirectDepth) finishSubRedirect()
                                token.append(command, i, i + opLen)
                                tokenStarted = true
                            } else {
                                flushSegment()
                            }
                            i += opLen
                        }
                        // 输出重定向：处理 `>`、`>>`、`>|`、`>&1`/`>&-`（fd 复制/关闭）及 `>&file`。
                        c == '>' -> {
                            if (i + 1 < n && command[i + 1] == '&') {
                                val dupEnd = consumeFdDupTargetEnd(i + 2)
                                if (dupEnd >= 0) {
                                    if (inSubst()) {
                                        if (subRedirectActive && substStack.size == subRedirectDepth) finishSubRedirect()
                                        token.append(command, i, dupEnd)
                                        tokenStarted = true
                                    } else {
                                        flushBeforeRedirectOperator()
                                    }
                                    i = dupEnd
                                } else {
                                    if (inSubst()) {
                                        if (subRedirectActive && substStack.size == subRedirectDepth) finishSubRedirect()
                                        token.append(">&")
                                        tokenStarted = true
                                        subRedirectActive = true
                                        subRedirectDepth = substStack.size
                                        subRedirectStarted = false
                                        subRedirectBuf.setLength(0)
                                    } else {
                                        flushBeforeRedirectOperator()
                                        redirectMode = REDIRECT_OUTPUT
                                    }
                                    i += 2
                                }
                            } else {
                                val opLen = if (i + 1 < n && (command[i + 1] == '>' || command[i + 1] == '|')) 2 else 1
                                if (inSubst()) {
                                    if (subRedirectActive && substStack.size == subRedirectDepth) finishSubRedirect()
                                    token.append(command, i, i + opLen)
                                    tokenStarted = true
                                    subRedirectActive = true
                                    subRedirectDepth = substStack.size
                                    subRedirectStarted = false
                                    subRedirectBuf.setLength(0)
                                } else {
                                    flushBeforeRedirectOperator()
                                    redirectMode = REDIRECT_OUTPUT
                                }
                                i += opLen
                            }
                        }
                        // 输入重定向：`<>` 按读写输出校验；`<&1`/`<&-` 为 fd 复制/关闭。
                        c == '<' -> {
                            if (i + 1 < n && command[i + 1] == '>') {
                                if (inSubst()) {
                                    if (subRedirectActive && substStack.size == subRedirectDepth) finishSubRedirect()
                                    token.append("<>")
                                    tokenStarted = true
                                    subRedirectActive = true
                                    subRedirectDepth = substStack.size
                                    subRedirectStarted = false
                                    subRedirectBuf.setLength(0)
                                } else {
                                    flushBeforeRedirectOperator()
                                    redirectMode = REDIRECT_OUTPUT
                                }
                                i += 2
                            } else if (i + 1 < n && command[i + 1] == '&') {
                                val dupEnd = consumeFdDupTargetEnd(i + 2)
                                if (dupEnd >= 0) {
                                    if (inSubst()) {
                                        if (subRedirectActive && substStack.size == subRedirectDepth) finishSubRedirect()
                                        token.append(command, i, dupEnd)
                                        tokenStarted = true
                                    } else {
                                        flushBeforeRedirectOperator()
                                    }
                                    i = dupEnd
                                } else {
                                    if (inSubst()) {
                                        if (subRedirectActive && substStack.size == subRedirectDepth) finishSubRedirect()
                                        token.append("<&")
                                        tokenStarted = true
                                    } else {
                                        flushBeforeRedirectOperator()
                                        redirectMode = REDIRECT_INPUT
                                    }
                                    i += 2
                                }
                            } else {
                                if (inSubst()) {
                                    if (subRedirectActive && substStack.size == subRedirectDepth) finishSubRedirect()
                                    token.append('<')
                                    tokenStarted = true
                                } else {
                                    flushBeforeRedirectOperator()
                                    redirectMode = REDIRECT_INPUT
                                }
                                i++
                            }
                        }
                        c.isWhitespace() -> {
                            if (inSubst()) {
                                if (subRedirectActive && substStack.size == subRedirectDepth && subRedirectStarted) {
                                    finishSubRedirect()
                                }
                                token.append(c)
                                tokenStarted = true
                            } else {
                                flushToken()
                            }
                            i++
                        }
                        else -> {
                            token.append(c)
                            tokenStarted = true
                            if (subRedirectActive) {
                                subRedirectBuf.append(c)
                                subRedirectStarted = true
                            }
                            i++
                        }
                    }
                }
            }
        }
        finishSubRedirect()
        if (quote != NONE) block("未闭合的引号") // 未闭合引号：不可信
        flushSegment()
        return Analysis(segments, blockers.isEmpty(), blockers, hasUnsafeOutputRedirect)
    }

    /** 跳过段首环境赋值后的有效 token（用于取程序名与匹配）。 */
    private fun effectiveTokens(tokens: List<String>): List<String> {
        var idx = 0
        while (idx < tokens.size && ENV_ASSIGN.matches(tokens[idx])) idx++
        return if (idx == 0) tokens else tokens.subList(idx, tokens.size)
    }

    /** 该段默认记忆的命令前缀 = 程序名 token；空段返回 null。 */
    fun programPrefix(tokens: List<String>): String? = effectiveTokens(tokens).firstOrNull()

    /**
     * 已知的「子命令分发器」程序：其第一个非 flag 参数是子命令（`git pull`/`npm install`/`cargo build`）。
     * 对这类程序，「始终允许」应连子命令一起记忆，避免仅记 `git` 就把 `git push`/`git reset --hard` 等
     * 一并放行。非分发器程序仍按程序名整体记忆（`cat`/`ls` 等）。
     */
    private val SUBCOMMAND_DISPATCHERS = setOf(
        "git", "gh",
        "npm", "npx", "yarn", "pnpm", "pnpx",
        "cargo", "rustup",
        "go",
        "pip", "pip3", "poetry", "uv", "conda",
        "gradle", "mvn", "sbt",
        "docker", "kubectl", "helm",
        "adb", "fastlane",
        "brew", "apt", "apt-get", "gem", "bundle",
        "az", "aws", "gcloud", "terraform", "ansible"
    )

    data class RmInfo(
        val isRm: Boolean,
        val isRecursive: Boolean,
        val isWildcard: Boolean,
        val targetPaths: List<String>
    )

    /** 解析该命令段是否为 rm 删除操作及其参数信息（是否递归、通配以及操作目标路径）。 */
    fun parseRmInfo(tokens: List<String>): RmInfo {
        val eff = effectiveTokens(tokens)
        val program = eff.firstOrNull()
        if (program != "rm" && program?.endsWith("/rm") != true) {
            return RmInfo(isRm = false, isRecursive = false, isWildcard = false, targetPaths = emptyList())
        }
        var isRecursive = false
        var isWildcard = false
        val targetPaths = mutableListOf<String>()
        var hasDoubleDash = false
        for (i in 1 until eff.size) {
            val t = eff[i]
            if (!hasDoubleDash && t == "--") {
                hasDoubleDash = true
                continue
            }
            if (!hasDoubleDash && t.startsWith("-") && t != "-") {
                if (t == "--recursive" || (!t.startsWith("--") && (t.contains('r') || t.contains('R')))) {
                    isRecursive = true
                }
                continue
            }
            if (t.contains('*') || t.contains('?')) {
                isWildcard = true
            }
            targetPaths.add(t)
        }
        return RmInfo(isRm = true, isRecursive = isRecursive, isWildcard = isWildcard, targetPaths = targetPaths)
    }

    /**
     * 「始终允许」可记忆的命令前缀：
     * - rm 命令精细处理：递归/通配符删除不可记忆；普通单文件删除精细记忆为完整命令前缀；
     * - 非分发器程序：仅程序名（`cat`/`ls`），与 [programPrefix] 一致；
     * - 子命令分发器：程序名 + 紧随其后的第一个非 flag 子命令（`git pull`/`git clone`/`npm install`），
     *   程序名后紧跟 flag（如 `git --version`）时退化为仅程序名。空段返回 null。
     *
     * 匹配仍走 [matches] 的 token 前缀语义：`git pull` 命中 `git pull origin main`，但不命中 `git clone`，
     * 故不同子命令各自独立记忆、互不串放。
     */
    fun rememberablePrefix(tokens: List<String>): String? {
        val eff = effectiveTokens(tokens)
        val program = eff.firstOrNull() ?: return null
        val rmInfo = parseRmInfo(tokens)
        if (rmInfo.isRm) {
            if (rmInfo.targetPaths.isEmpty() || rmInfo.isRecursive || rmInfo.isWildcard) {
                return null
            }
            return eff.joinToString(" ")
        }
        if (program !in SUBCOMMAND_DISPATCHERS) return program
        val subcommand = eff.getOrNull(1)?.takeIf { !it.startsWith("-") } ?: return program
        return "$program $subcommand"
    }

    /** token 前缀匹配：规则 [pattern] 的 token 序列是该段有效 token 序列的前缀即命中。 */
    fun matches(pattern: String, segmentTokens: List<String>): Boolean {
        val patternTokens = pattern.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (patternTokens.isEmpty()) return false
        val eff = effectiveTokens(segmentTokens)
        if (eff.size < patternTokens.size) return false
        for (k in patternTokens.indices) if (eff[k] != patternTokens[k]) return false
        return true
    }
}
