package com.aicode

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.aicode.feature.credentials.presentation.CredentialViewModel
import com.aicode.feature.credentials.presentation.component.CredentialScreen
import com.aicode.feature.editor.presentation.CodeEditorScreen
import com.aicode.feature.editor.presentation.EditorLeaveGuard
import com.aicode.feature.browser.presentation.BrowserScreen
import com.aicode.feature.browser.presentation.BrowserViewModel
import com.aicode.feature.git.presentation.GitViewModel
import com.aicode.feature.git.presentation.component.GitScreen
import com.aicode.feature.plugin.domain.PluginPage
import com.aicode.feature.plugin.presentation.PluginPageHost
import com.aicode.feature.plugin.presentation.PluginsViewModel
import com.aicode.feature.terminal.presentation.TerminalViewModel
import com.aicode.feature.terminal.presentation.component.TerminalScreen

/** 大屏右栏（工作台）当前承载的内容。 */
internal enum class WorkbenchPaneKind { NONE, EDITOR, TERMINAL, GIT, BROWSER, PLUGIN }

/**
 * 大屏下与聊天并排的右栏内容。
 *
 * 三个页面本身就是「参数 + 回调」的独立组件，这里只是把它们的返回动作接成「关闭右栏」；
 * 窄窗下这些页面仍走 NavHost 全屏路由，两条路径共用同一份 UI 代码。
 *
 * ViewModel 挂在聊天页的 NavBackStackEntry 上，右栏在几种内容间切换不会销毁它们——
 * 终端会话本身归单例 TerminalSessionManager 所有，与这里的作用域无关。
 *
 * [editorLeaveGuard] 供编辑器向宿主上报脏状态：右栏编辑器里有未保存修改时，换文件前先弹保存提示。
 */
@Composable
internal fun WorkbenchPaneContent(
    kind: WorkbenchPaneKind,
    editorPath: String,
    editorLine: Int,
    pluginPage: PluginPage?,
    pluginLanguageTag: String,
    pluginsViewModel: PluginsViewModel,
    currentSessionId: () -> String?,
    onClose: () -> Unit,
    onOpenFile: ((String) -> Unit)? = null,
    editorLeaveGuard: EditorLeaveGuard? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        when (kind) {
            WorkbenchPaneKind.NONE -> Unit

            WorkbenchPaneKind.EDITOR -> CodeEditorScreen(
                path = editorPath,
                initialLine = editorLine,
                onBack = onClose,
                embedded = true,
                leaveGuard = editorLeaveGuard
            )

            WorkbenchPaneKind.TERMINAL -> {
                val terminalViewModel: TerminalViewModel = hiltViewModel()
                TerminalScreen(
                    viewModel = terminalViewModel,
                    onNavigateBack = onClose,
                    embedded = true
                )
            }

            WorkbenchPaneKind.GIT -> {
                // 凭据页留在右栏内部翻一层，而不是跳全屏路由，否则大屏下会从双栏突然变成整屏。
                var showCredentials by rememberSaveable { mutableStateOf(false) }
                if (showCredentials) {
                    val credentialViewModel: CredentialViewModel = hiltViewModel()
                    CredentialScreen(
                        viewModel = credentialViewModel,
                        onNavigateBack = { showCredentials = false }
                    )
                } else {
                    val gitViewModel: GitViewModel = hiltViewModel()
                    GitScreen(
                        viewModel = gitViewModel,
                        onNavigateToCredentials = { showCredentials = true },
                        onNavigateBack = onClose,
                        onOpenFile = onOpenFile,
                        embedded = true
                    )
                }
            }
            WorkbenchPaneKind.BROWSER -> {
                val browserViewModel: BrowserViewModel = hiltViewModel()
                BrowserScreen(
                    browserManager = browserViewModel.browserManager,
                    onNavigateBack = onClose,
                    embedded = true
                )
            }

            WorkbenchPaneKind.PLUGIN -> {
                val page = pluginPage
                if (page != null) {
                    PluginPageHost(
                        page = page,
                        languageTag = pluginLanguageTag,
                        pluginsViewModel = pluginsViewModel,
                        onClose = onClose,
                        applySystemBarInsets = false,
                        currentSessionId = currentSessionId
                    )
                }
            }
        }
    }
}
