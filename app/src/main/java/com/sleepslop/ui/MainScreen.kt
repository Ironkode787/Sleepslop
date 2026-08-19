package com.sleepslop.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sleepslop.audio.AudioEngine
import com.sleepslop.audio.Sound
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun MainScreen() {
    val isPlaying by AudioEngine.isPlaying.collectAsState()
    val mix by AudioEngine.mix.collectAsState()
    val master by AudioEngine.masterVolume.collectAsState()
    val timerEndAt by AudioEngine.timerEndAt.collectAsState()

    var showTimerSheet by remember { mutableStateOf(false) }
    var showTuneSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(Modifier.fillMaxSize().background(Night)) {
        NightSky(isPlaying)

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Header(onTune = { showTuneSheet = true })

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(Sound.entries) { sound ->
                    SoundCard(
                        sound = sound,
                        volume = mix[sound],
                        playing = isPlaying,
                        onToggle = {
                            ensureNotificationPermission()
                            AudioEngine.toggle(sound)
                        },
                        onVolume = { AudioEngine.setVolume(sound, it) },
                    )
                }
            }

            ControlBar(
                isPlaying = isPlaying,
                master = master,
                timerEndAt = timerEndAt,
                onPlayPause = {
                    ensureNotificationPermission()
                    AudioEngine.togglePlayback()
                },
                onMaster = { AudioEngine.setMasterVolume(it) },
                onTimerClick = { showTimerSheet = true },
            )
        }

        if (showTuneSheet) {
            TuneSheet(onDismiss = { showTuneSheet = false })
        }

        if (showTimerSheet) {
            TimerSheet(
                timerEndAt = timerEndAt,
                onSelect = { minutes ->
                    AudioEngine.setTimer(minutes)
                    showTimerSheet = false
                },
                onDismiss = { showTimerSheet = false },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Background: drifting aurora orbs + twinkling stars
// ---------------------------------------------------------------------------

private class Star(val x: Float, val y: Float, val radius: Float, val phase: Float, val speed: Float)

@Composable
private fun NightSky(isPlaying: Boolean) {
    val stars = remember {
        val rnd = Random(12)
        List(110) {
            Star(
                x = rnd.nextFloat(),
                y = rnd.nextFloat(),
                radius = 0.6f + rnd.nextFloat() * 1.7f,
                phase = rnd.nextFloat() * 6.28f,
                speed = 0.4f + rnd.nextFloat() * 1.4f,
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "sky")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2832f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing)),
        label = "drift",
    )
    val twinkle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2832f,
        animationSpec = infiniteRepeatable(tween(6_000, easing = LinearEasing)),
        label = "twinkle",
    )
    val glowBoost by animateFloatAsState(if (isPlaying) 1f else 0.55f, tween(1600), label = "glow")

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Aurora orbs drifting in slow Lissajous paths.
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Periwinkle.copy(alpha = 0.16f * glowBoost), Color.Transparent),
                center = Offset(w * (0.25f + 0.10f * sin(t)), h * (0.20f + 0.06f * sin(t * 0.7f))),
                radius = w * 0.75f,
            ),
            radius = w * 0.75f,
            center = Offset(w * (0.25f + 0.10f * sin(t)), h * (0.20f + 0.06f * sin(t * 0.7f))),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Lavender.copy(alpha = 0.13f * glowBoost), Color.Transparent),
                center = Offset(w * (0.80f - 0.08f * sin(t * 1.3f)), h * (0.55f + 0.08f * sin(t * 0.9f))),
                radius = w * 0.65f,
            ),
            radius = w * 0.65f,
            center = Offset(w * (0.80f - 0.08f * sin(t * 1.3f)), h * (0.55f + 0.08f * sin(t * 0.9f))),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Aurora.copy(alpha = 0.08f * glowBoost), Color.Transparent),
                center = Offset(w * (0.45f + 0.12f * sin(t * 0.5f)), h * (0.90f - 0.05f * sin(t))),
                radius = w * 0.70f,
            ),
            radius = w * 0.70f,
            center = Offset(w * (0.45f + 0.12f * sin(t * 0.5f)), h * (0.90f - 0.05f * sin(t))),
        )

        // Stars.
        for (star in stars) {
            val alpha = 0.25f + 0.65f * (0.5f + 0.5f * sin(twinkle * star.speed + star.phase))
            drawCircle(
                color = Moonlight.copy(alpha = alpha * 0.8f),
                radius = star.radius,
                center = Offset(star.x * w, star.y * h * 0.92f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun Header(onTune: () -> Unit) {
    val eqEnabled by AudioEngine.eqEnabled.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Sleepslop",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                color = Moonlight,
                letterSpacing = 1.sp,
            )
            Text(
                "Pure math, no recordings. Mix your night.",
                style = MaterialTheme.typography.bodyMedium,
                color = Mist,
            )
        }
        Box(
            Modifier
                .clip(CircleShape)
                .background(if (eqEnabled) Periwinkle.copy(alpha = 0.18f) else NightSurface.copy(alpha = 0.7f))
                .clickable(onClick = onTune)
                .padding(12.dp)
        ) {
            Icon(
                Icons.Rounded.Tune,
                contentDescription = "Speaker tuning",
                tint = if (eqEnabled) Periwinkle else Mist,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Sound cards
// ---------------------------------------------------------------------------

@Composable
private fun SoundCard(
    sound: Sound,
    volume: Float?,
    playing: Boolean,
    onToggle: () -> Unit,
    onVolume: (Float) -> Unit,
) {
    val active = volume != null
    val glow by animateFloatAsState(if (active) 1f else 0f, tween(400), label = "cardGlow")
    val cardColor by animateColorAsState(
        if (active) NightSurfaceHigh else NightSurface.copy(alpha = 0.72f),
        tween(400),
        label = "cardColor",
    )

    val pulse = rememberInfiniteTransition(label = "pulse")
    val breathe by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_200), repeatMode = RepeatMode.Reverse),
        label = "breathe",
    )
    val borderAlpha = if (active && playing) 0.35f + 0.45f * breathe else 0.55f * glow

    Column(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(cardColor)
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Periwinkle.copy(alpha = borderAlpha),
                        Lavender.copy(alpha = borderAlpha * 0.7f),
                    )
                ),
                shape = RoundedCornerShape(24.dp),
            )
            .clickable(onClick = onToggle)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sound.emoji, fontSize = 26.sp)
            Spacer(Modifier.weight(1f))
            if (active) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(Aurora.copy(alpha = if (playing) 0.4f + 0.6f * breathe else 0.9f))
                )
            }
        }
        Spacer(Modifier.height(10.dp))
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
            minLines = 2,
        )
        AnimatedVisibility(
            visible = active,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Slider(
                value = volume ?: 0.75f,
                onValueChange = onVolume,
                modifier = Modifier.padding(top = 6.dp).height(26.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Periwinkle,
                    activeTrackColor = Periwinkle.copy(alpha = 0.85f),
                    inactiveTrackColor = Night.copy(alpha = 0.9f),
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom control dock
// ---------------------------------------------------------------------------

@Composable
private fun ControlBar(
    isPlaying: Boolean,
    master: Float,
    timerEndAt: Long?,
    onPlayPause: () -> Unit,
    onMaster: (Float) -> Unit,
    onTimerClick: () -> Unit,
) {
    Surface(
        color = NightSurface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayButton(isPlaying, onPlayPause)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (isPlaying) "Sleeping soundly…" else "Paused",
                    style = MaterialTheme.typography.labelMedium,
                    color = Mist,
                )
                Slider(
                    value = master,
                    onValueChange = onMaster,
                    modifier = Modifier.height(30.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Moonlight,
                        activeTrackColor = Lavender,
                        inactiveTrackColor = Night,
                    ),
                )
            }
            Spacer(Modifier.width(14.dp))
            TimerChip(timerEndAt, onTimerClick)
        }
    }
}

@Composable
private fun PlayButton(isPlaying: Boolean, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "playPulse")
    val halo by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_600, easing = LinearEasing)),
        label = "halo",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
        if (isPlaying) {
            Box(
                Modifier
                    .size((52 + 20 * halo).dp)
                    .clip(CircleShape)
                    .border(1.5.dp, Periwinkle.copy(alpha = (1f - halo) * 0.6f), CircleShape)
            )
        }
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Periwinkle, Lavender)))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Night,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@Composable
private fun TimerChip(timerEndAt: Long?, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timerEndAt) {
        while (timerEndAt != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val label = timerEndAt?.let {
        val totalSec = ((it - now).coerceAtLeast(0) / 1000).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (timerEndAt != null) Periwinkle.copy(alpha = 0.18f) else Night.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(
            Icons.Rounded.Bedtime,
            contentDescription = "Sleep timer",
            tint = if (timerEndAt != null) Periwinkle else Mist,
            modifier = Modifier.size(20.dp),
        )
        Text(
            label ?: "Timer",
            style = MaterialTheme.typography.labelSmall,
            color = if (timerEndAt != null) Periwinkle else Mist,
        )
    }
}

// ---------------------------------------------------------------------------
// Sleep timer sheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TimerSheet(
    timerEndAt: Long?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NightSurface) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 40.dp)) {
            Text(
                "Sleep timer",
                style = MaterialTheme.typography.titleLarge,
                color = Moonlight,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Sound fades out gently before stopping.",
                style = MaterialTheme.typography.bodySmall,
                color = Mist,
            )
            Spacer(Modifier.height(18.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TimerOption("Off", selected = timerEndAt == null) { onSelect(null) }
                listOf(15, 30, 45, 60, 90, 120, 240, 480).forEach { minutes ->
                    val label = when {
                        minutes < 60 -> "$minutes min"
                        minutes % 60 == 0 -> "${minutes / 60} h"
                        else -> "${minutes / 60}.5 h"
                    }
                    TimerOption(label, selected = false) { onSelect(minutes) }
                }
            }
        }
    }
}

@Composable
private fun TimerOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Night else Moonlight,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Periwinkle else NightSurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}
