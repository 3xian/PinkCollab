package dev.pinkcollab.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.pinkcollab.ui.theme.Base0
import dev.pinkcollab.ui.theme.PrimaryButton
import dev.pinkcollab.ui.theme.Purple700
import dev.pinkcollab.ui.theme.TextMid
import dev.pinkcollab.ui.theme.rememberHapticOnClick
import org.json.JSONObject

internal fun decodePairingCode(value: String): PairHostSheetState {
    val json = JSONObject(value)
    require(json.getInt("version") == 1) { "Unsupported pairing code version" }
    return PairHostSheetState(
        visible = true,
        url = json.getString("url"),
        token = json.getString("token"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairHostModal(
    state: PairHostSheetState,
    onStateChange: (PairHostSheetState) -> Unit,
    onPair: (url: String, token: String) -> Unit,
) {
    if (!state.visible) return

    val busy = state.attemptId != 0L
    val currentBusy = rememberUpdatedState(busy)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> target != SheetValue.Hidden || !currentBusy.value },
    )
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { value ->
            runCatching { decodePairingCode(value) }
                .onSuccess(onStateChange)
                .onFailure { error ->
                    onStateChange(
                        state.copy(error = "Unrecognized PinkCollab pairing code: ${error.message}"),
                    )
                }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onStateChange(PairHostSheetState()) },
        sheetState = sheetState,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.64f),
        dragHandle = {
            Box(
                Modifier.fillMaxWidth().background(Purple700),
                contentAlignment = Alignment.Center,
            ) {
                BottomSheetDefaults.DragHandle()
            }
        },
    ) {
        PairHostSheet(
            modifier = Modifier.background(
                Brush.verticalGradient(
                    0f to Purple700,
                    0.22f to Color(0xFF1B1123),
                    0.42f to Base0,
                    1f to Base0,
                ),
            ),
            url = state.url,
            token = state.token,
            busy = busy,
            error = state.error,
            onURL = { onStateChange(state.copy(url = it, error = null)) },
            onToken = { onStateChange(state.copy(token = it, error = null)) },
            scan = {
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN)
                        .setPrompt("Scan the pairing code from the Gateway")
                        .setBeepEnabled(false)
                        .setOrientationLocked(true),
                )
            },
            pair = { onPair(state.url, state.token) },
        )
    }
}

@Composable
private fun PairHostSheet(
    modifier: Modifier = Modifier,
    url: String,
    token: String,
    busy: Boolean,
    error: String?,
    onURL: (String) -> Unit,
    onToken: (String) -> Unit,
    scan: () -> Unit,
    pair: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 520.dp, max = 720.dp)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(
                "Connect your OMP host",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Run the pair command on the host and scan the QR code it prints. The code expires in 5 minutes.",
                color = TextMid,
            )
        }
        OutlinedButton(onClick = rememberHapticOnClick(scan), enabled = !busy) {
            Icon(Icons.Outlined.QrCodeScanner, null)
            Spacer(Modifier.size(8.dp))
            Text("Scan pairing code")
        }
        Text("Or enter it manually", style = MaterialTheme.typography.labelLarge, color = TextMid)
        OutlinedTextField(
            value = url,
            onValueChange = onURL,
            label = { Text("Gateway address") },
            placeholder = { Text("https://dev-server.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
        OutlinedTextField(
            value = token,
            onValueChange = onToken,
            label = { Text("One-time pairing token") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
        error?.let { message ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    message,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PrimaryButton(onClick = pair, enabled = url.isNotBlank() && token.isNotBlank() && !busy) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (busy) "Pairing…" else "Pair")
            }
        }
    }
}
