package com.topntown.dms.data.repository

import com.topntown.dms.data.datastore.UserPreferences
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.OtpType
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone OTP authentication backed by Supabase GoTrue.
 *
 * TNT distributors log in with a 10-digit phone number + a 6-digit OTP.
 * The phone is sent to GoTrue as the raw 10 digits (no country code), to
 * match the identifier format configured in our Supabase test phone list.
 *
 * With Supabase's "Test Phone Numbers" feature (dashboard → Authentication →
 * Providers → Phone), a preconfigured pair of (phone, OTP) is accepted by
 * [Auth.verifyPhoneOtp] without any SMS being dispatched — which is what we
 * rely on during QA. In production, a separate "Send OTP" step would call
 * [Auth.signInWith] with OTP provider first to trigger the SMS; see TODO below.
 *
 * NOTE: Flipping to a real SMS provider will require storing the phone in
 * E.164 (e.g. `+919...`) — see [COUNTRY_CODE] for the eventual prefix.
 *
 * After a successful verify we fetch the user's `profiles` row to read their
 * role and scope fields (zone_id / area_id), and enforce the distributor-only
 * rule here. Non-distributor roles are signed out immediately so a stale
 * Supabase session can't linger after we reject the login in the UI.
 *
 * Supabase-kt 2.x handles access-token refresh automatically via its
 * SessionManager, so we don't manually refresh anywhere in this class.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val auth: Auth,
    private val postgrest: Postgrest,
    private val userPreferences: UserPreferences
) {

    companion object {
        /**
         * India country code. Kept for future use when we switch to a real SMS
         * provider — at that point we'll prepend it back in [signIn] and also
         * update existing Supabase phone identifiers to E.164.
         */
        const val COUNTRY_CODE = "+91"

        /** The only role permitted to use this Android app. */
        const val ALLOWED_ROLE = "distributor"

        private const val PROFILES_TABLE = "profiles"
        private val PROFILE_COLUMNS = Columns.list(
            "id",
            "role",
            "full_name",
            "phone",
            "zone_id",
            "area_id"
        )
    }

    /** Result of a successful sign-in; the UI layer only needs these fields. */
    data class Profile(
        val userId: String,
        val role: String,
        val fullName: String,
        val phone: String,
        val zoneId: String,
        val areaId: String
    )

    /**
     * Sign in with phone + OTP. The phone is sent as the raw 10 digits (no
     * country code) to match how our Supabase test phone identifiers are
     * stored. For Supabase-configured test phone numbers the provided [otp] is
     * accepted without any SMS being dispatched.
     *
     * On success, we fetch the corresponding `profiles` row, enforce the
     * distributor-only rule, and persist the profile snapshot to DataStore so
     * subsequent app launches can skip the login screen.
     *
     * The returned [Result] is `failure(IllegalStateException)` with a
     * caller-friendly message when:
     *   - Supabase returns an invalid/expired OTP
     *   - the profile row is missing (should never happen in practice)
     *   - the role is not "distributor" — in which case we also sign the user out
     *     so Supabase doesn't hand back a partial session on next launch
     *
     * TODO(prod): split into `sendOtp(phone)` + `verifyOtp(phone, token)` so
     * real users trigger an SMS via `auth.signInWith(OTP) { phone = e164 }`
     * before being asked for the token. The current single-call shape is
     * purpose-built for the "Test Phone Numbers" path.
     */
    suspend fun signIn(phone: String, otp: String): Result<Profile> = runCatching {
        val digits = phone.filter(Char::isDigit)
        val token = otp.filter(Char::isDigit)

        auth.verifyPhoneOtp(
            type = OtpType.Phone.SMS,
            phone = digits,
            token = token
        )

        val userId = auth.currentUserOrNull()?.id
            ?: error("Sign-in succeeded but no user in session")

        // Use a list query instead of .single() so that a missing profile row
        // comes back as an empty list rather than a PostgREST "Cannot coerce
        // the result to a single JSON object" error. This lets us surface a
        // clean "Account not set up" message to the field user.
        val matches = postgrest.from(PROFILES_TABLE)
            .select(PROFILE_COLUMNS) {
                filter { eq("id", userId) }
                limit(1)
            }
            .decodeList<ProfileDto>()

        val dto = matches.firstOrNull() ?: run {
            // Auth user exists but no profile row — an administrator needs to
            // seed one with role='distributor'. Drop the session so the user
            // isn't left in a half-logged-in state.
            runCatching { auth.signOut() }
            userPreferences.clearSession()
            error("Account not set up yet. Please contact your administrator.")
        }

        if (dto.role != ALLOWED_ROLE) {
            // Don't leave an authenticated session behind for a rejected role.
            runCatching { auth.signOut() }
            userPreferences.clearSession()
            error("This app is for distributors only. Please use the web dashboard.")
        }

        val profile = Profile(
            userId = dto.id,
            role = dto.role,
            fullName = dto.fullName,
            phone = dto.phone.ifBlank { digits },
            zoneId = dto.zoneId.orEmpty(),
            areaId = dto.areaId.orEmpty()
        )

        userPreferences.saveSession(
            userId = profile.userId,
            role = profile.role,
            fullName = profile.fullName,
            phone = profile.phone,
            zoneId = profile.zoneId,
            areaId = profile.areaId
        )

        profile
    }

    /**
     * Sign the user out of Supabase and wipe the local profile snapshot.
     * Safe to call even if no session exists.
     */
    suspend fun signOut(): Result<Unit> = runCatching {
        runCatching { auth.signOut() } // don't fail logout just because GoTrue is unreachable
        userPreferences.clearSession()
    }

    /**
     * True when we have both a persisted profile in DataStore AND Supabase still
     * considers the session authenticated. Either condition alone isn't enough:
     *   - DataStore only means "we were logged in at some point" — the JWT may
     *     have been revoked server-side.
     *   - A Supabase session with no profile means a half-completed login and
     *     the UI has nothing to render.
     */
    suspend fun isLoggedIn(): Boolean {
        val prefs = userPreferences.sessionFlow.first()
        if (!prefs.isValid) return false
        return getSession() != null
    }

    /**
     * Current Supabase session or null if unauthenticated / still initialising.
     * Supabase-kt's SessionManager handles refresh on its own; we just observe.
     */
    fun getSession() = when (val status = auth.sessionStatus.value) {
        is SessionStatus.Authenticated -> status.session
        else -> null
    }
}

/**
 * Subset of the `profiles` row we need at login time.
 *
 * Declared at top level (rather than nested inside [AuthRepository]) because
 * kotlinx.serialization's compiler-generated serializers need to be resolvable
 * by inline reified lookups (`serializer<ProfileDto>()` used under the hood by
 * Supabase's `decodeList`). Nested private classes sometimes fail that lookup
 * at runtime with "Serializer for class 'ProfileDto' is not found"; pulling it
 * to the file top level avoids that pitfall. The `private` keyword keeps it
 * file-scoped — same encapsulation intent as before.
 */
@Serializable
private data class ProfileDto(
    @SerialName("id") val id: String = "",
    @SerialName("role") val role: String = "",
    @SerialName("full_name") val fullName: String = "",
    @SerialName("phone") val phone: String = "",
    @SerialName("zone_id") val zoneId: String? = null,
    @SerialName("area_id") val areaId: String? = null
)
