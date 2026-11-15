package com.aicode.feature.terminal.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aicode.core.util.FileLogger
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt

/**
 * 由额外按键行（Esc/Ctrl/Alt 等）驱动的虚拟修饰键状态。
 *
 * [TerminalView] 在分发按键/字符时会回调 `readControlKey()` 等读取这些标志，
 * 从而支持手机软键盘上没有的 Ctrl/Alt 组合（如 Ctrl-C）。
 *
 * 标志用 Compose 可观察状态承载：额外按键行据此渲染「已按下」高亮，让用户看清当前是否
 * 预置了 Ctrl/Alt。读写都发生在 UI 线程（按键分发、点击、consume 均在主线程），故安全。
 */
class TerminalKeyModifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var shift by mutableStateOf(false)
    var fn by mutableStateOf(false)

    /** 一次性修饰键：发出一个字符后自动复位（贴近物理键盘按一下即用的直觉）。 */
    fun consume() {
        ctrl = false
        alt = false
        shift = false
        fn = false
    }
}

/**
 * [TerminalSessionClient]：会话产生输出/标题/结束等事件时回调。
 * 主要职责是把屏幕刷新转交给 [TerminalView]，并接驳系统剪贴板。
 */
class AppTerminalSessionClient(
    private val context: Context,
    private val viewProvider: () -> TerminalView?,
    private val onFinished: (TerminalSession) -> Unit,
    var cursorStyle: Int = 0
) : TerminalSessionClient {

    private companion object { const val TAG = "TerminalSession" }

    override fun onTextChanged(changedSession: TerminalSession) {
        viewProvider()?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) { /* 暂不展示标题 */ }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        onFinished(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        if (text.isNotEmpty()) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            session?.write(bytes, 0, bytes.size)
        }
    }

    override fun onBell(session: TerminalSession) { /* 忽略响铃 */ }
    override fun onColorsChanged(session: TerminalSession) { viewProvider()?.onScreenUpdated() }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = cursorStyle

    override fun logError(tag: String?, message: String?) { FileLogger.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { FileLogger.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { FileLogger.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        FileLogger.e(tag ?: TAG, message ?: "", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { FileLogger.e(tag ?: TAG, "", e) }
}

/**
 * [TerminalViewClient]：视图层的输入/手势/缩放回调。
 * 用合理默认值实现，并把 Ctrl/Alt 等虚拟修饰键交给 [TerminalKeyModifiers]。
 */
class AppTerminalViewClient(
    private val context: Context,
    private val viewProvider: () -> TerminalView?,
    private val modifiers: TerminalKeyModifiers,
    private val currentFontSizeSp: () -> Int,
    private val onFontSizeChange: (Int) -> Unit,
    private val onFontSizeCommit: (Int) -> Unit
) : TerminalViewClient {

    private companion object {
        const val TAG = "TerminalView"
        const val MIN_FONT_SP = 5
        const val MAX_FONT_SP = 22
        const val FONT_RESIZE_INTERVAL_MS = 80L
    }

    private var baseFontSizeSp = 0
    private var targetFontSizeSp = 0f
    private var appliedFontSizeSp = 0
    private var lastFontResizeTime = 0L

    override fun onScaleBegin() {
        baseFontSizeSp = currentFontSizeSp()
        targetFontSizeSp = baseFontSizeSp.toFloat()
        appliedFontSizeSp = baseFontSizeSp
        lastFontResizeTime = SystemClock.uptimeMillis() - FONT_RESIZE_INTERVAL_MS
    }

    override fun onScale(scale: Float) {
        // 每次增量都钳在边界内，避免超出上限/下限后反向捏合出现空行程。
        targetFontSizeSp = (targetFontSizeSp * scale).coerceIn(MIN_FONT_SP.toFloat(), MAX_FONT_SP.toFloat())
        val fontSizeSp = targetFontSizeSp.roundToInt()
        val now = SystemClock.uptimeMillis()
        // 合并高频手势事件，给 shell 处理窗口变化和重绘的时间。
        if (fontSizeSp != appliedFontSizeSp && now - lastFontResizeTime >= FONT_RESIZE_INTERVAL_MS) {
            appliedFontSizeSp = fontSizeSp
            lastFontResizeTime = now
            onFontSizeChange(fontSizeSp)
        }
    }

    override fun onScaleEnd() {
        val fontSizeSp = targetFontSizeSp.roundToInt()
        if (fontSizeSp != appliedFontSizeSp) {
            appliedFontSizeSp = fontSizeSp
            onFontSizeChange(fontSizeSp)
        }
        if (fontSizeSp != baseFontSizeSp) onFontSizeCommit(fontSizeSp)
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        val view = viewProvider() ?: return
        view.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    // 返回 false → 输入框用 InputType.TYPE_NULL（Termux 上游默认）。这样 Gboard 等多数输入法会把
    // 方向键/退格等作为真正的 KeyEvent 下发，TerminalView 才能映射成光标移动序列；若返回 true 走
    // 「字符输入」模式（VISIBLE_PASSWORD），方向键会被输入法吞作内部光标移动，永远到不了终端。
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = modifiers.ctrl
    override fun readAltKey(): Boolean = modifiers.alt
    override fun readShiftKey(): Boolean = modifiers.shift
    override fun readFnKey(): Boolean = modifiers.fn

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        // 字符已发出，复位一次性修饰键
        modifiers.consume()
        return false
    }

    override fun onEmulatorSet() {}

    override fun logError(tag: String?, message: String?) { FileLogger.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { FileLogger.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { FileLogger.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        FileLogger.e(tag ?: TAG, message ?: "", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { FileLogger.e(tag ?: TAG, "", e) }
}
