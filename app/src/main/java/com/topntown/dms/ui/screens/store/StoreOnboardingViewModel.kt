package com.topntown.dms.ui.screens.store

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topntown.dms.data.datastore.UserPreferences
import com.topntown.dms.data.repository.StoreRepository
import com.topntown.dms.domain.model.Store
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StoreOnboardingUiState(
    val storeName: String = "",
    val address: String = "",
    val phone: String = "",
    val isLoading: Boolean = false,
    val isComplete: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class StoreOnboardingViewModel @Inject constructor(
    private val storeRepository: StoreRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(StoreOnboardingUiState())
    val uiState: StateFlow<StoreOnboardingUiState> = _uiState.asStateFlow()

    fun onStoreNameChanged(value: String) {
        _uiState.update { it.copy(storeName = value) }
    }

    fun onAddressChanged(value: String) {
        _uiState.update { it.copy(address = value) }
    }

    fun onPhoneChanged(value: String) {
        _uiState.update { it.copy(phone = value) }
    }

    fun submitStore() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val session = userPreferences.sessionFlow.first()
            val store = Store(
                storeName = _uiState.value.storeName,
                address = _uiState.value.address,
                phone = _uiState.value.phone,
                distributorId = session.userId // Will be resolved via profile later
            )
            storeRepository.createStore(store)
                .onSuccess {
                    _uiState.update { it.copy(isComplete = true, isLoading = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message, isLoading = false) }
                }
        }
    }
}
