package com.topntown.dms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// TopNTown brand orange — kept local so the banner renders consistently whether the
// caller sits inside or outside the Material theme tree.
private val BannerOrange = Color(0xFFFF6F00)
private val BannerOnOrange = Color(0xFFFFFFFF)

/**
 * Orange "Order closes in HH:MM:SS" banner shown at the top of the order screen.
 *
 * The composable owns its own 1-second ticker so the parent ViewModel doesn't have to
 * emit state every second (which would thrash recomposition of the whole product list).
 * [cutoffEpochMillis] is the absolute wall-clock deadline (today's cut-off, assembled
 * once in the ViewModel from system_config.cut_off_time + the device's local date).
 *
 * When the remaining duration hits zero, [onExpired] fires exactly once. The parent is
 * responsible for swapping the whole form for the "order time has passed" state — this
 * banner purposely does NOT render the expired message itself, so it can be reused for
 * any screen that needs a countdown.
 */
@Composable
fun CutoffBanner(
    cutoffEpochMillis: Long,
    modifier: Modifier = Modifier,
    onExpired: () -> Unit = {}
) {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Re-tick every second while this composable is in composition. Keyed on the cutoff so
    // if the backend value changes (e.g. admin pushes a new cut-off) we restart cleanly.
    LaunchedEffect(cutoffEpochMillis) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            if (nowMillis >= cutoffEpochMillis) {
                onExpired()
                break
            }
            delay(1_000L)
        }
    }

    val remainingMs = (cutoffEpochMillis - nowMillis).coerceAtLeast(0L)
    val totalSec = remainingMs / 1_000L
    val hours = totalSec / 3_600L
    val minutes = (totalSec % 3_600L) / 60L
    val seconds = totalSec % 60L
    val countdownText = "%02d:%02d:%02d".format(hours, minutes, seconds)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BannerOrange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.AccessTime,
            contentDescription = null,
            tint = BannerOnOrange
        )
        Text(
            text = "Order closes in $countdownText",
            color = BannerOnOrange,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
