package com.topntown.dms.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String = "",
    val phone: String = "",
    val role: String = "",                  // "salesman", "distributor", "admin"
    @SerialName("full_name") val fullName: String = "",
    @SerialName("distributor_id") val distributorId: String? = null,
    @SerialName("distributor_name") val distributorName: String = "",
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class Product(
    val id: String = "",
    val name: String = "",
    val sku: String = "",
    @SerialName("category_id") val categoryId: String = "",
    @SerialName("unit_price") val unitPrice: Double = 0.0,
    @SerialName("mrp") val mrp: Double = 0.0,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("is_active") val isActive: Boolean = true
)

@Serializable
data class Order(
    val id: String = "",
    @SerialName("store_id") val storeId: String = "",
    @SerialName("salesman_id") val salesmanId: String = "",
    @SerialName("distributor_id") val distributorId: String = "",
    val status: String = "pending",         // "pending", "confirmed", "dispatched", "delivered"
    @SerialName("total_amount") val totalAmount: Double = 0.0,
    @SerialName("order_items") val orderItems: List<OrderItem> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = ""
)

@Serializable
data class OrderItem(
    val id: String = "",
    @SerialName("order_id") val orderId: String = "",
    @SerialName("product_id") val productId: String = "",
    @SerialName("product_name") val productName: String = "",
    val quantity: Int = 0,
    @SerialName("unit_price") val unitPrice: Double = 0.0,
    @SerialName("line_total") val lineTotal: Double = 0.0
)

@Serializable
data class Store(
    val id: String = "",
    @SerialName("store_name") val storeName: String = "",
    val address: String = "",
    val phone: String = "",
    @SerialName("beat_id") val beatId: String = "",
    @SerialName("distributor_id") val distributorId: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class Beat(
    val id: String = "",
    val name: String = "",
    @SerialName("salesman_id") val salesmanId: String = "",
    @SerialName("day_of_week") val dayOfWeek: String = "",    // "monday", "tuesday", etc.
    @SerialName("store_count") val storeCount: Int = 0
)

@Serializable
data class Payment(
    val id: String = "",
    @SerialName("store_id") val storeId: String = "",
    @SerialName("order_id") val orderId: String? = null,
    val amount: Double = 0.0,
    val mode: String = "",                  // "cash", "upi", "cheque"
    @SerialName("collected_by") val collectedBy: String = "",
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class Delivery(
    val id: String = "",
    @SerialName("order_id") val orderId: String = "",
    @SerialName("salesman_id") val salesmanId: String = "",
    @SerialName("store_id") val storeId: String = "",
    @SerialName("delivered_at") val deliveredAt: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val notes: String = ""
)

/**
 * Advance bill generated nightly (~22:00 IST) for a distributor, listing the
 * allocated SKUs and quantities they should expect for the next business day.
 */
@Serializable
data class Bill(
    val id: String = "",
    @SerialName("distributor_id") val distributorId: String = "",
    @SerialName("bill_number") val billNumber: String = "",
    @SerialName("bill_date") val billDate: String = "",
    @SerialName("sub_total") val subTotal: Double = 0.0,
    @SerialName("total_tax") val totalTax: Double = 0.0,
    @SerialName("total_amount") val totalAmount: Double = 0.0,
    @SerialName("pdf_url") val pdfUrl: String? = null,
    val status: String = "pending",
    @SerialName("generated_at") val generatedAt: String? = null
)

/** One line of a bill, already joined with its product for display. */
data class BillItem(
    val productName: String,
    val category: String,
    val allocatedQty: Int,
    val unitPrice: Double,
    val taxAmount: Double,
    val lineTotal: Double
)
