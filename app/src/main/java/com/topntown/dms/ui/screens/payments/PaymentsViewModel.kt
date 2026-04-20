package com.topntown.dms.ui.screens.payments

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject

/**
 * Pay screen — read-only summary of today's deliveries grouped by store.
 *
 * Cash is automatically considered collected at delivery time (delivery.total_value
 * IS the cash captured). No separate "record payment" flow exists, so this screen
 * is purely informational: "here's what was delivered and therefore collected, by store."
 */
data class PaymentsUiState(
    val stores: List<PayStoreRow> = emptyList(),
    val totalCollected: Double = 0.0,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)

data class PayStoreRow(
    val storeId: String,
    val storeName: String,
    val deliveriesCount: Int,
    val totalValue: Double,
    val latestDeliveredAt: String  // raw ISO for now; screen formats
)

@Serializable
private data class PaySummaryRpcRow(
    @SerialName("store_id") val storeId: String = "",
    @SerialName("store_name") val storeName: String? = null,
    @SerialName("deliveries_count") val deliveriesCount: Int = 0,
    @SerialName("total_value") val totalValue: Double = 0.0,
    @SerialName("latest_delivered") val latestDelivered: String = ""
)

private const val TAG = "PaymentsVM"

@HiltViewModel
class PaymentsViewModel @Inject constructor(
    private val postgrest: Postgrest
) : ViewModel() {

    private val _uiState = MutableStateFlow(PaymentsUiState())
    val uiState: StateFlow<PaymentsUiState> = _uiState.asStateFlow()

    init { load(isRefresh = false) }

    fun refresh() = load(isRefresh = true)

    private fun load(isRefresh: Boolean) {
        _uiState.update {
            it.copy(
                isLoading = !isRefresh,
                isRefreshing = isRefresh,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    postgrest.rpc("get_pay_summary").decodeList<PaySummaryRpcRow>()
                }
            }

            result.onSuccess { rows ->
                val mapped = rows.map { r ->
                    PayStoreRow(
                        storeId = r.storeId,
                        storeName = r.storeName ?: "(unnamed store)",
                        deliveriesCount = r.deliveriesCount,
                        totalValue = r.totalValue,
                        latestDeliveredAt = r.latestDelivered
                    )
                }
                _uiState.update {
                    it.copy(
                        stores = mapped,
                        totalCollected = mapped.sumOf { s -> s.totalValue },
                        isLoading = false,
                        isRefreshing = false
                    )
                }
            }.onFailure { e ->
                Log.e(TAG, "get_pay_summary failed", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = e.message ?: "Could not load today's collections."
                    )
                }
            }
        }
    }
}
