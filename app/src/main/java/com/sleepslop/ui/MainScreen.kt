package com.sleepslop.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sleepslop.audio.AudioEngine
import com.sleepslop.audio.Category
import com.sleepslop.audio.Sound
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

@Composable
fun MainScreen() {
    val isPlaying by AudioEngine.isPlaying.collectAsState()
    val mix by AudioEngine.mix.collectAsState()
    val master by AudioEngine.masterVolume.collectAsState()
    val timerEndAt by AudioEngine.timerEndAt.collectAsState()

    var showTimerSheet by remember { mutableStateOf(false) }
    var showTuneSheet by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var showNight by remember { mutableStateOf(false) }
    var showBedside by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    var paramSheetSound by remember { mutableStateOf<Sound?>(null) }

    val mixSounds = remember { Sound.entries.filter { it.category == Category.MIX } }
    val elementSounds = remember { Sound.entries.filter { it.category == Category.ELEMENT } }

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
        NightSky(isPlaying, mix)

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Header(
                onPresets = { showPresets = true },
                onNight = { showNight = true },
                onTune = { showTuneSheet = true },
            )

            Box(Modifier.padding(horizontal = 20.dp)) {
                SegmentedTabs(
                    labels = listOf("Mix", "Elements"),
                    selected = selectedTab,
                    onSelect = { selectedTab = it },
                )
            }
            Spacer(Modifier.height(12.dp))

            if (selectedTab == 0) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (mix.isEmpty()) {
                        item(span = { GridItemSpan(2) }) {
                            Text(
                                "Tap a sound to light your first star ✦",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Mist,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            )
                        }
                    }
                    items(mixSounds) { sound ->
                        SoundCard(
                            sound = sound,
                            volume = mix[sound],
                            playing = isPlaying,
                            onToggle = {
                                ensureNotificationPermission()
                                AudioEngine.toggle(sound)
                            },
                            onVolume = { AudioEngine.setVolume(sound, it) },
                            onOpenParams = { paramSheetSound = sound },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    listItems(elementSounds) { sound ->
                        ElementCard(
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
            }

            ControlDock(
                isPlaying = isPlaying,
                mixSize = mix.size,
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

        if (showTuneSheet) {
            TuneSheet(onDismiss = { showTuneSheet = false })
        }

        if (showPresets) {
            PresetsSheet(onDismiss = { showPresets = false })
        }

        if (showNight) {
            NightSheet(
                onBedside = { showBedside = true },
                onDismiss = { showNight = false },
            )
        }

        paramSheetSound?.let { sound ->
            ParamSheet(sound, onDismiss = { paramSheetSound = null })
        }

        if (showBedside) {
            BedsideScreen(onExit = { showBedside = false })
        }
    }
}

// ---------------------------------------------------------------------------
// Background: aurora, starfield, the living constellation, shooting stars
// ---------------------------------------------------------------------------

private class Star(val x: Float, val y: Float, val radius: Float, val phase: Float, val speed: Float)

/** Deterministic sky position for a sound's constellation star. */
private fun skyPosition(sound: Sound): Offset {
    val r = Random(sound.ordinal * 131 + 7)
    return Offset(0.08f + 0.84f * r.nextFloat(), 0.05f + 0.33f * r.nextFloat())
}

@Composable
private fun NightSky(isPlaying: Boolean, mix: Map<Sound, Float>) {
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
    val constellation = remember(mix.keys) {
        mix.keys.map { it to skyPosition(it) }.sortedBy { it.second.x }
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

    // A shooting star every so often — pure whimsy.
    val shootProgress = remember { Animatable(0f) }
    var shootStart by remember { mutableStateOf(Offset(0.2f, 0.1f)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(20_000, 65_000))
            shootStart = Offset(0.05f + Random.nextFloat() * 0.55f, 0.04f + Random.nextFloat() * 0.18f)
            shootProgress.snapTo(0.001f)
            shootProgress.animateTo(1f, tween(1_100, easing = LinearEasing))
            shootProgress.snapTo(0f)
        }
    }

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

        // Background starfield.
        for (star in stars) {
            val alpha = 0.25f + 0.65f * (0.5f + 0.5f * sin(twinkle * star.speed + star.phase))
            drawCircle(
                color = Moonlight.copy(alpha = alpha * 0.8f),
                radius = star.radius,
                center = Offset(star.x * w, star.y * h * 0.92f),
            )
        }

        // The living constellation: one star per active sound, brightness
        // following its volume, joined by faint lines.
        if (constellation.isNotEmpty()) {
            var previous: Offset? = null
            for ((sound, position) in constellation) {
                val center = Offset(position.x * w, position.y * h)
                previous?.let { prev ->
                    drawLine(
                        color = Moonlight.copy(alpha = 0.10f + 0.05f * glowBoost),
                        start = Offset(prev.x * w, prev.y * h),
                        end = center,
                        strokeWidth = 1.4f,
                    )
                }
                previous = position
                val volume = mix[sound] ?: 0f
                val pulse = 0.75f + 0.25f * sin(twinkle * 1.3f + sound.ordinal)
                val brightness = (0.35f + 0.65f * volume) * pulse * glowBoost
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(sound.accent.copy(alpha = 0.55f * brightness), Color.Transparent),
                        center = center,
                        radius = 26f,
                    ),
                    radius = 26f,
                    center = center,
                )
                drawCircle(
                    color = sound.accent.copy(alpha = (0.6f + 0.4f * brightness).coerceAtMost(1f)),
                    radius = 3.2f,
                    center = center,
                )
            }
        }

        // Shooting star.
        val p = shootProgress.value
        if (p > 0.0005f && p < 0.9995f) {
            val dir = Offset(1f, 0.42f).let { d ->
                val n = sqrt(d.x * d.x + d.y * d.y)
                Offset(d.x / n, d.y / n)
            }
            val travel = 0.42f * w
            val head = Offset(
                shootStart.x * w + dir.x * travel * p,
                shootStart.y * h + dir.y * travel * p,
            )
            val tail = Offset(head.x - dir.x * 110f * (1f - p * 0.4f), head.y - dir.y * 110f * (1f - p * 0.4f))
            drawLine(
                brush = Brush.linearGradient(
                    listOf(Color.Transparent, Moonlight.copy(alpha = 0.85f * (1f - p))),
                    start = tail,
                    end = head,
                ),
                start = tail,
                end = head,
                strokeWidth = 2.2f,
            )
            drawCircle(
                color = Moonlight.copy(alpha = 0.9f * (1f - p)),
                radius = 2.6f,
                center = head,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun Header(onPresets: () -> Unit, onNight: () -> Unit, onTune: () -> Unit) {
    val eqEnabled by AudioEngine.eqEnabled.collectAsState()
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..10 -> "Good morning, early bird"
            in 11..16 -> "Afternoon nap territory"
            in 17..21 -> "Good evening, night owl"
            else -> "Deep night — sleep well"
        }
    }
    val moonTilt = rememberInfiniteTransition(label = "moon")
    val tilt by moonTilt.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(tween(5_200), repeatMode = RepeatMode.Reverse),
        label = "moonTilt",
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "☾",
                    fontSize = 24.sp,
                    color = Periwinkle,
                    modifier = Modifier.graphicsLayer { rotationZ = tilt },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Sleepslop",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Moonlight,
                    letterSpacing = 0.5.sp,
                )
            }
            Text(
                greeting,
                style = MaterialTheme.typography.bodyMedium,
                color = Mist,
            )
        }
        HeaderIcon(Icons.Rounded.BookmarkBorder, "Presets", highlighted = false, onClick = onPresets)
        HeaderIcon(Icons.Rounded.DarkMode, "Night options", highlighted = false, onClick = onNight)
        HeaderIcon(Icons.Rounded.Tune, "Room and speaker tuning", highlighted = eqEnabled, onClick = onTune)
    }
}

@Composable
private fun HeaderIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (highlighted) Periwinkle.copy(alpha = 0.18f) else NightSurface.copy(alpha = 0.7f))
            .clickable(onClick = onClick)
            .padding(11.dp)
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (highlighted) Periwinkle else Mist,
            modifier = Modifier.size(21.dp),
        )
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
    onOpenParams: (() -> Unit)? = null,
) {
    val active = volume != null
    val accent = sound.accent
    val haptics = LocalHapticFeedback.current

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.965f else 1f,
        spring(stiffness = 650f),
        label = "cardScale",
    )
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
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.verticalGradient(
                    listOf(cardColor, if (active) accent.copy(alpha = 0.09f) else cardColor)
                )
            )
            .border(
                width = 1.2.dp,
                brush = Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = (borderAlpha + 0.08f).coerceAtMost(1f)),
                        accent.copy(alpha = borderAlpha * 0.5f + 0.04f),
                    )
                ),
                shape = RoundedCornerShape(26.dp),
            )
            .clickable(interactionSource = interaction, indication = null) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle()
            }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (active) 0.20f else 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(sound.emoji, fontSize = 20.sp)
            }
            Spacer(Modifier.weight(1f))
            if (active) {
                EqBars(accent, animate = playing)
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
            Column {
                Slider(
                    value = volume ?: 0.75f,
                    onValueChange = onVolume,
                    modifier = Modifier.padding(top = 6.dp).height(26.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent.copy(alpha = 0.85f),
                        inactiveTrackColor = Night.copy(alpha = 0.9f),
                    ),
                )
                if (onOpenParams != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Night.copy(alpha = 0.55f))
                            .clickable(onClick = onOpenParams)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Tune",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Floating control dock
// ---------------------------------------------------------------------------

@Composable
private fun ControlDock(
    isPlaying: Boolean,
    mixSize: Int,
    master: Float,
    timerEndAt: Long?,
    onPlayPause: () -> Unit,
    onMaster: (Float) -> Unit,
    onTimerClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp)
            .padding(bottom = 10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = NightSurface.copy(alpha = 0.94f),
            border = BorderStroke(
                1.dp,
                Brush.linearGradient(
                    listOf(Periwinkle.copy(alpha = 0.32f), Lavender.copy(alpha = 0.14f))
                )
            ),
            shadowElevation = 10.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayButton(isPlaying, onPlayPause)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    val status = when {
                        isPlaying -> "Sleeping soundly · $mixSize ${if (mixSize == 1) "sound" else "sounds"}"
                        mixSize > 0 -> "Paused · $mixSize ${if (mixSize == 1) "sound" else "sounds"}"
                        else -> "Choose your night"
                    }
                    Text(
                        status,
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
                Spacer(Modifier.width(12.dp))
                TimerChip(timerEndAt, onTimerClick)
            }
        }
    }
}

@Composable
private fun PlayButton(isPlaying: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val pulse = rememberInfiniteTransition(label = "playPulse")
    val halo by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_600, easing = LinearEasing)),
        label = "halo",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(70.dp)) {
        if (isPlaying) {
            Box(
                Modifier
                    .size((50 + 20 * halo).dp)
                    .clip(CircleShape)
                    .border(1.5.dp, Periwinkle.copy(alpha = (1f - halo) * 0.6f), CircleShape)
            )
        }
        Box(
            Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Periwinkle, Lavender)))
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Night,
                modifier = Modifier.size(32.dp),
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
            .padding(horizontal = 13.dp, vertical = 9.dp),
    ) {
        Icon(
            Icons.Rounded.Bedtime,
            contentDescription = "Sleep timer",
            tint = if (timerEndAt != null) Periwinkle else Mist,
            modifier = Modifier.size(19.dp),
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

@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
private fun TimerSheet(
    timerEndAt: Long?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NightSurface,
    ) {
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
            androidx.compose.foundation.layout.FlowRow(
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
