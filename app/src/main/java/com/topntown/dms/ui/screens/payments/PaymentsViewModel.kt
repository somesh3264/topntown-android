package com.topntown.dms.ui.screens.payments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topntown.dms.data.datastore.UserPreferences
import com.topntown.dms.data.repository.PaymentRepository
import com.topntown.dms.domain.model.Payment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PaymentsUiState(
    val payments: List<Payment> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class PaymentsViewModel @Inject constructor(
    private val paymentRepository: PaymentRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(PaymentsUiState())
    val uiState: StateFlow<PaymentsUiState> = _uiState.asStateFlow()

    init {
        loadPayments()
    }

    fun loadPayments() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val session = userPreferences.sessionFlow.first()
            paymentRepository.getPaymentsBySalesman(session.userId)
                .onSuccess { payments ->
                    _uiState.update { it.copy(payments = payments, isLoading = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message, isLoading = false) }
                }
        }
    }
}
