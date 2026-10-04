package dev.pinkcollab.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*

@Composable
internal fun AttentionCard(attention: Attention, enabled: Boolean, respond: (AttentionResponse) -> Unit) {
    var answer by rememberSaveable(attention.id) { mutableStateOf("") }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).retroPanel(accented = true),
        shape = RoundedCornerShape(3.dp),
        color = Color.Transparent,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "OMP needs your reply",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                color = WarningAmber,
            )
            Text(
                attention.text,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.SansSerif,
                color = TextHigh,
            )
            when (attention.type) {
                AttentionType.Select -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    attention.options.forEach { option ->
                        OutlinedButton(
                            onClick = rememberHapticOnClick { respond(AttentionResponse.Value(attention.id, option)) },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).retroPanel(inset = true),
                            shape = RoundedCornerShape(3.dp),
                            border = BorderStroke(1.dp, RetroBrass.copy(alpha = if (enabled) 0.45f else 0.18f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = RetroText),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(
                                option,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.SansSerif,
                            )
                            Spacer(Modifier.width(12.dp))
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null,
                                modifier = Modifier.size(18.dp), tint = if (enabled) RetroBrass else TextMid.copy(alpha = 0.38f))
                        }
                    }
                }
                AttentionType.Confirm -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = rememberHapticOnClick { respond(AttentionResponse.Confirmation(attention.id, false)) }, enabled = enabled) {
                        Text("Decline", fontFamily = FontFamily.SansSerif)
                    }
                    PrimaryButton(onClick = { respond(AttentionResponse.Confirmation(attention.id, true)) }, enabled = enabled) {
                        Text("Confirm", fontFamily = FontFamily.SansSerif)
                    }
                }
                else -> {
                    OutlinedTextField(
                        answer, { answer = it },
                        label = { Text("Your answer", fontFamily = FontFamily.SansSerif) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
                        minLines = if (attention.type == AttentionType.Editor) 4 else 1,
                        modifier = Modifier.fillMaxWidth().retroPanel(inset = true),
                        enabled = enabled,
                        shape = RoundedCornerShape(3.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RetroBrass,
                            unfocusedBorderColor = RetroBrass.copy(alpha = 0.25f), cursorColor = RetroBrass),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        PrimaryButton(onClick = { respond(AttentionResponse.Value(attention.id, answer)) }, enabled = enabled) {
                            Text("Submit", fontFamily = FontFamily.SansSerif)
                        }
                    }
                }
            }
            TextButton(
                onClick = rememberHapticOnClick { respond(AttentionResponse.Cancel(attention.id)) },
                modifier = Modifier.align(Alignment.End),
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = TextMid),
            ) { Text("Cancel", fontFamily = FontFamily.SansSerif) }
        }
    }
}
