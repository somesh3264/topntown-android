package com.topntown.dms.data.remote

import com.topntown.dms.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage

/**
 * Singleton Supabase client configured with Auth (GoTrue), Postgrest, Realtime, and Storage.
 * Credentials are injected via BuildConfig from local.properties.
 *
 * Note: in supabase-kt 2.x the `GoTrue` plugin was renamed to `Auth`. The artifact
 * is still `gotrue-kt` and the package is still `io.github.jan.supabase.gotrue`.
 */
object SupabaseClientProvider {

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth) {
                // Phone OTP is the primary auth method for TNT field users
            }
            install(Postgrest) {
                // Default serializer handles @Serializable data classes
            }
            install(Realtime) {
                // Used for live order status updates
            }
            install(Storage) {
                // Used for bill images and store photos
            }
        }
    }
}
