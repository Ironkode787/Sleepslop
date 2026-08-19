package com.sleepslop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepslop.audio.AudioEngine
import com.sleepslop.audio.Param
import com.sleepslop.audio.Sound
import kotlin.math.roundToInt

/** Pill button for the Mix / Elements tab switch. */
@Composable
fun TabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) Periwinkle else NightSurface.copy(alpha = 0.7f),
        tween(250),
        label = "tabBg",
    )
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Night else Mist,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

/**
 * Full-width card for an ambient element: toggle, volume, and one slider
 * per generator parameter (occurrence, pitch, character…).
 */
@Composable
fun ElementCard(
    sound: Sound,
    volume: Float?,
    onToggle: () -> Unit,
    onVolume: (Float) -> Unit,
) {
    val active = volume != null
    val cardColor by animateColorAsState(
        if (active) NightSurfaceHigh else NightSurface.copy(alpha = 0.72f),
        tween(350),
        label = "elementColor",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(cardColor)
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Periwinkle.copy(alpha = if (active) 0.55f else 0.12f),
                        Lavender.copy(alpha = if (active) 0.38f else 0.08f),
                    )
                ),
                shape = RoundedCornerShape(24.dp),
            )
            .clickable(onClick = onToggle)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sound.emoji, fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    sound.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = Moonlight,
                )
                Text(
                    sound.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = Mist,
                )
            }
            Switch(
                checked = active,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Night,
                    checkedTrackColor = Periwinkle,
                    uncheckedTrackColor = Night.copy(alpha = 0.6f),
                ),
            )
        }
        AnimatedVisibility(
            visible = active,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.padding(top = 8.dp)) {
                LabeledSlider(
                    label = "Volume",
                    valueText = "${((volume ?: 0f) * 100).roundToInt()}%",
                    value = volume ?: 0.75f,
                    min = 0f,
                    max = 1f,
                    steps = 0,
                    onChange = onVolume,
                )
                TiltSlider(sound)
                sound.params.forEach { param ->
                    ParamSlider(sound, param)
                }
            }
        }
    }
}

/** Per-sound brightness (spectral tilt) bound to the engine. */
@Composable
fun TiltSlider(sound: Sound) {
    val tilts by AudioEngine.tilts.collectAsState()
    val tilt = tilts[sound] ?: 0f
    LabeledSlider(
        label = "Brightness",
        valueText = if (tilt.roundLabel() == 0) "neutral" else "%+d%%".format(tilt.roundLabel()),
        value = tilt,
        min = -1f,
        max = 1f,
        steps = 0,
        onChange = { AudioEngine.setTilt(sound, it) },
    )
}

private fun Float.roundLabel(): Int = (this * 100).roundToInt()

/** One generator parameter bound to the engine. */
@Composable
fun ParamSlider(sound: Sound, param: Param) {
    val values by AudioEngine.paramValues.collectAsState()
    val value = values[sound]?.get(param.id) ?: param.default
    LabeledSlider(
        label = param.label,
        valueText = param.format(value),
        value = value,
        min = param.min,
        max = param.max,
        steps = if (param.integer) ((param.max - param.min).roundToInt() - 1).coerceAtLeast(0) else 0,
        onChange = { AudioEngine.setParam(sound, param.id, it) },
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    min: Float,
    max: Float,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Mist)
        Spacer(Modifier.weight(1f))
        Text(valueText, style = MaterialTheme.typography.labelMedium, color = Periwinkle)
    }
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = min..max,
        steps = steps,
        modifier = Modifier.height(26.dp),
        colors = SliderDefaults.colors(
            thumbColor = Periwinkle,
            activeTrackColor = Periwinkle.copy(alpha = 0.85f),
            inactiveTrackColor = Night.copy(alpha = 0.9f),
        ),
    )
}

/** Bottom sheet with all parameters of one sound (used by the simulated fan). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParamSheet(sound: Sound, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NightSurface) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sound.emoji, fontSize = 26.sp)
                Spacer(Modifier.width(12.dp))
                Text(
                    sound.label,
                    style = MaterialTheme.typography.titleLarge,
                    color = Moonlight,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                sound.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = Mist,
            )
            Spacer(Modifier.height(14.dp))
            TiltSlider(sound)
            sound.params.forEach { param ->
                ParamSlider(sound, param)
            }
        }
    }
}
