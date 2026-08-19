package com.sleepslop.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Smart sleep timer.
 *
 * While the user is playing sounds with the phone resting on the mattress,
 * the accelerometer picks up the micro-movements of someone still awake —
 * turning over, reaching for the phone, shifting a pillow. Once those stop
 * for a full [QUIET_MILLIS] the user is assumed asleep and the engine's own
 * sleep timer is armed for a short fade-out.
 *
 * Threading: the sensor listener is registered without a Handler, so its
 * callbacks arrive on the main looper; the [AudioEngine.isPlaying] collector
 * also runs on [Dispatchers.Main]. Every field below is therefore touched
 * from the main thread only and needs no synchronization. The exposed flows
 * are safe to read from anywhere.
 */
@SuppressLint("StaticFieldLeak")
object SmartTimer {

    private const val PREFS_NAME = "sleepslop"
    private const val KEY_ENABLED = "smart_timer"

    /** A movement above this (m/s², gravity removed) counts as a "stir". */
    private const val STIR_THRESHOLD = 0.6f

    /** How long the mattress must stay still before we call it sleep. */
    private const val QUIET_MILLIS = 20 * 60 * 1000L

    /** Handed to [AudioEngine.setTimer]; the engine fades then stops. */
    private const val FADE_MINUTES = 3

    /**
     * Smoothing factor of the per-axis gravity estimate. At
     * SENSOR_DELAY_NORMAL (~5 Hz) this is a time constant of roughly ten
     * seconds: slow enough that a real stir shows up as movement, fast
     * enough to track the phone being re-oriented on the bed.
     */
    private const val GRAVITY_ALPHA = 0.02f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var initialized = false

    private val _enabled = MutableStateFlow(false)

    /** User preference, persisted across launches. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _watching = MutableStateFlow(false)

    /** True while the accelerometer listener is actually registered. */
    val watching: StateFlow<Boolean> = _watching.asStateFlow()

    // ------------------------------------------------- main-thread only state
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var haveGravity = false
    private var lastStirAt = 0L
    private var registered = false

    /** Set once we have armed the fade; prevents a second trigger tonight. */
    private var trippedThisSession = false

    /** Called from the Application class. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val ctx = context.applicationContext
        appContext = ctx
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = p.getBoolean(KEY_ENABLED, false)
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager = sm
        accelerometer = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        scope.launch {
            AudioEngine.isPlaying.collect { playing ->
                if (playing) {
                    // A fresh playback session gets a fresh chance to trigger.
                    trippedThisSession = false
                    startWatching()
                } else {
                    stopWatching()
                }
            }
        }
    }

    fun setEnabled(v: Boolean) {
        if (_enabled.value == v) return
        _enabled.value = v
        prefs?.edit()?.putBoolean(KEY_ENABLED, v)?.apply()
        if (v) {
            if (AudioEngine.isPlaying.value) {
                trippedThisSession = false
                startWatching()
            }
        } else {
            stopWatching()
        }
    }

    // --------------------------------------------------------------- sensing

    private fun startWatching() {
        if (registered || trippedThisSession) return
        if (!_enabled.value || !AudioEngine.isPlaying.value) return
        val sm = sensorManager ?: return
        val sensor = accelerometer ?: return
        haveGravity = false
        lastStirAt = SystemClock.elapsedRealtime()
        registered = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        _watching.value = registered
    }

    private fun stopWatching() {
        if (registered) {
            sensorManager?.unregisterListener(listener)
            registered = false
        }
        _watching.value = false
    }

    private fun onSample(x: Float, y: Float, z: Float) {
        if (!haveGravity) {
            gravityX = x
            gravityY = y
            gravityZ = z
            haveGravity = true
            lastStirAt = SystemClock.elapsedRealtime()
            return
        }
        val dx = x - gravityX
        val dy = y - gravityY
        val dz = z - gravityZ
        val movement = sqrt(dx * dx + dy * dy + dz * dz)

        // Slow EMA: whatever is left after this is real motion, not tilt.
        gravityX += GRAVITY_ALPHA * dx
        gravityY += GRAVITY_ALPHA * dy
        gravityZ += GRAVITY_ALPHA * dz

        val now = SystemClock.elapsedRealtime()
        if (movement > STIR_THRESHOLD) {
            lastStirAt = now
            return
        }
        if (now - lastStirAt >= QUIET_MILLIS) {
            trippedThisSession = true
            stopWatching()
            AudioEngine.setTimer(FADE_MINUTES)
        }
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (!registered || event == null) return
            if (event.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
            val values = event.values ?: return
            if (values.size < 3) return
            onSample(values[0], values[1], values[2])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // Accuracy of a relative movement metric does not matter here.
        }
    }
}
