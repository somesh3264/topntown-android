package com.topntown.dms.data.repository

import com.topntown.dms.domain.model.Payment
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaymentRepository @Inject constructor(
    private val postgrest: Postgrest
) {

    suspend fun getPaymentsForStore(storeId: String): Result<List<Payment>> = runCatching {
        postgrest.from("payments")
            .select(Columns.ALL) {
                filter { eq("store_id", storeId) }
                order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
            }
            .decodeList()
    }

    suspend fun logPayment(payment: Payment): Result<Payment> = runCatching {
        postgrest.from("payments")
            .insert(payment) {
                select(Columns.ALL)
            }
            .decodeSingle()
    }

    suspend fun getPaymentsBySalesman(salesmanId: String): Result<List<Payment>> = runCatching {
        postgrest.from("payments")
            .select(Columns.ALL) {
                filter { eq("collected_by", salesmanId) }
                order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
            }
            .decodeList()
    }

    suspend fun getOutstandingForStore(storeId: String): Result<Double> = runCatching {
        // Sum outstanding from orders minus payments collected
        val payments = postgrest.from("payments")
            .select(Columns.raw("amount")) {
                filter { eq("store_id", storeId) }
            }
            .decodeList<Map<String, Double>>()

        payments.sumOf { it["amount"] ?: 0.0 }
    }
}
