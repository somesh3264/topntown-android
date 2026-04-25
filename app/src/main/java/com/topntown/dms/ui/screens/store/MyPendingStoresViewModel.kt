package com.topntown.dms.ui.screens.store

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topntown.dms.data.repository.StoreOnboardingRepository
import com.topntown.dms.domain.model.PendingStoreRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Read-only feed of the distributor's own store-onboarding submissions.
 *
 * Lifecycle: refreshed on init and on pull-to-refresh. Per the product
 * decision (read-only pending list) we don't expose edit/cancel actions —
 * if a request gets rejected, the distributor reads the rejection reason and
 * resubmits via [StoreOnboardingScreen].
 */
data class MyPendingStoresUiState(
    val rows: List<PendingStoreRow> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class MyPendingStoresViewModel @Inject constructor(
    private val repository: StoreOnboardingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MyPendingStoresUiState())
    val uiState: StateFlow<MyPendingStoresUiState> = _uiState.asStateFlow()

    init { load(initial = true) }

    fun refresh() = load(initial = false)

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = initial,
                    isRefreshing = !initial,
                    error = null,
                )
            }
            repository.getMyPendingStores().fold(
                onSuccess = { rows ->
                    _uiState.update {
                        it.copy(rows = rows, isLoading = false, isRefreshing = false)
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = e.message ?: "Could not load submissions"
                        )
                    }
                }
            )
        }
    }
}
