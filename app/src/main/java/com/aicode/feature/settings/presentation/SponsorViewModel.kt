package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.settings.data.remote.SponsorCatalog
import com.aicode.feature.settings.data.remote.SponsorMethod
import com.aicode.feature.settings.data.remote.SponsorUser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SponsorUiState(
    val methods: List<SponsorMethod> = SponsorCatalog.DEFAULT_METHODS,
    val users: List<SponsorUser> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class SponsorViewModel @Inject constructor(
    private val catalog: SponsorCatalog
) : ViewModel() {

    private val _uiState = MutableStateFlow(SponsorUiState())
    val uiState: StateFlow<SponsorUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

    fun refresh(clearExistingUsers: Boolean = false) {
        refreshJob?.cancel()
        _uiState.update { state ->
            state.copy(
                users = if (clearExistingUsers) emptyList() else state.users,
                isRefreshing = true,
                errorMessage = null
            )
        }
        refreshJob = viewModelScope.launch {
            catalog.fetchLatest()
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            methods = data.methods,
                            users = data.users,
                            isRefreshing = false,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            errorMessage = err.message ?: ""
                        )
                    }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
