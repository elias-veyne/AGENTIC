package com.jarves.mh.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Status card for on-device model provisioning.
 *
 * A card rather than a gate, on purpose. The download is 639 MB of the user's mobile
 * data and takes minutes; making the app unopenable until it finishes would be a worse
 * product than never having the feature, and would re-block every launch if the model
 * were removed. The user can dismiss it, and the app stays fully usable throughout -
 * routing simply stays on the remote provider until the model is resident.
 *
 * Rendered from [AppUiState] rather than owning state, so it survives recomposition
 * without leaking a download job into the composition.
 */
@Composable
fun LocalModelStatusCard(
    stage: LocalModelStage,
    progress: Float,
    message: String,
    bytes: Pair<Long, Long>?,
    benchmark: String?,
    error: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Nothing to say when idle, and nothing to say once dismissed.
    if (stage == LocalModelStage.IDLE || stage == LocalModelStage.DISMISSED) return

    val failed = stage == LocalModelStage.FAILED
    val done = stage == LocalModelStage.READY
    val busy = stage == LocalModelStage.DOWNLOADING ||
        stage == LocalModelStage.VERIFYING ||
        stage == LocalModelStage.INSTALLING ||
        stage == LocalModelStage.LOADING

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = headline(stage),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (busy) {
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = message,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            if (busy) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            bytes?.let { (downloaded, total) ->
                if (busy && total > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${mb(downloaded)} of ${mb(total)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.5.sp,
                    )
                }
            }

            // The measured result is the point of the whole feature: it is how the
            // PRD's latency targets get checked on real hardware.
            benchmark?.takeIf { done }?.let {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Measured on this device",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            error?.takeIf { failed }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.5.sp,
                    maxLines = 4,
                )
            }

            if (failed || done) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(if (done) "Dismiss" else "Not now", fontSize = 12.5.sp)
                    }
                    if (failed) {
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onRetry) { Text("Retry", fontSize = 12.5.sp) }
                    }
                }
            }
        }
    }
}

private fun headline(stage: LocalModelStage): String = when (stage) {
    LocalModelStage.DOWNLOADING -> "Setting up on-device AI"
    LocalModelStage.VERIFYING -> "Checking the download"
    LocalModelStage.INSTALLING -> "Installing"
    LocalModelStage.LOADING -> "Loading model"
    LocalModelStage.READY -> "On-device AI ready"
    LocalModelStage.FAILED -> "On-device AI unavailable"
    // IDLE and DISMISSED never render; the guard above returns first.
    LocalModelStage.IDLE, LocalModelStage.DISMISSED -> ""
}

private fun mb(bytes: Long): String = "${bytes / 1_000_000} MB"
