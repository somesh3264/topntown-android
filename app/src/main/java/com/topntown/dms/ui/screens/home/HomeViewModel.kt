package com.topntown.dms.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topntown.dms.data.datastore.UserPreferences
import com.topntown.dms.data.datastore.UserSession
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * UI state for the distributor Home screen. All KPIs come from a single
 * RPC (get_distributor_home); the "Today's Deliveries" list comes from a
 * second RPC (get_todays_deliveries). Countdown / greeting are derived
 * client-side from cut-off time.
 */
data class HomeUiState(
    val session: UserSession = UserSession(),
    val greetingSegment: String = "morning",          // "morning" | "afternoon" | "evening"

    // KPI tiles
    val deliveriesCount: Int = 0,
    val cashCollectedInr: Double = 0.0,
    val skusRemaining: Int = 0,
    val storesOnBeat: Int = 0,

    // Today's deliveries list (underneath the KPI grid)
    val todaysDeliveries: List<DeliveryItem> = emptyList(),

    // Cut-off — drives the countdown card at the top of Home
    val cutoffTime: String = "14:00",   // HH:MM (IST)
    val cutoffEnabled: Boolean = true,
    val cutoffPassed: Boolean = false,
    val timeUntilCutoff: String = "",   // "13h 2m" or "" when passed

    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)

data class DeliveryItem(
    val id: String,
    val storeName: String,
    val itemCount: Int,
    val deliveredAt: String, // pre-formatted "09:12 AM" style
    val totalValue: Double
)

// ── RPC DTOs ─────────────────────────────────────────────────────────────────
// Must match the column names of the RETURNS TABLE clauses in the migration
// 20260420140000_distributor_app_rpcs.sql.

@Serializable
private data class HomeRpcRow(
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("deliveries_count") val deliveriesCount: Int = 0,
    @SerialName("cash_collected") val cashCollected: Double = 0.0,
    @SerialName("skus_remaining") val skusRemaining: Double = 0.0,
    @SerialName("stores_on_beat") val storesOnBeat: Int = 0,
    @SerialName("cutoff_time") val cutoffTime: String = "14:00",
    @SerialName("cutoff_enabled") val cutoffEnabled: Boolean = true,
    @SerialName("support_contact") val supportContact: String? = null
)

@Serializable
private data class DeliveryRpcRow(
    @SerialName("delivery_id") val deliveryId: String = "",
    @SerialName("store_id") val storeId: String = "",
    @SerialName("store_name") val storeName: String? = null,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("delivered_at") val deliveredAt: String = "",
    @SerialName("total_value") val totalValue: Double = 0.0
)

private val IST = ZoneId.of("Asia/Kolkata")
private val TIME_DISPLAY_FMT = DateTimeFormatter.ofPattern("hh:mm a")

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val postgrest: Postgrest
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // First: pick up session (for full_name fallback + re-auth recovery).
        viewModelScope.launch {
            userPreferences.sessionFlow.collect { session ->
                _uiState.update {
                    it.copy(
                        session = session,
                        greetingSegment = currentGreetingSegment()
                    )
                }
                if (session.isValid) loadHome(isRefresh = false)
            }
        }
    }

    fun refresh() {
        if (!_uiState.value.session.isValid) return
        viewModelScope.launch { loadHome(isRefresh = true) }
    }

    private suspend fun loadHome(isRefresh: Boolean) {
        _uiState.update {
            it.copy(
                isLoading = !isRefresh && it.isLoading,
                isRefreshing = isRefresh,
                errorMessage = null,
                greetingSegment = currentGreetingSegment()
            )
        }

        withContext(Dispatchers.IO) {
            val homeJob = async {
                runCatching {
                    postgrest.rpc("get_distributor_home")
                        .decodeList<HomeRpcRow>()
                        .firstOrNull()
                }.getOrNull()
            }

            val deliveriesJob = async {
                runCatching {
                    postgrest.rpc("get_todays_deliveries")
                        .decodeList<DeliveryRpcRow>()
                }.getOrDefault(emptyList())
            }

            val home = homeJob.await()
            val deliveriesRaw = deliveriesJob.await()

            val cutoffTime = home?.cutoffTime ?: "14:00"
            val (cutoffPassed, msUntil) = computeCutoffState(cutoffTime)

            _uiState.update {
                it.copy(
                    // Prefer RPC's full_name so the screen updates when profile
                    // changes without requiring a new login session.
                    session = if (!home?.fullName.isNullOrBlank())
                        it.session.copy(fullName = home!!.fullName!!)
                    else it.session,
                    deliveriesCount = home?.deliveriesCount ?: 0,
                    cashCollectedInr = home?.cashCollected ?: 0.0,
                    skusRemaining = (home?.skusRemaining ?: 0.0).toInt(),
                    storesOnBeat = home?.storesOnBeat ?: 0,
                    cutoffTime = cutoffTime,
                    cutoffEnabled = home?.cutoffEnabled ?: true,
                    cutoffPassed = cutoffPassed,
                    timeUntilCutoff = if (cutoffPassed) "" else formatHoursMinutes(msUntil),
                    todaysDeliveries = deliveriesRaw.map { r ->
                        DeliveryItem(
                            id = r.deliveryId,
                            storeName = r.storeName ?: "(unnamed store)",
                            itemCount = r.itemCount,
                            deliveredAt = formatDeliveredAt(r.deliveredAt),
                            totalValue = r.totalValue
                        )
                    },
                    isLoading = false,
                    isRefreshing = false
                )
            }
        }
    }

    private fun currentGreetingSegment(): String {
        val hour = LocalDateTime.now(IST).hour
        return when {
            hour < 12 -> "morning"
            hour < 17 -> "afternoon"
            else -> "evening"
        }
    }

    /**
     * Returns (passed, msUntilNext). If today's cut-off has already passed,
     * the "next" cut-off is tomorrow, and passed=true.
     */
    private fun computeCutoffState(hhmm: String): Pair<Boolean, Long> {
        val parts = hhmm.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size != 2) return false to 0L
        val now = LocalDateTime.now(IST)
        var target = LocalDateTime.of(now.toLocalDate(), LocalTime.of(parts[0], parts[1]))
        val passed = !now.isBefore(target)
        if (passed) target = target.plusDays(1)
        val duration = Duration.between(now, target).toMillis()
        return passed to duration
    }

    private fun formatHoursMinutes(ms: Long): String {
        if (ms <= 0) return "0h 0m"
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return "${h}h ${m}m"
    }

    /** Accepts an ISO8601 timestamp and returns "09:12 AM" in IST, or "—" on parse fail. */
    private fun formatDeliveredAt(iso: String): String = runCatching {
        // Supabase returns timestamptz as ISO with offset, e.g. 2026-04-20T09:12:34.567+00:00
        val parsed = java.time.OffsetDateTime.parse(iso)
        parsed.atZoneSameInstant(IST).toLocalTime().format(TIME_DISPLAY_FMT)
    }.getOrDefault("—")
}
