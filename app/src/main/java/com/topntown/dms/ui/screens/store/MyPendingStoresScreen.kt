package com.topntown.dms.ui.screens.store

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.topntown.dms.domain.model.PendingStoreRow

/**
 * Read-only timeline of the distributor's own store-onboarding submissions.
 * Each row shows status (Pending / Approved / Rejected) and the rejection
 * reason if any. The product decision was read-only — no edit/cancel actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPendingStoresScreen(
    onBack: () -> Unit,
    viewModel: MyPendingStoresViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Submissions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                state.error != null -> Box(
                    Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Couldn't load your submissions: ${state.error}",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                state.rows.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center
                ) {
                    Text(
                        "You haven't submitted any new stores yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.rows)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.items(rows: List<PendingStoreRow>) {
    items(rows.size) { idx ->
        SubmissionRow(rows[idx])
    }
}

@Composable
private fun SubmissionRow(row: PendingStoreRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.storeName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                StatusPill(status = row.status)
            }

            val location = listOfNotNull(row.areaName, row.zoneName)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (location.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    location,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Submitted ${formatTimestamp(row.submittedAt)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (row.status == "rejected" && !row.rejectionReason.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFEE2E2))   // rose-100
                        .padding(10.dp)
                ) {
                    Text(
                        "Rejected: ${row.rejectionReason}",
                        fontSize = 12.sp,
                        color = Color(0xFF991B1B)         // rose-800
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    // Each branch picks a calmer pill colour — the screen would be visually
    // noisy if we used full primary chips for everything.
    val (bg, fg, label) = when (status) {
        "approved" -> Triple(Color(0xFFD1FAE5), Color(0xFF065F46), "Approved")
        "rejected" -> Triple(Color(0xFFFEE2E2), Color(0xFF991B1B), "Rejected")
        else       -> Triple(Color(0xFFFEF3C7), Color(0xFF92400E), "Pending")
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Cheap, dependency-free formatter — Postgres returns ISO-8601 like
 * "2026-04-25T07:48:11.123+00:00", we trim down to "2026-04-25 07:48".
 * Good enough for the field user; we don't ship a date library on Android 8+.
 */
private fun formatTimestamp(iso: String): String {
    if (iso.length < 16) return iso
    return iso.substring(0, 10) + " " + iso.substring(11, 16)
}
