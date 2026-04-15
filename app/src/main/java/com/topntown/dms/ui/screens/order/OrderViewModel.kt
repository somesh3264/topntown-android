package com.topntown.dms.ui.screens.order

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topntown.dms.data.datastore.UserPreferences
import com.topntown.dms.data.remote.SupabaseClientProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

// ---------- Screen-local domain types ----------
//
// These mirror the order-screen slice of the product master and today's order row. We keep
// them file-private (rather than polluting domain/model/Models.kt) because nothing outside
// this screen needs them and a few field names differ from the canonical Product (e.g. we
// always want distributor_price, not unit_price, for this role).

/** Product row as the distributor order screen consumes it. */
@Serializable
data class OrderProduct(
    val id: String = "",
    val name: String = "",
    @SerialName("sku_code") val skuCode: String? = null,
    val category: String = "",                              // "Bread" | "Biscuits" | "Cakes" | "Rusk" | ...
    val mrp: Double = 0.0,
    @SerialName("distributor_price") val distributorPrice: Double = 0.0,
    @SerialName("is_active") val isActive: Boolean = true
)

/** One row read back from `orders` for today's distributor. */
@Serializable
private data class ExistingOrderRow(
    val id: String = "",
    val status: String = "",                                // "draft" | "confirmed" | "billed"
    @SerialName("order_items") val orderItems: List<ExistingOrderItem> = emptyList()
)

@Serializable
private data class ExistingOrderItem(
    @SerialName("product_id") val productId: String = "",
    val quantity: Int = 0
)

/** One row from `system_config` — we only care about cut_off_time for this screen. */
@Serializable
private data class SystemConfigRow(
    val key: String = "",
    val value: String = ""
)

// ---------- UI state ----------

/** Lifecycle of the order for today, as far as this screen is concerned. */
enum class OrderMode {
    /** Normal case: no order yet, OR an editable draft/confirmed order exists. */
    Editable,
    /** status = "billed" — show values but no steppers, no submit button. */
    ReadOnly,
    /** Countdown reached zero. Entire form is replaced by the "call now" card. */
    CutoffPassed
}

data class OrderUiState(
    val isLoading: Boolean = true,
    val error: String? = null,

    /** Empty list until the first fetch resolves. */
    val products: List<OrderProduct> = emptyList(),

    /** productId -> quantity. Missing key means "0". */
    val quantities: Map<String, Int> = emptyMap(),

    /** "" = show everything. Otherwise matches OrderProduct.category case-insensitively. */
    val selectedCategory: String = "",

    val searchQuery: String = "",

    /** Absolute wall-clock deadline for today's cut-off, or null if config hasn't loaded. */
    val cutoffEpochMillis: Long? = null,

    /** Phone number from system_config.distributor_support_phone — fed into the dialer Intent. */
    val supportPhone: String = "+91-0000000000",

    val mode: OrderMode = OrderMode.Editable,

    /**
     * Existing order id for today (if any). Drives whether submitOrder() does an INSERT
     * or an UPDATE on the orders row.
     */
    val existingOrderId: String? = null,

    /** Non-null for one recomposition after submit resolves — used to drive snackbars. */
    val submitResult: SubmitResult? = null
) {
    val itemCount: Int get() = quantities.values.sum()
    val grandTotal: Double get() = products.sumOf { p ->
        (quantities[p.id] ?: 0) * p.distributorPrice
    }
}

sealed class SubmitResult {
    data object Success : SubmitResult()
    data class Error(val message: String) : SubmitResult()
}

@HiltViewModel
class OrderViewModel @Inject constructor(
    private val postgrest: Postgrest,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(OrderUiState())
    val uiState: StateFlow<OrderUiState> = _uiState.asStateFlow()

    init {
        loadAll()
    }

    // ---------- Data loading ----------

    /** Single entry point that fans out the three independent fetches in parallel-ish. */
    fun loadAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val session = userPreferences.sessionFlow.first()
                val distributorId = session.userId

                // Pull config first so we know the cut-off deadline before the user sees products.
                val (cutoffMillis, supportPhone) = loadSystemConfig()

                // Cut-off already passed? Short-circuit — no need to fetch products.
                val now = System.currentTimeMillis()
                if (cutoffMillis != null && now >= cutoffMillis) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            cutoffEpochMillis = cutoffMillis,
                            supportPhone = supportPhone,
                            mode = OrderMode.CutoffPassed
                        )
                    }
                    return@launch
                }

                val products = loadProducts()
                val existing = loadTodaysOrder(distributorId)

                val initialQuantities: Map<String, Int> = existing
                    ?.orderItems
                    ?.associate { it.productId to it.quantity }
                    .orEmpty()

                val mode = when (existing?.status) {
                    "billed" -> OrderMode.ReadOnly
                    else -> OrderMode.Editable
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        products = products,
                        quantities = initialQuantities,
                        cutoffEpochMillis = cutoffMillis,
                        supportPhone = supportPhone,
                        mode = mode,
                        existingOrderId = existing?.id
                    )
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isLoading = false, error = t.message ?: "Failed to load") }
            }
        }
    }

    private suspend fun loadProducts(): List<OrderProduct> = withContext(Dispatchers.IO) {
        postgrest.from("products")
            .select(Columns.list("id", "name", "sku_code", "category", "mrp", "distributor_price", "is_active")) {
                filter { eq("is_active", true) }
                order("category", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                order("name", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
            }
            .decodeList<OrderProduct>()
    }

    /**
     * Fetch `cut_off_time` (format "HH:mm" or "HH:mm:ss") and `distributor_support_phone`
     * from the `system_config` key/value table. Combines the time with *today's* local
     * date to produce an absolute epoch-millis deadline.
     *
     * Returns `(null, fallback)` if the row is missing — in that case we treat the order
     * as always-open so a config omission doesn't break the whole screen.
     */
    private suspend fun loadSystemConfig(): Pair<Long?, String> = withContext(Dispatchers.IO) {
        // system_config is a tiny key/value table — fetch all rows and pick out the keys
        // we care about. Avoids relying on a specific filter-DSL method name in the
        // supabase-kt version this project is pinned to.
        val rows: List<SystemConfigRow> = try {
            postgrest.from("system_config")
                .select(Columns.list("key", "value"))
                .decodeList()
        } catch (_: Throwable) {
            emptyList()
        }

        val configMap = rows.associate { it.key to it.value }
        val cutoffRaw = configMap["cut_off_time"]
        val phone = configMap["distributor_support_phone"] ?: "+91-0000000000"

        val cutoffMillis = cutoffRaw?.let { raw ->
            runCatching {
                val formatter = if (raw.count { it == ':' } == 1) {
                    DateTimeFormatter.ofPattern("HH:mm")
                } else {
                    DateTimeFormatter.ofPattern("HH:mm:ss")
                }
                val time = LocalTime.parse(raw, formatter)
                LocalDate.now()
                    .atTime(time)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        }

        cutoffMillis to phone
    }

    /**
     * Look for an editable order the distributor has already started/placed today. We filter
     * on created_at >= start-of-today rather than a dedicated `order_date` column so the
     * screen works with the schema as shipped (no migration required).
     *
     * Billed orders are intentionally excluded: once the nightly bill is generated (or an
     * admin taps "Generate Bill" on the dashboard) the order row flips to status='billed'
     * and is finalised. Previously we still loaded that row here, which forced the whole
     * screen into OrderMode.ReadOnly and hid every stepper — meaning a distributor whose
     * earlier order was already billed could not place a second order during the same
     * ordering window. The schema permits multiple orders per (distributor, order_date)
     * (only an index, no unique constraint on orders(distributor_id, order_date)), so the
     * right behaviour is to treat a billed order as "in the rear-view mirror" and let the
     * distributor start a fresh editable order. Any remaining draft/confirmed row is still
     * picked up and its quantities are prefilled as before.
     */
    private suspend fun loadTodaysOrder(distributorId: String): ExistingOrderRow? = withContext(Dispatchers.IO) {
        val startOfToday = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toString()

        runCatching {
            // Embedded select: orders + their order_items rows in one round-trip. Matches
            // the shape of ExistingOrderRow (flat status/id + list of items).
            postgrest.from("orders")
                .select(Columns.raw("id, status, order_items(product_id, quantity)")) {
                    filter {
                        eq("distributor_id", distributorId)
                        gte("created_at", startOfToday)
                        // Skip finalised rows so billed orders don't lock the screen into
                        // ReadOnly. See the kdoc above for the full reasoning.
                        neq("status", "billed")
                    }
                    order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                    limit(1)
                }
                .decodeList<ExistingOrderRow>()
                .firstOrNull()
        }.getOrNull()
    }

    // ---------- UI events ----------

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onCategorySelected(category: String) {
        // "All" from the UI maps to an empty filter string internally.
        val normalized = if (category.equals("All", ignoreCase = true)) "" else category
        _uiState.update { it.copy(selectedCategory = normalized) }
    }

    fun increment(productId: String) {
        if (_uiState.value.mode != OrderMode.Editable) return
        _uiState.update { state ->
            val current = state.quantities[productId] ?: 0
            state.copy(quantities = state.quantities + (productId to current + 1))
        }
    }

    fun decrement(productId: String) {
        if (_uiState.value.mode != OrderMode.Editable) return
        _uiState.update { state ->
            val current = state.quantities[productId] ?: 0
            if (current <= 0) return@update state
            val next = current - 1
            val nextMap = if (next == 0) state.quantities - productId else state.quantities + (productId to next)
            state.copy(quantities = nextMap)
        }
    }

    /** Filtered list applied against both search and category state. */
    fun visibleProducts(): List<OrderProduct> {
        val state = _uiState.value
        return state.products.filter { p ->
            val categoryMatches = state.selectedCategory.isEmpty() ||
                p.category.equals(state.selectedCategory, ignoreCase = true)
            val searchMatches = state.searchQuery.isBlank() ||
                p.name.contains(state.searchQuery.trim(), ignoreCase = true)
            categoryMatches && searchMatches
        }
    }

    /**
     * Called by the Cutoff banner when the countdown hits zero. We flip mode to
     * CutoffPassed; the screen swaps itself for the "Order time has passed" card.
     */
    fun onCutoffExpired() {
        _uiState.update { it.copy(mode = OrderMode.CutoffPassed) }
    }

    /** Clear the one-shot result after the snackbar has been shown. */
    fun consumeSubmitResult() {
        _uiState.update { it.copy(submitResult = null) }
    }

    // ---------- Submit ----------

    /**
     * Push the current quantities to Supabase via the `submit_distributor_order` RPC. The
     * RPC is expected to:
     *   1. Re-validate that now() is before system_config.cut_off_time (authoritative check).
     *   2. Upsert the orders row keyed by (distributor_id, order_date = today).
     *   3. Replace order_items with the submitted array.
     *   4. Return the persisted order id + total.
     *
     * If the RPC isn't available yet we fall back to a plain INSERT/UPDATE on `orders`
     * so the screen remains functional during pre-integration testing.
     */
    fun submitOrder() {
        val state = _uiState.value
        if (state.mode != OrderMode.Editable) return

        // Local cut-off guard — the server does the authoritative re-check.
        val cutoff = state.cutoffEpochMillis
        if (cutoff != null && System.currentTimeMillis() >= cutoff) {
            _uiState.update {
                it.copy(
                    mode = OrderMode.CutoffPassed,
                    submitResult = SubmitResult.Error("Order time has passed for today.")
                )
            }
            return
        }

        if (state.itemCount == 0) {
            _uiState.update {
                it.copy(submitResult = SubmitResult.Error("Add at least one product to submit."))
            }
            return
        }

        viewModelScope.launch {
            try {
                val session = userPreferences.sessionFlow.first()
                val distributorId = session.userId

                val items: List<Pair<OrderProduct, Int>> = state.products.mapNotNull { p ->
                    val qty = state.quantities[p.id] ?: 0
                    if (qty > 0) p to qty else null
                }

                val rpcBody = buildJsonObject {
                    put("p_distributor_id", distributorId)
                    put("p_total_amount", state.grandTotal)
                    // Pass JSON null (not an empty string!) when there's no existing
                    // order — Postgres will reject "" as an invalid UUID otherwise.
                    put("p_existing_order_id", state.existingOrderId)
                    put("p_items", buildJsonArray {
                        items.forEach { (product, qty) ->
                            addJsonObject {
                                put("product_id", product.id)
                                put("product_name", product.name)
                                put("quantity", qty)
                                put("unit_price", product.distributorPrice)
                                put("line_total", qty * product.distributorPrice)
                            }
                        }
                    })
                }

                withContext(Dispatchers.IO) {
                    try {
                        // Preferred path: atomic server-side RPC.
                        SupabaseClientProvider.client.postgrest.rpc("submit_distributor_order", rpcBody)
                    } catch (rpcError: Throwable) {
                        // Fallback: if the RPC isn't deployed yet ("Could not find the function",
                        // PGRST202, 404 etc.) we fall back to a direct INSERT/UPDATE of the
                        // `orders` + `order_items` rows so the app remains functional during
                        // pre-integration testing. Any other error (auth, RLS, schema) is
                        // rethrown so it surfaces in the snackbar.
                        if (!isRpcMissingError(rpcError)) throw rpcError
                        submitViaDirectWrite(
                            distributorId = distributorId,
                            existingOrderId = state.existingOrderId,
                            totalAmount = state.grandTotal,
                            items = items
                        )
                    }
                }

                _uiState.update {
                    it.copy(
                        submitResult = SubmitResult.Success,
                        // Keep existing quantities in place — if the user taps Review again
                        // they see what they submitted.
                    )
                }
            } catch (t: Throwable) {
                // Full stack trace goes to Logcat under the "OrderSubmit" tag — filter on
                // that in Android Studio to see the complete error body (PostgREST returns
                // the failing constraint / RLS policy in the response text).
                Log.e("OrderSubmit", "submitOrder failed", t)

                _uiState.update {
                    it.copy(submitResult = SubmitResult.Error(friendlySubmitError(t)))
                }
            }
        }
    }

    /**
     * The raw error from ktor/Supabase often contains the full request URL and the
     * Bearer JWT — neither is safe or useful in a user-facing snackbar. This strips
     * them and returns a short, readable message. The complete text is still in Logcat.
     */
    private fun friendlySubmitError(t: Throwable): String {
        val raw = t.message ?: return "Could not submit order"

        // Prefer a PostgREST JSON body if one is embedded in the message.
        val jsonStart = raw.indexOf('{')
        val jsonEnd = raw.lastIndexOf('}')
        if (jsonStart in 0 until jsonEnd) {
            val body = raw.substring(jsonStart, jsonEnd + 1)
            Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)?.let {
                return it.take(200)
            }
            Regex("\"hint\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)?.let {
                return it.take(200)
            }
        }

        // Otherwise strip the request URL + headers dump so at least the human-readable
        // prefix survives.
        val urlCut = raw.indexOf("https://")
        val headersCut = raw.indexOf("Headers:")
        val cutAt = listOf(urlCut, headersCut).filter { it >= 0 }.minOrNull() ?: raw.length
        return raw.substring(0, cutAt).trim().ifBlank { "Could not submit order" }.take(200)
    }

    /** Heuristic: does this look like "RPC function not found on the backend"? */
    private fun isRpcMissingError(t: Throwable): Boolean {
        val msg = (t.message ?: "").lowercase()
        return "could not find the function" in msg ||
            "pgrst202" in msg ||
            "function " in msg && "does not exist" in msg ||
            "404" in msg && "rpc" in msg
    }

    /**
     * Fallback write path used when the `submit_distributor_order` RPC isn't available.
     *
     * This is NOT atomic — an app/network failure between the two writes can leave the
     * orders row without items. Real production submissions should go through the RPC.
     * We keep this here strictly so the UI remains usable before that migration ships.
     */
    private suspend fun submitViaDirectWrite(
        distributorId: String,
        existingOrderId: String?,
        totalAmount: Double,
        items: List<Pair<OrderProduct, Int>>
    ) {
        val orderId: String = if (existingOrderId.isNullOrBlank()) {
            // Fresh order — INSERT and read the id back. Columns match the actual
            // `orders` schema: distributor_id, order_date, status, total_amount.
            // (`id` and `created_at` are filled by defaults; there is no
            // `updated_at`, `store_id`, or `salesman_id`.)
            val inserted = postgrest.from("orders")
                .insert(
                    buildJsonObject {
                        put("distributor_id", distributorId)
                        put("order_date", LocalDate.now().toString())
                        put("status", "confirmed")
                        put("total_amount", totalAmount)
                    }
                ) { select(Columns.list("id")) }
                .decodeList<InsertedOrderId>()
                .firstOrNull()
                ?: throw IllegalStateException("Order insert returned no row")
            inserted.id
        } else {
            // Existing draft — UPDATE the status/total, then wipe and re-insert items.
            postgrest.from("orders")
                .update({
                    set("status", "confirmed")
                    set("total_amount", totalAmount)
                }) {
                    filter { eq("id", existingOrderId) }
                }
            postgrest.from("order_items")
                .delete { filter { eq("order_id", existingOrderId) } }
            existingOrderId
        }

        if (items.isNotEmpty()) {
            // `order_items` has only: id, order_id, product_id, quantity, unit_price,
            // created_at. No product_name or line_total columns — don't send them.
            val itemRows = buildJsonArray {
                items.forEach { (product, qty) ->
                    addJsonObject {
                        put("order_id", orderId)
                        put("product_id", product.id)
                        put("quantity", qty)
                        put("unit_price", product.distributorPrice)
                    }
                }
            }
            postgrest.from("order_items").insert(itemRows)
        }
    }

    @Serializable
    private data class InsertedOrderId(val id: String = "")
}

