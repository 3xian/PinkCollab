package dev.pinkcollab.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pinkcollab.R
import dev.pinkcollab.ui.theme.PrimaryButton

@Composable
internal fun EmptyState(
    title: String,
    description: String,
    action: String? = null,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.moon_robot),
                contentDescription = null,
                modifier = Modifier.size(120.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                title,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                description,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            action?.let {
                Spacer(Modifier.height(24.dp))
                PrimaryButton(onClick = onAction) { Text(it) }
            }
        }
    }
}
