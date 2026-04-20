package com.topntown.dms.ui.screens.beat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
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
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * State for the Deliver (Beat) screen.
 *
 * The list is always sorted — by distance when GPS is available, alphabetically
 * by name otherwise. The UI reads `gpsActive` to decide whether to show the
 * green "GPS active" banner and the distance chip on each card.
 */
data class BeatUiState(
    val stores: List<BeatStore> = emptyList(),
    val gpsActive: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null
)

data class BeatStore(
    val id: String,
    val name: String,
    val ownerName: String,
    val areaName: String,
    val gpsLat: Double?,
    val gpsLng: Double?,
    val lastDelivered: String?,  // ISO timestamp or null
    /** Metres from the distributor's current position. null if GPS unavailable. */
    val distanceMeters: Double?
) {
    val isNearest: Boolean get() = false  // set by the VM via copy(), see below
}

@Serializable
private data class StoreRpcRow(
    val id: String = "",
    val name: String? = null,
    @SerialName("owner_name") val ownerName: String? = null,
    @SerialName("area_name") val areaName: String? = null,
    @SerialName("gps_lat") val gpsLat: Double? = null,
    @SerialName("gps_lng") val gpsLng: Double? = null,
    @SerialName("last_delivered") val lastDelivered: String? = null
)

@HiltViewModel
class BeatViewModel @Inject constructor(
    private val postgrest: Postgrest,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(BeatUiState())
    val uiState: StateFlow<BeatUiState> = _uiState.asStateFlow()

    init { load() }

    fun refresh() = load()

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val location = bestEffortLocation()
            val gpsActive = location != null

            val rows: List<StoreRpcRow> = withContext(Dispatchers.IO) {
                runCatching {
                    postgrest.rpc("get_stores_for_beat")
                        .decodeList<StoreRpcRow>()
                }.getOrDefault(emptyList())
            }

            val stores = rows.map { r ->
                BeatStore(
                    id = r.id,
                    name = r.name ?: "(unnamed store)",
                    ownerName = r.ownerName.orEmpty(),
                    areaName = r.areaName.orEmpty(),
                    gpsLat = r.gpsLat,
                    gpsLng = r.gpsLng,
                    lastDelivered = r.lastDelivered,
                    distanceMeters = if (location != null && r.gpsLat != null && r.gpsLng != null)
                        haversineMeters(location.latitude, location.longitude, r.gpsLat, r.gpsLng)
                    else null
                )
            }

            val sorted = if (gpsActive) {
                // Nulls (stores missing GPS) float to the bottom via max-value fallback.
                stores.sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
            } else {
                stores.sortedBy { it.name.lowercase() }
            }

            _uiState.update {
                it.copy(
                    stores = sorted,
                    gpsActive = gpsActive,
                    isLoading = false
                )
            }
        }
    }

    /**
     * Tries to return the best recent location without blocking. Prefers GPS,
     * falls back to NETWORK. Returns null if neither provider has a fix or if
     * location permission isn't granted.
     */
    @SuppressLint("MissingPermission")  // checked explicitly below
    private fun bestEffortLocation(): Location? {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        val providers = buildList {
            if (fineGranted) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }

        return providers
            .mapNotNull { p ->
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            }
            // Prefer the most recent fix across all available providers.
            .maxByOrNull { it.time }
    }

    /** Haversine distance in metres. Simple, dependency-free, accurate within ~0.5%. */
    private fun haversineMeters(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double
    ): Double {
        val r = 6_371_000.0  // Earth radius in metres
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
