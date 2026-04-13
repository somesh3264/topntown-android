package com.topntown.dms.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topntown.dms.data.datastore.UserPreferences
import com.topntown.dms.data.datastore.UserSession
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Derived state for the home dashboard. Kept as a flat data class rather than
 * a sealed hierarchy because every KPI refreshes together — there's no partial
 * success state worth rendering separately.
 */
data class HomeUiState(
    val session: UserSession = UserSession(),
    val greetingSegment: String = "morning",          // "morning" | "afternoon" | "evening"

    // KPI tiles
    val deliveriesToday: Int = 0,
    val assignedStoresToday: Int = 0,
    val billReady: Boolean = false,
    val billLabel: String = "Awaiting tonight's bill",
    val stockRemainingPct: Int = 0,
    val paymentsCollectedInr: Double = 0.0,

    // Stock breakdown
    val topStock: List<StockRow> = emptyList(),

    // Action button gating — Place Order is disabled after the daily cut-off
    // (default 18:00 local), which is when the distributor backend starts
    // compiling tonight's consolidated bill.
    val placeOrderEnabled: Boolean = true,
    val cutOffStatus: String = "",

    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)

data class StockRow(
    val sku: String,
    val productName: String,
    val allocated: Int,
    val remaining: Int
) {
    /** 0f..1f — clamped so a negative/inflated backend value can't blow up the bar. */
    val progress: Float
        get() = if (allocated <= 0) 0f
        else (remaining.toFloat() / allocated.toFloat()).coerceIn(0f, 1f)
}

/**
 * Shape of the `bills` row we care about on Home. Defined inline (rather than
 * adding to domain/model/Models.kt) because only this screen consumes it
 * today; we can promote it later if Bill screens start using the same fields.
 */
@Serializable
private data class BillRow(
    val id: String = "",
    @SerialName("distributor_id") val distributorId: String = "",
    @SerialName("bill_date") val billDate: String = "",
    val status: String = "pending"
)

@Serializable
private data class StockAllocationRow(
    val id: String = "",
    @SerialName("distributor_id") val distributorId: String = "",
    @SerialName("bill_id") val billId: String = "",
    @SerialName("product_id") val productId: String = "",
    val sku: String = "",
    @SerialName("product_name") val productName: String = "",
    @SerialName("quantity_allocated") val allocated: Int = 0,
    @SerialName("quantity_remaining") val remaining: Int = 0
)

@Serializable
private data class PaymentAmountRow(val amount: Double = 0.0)

@Serializable
private data class DeliveryCountRow(val id: String = "")

/** Daily cut-off after which distributors can no longer place new orders. */
private val ORDER_CUTOFF: LocalTime = LocalTime.of(18, 0)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val postgrest: Postgrest
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // Session is the source of truth for distributor_id; every KPI query
        // is scoped by it, so we collect it first, then fan out.
        viewModelScope.launch {
            userPreferences.sessionFlow.collect { session ->
                _uiState.update { it.copy(session = session, greetingSegment = currentGreetingSegment()) }
                if (session.isValid) {
                    loadDashboard(session, isRefresh = false)
                }
            }
        }
    }

    /**
     * Pull-to-refresh entry point. Uses the session already in state; we
     * intentionally don't re-read DataStore here — the collector in [init]
     * keeps [HomeUiState.session] current.
     */
    fun refresh() {
        val session = _uiState.value.session
        if (!session.isValid) return
        viewModelScope.launch { loadDashboard(session, isRefresh = true) }
    }

    private suspend fun loadDashboard(session: UserSession, isRefresh: Boolean) {
        _uiState.update {
            it.copy(
                isLoading = !isRefresh && it.isLoading,
                isRefreshing = isRefresh,
                errorMessage = null,
                greetingSegment = currentGreetingSegment(),
                placeOrderEnabled = LocalTime.now().isBefore(ORDER_CUTOFF),
                cutOffStatus = cutOffStatusText()
            )
        }

        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val distributorId = session.userId

        // Run all four queries in parallel on the IO dispatcher. Each call is
        // wrapped in its own runCatching so one failed query (e.g. a missing
        // bill row) doesn't wipe the other KPIs.
        withContext(Dispatchers.IO) {
            val deliveriesJob = async {
                runCatching {
                    postgrest.from("deliveries")
                        .select(Columns.raw("id")) {
                            filter {
                                eq("distributor_id", distributorId)
                                eq("delivery_date", today)
                            }
                        }
                        .decodeList<DeliveryCountRow>()
                        .size
                }.getOrDefault(0)
            }

            val assignedStoresJob = async {
                runCatching {
                    postgrest.from("stores")
                        .select(Columns.raw("id")) {
                            filter {
                                eq("distributor_id", distributorId)
                                eq("is_active", true)
                            }
                        }
                        .decodeList<DeliveryCountRow>()
                        .size
                }.getOrDefault(0)
            }

            val billJob = async {
                runCatching {
                    postgrest.from("bills")
                        .select(Columns.ALL) {
                            filter {
                                eq("distributor_id", distributorId)
                                eq("bill_date", today)
                            }
                            limit(1)
                        }
                        .decodeList<BillRow>()
                        .firstOrNull()
                }.getOrNull()
            }

            val stockJob = async {
                runCatching {
                    postgrest.from("stock_allocations")
                        .select(Columns.ALL) {
                            filter { eq("distributor_id", distributorId) }
                            order("quantity_remaining", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                        }
                        .decodeList<StockAllocationRow>()
                }.getOrDefault(emptyList())
            }

            val paymentsJob = async {
                runCatching {
                    // Postgrest doesn't easily support DATE(collected_at) via
                    // filter DSL, so we filter on the half-open [today, tomorrow)
                    // window, which is index-friendly anyway.
                    val tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                    postgrest.from("payments")
                        .select(Columns.raw("amount")) {
                            filter {
                                eq("distributor_id", distributorId)
                                gte("collected_at", today)
                                lt("collected_at", tomorrow)
                            }
                        }
                        .decodeList<PaymentAmountRow>()
                        .sumOf { it.amount }
                }.getOrDefault(0.0)
            }

            // awaitAll() can't return a mixed-type tuple, so await each Deferred
            // individually. Because all five were launched via `async` before
            // the first `.await()`, they still execute concurrently.
            val deliveries = deliveriesJob.await()
            val assigned = assignedStoresJob.await()
            val bill = billJob.await()
            val stock = stockJob.await()
            val payments = paymentsJob.await()

            val totalAllocated = stock.sumOf { it.allocated }
            val totalRemaining = stock.sumOf { it.remaining }
            val remainingPct = if (totalAllocated > 0) {
                ((totalRemaining.toDouble() / totalAllocated.toDouble()) * 100).toInt()
            } else 0

            val billReady = bill?.status?.equals("ready", ignoreCase = true) == true
            val billLabel = when {
                billReady -> "Bill Ready"
                bill != null -> "Bill ${bill.status.replaceFirstChar { it.uppercase() }}"
                else -> "Awaiting tonight's bill"
            }

            _uiState.update {
                it.copy(
                    deliveriesToday = deliveries,
                    assignedStoresToday = assigned,
                    billReady = billReady,
                    billLabel = billLabel,
                    stockRemainingPct = remainingPct,
                    paymentsCollectedInr = payments,
                    topStock = stock.take(5).map { row ->
                        StockRow(
                            sku = row.sku,
                            productName = row.productName,
                            allocated = row.allocated,
                            remaining = row.remaining
                        )
                    },
                    isLoading = false,
                    isRefreshing = false
                )
            }
        }
    }

    private fun currentGreetingSegment(): String {
        val hour = LocalTime.now().hour
        return when {
            hour < 12 -> "morning"
            hour < 17 -> "afternoon"
            else -> "evening"
        }
    }

    private fun cutOffStatusText(): String {
        val now = LocalTime.now()
        return if (now.isBefore(ORDER_CUTOFF)) {
            "Order cut-off at ${ORDER_CUTOFF.format(DateTimeFormatter.ofPattern("h:mm a"))}"
        } else {
            "Cut-off passed — orders resume tomorrow"
        }
    }
}

