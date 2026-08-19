package com.sleepslop.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// NOTE: <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
// is added to AndroidManifest.xml by the orchestrator — without it the direct
// ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog throws and we fall back to
// the generic battery-optimization settings list.

/** True when the OS will leave our overnight playback alone (no Doze/app-standby kill). */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Compact advisory for the settings sheet. Renders nothing once the app is already
 * exempt from battery optimizations.
 *
 * Re-check strategy (pragmatic, no lifecycle-compose dependency in this module): the
 * exemption is read on every entry into composition — i.e. each time the settings sheet
 * opens — and again right after the button is pressed, via the [refresh] key.
 */
@Composable
fun BatteryCard() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val exempt = remember(refresh) { isIgnoringBatteryOptimizations(context) }

    if (exempt) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NightSurfaceHigh)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            text = "All-night playback",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = Moonlight,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Some phones stop background audio while you sleep — exempt Sleepslop from battery optimization to keep the night going.",
            style = MaterialTheme.typography.bodySmall,
            color = Mist,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                val direct = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + context.packageName),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(direct)
                } catch (e: Exception) {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (ignored: Exception) {
                        // No settings screen on this device; nothing more we can do.
                    }
                }
                // Re-read the state; if the user granted it inline the card disappears.
                refresh++
            },
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Periwinkle,
                contentColor = Night,
            ),
        ) {
            Text(
                text = "Allow all-night playback",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
