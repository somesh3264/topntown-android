package com.topntown.dms.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for the distributor-side store-onboarding flow (BR-11 / BR-12 from
 * FRD v1.2). These are deliberately split out from the existing [Store]
 * model in Models.kt because they map to specific RPCs (not the raw `stores`
 * table) and have different shapes — see
 * `supabase/migrations/20260425100000_distributor_store_onboarding.sql`.
 *
 * They live alongside the existing models so Hilt-injected repositories can
 * use them without dragging in extra Gradle modules.
 */

/** Result of `submit_store_for_approval` — just the new store UUID. */
@Serializable
data class SubmitStoreResponse(val store_id: String)

/** Lookup row used by both the Zone and Area pickers on the form. */
@Serializable
data class LookupRow(
    val id: String = "",
    val name: String = ""
)

/**
 * One row in the distributor's "My Submissions" list — populated from the
 * `get_my_pending_stores()` RPC. The status field is one of:
 *   "pending"   — Super Admin has not reviewed yet
 *   "approved"  — store is now live (also visible in Beat / Deliver)
 *   "rejected"  — Super Admin declined; rejectionReason explains why
 */
@Serializable
data class PendingStoreRow(
    @SerialName("approval_id")      val approvalId: String = "",
    @SerialName("store_id")         val storeId: String = "",
    @SerialName("store_name")       val storeName: String = "",
    @SerialName("area_name")        val areaName: String? = null,
    @SerialName("zone_name")        val zoneName: String? = null,
    val status: String = "pending",
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("submitted_at")     val submittedAt: String = "",
    @SerialName("reviewed_at")      val reviewedAt: String? = null
)
