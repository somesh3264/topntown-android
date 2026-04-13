package com.topntown.dms.data.repository

import com.topntown.dms.domain.model.Delivery
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeliveryRepository @Inject constructor(
    private val postgrest: Postgrest
) {

    suspend fun logDelivery(delivery: Delivery): Result<Delivery> = runCatching {
        postgrest.from("deliveries")
            .insert(delivery) {
                select(Columns.ALL)
            }
            .decodeSingle()
    }

    suspend fun getDeliveriesForOrder(orderId: String): Result<List<Delivery>> = runCatching {
        postgrest.from("deliveries")
            .select(Columns.ALL) {
                filter { eq("order_id", orderId) }
                order("delivered_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
            }
            .decodeList()
    }

    suspend fun getDeliveriesBySalesman(salesmanId: String): Result<List<Delivery>> = runCatching {
        postgrest.from("deliveries")
            .select(Columns.ALL) {
                filter { eq("salesman_id", salesmanId) }
                order("delivered_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
            }
            .decodeList()
    }
}
