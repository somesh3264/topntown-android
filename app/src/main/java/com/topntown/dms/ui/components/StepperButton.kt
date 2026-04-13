package com.topntown.dms.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Large +/- stepper used as the ONLY way to enter product quantity.
 *
 * Design rationale (low-literacy UX — strictly enforced):
 *  - 48dp circular tap targets on both buttons (meets Material accessibility + fat-finger use on
 *    low-end 2GB devices held by distributors in the field).
 *  - Quantity display is 24sp bold. It is *not* focusable, *not* clickable, and consumes any
 *    incoming click/focus so the soft keyboard can never open for the quantity field. This is a
 *    hard requirement — distributors must never be asked to type a number.
 *  - Haptic feedback (LongPress-strength bump) fires on every successful tap, giving a clear
 *    confirmation that doesn't rely on literacy or sound.
 *  - Minus is disabled at [minValue] (default 0). Plus is disabled at [maxValue].
 */
@Composable
fun StepperButton(
    value: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    modifier: Modifier = Modifier,
    minValue: Int = 0,
    maxValue: Int = 999,
    enabled: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    // A non-indicating interaction source wired into the Text via .clickable with a null
    // indication swallows any focus/click. Combined with clearAndSetSemantics this guarantees
    // screen readers and the IME treat the quantity as decorative text, not an input.
    val sinkInteraction = remember { MutableInteractionSource() }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FilledIconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDecrement()
            },
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            enabled = enabled && value > minValue,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "Decrease quantity",
                modifier = Modifier.size(28.dp)
            )
        }

        Text(
            text = "$value",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(min = 48.dp)
                // Explicitly make the quantity non-focusable and non-editable.
                .focusProperties { canFocus = false }
                // Swallow any stray clicks with a no-op so the text itself never triggers
                // keyboard input or selection toolbars.
                .clickable(
                    interactionSource = sinkInteraction,
                    indication = null,
                    enabled = true,
                    onClick = { /* intentionally blank — quantity is stepper-only */ }
                )
                .clearAndSetSemantics { }
        )

        FilledIconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onIncrement()
            },
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            enabled = enabled && value < maxValue,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Increase quantity",
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
