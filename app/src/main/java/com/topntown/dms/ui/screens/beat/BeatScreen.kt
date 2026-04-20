package com.topntown.dms.ui.screens.beat

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.topntown.dms.ui.components.LoadingScreen
import com.topntown.dms.ui.navigation.Routes
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Deliver (a.k.a. Beat) screen — step 1 of the delivery flow.
 *
 * • Lists every store where primary_distributor_id = current distributor.
 * • Sorted nearest-first when location is available; alphabetically otherwise.
 * • Top green banner surfaces GPS state so the operator knows the sort they're seeing.
 * • Tap a store → navigate to per-store delivery entry screen.
 */
@Composable
fun BeatScreen(
    navController: NavController,
    viewModel: BeatViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Request location on first composition. If the user grants, we re-load so
    // the sort becomes distance-based. If denied, we just fall through with
    // alphabetical sort — no hard gate on the screen.
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) viewModel.refresh()
    }
    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    if (state.isLoading) {
        LoadingScreen(message = "Loading stores…")
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header(gpsActive = state.gpsActive) }

        if (state.gpsActive) {
            item { GpsBanner() }
        } else {
            item { NoGpsHint() }
        }

        if (state.stores.isEmpty()) {
            item { EmptyState() }
        } else {
            itemsIndexed(stores = state.stores, gpsActive = state.gpsActive) { store, isNearest ->
                StoreCard(
                    store = store,
                    isNearest = isNearest,
                    onClick = {
                        navController.navigate(Routes.deliverEntry(store.id)) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
    }
}

/**
 * Thin wrapper around LazyColumn.items that also tells each row whether it's
 * the nearest (first with a finite distance) so the UI can highlight it.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    stores: List<BeatStore>,
    gpsActive: Boolean,
    content: @Composable (store: BeatStore, isNearest: Boolean) -> Unit
) {
    // "Nearest" only makes sense when GPS is active. Without it every row is
    // equally "far," so we don't decorate any of them.
    val nearestId: String? = if (gpsActive) {
        stores.firstOrNull { it.distanceMeters != null }?.id
    } else null

    items(items = stores, key = { it.id }) { store ->
        content(store, store.id == nearestId)
    }
}

// ── Pieces ───────────────────────────────────────────────────────────────────

@Composable
private fun Header(gpsActive: Boolean) {
    Column {
        Text(
            text = "Log Delivery",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (gpsActive) "Select store · nearest first" else "Select store",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GpsBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFDCFCE7)  // emerald-100
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = "\uD83D\uDCCD GPS active · Showing nearest stores",
            fontSize = 13.sp,
            color = Color(0xFF166534),  // emerald-800
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun NoGpsHint() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFEF3C7)  // amber-100
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = "Location unavailable · Showing stores alphabetically",
            fontSize = 13.sp,
            color = Color(0xFF92400E),  // amber-900
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun StoreCard(
    store: BeatStore,
    isNearest: Boolean,
    onClick: () -> Unit
) {
    val highlightColour = MaterialTheme.colorScheme.secondary  // brand brown
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .then(
                if (isNearest) Modifier.border(
                    width = 2.dp,
                    color = highlightColour,
                    shape = RoundedCornerShape(20.dp)
                ) else Modifier
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon chip
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = Color(0xFFF5F0EA),  // stone-100-ish
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Storefront,
                    contentDescription = null,
                    tint = Color(0xFF78716C),
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.size(12.dp))

            // Middle column — name, owner · area, last visit
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = store.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                val subtitle = listOf(store.ownerName, store.areaName)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                val last = formatLastDelivered(store.lastDelivered)
                if (last != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Last: $last",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            // Right column — distance + NEAREST badge
            Column(horizontalAlignment = Alignment.End) {
                val distText = formatDistance(store.distanceMeters)
                if (distText != null) {
                    Text(
                        text = distText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isNearest) highlightColour else Color.Unspecified
                    )
                }
                if (isNearest) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "NEAREST",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = highlightColour
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Storefront,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = "No stores assigned",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Ask the admin to assign retail stores to your beat.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Formatters ───────────────────────────────────────────────────────────────

private val LAST_FMT = DateTimeFormatter.ofPattern("d MMM")

private fun formatLastDelivered(iso: String?): String? {
    if (iso.isNullOrBlank()) return "Never"
    return runCatching {
        val date = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDate()
        if (date == LocalDate.now(ZoneId.of("Asia/Kolkata"))) "Today" else date.format(LAST_FMT)
    }.getOrNull()
}

private fun formatDistance(meters: Double?): String? {
    if (meters == null) return null
    return if (meters < 1000) "${meters.toInt()}m"
    else "%.1fkm".format(meters / 1000.0)
}
