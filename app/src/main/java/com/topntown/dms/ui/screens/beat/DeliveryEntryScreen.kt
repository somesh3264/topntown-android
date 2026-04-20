package com.topntown.dms.ui.screens.beat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.NumberFormat
import java.util.Locale

/**
 * Per-store delivery entry. Four visual states:
 *   Entry      — step products with +/− buttons, floating confirm bar.
 *   Confirming — modal bottom sheet with line items + Submit button.
 *   Submitting — spinner overlay.
 *   Success    — green success screen with "New Delivery" / "Share via WhatsApp".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryEntryScreen(
    storeId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: DeliveryEntryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when (state.phase) {
        DeliveryEntryUiState.Phase.Loading -> LoadingView(onBack = onBack)
        DeliveryEntryUiState.Phase.Success -> SuccessView(state = state, onDone = onDone)
        else -> EntryView(
            state = state,
            onBack = onBack,
            onIncrement = viewModel::increment,
            onDecrement = viewModel::decrement,
            onOpenConfirm = viewModel::openConfirm,
            onCancelConfirm = viewModel::cancelConfirm,
            onSubmit = viewModel::submit,
            onErrorDismiss = viewModel::clearError
        )
    }
}

// ── Entry View ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryView(
    state: DeliveryEntryUiState,
    onBack: () -> Unit,
    onIncrement: (String) -> Unit,
    onDecrement: (String) -> Unit,
    onOpenConfirm: () -> Unit,
    onCancelConfirm: () -> Unit,
    onSubmit: () -> Unit,
    onErrorDismiss: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = 12.dp,
                // Leave space for the floating Confirm bar at the bottom.
                bottom = if (state.canSubmit) 96.dp else 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Header(state = state, onBack = onBack) }

            if (state.errorMessage != null) {
                item {
                    ErrorBanner(message = state.errorMessage, onDismiss = onErrorDismiss)
                }
            }

            item {
                Text(
                    text = "Select products delivered",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(items = state.products, key = { it.productId }) { product ->
                ProductRow(
                    product = product,
                    quantity = state.quantities[product.productId] ?: 0,
                    onIncrement = { onIncrement(product.productId) },
                    onDecrement = { onDecrement(product.productId) }
                )
            }
        }

        if (state.canSubmit) {
            FloatingConfirmBar(
                itemCount = state.itemCount,
                total = state.totalValue,
                onClick = onOpenConfirm,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        if (state.phase == DeliveryEntryUiState.Phase.Submitting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = Color.White) }
        }
    }

    // Confirm modal bottom sheet
    if (state.phase == DeliveryEntryUiState.Phase.Confirming) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onCancelConfirm,
            sheetState = sheetState
        ) {
            ConfirmSheetContent(
                state = state,
                onCancel = onCancelConfirm,
                onSubmit = onSubmit
            )
        }
    }
}

@Composable
private fun Header(state: DeliveryEntryUiState, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Filled.ArrowBack,
                contentDescription = "Back"
            )
        }
        Spacer(modifier = Modifier.size(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.storeName,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            val subtitle = buildString {
                val dist = formatDistance(state.distanceMeters)
                if (dist != null) {
                    append("$dist away")
                    if (state.storeArea.isNotBlank()) append(" · ")
                }
                if (state.storeArea.isNotBlank()) append(state.storeArea)
            }
            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProductRow(
    product: DeliveryProduct,
    quantity: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFFF5F0EA), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Inventory2,
                    contentDescription = null,
                    tint = Color(0xFF78716C),
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = listOf(product.productName, product.productWeight)
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Stock: ${product.stockRemaining} left",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Stepper(
                quantity = quantity,
                onIncrement = onIncrement,
                onDecrement = onDecrement,
                maxReached = quantity >= product.stockRemaining
            )
        }
    }
}

@Composable
private fun Stepper(
    quantity: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    maxReached: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepperBtn(
            symbol = "−",
            enabled = quantity > 0,
            onClick = onDecrement,
            bg = Color(0xFFEDE4DC),
            fg = Color(0xFF57534E)
        )
        Text(
            text = quantity.toString(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp),
            maxLines = 1
        )
        StepperBtn(
            symbol = "+",
            enabled = !maxReached,
            onClick = onIncrement,
            bg = MaterialTheme.colorScheme.secondary,
            fg = MaterialTheme.colorScheme.onSecondary
        )
    }
}

@Composable
private fun StepperBtn(
    symbol: String,
    enabled: Boolean,
    onClick: () -> Unit,
    bg: Color,
    fg: Color
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(
                color = if (enabled) bg else bg.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = symbol, color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FloatingConfirmBar(
    itemCount: Int,
    total: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary
            )
        ) {
            Text(
                text = "Confirm Delivery · $itemCount item${if (itemCount == 1) "" else "s"} · ${formatInr(total)}",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onDismiss() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = "⚠ $message",
            fontSize = 13.sp,
            color = Color(0xFF92400E),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

// ── Confirm sheet ────────────────────────────────────────────────────────────

@Composable
private fun ConfirmSheetContent(
    state: DeliveryEntryUiState,
    onCancel: () -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .navigationBarsPadding()
    ) {
        Text(
            text = "Confirm Delivery",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = buildString {
                append(state.storeName)
                val dist = formatDistance(state.distanceMeters)
                if (dist != null) append(" · $dist away")
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Line items summary card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFAF5EF)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                state.products.forEach { product ->
                    val qty = state.quantities[product.productId] ?: 0
                    if (qty <= 0) return@forEach
                    val line = qty * product.unitPrice
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${product.productName} × $qty",
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = formatInr(line),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0xFFE7E0D7))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Total", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = formatInr(state.totalValue),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Text(
                text = "⚠ Delivery entries cannot be edited after submission",
                fontSize = 12.sp,
                color = Color(0xFF92400E),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(text = "Cancel", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onSubmit,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Text(text = "Submit Delivery", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ── Success View ─────────────────────────────────────────────────────────────

@Composable
private fun SuccessView(state: DeliveryEntryUiState, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(Color(0xFFFEF3C7), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Inventory2,
                contentDescription = null,
                tint = Color(0xFFB45309),
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(text = "Delivery Logged!", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.storeName,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${state.deliveredItemCount} ${if (state.deliveredItemCount == 1) "item" else "items"} · ${formatInr(state.deliveredTotal)}",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.gpsCaptured) {
            Spacer(modifier = Modifier.height(20.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFDCFCE7)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Text(
                    text = "\uD83D\uDCCD GPS location captured",
                    fontSize = 13.sp,
                    color = Color(0xFF166534),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onDone,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(text = "New Delivery", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = { /* TODO: WATI send-bill — deferred per scope. */ },
                enabled = false,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF22C55E),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF22C55E).copy(alpha = 0.4f),
                    disabledContentColor = Color.White
                )
            ) {
                Text(text = "Share via WhatsApp", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── Loading placeholder ──────────────────────────────────────────────────────

@Composable
private fun LoadingView(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(8.dp)
        ) {
            Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

// ── Formatters ───────────────────────────────────────────────────────────────

private fun formatInr(amount: Double): String {
    val fmt = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    fmt.maximumFractionDigits = 0
    return fmt.format(amount)
}

private fun formatDistance(meters: Double?): String? {
    if (meters == null) return null
    return if (meters < 1000) "${meters.toInt()}m"
    else "%.1fkm".format(meters / 1000.0)
}
