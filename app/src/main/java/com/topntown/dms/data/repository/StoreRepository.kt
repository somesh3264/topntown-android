package com.topntown.dms.data.repository

import com.topntown.dms.domain.model.Store
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StoreRepository @Inject constructor(
    private val postgrest: Postgrest
) {

    suspend fun getStoresForBeat(beatId: String): Result<List<Store>> = runCatching {
        postgrest.from("stores")
            .select(Columns.ALL) {
                filter { eq("beat_id", beatId) }
                order("store_name", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
            }
            .decodeList()
    }

    suspend fun getStoreById(storeId: String): Result<Store> = runCatching {
        postgrest.from("stores")
            .select(Columns.ALL) {
                filter { eq("id", storeId) }
            }
            .decodeSingle()
    }

    suspend fun createStore(store: Store): Result<Store> = runCatching {
        postgrest.from("stores")
            .insert(store) {
                select(Columns.ALL)
            }
            .decodeSingle()
    }

    suspend fun updateStore(store: Store): Result<Unit> = runCatching {
        postgrest.from("stores")
            .update({
                set("store_name", store.storeName)
                set("address", store.address)
                set("phone", store.phone)
                set("latitude", store.latitude)
                set("longitude", store.longitude)
            }) {
                filter { eq("id", store.id) }
            }
    }
}
