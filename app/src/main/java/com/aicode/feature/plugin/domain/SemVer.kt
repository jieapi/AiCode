package com.aicode.feature.plugin.domain

/** 语义化版本 `主.次.修订[-预发布]`；构建元数据（`+...`）不参与比较，解析时丢弃。 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList()
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            // 带预发布标识的版本低于同号正式版
            return other.preRelease.size.compareTo(preRelease.size).coerceIn(-1, 1)
        }
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            comparePreReleaseId(preRelease[i], other.preRelease[i]).let { if (it != 0) return it }
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (preRelease.isNotEmpty()) append('-').append(preRelease.joinToString("."))
    }

    companion object {
        private val PATTERN = Regex(
            """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""" +
                """(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"""
        )

        fun parse(text: String): SemVer? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, pre) = match.destructured
            val preRelease = pre.takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            // 数字标识不允许前导零
            if (preRelease.any { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) return null
            return SemVer(
                major = major.toIntOrNull() ?: return null,
                minor = minor.toIntOrNull() ?: return null,
                patch = patch.toIntOrNull() ?: return null,
                preRelease = preRelease
            )
        }

        private fun comparePreReleaseId(a: String, b: String): Int {
            val aNum = a.all(Char::isDigit)
            val bNum = b.all(Char::isDigit)
            return when {
                aNum && bNum -> a.toBigInteger().compareTo(b.toBigInteger())
                aNum -> -1
                bNum -> 1
                else -> a.compareTo(b)
            }
        }
    }
}

/**
 * SemVer 范围：空格分隔的比较子取交集。支持 `^x.y.z`、`~x.y.z`、`>= > <= < =` 加完整版本，
 * 以及不带运算符的精确版本。不支持 `1.x`、`*`、`||`、连字符区间等写法，遇到即视为非法。
 * 按版本的全序比较，不套用 npm 对预发布版本的特殊匹配规则。
 */
class SemVerRange private constructor(private val bounds: List<Bound>) {

    private data class Bound(val op: Op, val version: SemVer)

    private enum class Op { GE, GT, LE, LT, EQ }

    fun matches(version: SemVer): Boolean = bounds.all { (op, bound) ->
        val c = version.compareTo(bound)
        when (op) {
            Op.GE -> c >= 0
            Op.GT -> c > 0
            Op.LE -> c <= 0
            Op.LT -> c < 0
            Op.EQ -> c == 0
        }
    }

    companion object {
        fun parse(text: String): SemVerRange? {
            val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            val bounds = mutableListOf<Bound>()
            for (token in tokens) {
                bounds += parseToken(token) ?: return null
            }
            return SemVerRange(bounds)
        }

        private fun parseToken(token: String): List<Bound>? {
            fun version(raw: String) = SemVer.parse(raw)
            return when {
                token.startsWith("^") -> {
                    val base = version(token.drop(1)) ?: return null
                    val upper = when {
                        base.major > 0 -> SemVer(base.major + 1, 0, 0)
                        base.minor > 0 -> SemVer(0, base.minor + 1, 0)
                        else -> SemVer(0, 0, base.patch + 1)
                    }
                    listOf(Bound(Op.GE, base), Bound(Op.LT, upper))
                }
                token.startsWith("~") -> {
                    val base = version(token.drop(1)) ?: return null
                    listOf(Bound(Op.GE, base), Bound(Op.LT, SemVer(base.major, base.minor + 1, 0)))
                }
                token.startsWith(">=") -> listOf(Bound(Op.GE, version(token.drop(2)) ?: return null))
                token.startsWith("<=") -> listOf(Bound(Op.LE, version(token.drop(2)) ?: return null))
                token.startsWith(">") -> listOf(Bound(Op.GT, version(token.drop(1)) ?: return null))
                token.startsWith("<") -> listOf(Bound(Op.LT, version(token.drop(1)) ?: return null))
                token.startsWith("=") -> listOf(Bound(Op.EQ, version(token.drop(1)) ?: return null))
                else -> listOf(Bound(Op.EQ, version(token) ?: return null))
            }
        }
    }
}
