package com.topntown.dms.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.topntown.dms.ui.theme.TntOfflineBanner

/**
 * Returns a [State]<Boolean> that reflects whether the device currently has a
 * validated internet-capable network. Internally registers a
 * [ConnectivityManager.NetworkCallback] scoped to the enclosing composition —
 * the callback is unregistered in [DisposableEffect]'s `onDispose`.
 *
 * We track a set of active networks rather than a single boolean because the
 * platform fires `onLost` for Wi-Fi *before* `onAvailable` for cellular during
 * a handoff — if we flipped a single flag on `onLost` we'd flash the offline
 * banner for a fraction of a second every time the user walks out of Wi-Fi.
 */
@Composable
fun rememberIsOnline(): State<Boolean> {
    val context = LocalContext.current
    val isOnline = remember { mutableStateOf(currentlyOnline(context)) }

    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetworks = mutableSetOf<Network>()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeNetworks.add(network)
                isOnline.value = true
            }

            override fun onLost(network: Network) {
                activeNetworks.remove(network)
                isOnline.value = activeNetworks.isNotEmpty()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Treat a network as active only while it still claims INTERNET
                // capability. Hotspots in captive-portal state drop this bit.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    activeNetworks.add(network)
                } else {
                    activeNetworks.remove(network)
                }
                isOnline.value = activeNetworks.isNotEmpty()
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, callback)

        onDispose {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    return isOnline
}

private fun currentlyOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * Full-width red banner shown at the top of the screen while the device is
 * offline. Callers can either pass an explicit [isOnline] flag (useful in
 * previews/tests) or let the composable observe connectivity itself.
 */
@Composable
fun OfflineNotice(
    modifier: Modifier = Modifier,
    isOnline: Boolean? = null,
    message: String = "No internet connection — delivery logging unavailable"
) {
    val observed = if (isOnline == null) rememberIsOnline().value else isOnline
    val visible = !observed

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(TntOfflineBanner)
                .padding(vertical = 10.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
