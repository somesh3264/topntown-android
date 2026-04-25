package com.topntown.dms.ui.screens.store

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * Distributor store-onboarding screen — mobile parity to the dashboard's
 * `StoreForm.tsx` slide-over (FRD v1.2 BR-11).
 *
 * Required fields (matches dashboard guards):
 *   • Store name
 *   • Zone + Area (cascading)
 *   • Live GPS fix
 *   • Live shop photo (BR-12 — gallery picks are NOT accepted)
 *
 * On Submit, the store is created with `is_active = false` and a
 * `store_approval_requests` row is queued for the Super Admin. The
 * distributor stays opted-out of the store until approval — they can track
 * status via [MyPendingStoresScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreOnboardingScreen(
    onComplete: () -> Unit,
    onViewPending: () -> Unit,
    onBack: () -> Unit,
    viewModel: StoreOnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Show transient errors via the snackbar so the user can keep typing.
    LaunchedEffect(state.error) {
        state.error?.let { msg ->
            scope.launch {
                snackbarHostState.showSnackbar(msg)
                viewModel.dismissError()
            }
        }
    }

    // Bubble success up to the navigator. We delay slightly so the success
    // banner is visible before the screen pops.
    LaunchedEffect(state.submittedStoreId) {
        if (state.submittedStoreId != null) {
            scope.launch {
                snackbarHostState.showSnackbar("Submitted for Super Admin approval.")
                onComplete()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add New Store") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onViewPending) {
                        Text("My Submissions")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { ApprovalNoticeCard() }

            item {
                OutlinedTextField(
                    value = state.storeName,
                    onValueChange = viewModel::onStoreNameChanged,
                    label = { Text("Store Name *") },
                    placeholder = { Text("e.g. Krishna General Store") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = state.ownerName,
                    onValueChange = viewModel::onOwnerNameChanged,
                    label = { Text("Owner Name") },
                    placeholder = { Text("e.g. Ramesh Patel") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = state.phone,
                    onValueChange = viewModel::onPhoneChanged,
                    label = { Text("Phone (10 digits)") },
                    placeholder = { Text("9876543210") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = state.address,
                    onValueChange = viewModel::onAddressChanged,
                    label = { Text("Address") },
                    placeholder = { Text("Full street address") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── Zone picker ──────────────────────────────────────────────
            item {
                LookupDropdown(
                    label = "Zone *",
                    options = state.zones.map { it.id to it.name },
                    selectedId = state.zoneId,
                    placeholder = "Select zone…",
                    onSelected = viewModel::onZoneSelected,
                )
            }

            // ── Area picker ──────────────────────────────────────────────
            item {
                LookupDropdown(
                    label = "Area *",
                    options = state.areas.map { it.id to it.name },
                    selectedId = state.areaId,
                    placeholder = when {
                        state.zoneId.isBlank() -> "Select a zone first"
                        state.areasLoading -> "Loading areas…"
                        state.areas.isEmpty() -> "No areas in this zone"
                        else -> "Select area…"
                    },
                    onSelected = viewModel::onAreaSelected,
                    enabled = state.zoneId.isNotBlank() && !state.areasLoading,
                )
            }

            item { GpsCard(state = state, onCapture = viewModel::captureGps) }

            item { PhotoCard(state = state, onPhotoCaptured = viewModel::onPhotoCaptured) }

            item {
                MissingFieldsHint(state)
            }

            item {
                Button(
                    onClick = viewModel::submit,
                    enabled = state.canSubmit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    if (state.isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Submit for Approval", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ── Approval-flow notice ──────────────────────────────────────────────────────

@Composable
private fun ApprovalNoticeCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)) // amber-50
    ) {
        Text(
            text = "New stores require Super Admin approval before they appear " +
                "on your beat. You'll see the status under \"My Submissions\".",
            color = Color(0xFFB45309),  // amber-700
            fontSize = 13.sp,
            modifier = Modifier.padding(14.dp)
        )
    }
}

// ── Generic dropdown wrapper ─────────────────────────────────────────────────
//
// Avoids `ExposedDropdownMenuBox` because that API's companion symbols
// (`ExposedDropdownMenu`, `Modifier.menuAnchor()`) move between Material 3
// versions. A Box-anchored DropdownMenu over a read-only TextField behaves
// the same and works on every material3 release.

@Composable
private fun LookupDropdown(
    label: String,
    options: List<Pair<String, String>>,  // id → name
    selectedId: String,
    placeholder: String,
    onSelected: (String) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = options.firstOrNull { it.first == selectedId }?.second ?: ""

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            trailingIcon = {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            },
            modifier = Modifier.fillMaxWidth()
        )
        // Transparent overlay to capture taps on the read-only TextField — the
        // text field itself swallows clicks when readOnly + enabled, so a Box
        // on top with `clickable` is the simplest path that doesn't break
        // a11y semantics or focus handling.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(enabled = enabled) { expanded = !expanded }
        )
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            placeholder,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    enabled = false,
                    onClick = {}
                )
            } else {
                options.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onSelected(id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

// ── GPS card ──────────────────────────────────────────────────────────────────

@Composable
private fun GpsCard(state: StoreOnboardingUiState, onCapture: () -> Unit) {
    val context = LocalContext.current
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) onCapture()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "GPS Location *",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))

            if (state.gpsLat != null && state.gpsLng != null) {
                Text(
                    "Captured: ${"%.5f".format(state.gpsLat)}, ${"%.5f".format(state.gpsLng)}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Stand at the storefront and tap Capture.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) onCapture()
                    else locationLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                },
                enabled = !state.gpsCapturing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.gpsCapturing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    if (state.gpsLat != null) "Re-capture GPS"
                    else if (state.gpsAttempted) "Try Again"
                    else "Capture GPS"
                )
            }
        }
    }
}

// ── Photo card ────────────────────────────────────────────────────────────────

@Composable
private fun PhotoCard(
    state: StoreOnboardingUiState,
    onPhotoCaptured: (android.graphics.Bitmap) -> Unit,
) {
    val context = LocalContext.current

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        // Null = user backed out of the camera UI without taking a shot.
        if (bitmap != null) onPhotoCaptured(bitmap)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) cameraLauncher.launch(null)
    }

    fun launchCamera() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) cameraLauncher.launch(null) else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CameraAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Live Shop Photo *",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Take a photo of the storefront. Gallery uploads are not allowed.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            if (state.photoBitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black)
                        .clickable { launchCamera() }
                ) {
                    Image(
                        bitmap = state.photoBitmap.asImageBitmap(),
                        contentDescription = "Captured shop photo",
                        modifier = Modifier.fillMaxSize()
                    )
                    if (state.photoUploading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    } else if (state.photoUrl != null) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Uploaded") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = Color(0xFFD1FAE5),  // emerald-100
                                labelColor = Color(0xFF065F46),
                                leadingIconContentColor = Color(0xFF065F46),
                            ),
                            modifier = Modifier
                                .padding(8.dp)
                                .align(Alignment.TopEnd)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { launchCamera() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Re-take Photo")
                }
            } else {
                Button(
                    onClick = { launchCamera() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Open Camera")
                }
            }
        }
    }
}

// ── Submit-time hint listing what's still missing ─────────────────────────────

@Composable
private fun MissingFieldsHint(state: StoreOnboardingUiState) {
    val missing = buildList {
        if (state.storeName.isBlank()) add("• Store name")
        if (state.areaId.isBlank()) add("• Zone and Area")
        if (state.gpsLat == null || state.gpsLng == null) add("• GPS location")
        if (state.photoUrl == null) add("• Shop photo (and upload to finish)")
    }
    if (missing.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Required before submitting:",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            missing.forEach {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
