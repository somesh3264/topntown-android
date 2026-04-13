package com.topntown.dms.ui.screens.bill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topntown.dms.data.datastore.UserPreferences
import com.topntown.dms.data.datastore.UserSession
import com.topntown.dms.data.repository.BillRepository
import com.topntown.dms.domain.model.Bill
import com.topntown.dms.domain.model.BillItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

/**
 * UI states for the advance-bill screen. Emitted by [BillViewModel] and
 * consumed by [BillScreen].
 */
sealed interface BillUiState {
    data object Loading : BillUiState
    data class NoBill(val message: String = "Your bill will be ready at 10 PM tonight") : BillUiState
    data class BillReady(
        val bill: Bill,
        val items: List<BillItem>,
        val distributor: UserSession
    ) : BillUiState
    data class Error(val message: String) : BillUiState
}

@HiltViewModel
class BillViewModel @Inject constructor(
    private val repository: BillRepository,
    private val userPrefs: UserPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow<BillUiState>(BillUiState.Loading)
    val uiState: StateFlow<BillUiState> = _uiState.asStateFlow()

    private var autoRefreshJob: Job? = null

    init {
        loadBill()
        startAutoRefreshLoop()
    }

    /** Manual or internal refresh trigger. */
    fun loadBill() {
        viewModelScope.launch {
            if (_uiState.value !is BillUiState.BillReady) {
                _uiState.value = BillUiState.Loading
            }
            runCatching {
                val session = userPrefs.sessionFlow.first()
                if (!session.isValid) error("Distributor profile not found")

                val result = repository.fetchTodaysBill(session.userId)
                if (result == null) {
                    _uiState.value = BillUiState.NoBill()
                } else {
                    _uiState.value = BillUiState.BillReady(
                        bill = result.bill,
                        items = result.items,
                        distributor = session
                    )
                    autoRefreshJob?.cancel()
                    autoRefreshJob = null
                }
            }.onFailure { e ->
                _uiState.value = BillUiState.Error(e.message ?: "Failed to load bill")
            }
        }
    }

    /**
     * Polls every 5 minutes, but only issues a fetch when local time is between
     * 21:30 and 22:30. Stops itself once a bill is loaded.
     */
    private fun startAutoRefreshLoop() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                val now = LocalTime.now()
                val inWindow = !now.isBefore(WINDOW_START) && now.isBefore(WINDOW_END)
                if (inWindow && _uiState.value !is BillUiState.BillReady) {
                    loadBill()
                    if (_uiState.value is BillUiState.BillReady) return@launch
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    override fun onCleared() {
        autoRefreshJob?.cancel()
        super.onCleared()
    }

    companion object {
        private val WINDOW_START: LocalTime = LocalTime.of(21, 30)
        private val WINDOW_END: LocalTime = LocalTime.of(22, 30)
        private const val POLL_INTERVAL_MS = 5 * 60 * 1000L
    }
}
