package dev.pinkcollab.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics

/** Full-width message body. A long press copies [text]; empty or non-copyable bodies do not. */
@Composable
internal fun TimelineMessageBody(
    text: String,
    copyable: Boolean = true,
    captureTouch: Boolean = true,
    content: @Composable (onCopy: (() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val latestText = rememberUpdatedState(text)
    val enabled = copyable && text.isNotEmpty()
    val copy = remember(context, haptics) {
        {
            val value = latestText.value
            if (value.isNotEmpty()) {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Message", value))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }
    val action = if (enabled) copy else null
    Box(
        if (enabled && captureTouch) Modifier.fillMaxWidth().longPressToCopy(copy) else Modifier.fillMaxWidth(),
    ) { content(action) }
}

@Composable
private fun Modifier.longPressToCopy(onLongPress: () -> Unit): Modifier {
    val current = rememberUpdatedState(onLongPress)
    return semantics {
        onLongClick(label = "Copy message") {
            current.value()
            true
        }
    }.pointerInput(Unit) {
        detectTapGestures(onLongPress = { current.value() })
    }
}

/** Assistant Markdown consumes touches, so long-press copy has to live on the text view. */
internal class MessageBodyTextView(context: Context) : TextView(context) {
    var onCopy: (() -> Unit)? = null

    override fun performLongClick(): Boolean {
        val copy = onCopy ?: return super.performLongClick()
        copy()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
        return true
    }
}
