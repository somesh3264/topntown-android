package com.topntown.dms.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable, session-scoped state for the signed-in distributor.
 *
 * The Supabase Kotlin SDK already persists its own JWT/refresh token via its
 * internal SessionManager, so this DataStore intentionally stores only the
 * *profile-derived* fields we need to render UI and scope queries (zone_id /
 * area_id filters on list screens, full_name on the header, etc.) without
 * having to round-trip to the `profiles` table on every screen.
 *
 * A non-empty [UserSession.userId] is treated as "has a persisted session".
 */
data class UserSession(
    val userId: String = "",
    val role: String = "",          // For TNT Android this must be "distributor"
    val fullName: String = "",
    val phone: String = "",         // E.164-ish: what the user typed, without the @topntown.local suffix
    val zoneId: String = "",
    val areaId: String = ""
) {
    /** True when we have enough persisted state to skip the login screen. */
    val isValid: Boolean get() = userId.isNotBlank() && role == "distributor"

    /**
     * Compatibility alias. Existing screens (Home header, Profile row) render a
     * "distributor name" — for a distributor-role user that's just their own
     * full name. Kept as a computed property so callers don't need updating.
     */
    val distributorName: String get() = fullName
}

@Singleton
class UserPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    companion object {
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_ROLE = stringPreferencesKey("role")
        private val KEY_FULL_NAME = stringPreferencesKey("full_name")
        private val KEY_PHONE = stringPreferencesKey("phone")
        private val KEY_ZONE_ID = stringPreferencesKey("zone_id")
        private val KEY_AREA_ID = stringPreferencesKey("area_id")
    }

    val sessionFlow: Flow<UserSession> = dataStore.data.map { prefs ->
        UserSession(
            userId = prefs[KEY_USER_ID].orEmpty(),
            role = prefs[KEY_ROLE].orEmpty(),
            fullName = prefs[KEY_FULL_NAME].orEmpty(),
            phone = prefs[KEY_PHONE].orEmpty(),
            zoneId = prefs[KEY_ZONE_ID].orEmpty(),
            areaId = prefs[KEY_AREA_ID].orEmpty()
        )
    }

    /**
     * Persist the profile snapshot we just fetched from Supabase after a
     * successful sign-in. Overwrites any prior values (e.g., if the distributor
     * was moved to a different area).
     */
    suspend fun saveSession(
        userId: String,
        role: String,
        fullName: String,
        phone: String,
        zoneId: String,
        areaId: String
    ) {
        dataStore.edit { prefs ->
            prefs[KEY_USER_ID] = userId
            prefs[KEY_ROLE] = role
            prefs[KEY_FULL_NAME] = fullName
            prefs[KEY_PHONE] = phone
            prefs[KEY_ZONE_ID] = zoneId
            prefs[KEY_AREA_ID] = areaId
        }
    }

    /** Wipe everything — called on logout and on detection of an expired session. */
    suspend fun clearSession() {
        dataStore.edit { it.clear() }
    }
}
