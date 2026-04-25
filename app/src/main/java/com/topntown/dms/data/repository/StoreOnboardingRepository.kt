package com.topntown.dms.data.repository

import com.topntown.dms.data.datastore.UserPreferences
import com.topntown.dms.domain.model.LookupRow
import com.topntown.dms.domain.model.PendingStoreRow
import com.topntown.dms.domain.model.SubmitStoreResponse
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for the distributor-side **store onboarding** workflow
 * (FRD v1.2 — BR-11, BR-12). Encapsulates everything the Android app needs
 * to add a new store and track approvals:
 *
 *   • Zone / Area lookups (via `get_zones_for_onboarding` / `get_areas_for_onboarding` RPCs).
 *   • Photo upload to Supabase Storage (`store-photos` bucket).
 *   • Store submission via the `submit_store_for_approval` SECURITY DEFINER RPC.
 *   • Read-only listing of the distributor's own submissions
 *     (`get_my_pending_stores`).
 *
 * This is intentionally kept separate from [StoreRepository] (which serves the
 * Beat / Deliver screens) because the onboarding workflow speaks to RPCs and
 * Storage, not the raw `stores` table — the dashboard does it the same way for
 * the same reasons (see actions.ts).
 */
@Singleton
class StoreOnboardingRepository @Inject constructor(
    private val postgrest: Postgrest,
    private val storage: Storage,
    // We pull the UID from UserPreferences rather than `auth.currentUserOrNull()`.
    // The Supabase Kotlin SDK can return a null user even when there's a valid
    // authenticated session (the user object is loaded lazily); UserPreferences
    // is populated at login time by AuthRepository and is reliable across
    // app restarts.
    private val userPreferences: UserPreferences,
) {

    companion object {
        private const val BUCKET = "store-photos"
    }

    /** Zones the distributor can onboard into. */
    suspend fun getZones(): Result<List<LookupRow>> = runCatching {
        postgrest.rpc("get_zones_for_onboarding").decodeList<LookupRow>()
    }

    /** Areas in the chosen zone. Cascading-dropdown helper. */
    suspend fun getAreasForZone(zoneId: String): Result<List<LookupRow>> = runCatching {
        postgrest.rpc(
            "get_areas_for_onboarding",
            buildJsonObject { put("p_zone_id", JsonPrimitive(zoneId)) }
        ).decodeList<LookupRow>()
    }

    /**
     * Upload a JPEG photo for the as-yet-uncreated store to Storage. We don't
     * have a store_id at this point so the path lives under `pending/<uid>/…`
     * — Storage RLS confines distributors to their own pending prefix.
     *
     * Returns the public URL we can hand to the SECURITY DEFINER RPC.
     */
    suspend fun uploadPendingPhoto(jpegBytes: ByteArray): Result<String> = runCatching {
        val uid = userPreferences.sessionFlow.first().userId
        if (uid.isBlank()) error("Not authenticated — please sign in again.")

        val path = "pending/$uid/${System.currentTimeMillis()}.jpg"

        val bucket = storage.from(BUCKET)
        bucket.upload(
            path = path,
            data = jpegBytes,
            upsert = false
        )
        bucket.publicUrl(path)
    }

    /**
     * Submit a new store for Super-Admin approval. Mirrors the dashboard's
     * `createStore` server action but takes the photo's *URL* (already
     * uploaded) rather than a base64 blob — we don't want to round-trip the
     * image through Postgres.
     *
     * The RPC enforces ownership and validation on the server; this client
     * function deliberately does NOT pass primary_distributor_id, is_active,
     * or onboarded_by — those are fixed by the SECURITY DEFINER body.
     */
    suspend fun submitStore(
        name: String,
        ownerName: String,
        phone: String,
        address: String,
        areaId: String,
        gpsLat: Double,
        gpsLng: Double,
        photoUrl: String,
    ): Result<String> = runCatching {
        val params = buildJsonObject {
            put("p_name", JsonPrimitive(name))
            put("p_owner_name", JsonPrimitive(ownerName))
            put("p_phone", JsonPrimitive(phone))
            put("p_address", JsonPrimitive(address))
            put("p_area_id", JsonPrimitive(areaId))
            put("p_gps_lat", JsonPrimitive(gpsLat))
            put("p_gps_lng", JsonPrimitive(gpsLng))
            put("p_photo_url", JsonPrimitive(photoUrl))
        }
        // The RPC returns a single-row table { store_id: uuid }, which
        // PostgREST renders as `[{"store_id": "..."}]`. We unwrap to a string
        // so the caller can hold a plain id without coupling to the DTO.
        val rows = postgrest.rpc("submit_store_for_approval", params)
            .decodeList<SubmitStoreResponse>()
        rows.firstOrNull()?.store_id
            ?: error("submit_store_for_approval returned no rows")
    }

    /** Distributor-scoped list of own submissions, newest first. */
    suspend fun getMyPendingStores(): Result<List<PendingStoreRow>> = runCatching {
        postgrest.rpc("get_my_pending_stores").decodeList<PendingStoreRow>()
    }
}
