package dev.pinkcollab.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Decorates a click with Android's light key-tap feedback while keeping the callback stable
 * across recompositions. Platform haptic settings remain authoritative.
 */
@Composable
internal fun rememberHapticOnClick(onClick: () -> Unit): () -> Unit {
    val haptics = LocalHapticFeedback.current
    val currentOnClick = rememberUpdatedState(onClick)
    return remember(haptics) {
        {
            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
            currentOnClick.value()
        }
    }
}
