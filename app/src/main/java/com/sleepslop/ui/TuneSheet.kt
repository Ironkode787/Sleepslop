package com.sleepslop.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sleepslop.audio.AudioEngine
import com.sleepslop.audio.SpeakerTuner
import kotlin.math.abs
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuneSheet(onDismiss: () -> Unit) {
    val state by SpeakerTuner.state.collectAsState()
    val eqGains by AudioEngine.eqGains.collectAsState()
    val eqEnabled by AudioEngine.eqEnabled.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) SpeakerTuner.start(context)
    }

    fun startCalibration() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            SpeakerTuner.start(context)
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            SpeakerTuner.cancel()
            onDismiss()
        },
        containerColor = NightSurface,
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 40.dp)) {
            Text(
                "Speaker tuning",
                style = MaterialTheme.typography.titleLarge,
                color = Moonlight,
                fontWeight = FontWeight.Medium,
            )

            when (val s = state) {
                is SpeakerTuner.State.Idle -> {
                    Text(
                        "Sleepslop can measure your speaker and room with the microphone " +
                            "and flatten the response with an EQ. Works with Bluetooth " +
                            "speakers — the measurement is immune to Bluetooth latency.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Mist,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (eqGains != null) {
                        EqCurve(eqGains!!)
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Room EQ",
                                style = MaterialTheme.typography.titleSmall,
                                color = Moonlight,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = eqEnabled,
                                onCheckedChange = { AudioEngine.setEqEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Night,
                                    checkedTrackColor = Periwinkle,
                                ),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SheetButton("Recalibrate", primary = true) { startCalibration() }
                            SheetButton("Remove", primary = false) { AudioEngine.setEq(null) }
                        }
                    } else {
                        Text(
                            "Place the phone about an arm's length from the speaker, set a " +
                                "comfortable volume, keep the room quiet, and hold still for " +
                                "ten seconds of pink noise.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Mist,
                        )
                        Spacer(Modifier.height(16.dp))
                        SheetButton("Start calibration", primary = true) { startCalibration() }
                    }
                }

                is SpeakerTuner.State.Measuring -> {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (s.progress < 0.2f) "Letting the speaker settle…"
                        else "Listening to the pink noise…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Moonlight,
                    )
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = Periwinkle,
                        trackColor = Night,
                        strokeCap = StrokeCap.Round,
                    )
                    Spacer(Modifier.height(16.dp))
                    SheetButton("Cancel", primary = false) { SpeakerTuner.cancel() }
                }

                is SpeakerTuner.State.Done -> {
                    Text(
                        "Correction curve — boosts where your speaker is weak, cuts where " +
                            "it shouts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Mist,
                    )
                    Spacer(Modifier.height(16.dp))
                    EqCurve(s.gainsDb)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SheetButton("Apply", primary = true) {
                            AudioEngine.setEq(s.gainsDb)
                            SpeakerTuner.reset()
                            onDismiss()
                        }
                        SheetButton("Retry", primary = false) { startCalibration() }
                        SheetButton("Discard", primary = false) { SpeakerTuner.reset() }
                    }
                }

                is SpeakerTuner.State.Error -> {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Lavender,
                    )
                    Spacer(Modifier.height(16.dp))
                    SheetButton("Try again", primary = true) { startCalibration() }
                }
            }
        }
    }
}

/** Bar chart of the per-band correction, centered on 0 dB. */
@Composable
private fun EqCurve(gainsDb: FloatArray) {
    Column {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val bandWidth = size.width / gainsDb.size
            val zero = size.height / 2f
            drawLine(
                color = Mist.copy(alpha = 0.35f),
                start = Offset(0f, zero),
                end = Offset(size.width, zero),
                strokeWidth = 1.5f,
            )
            gainsDb.forEachIndexed { i, gain ->
                val frac = (gain / SpeakerTuner.MAX_CUT_DB).coerceIn(-1f, 1f)
                val barHeight = abs(frac) * (zero - 6f)
                val top = if (gain >= 0) zero - barHeight else zero
                drawRoundRect(
                    color = if (gain >= 0) Periwinkle else Lavender,
                    topLeft = Offset(i * bandWidth + bandWidth * 0.22f, min(top, zero)),
                    size = Size(bandWidth * 0.56f, barHeight.coerceAtLeast(3f)),
                    cornerRadius = CornerRadius(6f, 6f),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("63", "125", "250", "500", "1k", "2k", "4k", "8k").forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Mist,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SheetButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (primary) Night else Moonlight,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (primary) Periwinkle else NightSurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}
