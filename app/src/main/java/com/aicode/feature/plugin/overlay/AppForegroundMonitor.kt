package com.aicode.feature.plugin.overlay

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级前后台状态。
 *
 * 以 [ProcessLifecycleOwner] 为准：只要还有一个 Activity 处于 STARTED 就算前台，
 * 只有服务/插件进程存活不算。观察者必须在主线程注册，因此这里统一 post 到主线程。
 */
@Singleton
class AppForegroundMonitor @Inject constructor() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    init {
        mainHandler.post {
            _isForeground.value = currentState()
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    _isForeground.value = true
                }

                override fun onStop(owner: LifecycleOwner) {
                    _isForeground.value = false
                }
            })
        }
    }

    fun current(): Boolean = _isForeground.value

    private fun currentState(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
