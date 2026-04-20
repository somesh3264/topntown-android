package com.topntown.dms.ui.screens.stock

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
 * Stock Balance screen state. Summary row = totals across all SKUs;
 * per-SKU rows are rendered as cards with a progress bar below.
 */
data class StockUiState(
    val totalAllocated: Int = 0,
    val totalDelivered: Int = 0,
    val totalRemaining: Int = 0,
    val rows: List<StockRow> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)

data class StockRow(
    val productId: String,
    val productName: String,
    val skuCode: String,
    val category: String,
    val allocated: Int,
    val delivered: Int,
    val remaining: Int
) {
    /** 0..100 — the delivered percentage of the allocation. */
    val deliveredPct: Int
        get() = if (allocated <= 0) 0
        else ((delivered.toFloat() / allocated.toFloat()) * 100f).toInt().coerceIn(0, 100)

    /** 0f..1f for the progress bar. */
    val progress: Float
        get() = if (allocated <= 0) 0f else (delivered.toFloat() / allocated.toFloat()).coerceIn(0f, 1f)
}

@Serializable
private data class StockRpcRow(
    @SerialName("product_id") val productId: String = "",
    @SerialName("product_name") val productName: String? = null,
    @SerialName("sku_code") val skuCode: String? = null,
    val category: String? = null,
    @SerialName("allocated_qty") val allocatedQty: Double = 0.0,
    @SerialName("delivered_qty") val deliveredQty: Double = 0.0,
    @SerialName("remaining_qty") val remainingQty: Double = 0.0
)

@HiltViewModel
class StockViewModel @Inject constructor(
    private val postgrest: Postgrest
) : ViewModel() {

    private val _uiState = MutableStateFlow(StockUiState())
    val uiState: StateFlow<StockUiState> = _uiState.asStateFlow()

    init { load(isRefresh = false) }

    fun refresh() = load(isRefresh = true)

    private fun load(isRefresh: Boolean) {
        _uiState.update { it.copy(isLoading = !isRefresh, isRefreshing = isRefresh, errorMessage = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val rows = runCatching {
                    postgrest.rpc("get_stock_balance")
                        .decodeList<StockRpcRow>()
                }.getOrDefault(emptyList())

                val mapped = rows.map { r ->
                    StockRow(
                        productId = r.productId,
                        productName = r.productName ?: "(unnamed)",
                        skuCode = r.skuCode ?: "—",
                        category = r.category ?: "",
                        allocated = r.allocatedQty.toInt(),
                        delivered = r.deliveredQty.toInt(),
                        remaining = r.remainingQty.toInt()
                    )
                }

                _uiState.update {
                    it.copy(
                        totalAllocated = mapped.sumOf { r -> r.allocated },
                        totalDelivered = mapped.sumOf { r -> r.delivered },
                        totalRemaining = mapped.sumOf { r -> r.remaining },
                        rows = mapped,
                        isLoading = false,
                        isRefreshing = false
                    )
                }
            }
        }
    }
}
