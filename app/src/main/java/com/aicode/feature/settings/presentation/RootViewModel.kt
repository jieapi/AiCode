package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import com.aicode.feature.agent.domain.root.RootManager
import com.aicode.feature.agent.domain.root.RootState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Root 执行后端的设置页状态与操作入口。 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val rootManager: RootManager
) : ViewModel() {

    val state: StateFlow<RootState> = rootManager.state

    /**
     * 重新探测状态。
     *
     * root 没有可编程的授权 API，探测本身就会触发 root 管理器的授权弹窗，
     * 属于预期行为（用户可从设置页主动触发授权）。
     */
    fun refresh() = rootManager.refreshState()
}