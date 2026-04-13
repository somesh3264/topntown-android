package com.topntown.dms.ui.screens.beat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topntown.dms.data.datastore.UserPreferences
import com.topntown.dms.data.repository.StoreRepository
import com.topntown.dms.domain.model.Store
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BeatUiState(
    val stores: List<Store> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class BeatViewModel @Inject constructor(
    private val storeRepository: StoreRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(BeatUiState())
    val uiState: StateFlow<BeatUiState> = _uiState.asStateFlow()

    init {
        // Placeholder — beat loading will be implemented in Sprint 3
        _uiState.update { it.copy(isLoading = false) }
    }

    fun loadStoresForBeat(beatId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            storeRepository.getStoresForBeat(beatId)
                .onSuccess { stores ->
                    _uiState.update { it.copy(stores = stores, isLoading = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message, isLoading = false) }
                }
        }
    }
}
