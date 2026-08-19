package com.sleepslop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sleepslop.audio.AudioEngine
import com.sleepslop.audio.SmartTimer
import com.sleepslop.audio.WakeRoutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * "Night options" sheet: drift evolution, stereo space, smart sleep timer,
 * wake-up routine, bedside mode entry, and the battery-optimization prompt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NightSheet(onBedside: () -> Unit, onDismiss: () -> Unit) {
    val driftEnabled by AudioEngine.driftEnabled.collectAsState()
    val driftIntensity by AudioEngine.driftIntensity.collectAsState()
    val spaceWidth by AudioEngine.spaceWidth.collectAsState()
    val spaceRoom by AudioEngine.spaceRoom.collectAsState()
    val smartEnabled by SmartTimer.enabled.collectAsState()
    val smartWatching by SmartTimer.watching.collectAsState()
    val wakeArmed by WakeRoutine.armed.collectAsState()
    val wakeAt by WakeRoutine.wakeAtMillis.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NightSurface) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Night options",
                style = MaterialTheme.typography.titleLarge,
                color = Moonlight,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(16.dp))

            // ------------------------------------------------------- drift
            SectionRow(
                title = "Drift",
                subtitle = "Let the mix breathe and evolve over minutes",
                checked = driftEnabled,
                onChecked = { AudioEngine.setDriftEnabled(it) },
            )
            if (driftEnabled) {
                NightSlider(
                    label = "Movement",
                    valueText = "${(driftIntensity * 100).roundToInt()}%",
                    value = driftIntensity,
                    onChange = { AudioEngine.setDriftIntensity(it) },
                )
            }
            Spacer(Modifier.height(18.dp))

            // ------------------------------------------------------- space
            Text("Space", style = MaterialTheme.typography.titleSmall, color = Moonlight)
            Text(
                "Stereo width and a soft, dark room around the mix",
                style = MaterialTheme.typography.bodySmall,
                color = Mist,
            )
            NightSlider(
                label = "Width",
                valueText = "${(spaceWidth * 200).roundToInt()}%",
                value = spaceWidth,
                onChange = { AudioEngine.setSpaceWidth(it) },
            )
            NightSlider(
                label = "Room",
                valueText = "${(spaceRoom * 100).roundToInt()}%",
                value = spaceRoom,
                onChange = { AudioEngine.setSpaceRoom(it) },
            )
            Spacer(Modifier.height(18.dp))

            // ------------------------------------------------- smart timer
            SectionRow(
                title = "Smart sleep timer",
                subtitle = if (smartWatching) {
                    "Watching for stillness — fades out 20 min after you stop moving"
                } else {
                    "Phone on the mattress; fades out once you've stopped moving"
                },
                checked = smartEnabled,
                onChecked = { SmartTimer.setEnabled(it) },
            )
            Spacer(Modifier.height(18.dp))

            // ----------------------------------------------------- wake-up
            Text("Wake-up", style = MaterialTheme.typography.titleSmall, color = Moonlight)
            if (wakeArmed) {
                val timeText = wakeAt?.let {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
                } ?: "—"
                Text(
                    "Armed for $timeText — a dawn chorus fades in over the last 12 minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Mist,
                )
                Spacer(Modifier.height(10.dp))
                SheetActionButton("Disarm", primary = false) { WakeRoutine.disarm() }
            } else {
                Text(
                    "Cross-fades your mix into birdsong at the set time — a gentle alarm.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Mist,
                )
                Spacer(Modifier.height(10.dp))
                val timeState = rememberTimePickerState(initialHour = 7, initialMinute = 0, is24Hour = true)
                TimeInput(state = timeState)
                SheetActionButton("Arm wake-up", primary = true) {
                    WakeRoutine.arm(timeState.hour, timeState.minute)
                }
            }
            Spacer(Modifier.height(18.dp))

            // ----------------------------------------------------- bedside
            SheetActionButton("Bedside clock mode", primary = true) {
                onDismiss()
                onBedside()
            }
            Spacer(Modifier.height(14.dp))

            BatteryCard()
        }
    }
}

@Composable
private fun SectionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Moonlight)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Mist)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Night,
                checkedTrackColor = Periwinkle,
                uncheckedTrackColor = Night.copy(alpha = 0.6f),
            ),
        )
    }
}

@Composable
private fun NightSlider(
    label: String,
    valueText: String,
    value: Float,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Mist)
        Spacer(Modifier.weight(1f))
        Text(valueText, style = MaterialTheme.typography.labelMedium, color = Periwinkle)
    }
    Slider(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.height(26.dp),
        colors = SliderDefaults.colors(
            thumbColor = Periwinkle,
            activeTrackColor = Periwinkle.copy(alpha = 0.85f),
            inactiveTrackColor = Night.copy(alpha = 0.9f),
        ),
    )
}

@Composable
private fun SheetActionButton(label: String, primary: Boolean, onClick: () -> Unit) {
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
