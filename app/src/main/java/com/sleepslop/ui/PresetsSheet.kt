package com.sleepslop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepslop.audio.AudioEngine
import com.sleepslop.data.Preset
import com.sleepslop.data.PresetStore

/**
 * Saved scenes: tap one to load it into the engine, or name the current mix
 * and keep it. Applying never starts playback — it only sets engine state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsSheet(onDismiss: () -> Unit) {
    val presets by PresetStore.presets.collectAsState()
    val mix by AudioEngine.mix.collectAsState()
    var name by remember { mutableStateOf("") }

    val builtIns = presets.filter { it.builtIn }
    val mine = presets.filter { !it.builtIn }
    val canSave = name.isNotBlank() && mix.isNotEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NightSurface) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
        ) {
            Text(
                "Presets",
                style = MaterialTheme.typography.titleLarge,
                color = Moonlight,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Ready-made nights, and the mixes you keep.",
                style = MaterialTheme.typography.bodySmall,
                color = Mist,
            )

            SectionLabel("Built-in scenes")
            builtIns.forEach { preset ->
                PresetRow(
                    preset = preset,
                    onApply = {
                        PresetStore.apply(preset)
                        onDismiss()
                    },
                    onDelete = null,
                )
                Spacer(Modifier.height(10.dp))
            }

            SectionLabel("Your presets")
            if (mine.isEmpty()) {
                Text(
                    "Nothing saved yet — build a mix, name it below, and it lands here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Mist,
                )
            } else {
                mine.forEach { preset ->
                    PresetRow(
                        preset = preset,
                        onApply = {
                            PresetStore.apply(preset)
                            onDismiss()
                        },
                        onDelete = { PresetStore.delete(preset.name) },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            SectionLabel("Save the current mix")
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = {
                        Text(
                            "Name this night",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Mist,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Moonlight,
                        unfocusedTextColor = Moonlight,
                        cursorColor = Periwinkle,
                        focusedBorderColor = Periwinkle,
                        unfocusedBorderColor = Mist.copy(alpha = 0.45f),
                        focusedContainerColor = Night.copy(alpha = 0.45f),
                        unfocusedContainerColor = Night.copy(alpha = 0.45f),
                        focusedPlaceholderColor = Mist,
                        unfocusedPlaceholderColor = Mist,
                    ),
                )
                SheetButton("Save", primary = true, enabled = canSave) {
                    PresetStore.saveCurrent(name)
                    name = ""
                }
            }
            if (mix.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Turn on at least one sound to save a preset.",
                    style = MaterialTheme.typography.labelMedium,
                    color = Mist,
                )
            }
        }
    }
}

/** Small caps-ish divider label between the sheet's sections. */
@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Mist,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

/**
 * One tappable preset: name, the emoji of every sound it turns on, and an
 * optional ✕ that removes it (user presets only).
 */
@Composable
private fun PresetRow(
    preset: Preset,
    onApply: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val summary = preset.mix.keys
        .sortedBy { it.ordinal }
        .joinToString(" ") { it.emoji }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NightSurfaceHigh)
            .clickable(onClick = onApply)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                preset.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = Moonlight,
            )
            Spacer(Modifier.height(2.dp))
            Text(summary, fontSize = 15.sp, color = Mist)
        }
        if (onDelete != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(Night.copy(alpha = 0.55f))
                    .clickable(onClick = onDelete)
                    .padding(8.dp)
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Delete ${preset.name}",
                    tint = Mist,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Same pill button as the other sheets, with a disabled state. */
@Composable
private fun SheetButton(
    label: String,
    primary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val background = when {
        !enabled -> NightSurfaceHigh.copy(alpha = 0.5f)
        primary -> Periwinkle
        else -> NightSurfaceHigh
    }
    val content = when {
        !enabled -> Mist
        primary -> Night
        else -> Moonlight
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = content,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    )
}
