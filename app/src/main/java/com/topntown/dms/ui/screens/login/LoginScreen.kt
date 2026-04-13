package com.topntown.dms.ui.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest

/**
 * Full-screen, centered phone+password login for TopNTown distributors.
 *
 * Brand treatment (per product spec, not theme colors):
 *   - Logo text uses #7B4A37 (TNT secondary / earth tone) directly — we deliberately
 *     bypass MaterialTheme.primary here because the spec ties the logo to the
 *     secondary palette, while the Login button uses the primary navy #1B3A6B.
 *   - All interactive elements meet the 48dp minimum tap target.
 *
 * The screen observes one-shot [LoginNavEvent]s from the ViewModel to trigger
 * navigation, so a config change (rotation, dark-mode toggle) won't replay
 * an earlier NavigateToHome event.
 */

private val BrandBrown = Color(0xFF7B4A37)
private val BrandNavy = Color(0xFF1B3A6B)

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collectLatest { event ->
            when (event) {
                LoginNavEvent.NavigateToHome -> onLoginSuccess()
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            LoginCard(
                state = state,
                onPhoneChanged = viewModel::onPhoneChanged,
                onOtpChanged = viewModel::onOtpChanged,
                onToggleVisibility = viewModel::toggleOtpVisibility,
                onLogin = viewModel::login
            )
        }
    }
}

@Composable
private fun LoginCard(
    state: LoginUiState,
    onPhoneChanged: (String) -> Unit,
    onOtpChanged: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onLogin: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .sizeIn(maxWidth = 480.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(horizontal = 24.dp, vertical = 32.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            BrandHeader()

            Spacer(modifier = Modifier.height(32.dp))

            PhoneField(
                value = state.phone,
                onValueChange = onPhoneChanged,
                enabled = !state.isLoading
            )

            Spacer(modifier = Modifier.height(16.dp))

            OtpField(
                value = state.otp,
                onValueChange = onOtpChanged,
                isVisible = state.isOtpVisible,
                onToggleVisibility = onToggleVisibility,
                enabled = !state.isLoading,
                onImeDone = onLogin
            )

            Spacer(modifier = Modifier.height(24.dp))

            LoginButton(
                isLoading = state.isLoading,
                enabled = state.canSubmit,
                onClick = onLogin
            )

            if (state.error != null) {
                Spacer(modifier = Modifier.height(12.dp))
                ErrorText(message = state.error)
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Text(
        text = "TOP N TOWN",
        color = BrandBrown,
        fontSize = 28.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 4.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Distributor App",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PhoneField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Phone") },
        placeholder = { Text("10-digit mobile") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Next
        ),
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
    )
}

@Composable
private fun OtpField(
    value: String,
    onValueChange: (String) -> Unit,
    isVisible: Boolean,
    onToggleVisibility: () -> Unit,
    enabled: Boolean,
    onImeDone: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("OTP") },
        placeholder = { Text("6-digit OTP") },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onImeDone() }),
        textStyle = MaterialTheme.typography.bodyLarge,
        trailingIcon = {
            IconButton(
                onClick = onToggleVisibility,
                // Ensure a 48dp tap target around the icon even on small devices.
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (isVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (isVisible) "Hide OTP" else "Show OTP"
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
    )
}

@Composable
private fun LoginButton(
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = BrandNavy,
            contentColor = Color.White,
            disabledContainerColor = BrandNavy.copy(alpha = 0.4f),
            disabledContentColor = Color.White.copy(alpha = 0.7f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            // 56dp keeps the tap target comfortably above the 48dp minimum and
            // matches the field heights so the form visually aligns.
            .height(56.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.5.dp
            )
        } else {
            Text(
                text = "Login",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}
