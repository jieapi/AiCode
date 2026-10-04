package com.aicode.feature.agent.domain.tool.browser

/**
 * 浏览器 User-Agent 预设。
 *
 * 用户在「设置 → 运行环境 → 浏览器 → UA 设置」中选择，浏览器新建标签时套用对应的
 * User-Agent 字符串与客户端提示（client hints）平台信息。
 *
 * [DEFAULT] 表示不覆盖，使用系统 WebView 的默认 UA；其余为常见设备/系统 UA。
 * [WINDOWS_10] 与 [WINDOWS_11] 的 UA 字符串一致（Windows 11 的 UA 仍报 Windows NT 10.0），
 * 区别体现在客户端提示的 platform-version。
 */
enum class BrowserUserAgent(
    val id: String,
    /** Android 手机 UA。 */
    val userAgent: String?,
    /** 客户端提示 sec-ch-ua-platform（含引号按原样传入），null 表示不设置。 */
    val platform: String? = null,
    val platformVersion: String? = null,
    val architecture: String? = null,
    val bitness: Int = 64,
    val mobile: Boolean = false
) {
    /** 系统默认，不覆盖 UA。 */
    DEFAULT("default", null),

    /** 安卓手机。 */
    ANDROID_PHONE(
        id = "android_phone",
        userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        platform = "Android",
        platformVersion = "14.0.0",
        mobile = true
    ),

    /** 安卓平板。 */
    ANDROID_TABLET(
        id = "android_tablet",
        userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel Tablet) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        platform = "Android",
        platformVersion = "14.0.0",
        mobile = false
    ),

    /** Windows 10 电脑。 */
    WINDOWS_10(
        id = "windows_10",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        platform = "Windows",
        platformVersion = "10.0.0",
        architecture = "x86"
    ),

    /** Windows 11 电脑（UA 同 Win10，客户端提示版本不同）。 */
    WINDOWS_11(
        id = "windows_11",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        platform = "Windows",
        platformVersion = "13.0.0",
        architecture = "x86"
    ),

    /** Mac 电脑（Intel 芯片）。 */
    MAC_INTEL(
        id = "mac_intel",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        platform = "macOS",
        platformVersion = "15.0.0",
        architecture = "x86"
    ),

    /** Mac 电脑（Apple 芯片）。 */
    MAC_APPLE_SILICON(
        id = "mac_apple_silicon",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        platform = "macOS",
        platformVersion = "15.0.0",
        architecture = "arm"
    );

    companion object {
        fun fromId(id: String?): BrowserUserAgent =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * 浏览器工具可被 AI 调用的全部 action（与 [com.aicode.feature.agent.domain.tool.browser.BrowserTool] 的 actionEnum 一致）。
 *
 * 作为「AI 控制浏览器权限」开关的唯一事实源：设置页按此顺序逐项渲染开关，
 * 权限默认全开；用户关闭某项后，BrowserTool 执行时直接拒绝该 action。
 */
object BrowserActionCatalog {
    /** action 名，顺序即设置页展示顺序。 */
    val ALL: List<String> = listOf(
        "navigate", "evaluate",
        "click", "fill", "select", "hover", "press",
        "getText", "getHtml", "getBackbone", "screenshot", "console", "wait",
        "scroll", "dialog",
        "back", "forward", "reload",
        "newTab", "closeTab", "selectTab", "listTabs"
    )
}
