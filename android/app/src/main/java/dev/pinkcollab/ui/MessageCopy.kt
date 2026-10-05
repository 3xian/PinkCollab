package dev.pinkcollab.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pinkcollab.ui.theme.BrandPurple
import dev.pinkcollab.ui.theme.rememberHapticOnClick

/** Keeps the body full-width and reserves a short footer for the copy action. */
@Composable
internal fun TimelineMessageBody(text: String, copyable: Boolean = true, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(bottom = if (copyable) 20.dp else 0.dp)) { content() }
        if (copyable) {
            Box(Modifier.matchParentSize()) {
                CopyMessageButton(text, Modifier.align(Alignment.BottomEnd).offset(x = 8.dp, y = 12.dp))
            }
        }
    }
}

@Composable
private fun CopyMessageButton(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Streaming updates change the copied value, not the lifetime of click feedback.
    var copyCount by remember { mutableIntStateOf(0) }
    val feedback = remember { Animatable(1f) }
    LaunchedEffect(copyCount) {
        if (copyCount > 0) {
            feedback.snapTo(0f)
            feedback.animateTo(1f, tween(1_000))
        }
    }
    Box(modifier.size(32.dp)) {
        IconButton(
            onClick = rememberHapticOnClick {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Message", text))
                copyCount++
            },
            modifier = Modifier.fillMaxSize(),
            enabled = text.isNotBlank(),
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "Copy message",
                modifier = Modifier.size(14.dp),
                tint = BrandPurple,
            )
        }
        if (feedback.value < 1f) {
            Text(
                "Copied",
                modifier = Modifier.align(Alignment.TopCenter)
                    .wrapContentSize(unbounded = true)
                    .offset(y = (-16).dp)
                    .graphicsLayer {
                        val progress = feedback.value
                        translationY = -12.dp.toPx() * progress
                        alpha = minOf(progress / 0.12f, (1f - progress) / 0.4f, 1f)
                    },
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Default,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}
