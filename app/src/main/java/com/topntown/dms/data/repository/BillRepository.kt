package com.topntown.dms.data.repository

import com.topntown.dms.data.remote.SupabaseClientProvider
import com.topntown.dms.domain.model.Bill
import com.topntown.dms.domain.model.BillItem
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches today's advance bill (with line items and product names) for a
 * given distributor from Supabase.
 *
 * Schema expected:
 *   bills(id, distributor_id, bill_number, bill_date, sub_total, total_tax,
 *         total_amount, pdf_url, status, generated_at)
 *   bill_items(id, bill_id, product_id, allocated_qty, unit_price, tax_amount, line_total)
 *   products(id, name, category_id)
 */
@Singleton
class BillRepository @Inject constructor() {

    private val supabase get() = SupabaseClientProvider.client

    suspend fun fetchTodaysBill(distributorId: String): BillWithItems? =
        withContext(Dispatchers.IO) {
            val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)

            val bill = supabase.postgrest["bills"]
                .select {
                    filter {
                        eq("distributor_id", distributorId)
                        eq("bill_date", today)
                    }
                    limit(1)
                }
                .decodeSingleOrNull<Bill>() ?: return@withContext null

            val items = supabase.postgrest["bill_items"]
                .select(
                    columns = Columns.raw(
                        """
                        allocated_qty,
                        unit_price,
                        tax_amount,
                        line_total,
                        products:product_id ( name, category_id )
                        """.trimIndent()
                    )
                ) {
                    filter { eq("bill_id", bill.id) }
                }
                .decodeList<BillItemRow>()
                .map { it.toDomain() }

            BillWithItems(bill = bill, items = items)
        }

    @Serializable
    private data class BillItemRow(
        @SerialName("allocated_qty") val allocatedQty: Int = 0,
        @SerialName("unit_price") val unitPrice: Double = 0.0,
        @SerialName("tax_amount") val taxAmount: Double = 0.0,
        @SerialName("line_total") val lineTotal: Double = 0.0,
        val products: ProductSlim? = null
    ) {
        fun toDomain() = BillItem(
            productName = products?.name.orEmpty(),
            category = products?.categoryId.orEmpty(),
            allocatedQty = allocatedQty,
            unitPrice = unitPrice,
            taxAmount = taxAmount,
            lineTotal = lineTotal
        )
    }

    @Serializable
    private data class ProductSlim(
        val name: String = "",
        @SerialName("category_id") val categoryId: String = ""
    )

    data class BillWithItems(
        val bill: Bill,
        val items: List<BillItem>
    )
}
