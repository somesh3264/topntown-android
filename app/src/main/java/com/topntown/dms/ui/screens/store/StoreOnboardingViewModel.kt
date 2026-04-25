package com.topntown.dms.ui.screens.store

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topntown.dms.data.repository.StoreOnboardingRepository
import com.topntown.dms.domain.model.LookupRow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Form state for the distributor-side store-onboarding screen.
 *
 * Field set deliberately mirrors the dashboard's `StoreForm.tsx` minus the
 * "Assigned Distributor" picker — a distributor onboarding their own store is
 * always the primary distributor, and the SECURITY DEFINER RPC fixes that
 * server-side regardless of what the client sends.
 */
data class StoreOnboardingUiState(
    // ── Form fields ──────────────────────────────────────────────────────────
    val storeName: String = "",
    val ownerName: String = "",
    val phone: String = "",
    val address: String = "",

    // ── Cascading dropdowns ──────────────────────────────────────────────────
    val zones: List<LookupRow> = emptyList(),
    val areas: List<LookupRow> = emptyList(),
    val zoneId: String = "",
    val areaId: String = "",
    val areasLoading: Boolean = false,

    // ── GPS ──────────────────────────────────────────────────────────────────
    val gpsLat: Double? = null,
    val gpsLng: Double? = null,
    val gpsCapturing: Boolean = false,
    /** True after the first capture attempt — drives "Re-capture" button label. */
    val gpsAttempted: Boolean = false,

    // ── Photo ────────────────────────────────────────────────────────────────
    /** In-memory thumbnail to render in the UI. Cleared on submit. */
    val photoBitmap: Bitmap? = null,
    /** Public URL after the photo lands in Storage. Sent to the RPC. */
    val photoUrl: String? = null,
    val photoUploading: Boolean = false,

    // ── Top-level state ──────────────────────────────────────────────────────
    val isSubmitting: Boolean = false,
    /** Set on success — UI uses it to show the confirmation + nav back. */
    val submittedStoreId: String? = null,
    val error: String? = null,
) {
    val canSubmit: Boolean
        get() = storeName.isNotBlank()
            && areaId.isNotBlank()
            && gpsLat != null && gpsLng != null
            && photoUrl != null
            && !isSubmitting
            && !photoUploading
}

@HiltViewModel
class StoreOnboardingViewModel @Inject constructor(
    private val repository: StoreOnboardingRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StoreOnboardingUiState())
    val uiState: StateFlow<StoreOnboardingUiState> = _uiState.asStateFlow()

    init {
        loadZones()
    }

    // ── Field setters ────────────────────────────────────────────────────────

    fun onStoreNameChanged(value: String) =
        _uiState.update { it.copy(storeName = value, error = null) }

    fun onOwnerNameChanged(value: String) =
        _uiState.update { it.copy(ownerName = value) }

    /** Phone is constrained to 10 digits to match the server-side guard. */
    fun onPhoneChanged(value: String) =
        _uiState.update { it.copy(phone = value.filter(Char::isDigit).take(10)) }

    fun onAddressChanged(value: String) =
        _uiState.update { it.copy(address = value) }

    fun onZoneSelected(zoneId: String) {
        _uiState.update {
            // Reset the area whenever the zone changes — the previously-selected
            // area is no longer in the list.
            it.copy(zoneId = zoneId, areaId = "", areas = emptyList())
        }
        if (zoneId.isNotBlank()) loadAreas(zoneId)
    }

    fun onAreaSelected(areaId: String) =
        _uiState.update { it.copy(areaId = areaId) }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    // ── Lookup loaders ───────────────────────────────────────────────────────

    private fun loadZones() {
        viewModelScope.launch {
            repository.getZones().fold(
                onSuccess = { zones -> _uiState.update { it.copy(zones = zones) } },
                onFailure = { e -> _uiState.update { it.copy(error = "Could not load zones: ${e.message}") } }
            )
        }
    }

    private fun loadAreas(zoneId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(areasLoading = true) }
            repository.getAreasForZone(zoneId).fold(
                onSuccess = { areas ->
                    _uiState.update { it.copy(areas = areas, areasLoading = false) }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(areasLoading = false, error = "Could not load areas: ${e.message}")
                    }
                }
            )
        }
    }

    // ── GPS capture ──────────────────────────────────────────────────────────

    /**
     * Pulls the most recent fix from FusedLocation/LocationManager. Reuses the
     * pattern from BeatViewModel — a fully synchronous "best last fix" probe;
     * the screen prompts for ACCESS_FINE_LOCATION before invoking this.
     *
     * If no fix is available we surface that to the user — they can then walk
     * to a window or step outside and retry. We do NOT block submission with
     * stale coordinates.
     */
    fun captureGps() {
        _uiState.update { it.copy(gpsCapturing = true, gpsAttempted = true) }
        val location = bestEffortLocation()
        if (location != null) {
            _uiState.update {
                it.copy(
                    gpsLat = location.latitude,
                    gpsLng = location.longitude,
                    gpsCapturing = false
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    gpsCapturing = false,
                    error = "Couldn't get a GPS fix. Step outside or wait a moment, then tap Capture again."
                )
            }
        }
    }

    @SuppressLint("MissingPermission")  // we check permissions explicitly here
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
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    // ── Photo capture + upload ───────────────────────────────────────────────

    /**
     * Compress the captured Bitmap (TakePicturePreview returns one) and stream
     * it to Storage straight away. We upload eagerly — even before the user
     * fills in the rest of the form — so the slow operation is overlapped
     * with their typing rather than tacked on to Submit.
     *
     * BR-12 (mandatory live shop photo): we don't accept an image picked from
     * the gallery. The screen wires the contract to TakePicturePreview only.
     */
    fun onPhotoCaptured(bitmap: Bitmap) {
        // Re-capture clears any previous URL — the old object is orphaned in
        // Storage but lives under the user's pending/<uid>/ folder so it
        // doesn't pollute the public listing of any real store.
        _uiState.update {
            it.copy(photoBitmap = bitmap, photoUrl = null, photoUploading = true, error = null)
        }
        viewModelScope.launch {
            val jpeg = compressToJpeg(bitmap)
            repository.uploadPendingPhoto(jpeg).fold(
                onSuccess = { url ->
                    _uiState.update { it.copy(photoUrl = url, photoUploading = false) }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            photoBitmap = null,
                            photoUploading = false,
                            error = "Photo upload failed: ${e.message}. Tap the camera again to retry."
                        )
                    }
                }
            )
        }
    }

    /**
     * 80% quality JPEG keeps photos around 200–300 KB on a 12-MP capture —
     * fine for the Super Admin's verification thumbnail, and well below the
     * 50 MB Storage limit. We don't downsample further: a low-res shop photo
     * defeats the purpose of BR-12.
     */
    private fun compressToJpeg(bitmap: Bitmap): ByteArray {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        return baos.toByteArray()
    }

    // ── Submit ───────────────────────────────────────────────────────────────

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            repository.submitStore(
                name = state.storeName.trim(),
                ownerName = state.ownerName.trim(),
                phone = state.phone.trim(),
                address = state.address.trim(),
                areaId = state.areaId,
                gpsLat = state.gpsLat!!,
                gpsLng = state.gpsLng!!,
                photoUrl = state.photoUrl!!,
            ).fold(
                onSuccess = { storeId ->
                    _uiState.update {
                        it.copy(isSubmitting = false, submittedStoreId = storeId)
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            error = "Submission failed: ${e.message}"
                        )
                    }
                }
            )
        }
    }
}
