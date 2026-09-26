package dev.pinkcollab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*

@Composable
internal fun AttentionCard(attention: Attention, enabled: Boolean, respond: (AttentionResponse) -> Unit) {

    val attentionColor = statusColor(SessionStatus.NeedsInput)
    var answer by rememberSaveable(attention.id) { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .timelineBand(
                tint = Purple400,
                tintAlpha = 0.10f,
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlowDot(attentionColor, pulse = true)
            Spacer(Modifier.width(8.dp))
            Text("OMP needs your reply", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = attentionColor)
        }
        Text(attention.text, style = MaterialTheme.typography.bodyMedium, color = TextHigh)
        when (attention.type) {
            AttentionType.Select -> attention.options.forEach { option ->
                OutlinedButton(onClick = rememberHapticOnClick { respond(AttentionResponse.Value(attention.id, option)) }, enabled = enabled) { Text(option) }
            }
            AttentionType.Confirm -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = rememberHapticOnClick { respond(AttentionResponse.Confirmation(attention.id, false)) }, enabled = enabled) { Text("Decline") }
                Spacer(Modifier.width(8.dp))
                PrimaryButton(onClick = { respond(AttentionResponse.Confirmation(attention.id, true)) }, enabled = enabled) { Text("Confirm") }
            }
            else -> {
                OutlinedTextField(answer, { answer = it }, label = { Text("Your answer") }, minLines = if (attention.type == AttentionType.Editor) 4 else 1, modifier = Modifier.fillMaxWidth(), enabled = enabled, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple400, unfocusedBorderColor = Color.White.copy(alpha = 0.14f), cursorColor = Purple400))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PrimaryButton(onClick = { respond(AttentionResponse.Value(attention.id, answer)) }, enabled = enabled) { Text("Submit") }
                }
            }
        }
        TextButton(onClick = rememberHapticOnClick { respond(AttentionResponse.Cancel(attention.id)) }, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = TextMid)) { Text("Cancel") }
    }
}
