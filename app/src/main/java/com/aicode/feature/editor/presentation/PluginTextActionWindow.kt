package com.aicode.feature.editor.presentation

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import com.aicode.feature.plugin.domain.PluginUiEntry
import com.aicode.feature.plugin.presentation.PluginEntryContext
import io.github.rosemoe.sora.event.ColorSchemeUpdateEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * 选区浮动菜单的扩展：在系统复制/粘贴按钮旁追加一个「插件」按钮，
 * 点开只展示动作型入口。选中文本变化时由宿主重新取上下文。
 */
internal class PluginTextActionWindow(
    private val hostEditor: CodeEditor,
    private val entriesProvider: () -> List<PluginUiEntry>,
    private val languageTagProvider: () -> String,
    private val contextProvider: () -> PluginEntryContext,
    private val onEntry: (PluginUiEntry, PluginEntryContext) -> Unit
) : EditorTextActionWindow(hostEditor) {

    private val pluginButton: TextView = TextView(hostEditor.context).apply {
        text = "插件"
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        isClickable = true
        isFocusable = true
        includeFontPadding = false
        contentDescription = "插件动作"
        val background = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, background, true)
        setBackgroundResource(background.resourceId)
        setOnClickListener { showEntries() }
    }

    init {
        val root = getView()
        val scroll = root.getChildAt(0) as? ViewGroup
        val container = scroll?.getChildAt(0) as? ViewGroup
        container?.addView(
            pluginButton,
            0,
            ViewGroup.LayoutParams(dp(45), ViewGroup.LayoutParams.MATCH_PARENT)
        )
        applyButtonColor()
    }

    override fun displayWindow() {
        pluginButton.visibility = if (hostEditor.cursor.isSelected && entriesProvider().isNotEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        applyButtonColor()
        super.displayWindow()
    }

    override fun onEditorColorChange(event: ColorSchemeUpdateEvent) {
        super.onEditorColorChange(event)
        applyButtonColor()
    }

    private fun showEntries() {
        val entries = entriesProvider()
        if (entries.isEmpty()) return
        // 弹出菜单会让编辑器失焦，先把当前选区冻结下来。
        val snapshot = contextProvider()
        val popup = PopupMenu(pluginButton.context, pluginButton)
        entries.forEachIndexed { index, entry ->
            popup.menu.add(0, index + 1, index, entry.title.resolve(languageTagProvider()))
        }
        popup.setOnMenuItemClickListener { item ->
            entries.getOrNull(item.itemId - 1)?.let { onEntry(it, snapshot) }
            true
        }
        popup.show()
    }

    private fun applyButtonColor() {
        pluginButton.setTextColor(
            hostEditor.colorScheme.getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR)
        )
    }

    private fun dp(value: Int): Int =
        (value * pluginButton.resources.displayMetrics.density).toInt()
}
