package com.sleepslop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepslop.audio.AudioEngine
import com.sleepslop.audio.Param
import com.sleepslop.audio.Sound
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** Segmented tab switch with a sliding gradient indicator. */
@Composable
fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(NightSurface.copy(alpha = 0.8f))
            .border(1.dp, NightSurfaceHigh.copy(alpha = 0.8f), RoundedCornerShape(22.dp))
    ) {
        val segmentWidth = maxWidth / labels.size
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * selected,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 380f),
            label = "tabIndicator",
        )
        Box(
            Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Periwinkle, Lavender)))
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            labels.forEachIndexed { index, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (index == selected) Night else Mist,
                    )
                }
            }
        }
    }
}

/** Tiny dancing activity bars shown on cards that are currently sounding. */
@Composable
fun EqBars(color: Color, animate: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "eq")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2832f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "eqPhase",
    )
    Canvas(modifier.size(width = 18.dp, height = 16.dp)) {
        val barWidth = size.width / 5.5f
        for (i in 0 until 4) {
            val h = if (animate) {
                size.height * (0.30f + 0.65f * abs(sin(t + i * 1.15f)))
            } else {
                size.height * 0.35f
            }
            drawRoundRect(
                color = color.copy(alpha = if (animate) 0.9f else 0.45f),
                topLeft = Offset(i * barWidth * 1.4f, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/**
 * Full-width card for an ambient element: toggle, volume, brightness, and
 * one slider per generator parameter, all tinted with the sound's accent.
 */
@Composable
fun ElementCard(
    sound: Sound,
    volume: Float?,
    playing: Boolean,
    onToggle: () -> Unit,
    onVolume: (Float) -> Unit,
) {
    val active = volume != null
    val accent = sound.accent
    val haptics = LocalHapticFeedback.current
    val cardColor by animateColorAsState(
        if (active) NightSurfaceHigh else NightSurface.copy(alpha = 0.72f),
        tween(350),
        label = "elementColor",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.verticalGradient(
                    listOf(cardColor, if (active) accent.copy(alpha = 0.10f) else cardColor)
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = if (active) 0.55f else 0.10f),
                        accent.copy(alpha = if (active) 0.25f else 0.05f),
                    )
                ),
                shape = RoundedCornerShape(26.dp),
            )
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle()
            }
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (active) 0.20f else 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(sound.emoji, fontSize = 21.sp)
            }
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
            if (active) {
                EqBars(accent, animate = playing, modifier = Modifier.padding(end = 10.dp))
            }
            Switch(
                checked = active,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Night,
                    checkedTrackColor = accent,
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
                    accent = accent,
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
        accent = sound.accent,
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
        accent = sound.accent,
        onChange = { AudioEngine.setParam(sound, param.id, it) },
    )
}

@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    min: Float,
    max: Float,
    steps: Int,
    onChange: (Float) -> Unit,
    accent: Color = Periwinkle,
) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Mist)
        Spacer(Modifier.weight(1f))
        Text(valueText, style = MaterialTheme.typography.labelMedium, color = accent)
    }
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = min..max,
        steps = steps,
        modifier = Modifier.height(26.dp),
        colors = SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent.copy(alpha = 0.85f),
            inactiveTrackColor = Night.copy(alpha = 0.9f),
            activeTickColor = Night.copy(alpha = 0.4f),
            inactiveTickColor = accent.copy(alpha = 0.25f),
        ),
    )
}

/** Bottom sheet with brightness + all parameters of one sound. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParamSheet(sound: Sound, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NightSurface) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(sound.accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(sound.emoji, fontSize = 22.sp)
                }
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
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(14.dp))
            TiltSlider(sound)
            sound.params.forEach { param ->
                ParamSlider(sound, param)
            }
        }
    }
}
