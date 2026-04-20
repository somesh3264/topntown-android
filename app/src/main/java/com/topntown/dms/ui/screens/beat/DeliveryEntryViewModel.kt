package com.topntown.dms.ui.screens.beat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per-store delivery entry screen state. Starts in Loading, becomes Entry
 * once products load, transitions through Confirming / Submitting / Success
 * based on user actions.
 */
data class DeliveryEntryUiState(
    val storeName: String = "",
    val storeArea: String = "",
    val distanceMeters: Double? = null,
    val products: List<DeliveryProduct> = emptyList(),
    val quantities: Map<String, Int> = emptyMap(),
    val phase: Phase = Phase.Loading,
    val errorMessage: String? = null,
    // After success
    val deliveredTotal: Double = 0.0,
    val deliveredItemCount: Int = 0,
    val gpsCaptured: Boolean = false
) {
    enum class Phase { Loading, Entry, Confirming, Submitting, Success }

    val totalValue: Double
        get() = products.sumOf { p ->
            (quantities[p.productId] ?: 0) * p.unitPrice
        }
    val itemCount: Int
        get() = quantities.count { it.value > 0 }
    val canSubmit: Boolean
        get() = itemCount > 0 && phase == Phase.Entry
}

data class DeliveryProduct(
    val productId: String,
    val productName: String,
    val productWeight: String,
    val unitPrice: Double,
    val stockRemaining: Int
)

@Serializable
private data class ContextRpcRow(
    @SerialName("store_id") val storeId: String = "",
    @SerialName("store_name") val storeName: String? = null,
    @SerialName("store_area") val storeArea: String? = null,
    @SerialName("store_gps_lat") val storeGpsLat: Double? = null,
    @SerialName("store_gps_lng") val storeGpsLng: Double? = null,
    @SerialName("product_id") val productId: String = "",
    @SerialName("product_name") val productName: String? = null,
    @SerialName("product_weight") val productWeight: String? = null,
    @SerialName("unit_price") val unitPrice: Double = 0.0,
    @SerialName("stock_remaining") val stockRemaining: Double = 0.0
)

@Serializable
private data class SubmitRpcRow(
    @SerialName("delivery_id") val deliveryId: String = "",
    @SerialName("total_value") val totalValue: Double = 0.0,
    @SerialName("item_count") val itemCount: Int = 0
)

private const val TAG = "DeliveryEntryVM"

@HiltViewModel
class DeliveryEntryViewModel @Inject constructor(
    private val postgrest: Postgrest,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** Pulled from NavGraph's deliver_entry/{storeId} argument. */
    private val storeId: String = savedStateHandle.get<String>("storeId").orEmpty()

    private val _uiState = MutableStateFlow(DeliveryEntryUiState())
    val uiState: StateFlow<DeliveryEntryUiState> = _uiState.asStateFlow()

    init { load() }

    fun increment(productId: String) {
        val state = _uiState.value
        if (state.phase != DeliveryEntryUiState.Phase.Entry) return
        val product = state.products.firstOrNull { it.productId == productId } ?: return
        val current = state.quantities[productId] ?: 0
        if (current >= product.stockRemaining) return  // hard-block at allocated stock
        _uiState.update { it.copy(quantities = it.quantities + (productId to current + 1)) }
    }

    fun decrement(productId: String) {
        val state = _uiState.value
        if (state.phase != DeliveryEntryUiState.Phase.Entry) return
        val current = state.quantities[productId] ?: 0
        if (current <= 0) return
        _uiState.update { it.copy(quantities = it.quantities + (productId to current - 1)) }
    }

    fun openConfirm() {
        if (_uiState.value.canSubmit) {
            _uiState.update { it.copy(phase = DeliveryEntryUiState.Phase.Confirming) }
        }
    }

    fun cancelConfirm() {
        _uiState.update { it.copy(phase = DeliveryEntryUiState.Phase.Entry) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun submit() {
        val state = _uiState.value
        if (state.phase != DeliveryEntryUiState.Phase.Confirming) return
        _uiState.update { it.copy(phase = DeliveryEntryUiState.Phase.Submitting, errorMessage = null) }

        viewModelScope.launch {
            val location = bestEffortLocation()
            val payload = buildJsonObject {
                put("p_store_id", storeId)
                put("p_items", buildJsonArray {
                    state.quantities
                        .filter { it.value > 0 }
                        .forEach { (pid, qty) ->
                            addJsonObject {
                                put("product_id", pid)
                                put("quantity", qty)
                            }
                        }
                })
                location?.let {
                    put("p_gps_lat", it.first)
                    put("p_gps_lng", it.second)
                }
            }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    postgrest.rpc("submit_delivery", payload)
                        .decodeList<SubmitRpcRow>()
                }
            }

            result.onSuccess { rows ->
                val row = rows.firstOrNull()
                if (row == null) {
                    _uiState.update {
                        it.copy(
                            phase = DeliveryEntryUiState.Phase.Entry,
                            errorMessage = "Delivery could not be saved — no response from server."
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            phase = DeliveryEntryUiState.Phase.Success,
                            deliveredTotal = row.totalValue,
                            deliveredItemCount = row.itemCount,
                            gpsCaptured = location != null
                        )
                    }
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        phase = DeliveryEntryUiState.Phase.Entry,
                        errorMessage = e.message ?: "Delivery submission failed."
                    )
                }
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(phase = DeliveryEntryUiState.Phase.Loading) }

            Log.d(TAG, "load(): calling get_store_delivery_context with storeId=$storeId")

            val rowsResult = withContext(Dispatchers.IO) {
                runCatching {
                    postgrest.rpc(
                        "get_store_delivery_context",
                        buildJsonObject { put("p_store_id", storeId) }
                    ).decodeList<ContextRpcRow>()
                }
            }

            val rows = rowsResult.getOrElse { e ->
                Log.e(TAG, "get_store_delivery_context failed", e)
                _uiState.update {
                    it.copy(
                        phase = DeliveryEntryUiState.Phase.Entry,
                        errorMessage = "Could not load products — ${e.message ?: "unknown error"}"
                    )
                }
                return@launch
            }

            Log.d(TAG, "load(): RPC returned ${rows.size} row(s)")

            if (rows.isEmpty()) {
                _uiState.update {
                    it.copy(
                        phase = DeliveryEntryUiState.Phase.Entry,
                        errorMessage = "No deliverable stock. Make sure an order has been picked up from the factory."
                    )
                }
                return@launch
            }

            val first = rows.first()
            val products = rows.map { r ->
                DeliveryProduct(
                    productId = r.productId,
                    productName = r.productName ?: "(unnamed)",
                    productWeight = r.productWeight.orEmpty(),
                    unitPrice = r.unitPrice,
                    stockRemaining = r.stockRemaining.toInt()
                )
            }

            val myLocation = bestEffortLocation()
            val distanceMeters = if (
                myLocation != null && first.storeGpsLat != null && first.storeGpsLng != null
            ) haversineMeters(myLocation.first, myLocation.second, first.storeGpsLat, first.storeGpsLng)
            else null

            _uiState.update {
                it.copy(
                    storeName = first.storeName ?: "(unnamed store)",
                    storeArea = first.storeArea.orEmpty(),
                    distanceMeters = distanceMeters,
                    products = products,
                    quantities = emptyMap(),
                    phase = DeliveryEntryUiState.Phase.Entry
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestEffortLocation(): Pair<Double, Double>? {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = buildList {
            if (fine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        val loc = providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?: return null
        return loc.latitude to loc.longitude
    }

    private fun haversineMeters(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double
    ): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
