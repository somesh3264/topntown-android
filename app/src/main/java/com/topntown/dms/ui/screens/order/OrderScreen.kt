package com.topntown.dms.ui.screens.order

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.topntown.dms.ui.components.CutoffBanner
import com.topntown.dms.ui.components.LoadingScreen
import com.topntown.dms.ui.components.StepperButton
import com.topntown.dms.ui.navigation.Routes
import kotlinx.coroutines.launch

// Chip list is hardcoded per spec — the distributor product master is known to be these
// four categories (plus "All"). If a product has an unknown category it will simply not
// match any specific chip; "All" still shows it.
private val CATEGORY_CHIPS = listOf("All", "Bread", "Biscuits", "Cakes", "Rusk")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderScreen(
    navController: NavController,
    viewModel: OrderViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showReviewSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // One-shot snackbar on submit result, then navigate home on success.
    LaunchedEffect(state.submitResult) {
        when (val r = state.submitResult) {
            is SubmitResult.Success -> {
                showReviewSheet = false
                snackbarHostState.showSnackbar("Order placed successfully")
                viewModel.consumeSubmitResult()
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.ORDER) { inclusive = true }
                    launchSingleTop = true
                }
            }
            is SubmitResult.Error -> {
                snackbarHostState.showSnackbar(r.message)
                viewModel.consumeSubmitResult()
            }
            null -> Unit
        }
    }

    if (state.isLoading) {
        LoadingScreen()
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            // Hide the FAB in read-only and cutoff-passed modes — there's nothing to submit.
            if (state.mode == OrderMode.Editable && state.itemCount > 0) {
                ReviewOrderFab(
                    itemCount = state.itemCount,
                    onClick = { showReviewSheet = true }
                )
            }
        }
    ) { innerPadding ->
        when (state.mode) {
            OrderMode.CutoffPassed -> {
                CutoffPassedCard(
                    supportPhone = state.supportPhone,
                    onCallNow = { phone ->
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                )
            }
            OrderMode.Editable,
            OrderMode.ReadOnly -> {
                EditableOrderContent(
                    state = state,
                    viewModel = viewModel,
                    contentPadding = innerPadding
                )
            }
        }
    }

    if (showReviewSheet) {
        ModalBottomSheet(
            onDismissRequest = { showReviewSheet = false },
            sheetState = sheetState
        ) {
            ReviewOrderSheet(
                state = state,
                onConfirm = { viewModel.submitOrder() },
                onDismiss = {
                    scope.launch {
                        sheetState.hide()
                        showReviewSheet = false
                    }
                }
            )
        }
    }
}

// ---------- Editable / read-only main content ----------

@Composable
private fun EditableOrderContent(
    state: OrderUiState,
    viewModel: OrderViewModel,
    contentPadding: PaddingValues
) {
    // Compute filtered view directly from state. Calling a VM method inside `remember`
    // was brittle — the VM reads `_uiState.value` (a snapshot) which can drift from the
    // `state` the Composable observed, and callers were racing recompositions.
    val visible = remember(state.products, state.searchQuery, state.selectedCategory) {
        val query = state.searchQuery.trim()
        val category = state.selectedCategory
        state.products.filter { p ->
            val categoryMatches = category.isEmpty() || p.category.equals(category, ignoreCase = true)
            val searchMatches = query.isBlank() || p.name.contains(query, ignoreCase = true)
            categoryMatches && searchMatches
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        state.cutoffEpochMillis?.let { cutoff ->
            CutoffBanner(
                cutoffEpochMillis = cutoff,
                onExpired = { viewModel.onCutoffExpired() }
            )
            Spacer(Modifier.height(12.dp))
        }

        SearchBar(
            query = state.searchQuery,
            onQueryChange = viewModel::onSearchQueryChange
        )

        Spacer(Modifier.height(12.dp))

        CategoryFilterRow(
            selectedCategory = state.selectedCategory,
            onSelect = viewModel::onCategorySelected
        )

        Spacer(Modifier.height(8.dp))

        if (visible.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.products.isEmpty()) "No products available"
                    else "No products match your search",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // `weight(1f)` guarantees a bounded maxHeight for the LazyColumn — without
            // it, LazyColumn inside a fillMaxSize Column can hit
            // "Vertically scrollable component was measured with an infinity maximum
            // height constraints" when the parent layout happens to be unbounded
            // (e.g. when the sheet compositing pass runs).
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                // Leave breathing room below the FAB so the last card isn't hidden.
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(visible, key = { it.id }) { product ->
                    ProductCard(
                        product = product,
                        quantity = state.quantities[product.id] ?: 0,
                        readOnly = state.mode == OrderMode.ReadOnly,
                        onIncrement = { viewModel.increment(product.id) },
                        onDecrement = { viewModel.decrement(product.id) }
                    )
                }
            }
        }
    }
}

// ---------- Search bar ----------

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search products") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )
}

// ---------- Category chips ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryFilterRow(selectedCategory: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CATEGORY_CHIPS.forEach { label ->
            val selected = (label == "All" && selectedCategory.isEmpty()) ||
                label.equals(selectedCategory, ignoreCase = true)
            FilterChip(
                selected = selected,
                onClick = { onSelect(label) },
                label = { Text(label, fontSize = 14.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}

// ---------- Product card ----------

@Composable
private fun ProductCard(
    product: OrderProduct,
    quantity: Int,
    readOnly: Boolean,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryIcon(category = product.category)

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                CategoryBadge(category = product.category)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "MRP ₹%.0f".format(product.mrp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Your price ₹%.2f".format(product.distributorPrice),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            if (readOnly) {
                // Status = billed — show the quantity as a read-only pill, no steppers.
                Text(
                    text = "Qty $quantity",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                StepperButton(
                    value = quantity,
                    onIncrement = onIncrement,
                    onDecrement = onDecrement
                )
            }
        }
    }
}

/** Colored circular icon keyed off category — fast visual grouping for low-literacy users. */
@Composable
private fun CategoryIcon(category: String) {
    val (tint, icon) = when (category.lowercase()) {
        "bread" -> Color(0xFFFFC107) to Icons.Default.Fastfood
        "biscuits" -> Color(0xFF8D6E63) to Icons.Default.Cookie
        "cakes" -> Color(0xFFE91E63) to Icons.Default.Cake
        "rusk" -> Color(0xFFFF7043) to Icons.Default.Inventory2
        else -> MaterialTheme.colorScheme.primary to Icons.Default.Inventory2
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = category.ifBlank { "Product" },
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun CategoryBadge(category: String) {
    if (category.isBlank()) return
    Text(
        text = category,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

// ---------- FAB ----------

@Composable
private fun ReviewOrderFab(itemCount: Int, onClick: () -> Unit) {
    // The FAB sits above the bottom nav bar automatically because the parent Scaffold
    // (in NavGraph) applies the nav-bar inset as innerPadding; this Scaffold's FAB slot
    // respects that.
    ExtendedFloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ) { Text("$itemCount") }
            }
        ) {
            Icon(Icons.Default.ShoppingCart, contentDescription = null)
        }
        Spacer(Modifier.width(12.dp))
        Text("Review Order", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- Review bottom sheet ----------

/**
 * Named data holder for a row in the Review sheet. Replaces a fragile
 * `Triple<OrderProduct, Int, Double>` whose destructuring-in-lambda pattern was
 * causing a crash when combined with LazyListScope.items's `key =` parameter
 * on some Kotlin/Compose compiler combinations.
 */
private data class ReviewLineItem(
    val product: OrderProduct,
    val qty: Int,
    val lineTotal: Double
)

@Composable
private fun ReviewOrderSheet(
    state: OrderUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // Build line items defensively:
    //  - skip products with blank ids (would otherwise collide as LazyColumn keys)
    //  - de-duplicate by id so `items(key=...)` never sees duplicates
    val lineItems: List<ReviewLineItem> = remember(state.products, state.quantities) {
        val seen = HashSet<String>()
        state.products.mapNotNull { product ->
            val id = product.id
            if (id.isBlank() || !seen.add(id)) return@mapNotNull null
            val qty = state.quantities[id] ?: 0
            if (qty <= 0) null
            else ReviewLineItem(
                product = product,
                qty = qty,
                lineTotal = qty * product.distributorPrice
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Review your order",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))

        if (lineItems.isEmpty()) {
            // Guard: the FAB gates on itemCount > 0, but itemCount sums `quantities` while
            // lineItems sums `products` — if the two ever drift (bad product id, stale
            // draft, etc.) the sheet used to render an empty list with no feedback.
            Text(
                text = "Add at least one product before reviewing.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = lineItems,
                    key = { item -> item.product.id }
                ) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.product.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Qty ${item.qty} × ₹%.2f".format(item.product.distributorPrice),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "₹%.2f".format(item.lineTotal),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Keep grand-total consistent with what's actually listed above — don't rely
        // on state.grandTotal which sums ALL products (including ones we might have
        // skipped above because of duplicate / blank ids).
        val displayedTotal = lineItems.sumOf { it.lineTotal }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Grand Total", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "₹%.2f".format(displayedTotal),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onConfirm,
            enabled = lineItems.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Confirm Order", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ---------- Cut-off passed state ----------

@Composable
private fun CutoffPassedCard(
    supportPhone: String,
    onCallNow: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Order time has passed for today.",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Button(
            onClick = { onCallNow(supportPhone) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Call,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Call Now: $supportPhone",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

