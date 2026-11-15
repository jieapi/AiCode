package com.aicode.feature.plugin.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.plugin.runtime.PluginTip
import com.aicode.feature.plugin.runtime.PluginTipManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 对话内插件提示的展示与动作分发。 */
@HiltViewModel
class PluginTipsViewModel @Inject constructor(
    private val tipManager: PluginTipManager
) : ViewModel() {

    val tips: StateFlow<List<PluginTip>> = tipManager.tips

    fun dismiss(tipId: String) = tipManager.dismiss(tipId)

    fun invokeAction(tipId: String) {
        viewModelScope.launch(Dispatchers.IO) { tipManager.invokeAction(tipId) }
    }
}
