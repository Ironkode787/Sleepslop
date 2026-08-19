package com.sleepslop.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sleepslop.audio.AudioEngine
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Warm, night-vision-friendly red for the "ember" dimness look. */
private val Ember = Color(0xFFB35C4A)

/** Milliseconds from [nowMillis] until the next whole minute boundary. */
private fun millisUntilNextMinute(nowMillis: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = nowMillis
    val intoMinute = cal.get(Calendar.SECOND) * 1_000L + cal.get(Calendar.MILLISECOND)
    return (60_000L - intoMinute).coerceIn(250L, 60_000L)
}

/** Walks the ContextWrapper chain until an Activity is found (Compose gives us a wrapper). */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Full-screen overnight clock face. Pure black for OLED, minute-resolution updates,
 * screen kept awake and dimmed, system bars hidden. Tap the clock to cycle dimness,
 * tap the bottom ghost button to play/pause, tap anywhere else to leave.
 */
@Composable
fun BedsideScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    val isPlaying by AudioEngine.isPlaying.collectAsState()
    val mix by AudioEngine.mix.collectAsState()

    // ------------------------------------------------------------------ clock
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(millisUntilNextMinute(now))
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()) }
    val stamp = remember(now) { Date(now) }

    // --------------------------------------------------------------- dimness
    // 0 = moon (cool white), 1 = ember (dim red, preserves night vision).
    var look by remember { mutableIntStateOf(0) }
    val faceColor = if (look == 0) Moonlight.copy(alpha = 0.35f) else Ember.copy(alpha = 0.45f)
    val subtleColor = faceColor.copy(alpha = faceColor.alpha * 0.62f)

    // ------------------------------------- keep awake + dim + immersive bars
    DisposableEffect(activity) {
        val window = activity?.window
        var controller: WindowInsetsControllerCompat? = null
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val attrs = window.attributes
            attrs.screenBrightness = 0.05f
            window.attributes = attrs

            val insets = WindowCompat.getInsetsController(window, window.decorView)
            insets.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
            controller = insets
        }
        onDispose {
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val restore = window.attributes
                restore.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = restore
                controller?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // ------------------------------------------------------------------- face
    val exitInteraction = remember { MutableInteractionSource() }
    val clockInteraction = remember { MutableInteractionSource() }
    val buttonInteraction = remember { MutableInteractionSource() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Anywhere that is not the clock or the ghost button leaves bedside mode.
            .clickable(
                interactionSource = exitInteraction,
                indication = null,
                onClick = onExit,
            )
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = timeFormat.format(stamp),
                color = faceColor,
                fontSize = 96.sp,
                lineHeight = 104.sp,
                fontWeight = FontWeight.ExtraLight,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.clickable(
                    interactionSource = clockInteraction,
                    indication = null,
                ) { look = if (look == 0) 1 else 0 },
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = dateFormat.format(stamp),
                color = subtleColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(18.dp))

            if (isPlaying) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(Aurora.copy(alpha = 0.45f))
                    )
                    Spacer(Modifier.width(8.dp))
                    val emojis = mix.keys.joinToString(" ") { it.emoji }
                    Text(
                        text = if (emojis.isEmpty()) "playing" else emojis,
                        color = subtleColor,
                        fontSize = 13.sp,
                        letterSpacing = 1.sp,
                    )
                }
            } else {
                Text(
                    text = "paused",
                    color = Mist.copy(alpha = 0.30f),
                    fontSize = 13.sp,
                    letterSpacing = 2.sp,
                )
            }
        }

        // Ghost transport button — its own tap target, does not exit.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp)
                .size(48.dp)
                .clip(CircleShape)
                .border(1.dp, subtleColor.copy(alpha = 0.35f), CircleShape)
                .clickable(
                    interactionSource = buttonInteraction,
                    indication = null,
                ) { AudioEngine.togglePlayback() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = subtleColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
