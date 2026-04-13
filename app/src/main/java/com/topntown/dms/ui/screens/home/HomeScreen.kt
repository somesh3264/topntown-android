package com.topntown.dms.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.topntown.dms.ui.components.LoadingScreen
import com.topntown.dms.ui.navigation.Routes
import com.topntown.dms.ui.theme.TntSuccess
import java.text.NumberFormat
import java.util.Locale

/**
 * Distributor dashboard — the first screen a signed-in user sees. Layout is
 * deliberately vertical-scroll-only so it stays usable on small phones held in
 * one hand while a user is out on a beat.
 *
 * Pull-to-refresh uses the Material3 1.2.x `PullToRefreshContainer` API (newer
 * `PullToRefreshBox` from 1.3+ isn't on this project's compose-bom yet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.isLoading) {
        LoadingScreen(message = "Loading today's summary…")
        return
    }

    val pullState = rememberPullToRefreshState()

    // Keep the PullToRefreshState in sync with our ViewModel-driven flag. The
    // container exposes two events (startRefresh / endRefresh) rather than a
    // setter, so we react to state transitions via LaunchedEffect.
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) viewModel.refresh()
    }
    LaunchedEffect(state.isRefreshing) {
        if (!state.isRefreshing) pullState.endRefresh()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pullState.nestedScrollConnection)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { GreetingHeader(name = state.session.fullName, segment = state.greetingSegment) }

            item { KpiGrid(state = state) }

            item { TodaysStockSection(rows = state.topStock) }

            item {
                ActionButtons(
                    placeOrderEnabled = state.placeOrderEnabled,
                    cutOffStatus = state.cutOffStatus,
                    onStartBeat = {
                        navController.navigate(Routes.BEAT) {
                            launchSingleTop = true
                        }
                    },
                    onPlaceOrder = {
                        navController.navigate(Routes.ORDER) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }

        PullToRefreshContainer(
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun GreetingHeader(name: String, segment: String) {
    Column {
        Text(
            text = "Good $segment,",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            // Fall back to a generic honorific rather than "Good morning, " with
            // a trailing comma — happens briefly on cold-start before DataStore
            // emits the first session value.
            text = name.ifBlank { "Distributor" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun KpiGrid(state: HomeUiState) {
    // 2×2 grid, not LazyVerticalGrid — the content is fixed at four tiles so a
    // plain Row×2 is simpler and composes at a predictable height.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KpiCard(
                modifier = Modifier.weight(1f),
                title = "Deliveries Today",
                value = "${state.deliveriesToday}/${state.assignedStoresToday.coerceAtLeast(state.deliveriesToday)}"
            )
            KpiCard(
                modifier = Modifier.weight(1f),
                title = "Bill Status",
                value = state.billLabel,
                valueSizeSp = 18,
                valueColor = if (state.billReady) TntSuccess else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KpiCard(
                modifier = Modifier.weight(1f),
                title = "Stock Remaining",
                value = "${state.stockRemainingPct}%"
            )
            KpiCard(
                modifier = Modifier.weight(1f),
                title = "Payments Collected",
                value = formatInr(state.paymentsCollectedInr),
                // Rupee amounts can get wide; drop the number a touch so long
                // figures don't truncate with an ellipsis.
                valueSizeSp = 22
            )
        }
    }
}

@Composable
private fun KpiCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    valueSizeSp: Int = 28,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = value,
                fontSize = valueSizeSp.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1
            )
            Text(
                text = title,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TodaysStockSection(rows: List<StockRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Today's Stock",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (rows.isEmpty()) {
            Text(
                text = "No stock allocated yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            rows.forEach { row -> StockProgressRow(row = row) }
        }
    }
}

@Composable
private fun StockProgressRow(row: StockRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = row.productName.ifBlank { row.sku },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = "${row.remaining}/${row.allocated}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { row.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun ActionButtons(
    placeOrderEnabled: Boolean,
    cutOffStatus: String,
    onStartBeat: () -> Unit,
    onPlaceOrder: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onStartBeat,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = TntSuccess,
                contentColor = Color.White
            )
        ) {
            Text("Start Beat", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Button(
            onClick = onPlaceOrder,
            enabled = placeOrderEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Place Order", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        if (cutOffStatus.isNotBlank()) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = cutOffStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (placeOrderEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** Formats as ₹ with Indian digit grouping (e.g. ₹1,25,300). */
private fun formatInr(amount: Double): String {
    val fmt = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    fmt.maximumFractionDigits = 0
    return fmt.format(amount)
}
