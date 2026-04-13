package com.topntown.dms.data.repository

import com.topntown.dms.domain.model.Order
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrderRepository @Inject constructor(
    private val postgrest: Postgrest
) {

    suspend fun getOrdersForSalesman(salesmanId: String): Result<List<Order>> = runCatching {
        postgrest.from("orders")
            .select(Columns.ALL) {
                filter { eq("salesman_id", salesmanId) }
                order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
            }
            .decodeList()
    }

    suspend fun getOrderById(orderId: String): Result<Order> = runCatching {
        postgrest.from("orders")
            .select(Columns.ALL) {
                filter { eq("id", orderId) }
            }
            .decodeSingle()
    }

    suspend fun createOrder(order: Order): Result<Order> = runCatching {
        postgrest.from("orders")
            .insert(order) {
                select(Columns.ALL)
            }
            .decodeSingle()
    }

    suspend fun updateOrderStatus(orderId: String, status: String): Result<Unit> = runCatching {
        postgrest.from("orders")
            .update({ set("status", status) }) {
                filter { eq("id", orderId) }
            }
    }
}
