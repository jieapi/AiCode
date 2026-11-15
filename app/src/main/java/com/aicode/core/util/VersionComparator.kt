package com.aicode.core.util

/** GitHub tag 形如 v1.7.0 / 1.7.0 / v1.7.0-rc1，提取出纯版本号。 */
internal fun parseVersionTag(tag: String): String? {
    val raw = tag.trim().removePrefix("v")
    val seg = raw.substringBefore(' ')
    return seg.ifBlank { null }
}

/**
 * 语义化版本比较：判断最新版本 [latest] 是否严格大于当前版本 [current]。
 * 若 latest 不大于 current（即最新版本已安装或当前属于开发/测试版），返回 true 表示已是最新。
 */
internal fun isUpToDate(latest: String, current: String): Boolean {
    val cmp = compareVersions(latest, current)
    return cmp <= 0
}

/**
 * 语义化版本号比较器（SemVer 兼容）：
 * 返回 >0 表示 v1 > v2，<0 表示 v1 < v2，0 表示相等。
 * 规则：
 * 1. 优先比较主.次.修（如 1.7.0 > 1.6.9）
 * 2. 主次修相同时，正式版 > 预发布版（1.7.0 > 1.7.0-rc1 > 1.7.0-dev）
 * 3. 均为预发布版时，同标识比序号（rc2 > rc1、dev.10 > dev.9），不同标识按字典序
 */
internal fun compareVersions(v1: String, v2: String): Int {
    if (v1 == v2) return 0

    val (base1, pre1) = splitVersion(v1)
    val (base2, pre2) = splitVersion(v2)

    val parts1 = base1.split('.').mapNotNull { it.toIntOrNull() }
    val parts2 = base2.split('.').mapNotNull { it.toIntOrNull() }

    val maxLen = maxOf(parts1.size, parts2.size)
    for (i in 0 until maxLen) {
        val p1 = parts1.getOrElse(i) { 0 }
        val p2 = parts2.getOrElse(i) { 0 }
        if (p1 != p2) return p1.compareTo(p2)
    }

    // 主版本号相同的情况下：
    // 正式版 (pre为空) > 预发布版 (pre不为空)
    if (pre1.isEmpty() && pre2.isNotEmpty()) return 1
    if (pre1.isNotEmpty() && pre2.isEmpty()) return -1

    return comparePreRelease(pre1, pre2)
}

private val PRE_RELEASE_TOKEN = Regex("^(\\D*?)(\\d+)$")

/**
 * 预发布段比较：「同标识 + 序号」按数字比（rc10 > rc9），避免字典序把 rc10 排在 rc9 前；
 * 标识不同或不含尾随数字时退回字典序。
 */
private fun comparePreRelease(a: String, b: String): Int {
    val ma = PRE_RELEASE_TOKEN.matchEntire(a)
    val mb = PRE_RELEASE_TOKEN.matchEntire(b)
    if (ma != null && mb != null && ma.groupValues[1] == mb.groupValues[1]) {
        return ma.groupValues[2].toInt().compareTo(mb.groupValues[2].toInt())
    }
    return a.compareTo(b)
}

internal fun splitVersion(v: String): Pair<String, String> {
    val clean = v.substringBefore('+') // 去掉构建哈希如 +g04bc2fa
    val base = clean.substringBefore('-')
    val pre = if (clean.contains('-')) clean.substringAfter('-') else ""
    return base to pre
}
