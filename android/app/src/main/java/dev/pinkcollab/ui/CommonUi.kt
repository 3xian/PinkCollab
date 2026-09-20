package dev.pinkcollab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pinkcollab.ui.theme.PrimaryButton
import dev.pinkcollab.ui.theme.Purple400

@Composable
internal fun EmptyState(
    title: String,
    description: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp).drawBehind {
                drawCircle(Brush.radialGradient(listOf(Purple400.copy(alpha = 0.30f), Color.Transparent)))
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Hub, null, Modifier.size(52.dp), tint = Purple400)
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        action?.let {
            Spacer(Modifier.height(24.dp))
            PrimaryButton(onClick = onAction) { Text(it) }
        }
    }
}
