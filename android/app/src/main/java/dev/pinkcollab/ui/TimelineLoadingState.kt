package dev.pinkcollab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pinkcollab.ui.theme.RetroBrass
import dev.pinkcollab.ui.theme.retroPanel

@Composable
internal fun TimelineLoadingState(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().retroPanel(inset = true).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            repeat(3) { index ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.width(if (index == 1) 72.dp else 48.dp).height(10.dp)
                        .background(RetroBrass.copy(alpha = 0.22f), RoundedCornerShape(3.dp)))
                    repeat(if (index == 1) 3 else 2) { line ->
                        Spacer(Modifier.fillMaxWidth(if (line == 1) 0.65f else 0.9f).height(12.dp)
                            .background(RetroBrass.copy(alpha = 0.14f), RoundedCornerShape(3.dp)))
                    }
                }
            }
        }
    }
}
