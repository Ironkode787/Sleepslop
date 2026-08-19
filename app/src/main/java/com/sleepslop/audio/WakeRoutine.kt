package com.sleepslop.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.sleepslop.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Wake-up routine.
 *
 * Armed for a wall-clock time, it spends the last twelve minutes before that
 * time cross-fading the night mix into a synthesized dawn chorus: everything
 * that was playing slides down to silence while [Sound.BIRDS] and
 * [Sound.CHIMES] rise. At the wake time the birds are the alarm — there is no
 * buzzer and playback simply continues until the user stops it.
 *
 * Public surface is deliberately small: [armed], [wakeAtMillis], [init],
 * [arm], [disarm].
 */
@SuppressLint("StaticFieldLeak")
object WakeRoutine {

    private const val PREFS_NAME = "sleepslop"
    private const val KEY_WAKE_AT = "wake_at"

    /** How long before the wake time the dawn sequence begins. */
    private const val LEAD_MILLIS = 12 * 60 * 1000L

    /** Cross-fade update interval. */
    private const val STEP_MILLIS = 5_000L

    private const val BIRDS_START = 0.05f
    private const val BIRDS_END = 0.75f
    private const val CHIMES_END = 0.25f
    private const val DAWN_ACTIVITY_START = 0.3f
    private const val DAWN_ACTIVITY_END = 1f

    /** Master volume is lifted to at least this by the wake time. */
    private const val MASTER_FLOOR = 0.75f

    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    private val _wakeAtMillis = MutableStateFlow<Long?>(null)
    val wakeAtMillis: StateFlow<Long?> = _wakeAtMillis.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    /** Called from the Application class. Re-arms a stored, still-future time. */
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        val stored = p.getLong(KEY_WAKE_AT, 0L)
        if (stored > System.currentTimeMillis()) {
            schedule(stored, persist = false)
        } else if (stored != 0L) {
            p.edit().remove(KEY_WAKE_AT).apply()
        }
    }

    /** Arms the next occurrence of [hour]:[minute] (today if still ahead). */
    fun arm(hour: Int, minute: Int) {
        schedule(nextOccurrence(hour, minute), persist = true)
        // Pin the process for the night; the service is a no-op if running.
        appContext?.let { PlaybackService.start(it) }
    }

    fun disarm() {
        job?.cancel()
        job = null
        _armed.value = false
        _wakeAtMillis.value = null
        prefs?.edit()?.remove(KEY_WAKE_AT)?.apply()
    }

    // -------------------------------------------------------------- internals

    private fun schedule(atMillis: Long, persist: Boolean) {
        job?.cancel()
        _wakeAtMillis.value = atMillis
        _armed.value = true
        if (persist) prefs?.edit()?.putLong(KEY_WAKE_AT, atMillis)?.apply()
        job = scope.launch {
            waitUntil(atMillis - LEAD_MILLIS)
            runDawnSequence(atMillis)
        }
    }

    /**
     * Sleeps until [targetMillis] in capped hops, re-reading the wall clock
     * each time so clock changes and delayed wake-ups cannot accumulate.
     */
    private suspend fun waitUntil(targetMillis: Long) {
        while (true) {
            val remaining = targetMillis - System.currentTimeMillis()
            if (remaining <= 0L) return
            val cap = if (remaining > 10 * 60_000L) 5 * 60_000L else 60_000L
            delay(remaining.coerceAtMost(cap))
        }
    }

    private suspend fun runDawnSequence(wakeAt: Long) {
        // The night mix, minus anything the dawn chorus itself uses.
        val nightMix = AudioEngine.mix.value
            .filterKeys { it != Sound.BIRDS && it != Sound.CHIMES }
        val startMaster = AudioEngine.masterVolume.value

        if (!AudioEngine.isPlaying.value) AudioEngine.play()
        ensureOn(Sound.BIRDS, BIRDS_START)
        ensureOn(Sound.CHIMES, 0f)
        AudioEngine.setParam(Sound.BIRDS, "activity", DAWN_ACTIVITY_START)

        val begin = System.currentTimeMillis()
        val span = (wakeAt - begin).coerceAtLeast(1L)

        while (true) {
            val progress = ((System.currentTimeMillis() - begin).toFloat() / span.toFloat())
                .coerceIn(0f, 1f)

            val live = AudioEngine.mix.value
            for ((sound, startVolume) in nightMix) {
                if (live.containsKey(sound)) {
                    AudioEngine.setVolume(sound, startVolume * (1f - progress))
                }
            }
            AudioEngine.setVolume(Sound.BIRDS, BIRDS_START + (BIRDS_END - BIRDS_START) * progress)
            AudioEngine.setVolume(Sound.CHIMES, CHIMES_END * progress)
            // The chorus itself wakes up too: more birds, faster phrases.
            AudioEngine.setParam(
                Sound.BIRDS,
                "activity",
                DAWN_ACTIVITY_START + (DAWN_ACTIVITY_END - DAWN_ACTIVITY_START) * progress,
            )
            if (startMaster < MASTER_FLOOR) {
                AudioEngine.setMasterVolume(
                    startMaster + (MASTER_FLOOR - startMaster) * progress
                )
            }

            if (progress >= 1f) break
            val toGo = wakeAt - System.currentTimeMillis()
            delay(STEP_MILLIS.coerceAtMost(toGo).coerceAtLeast(1L))
        }

        // Faded to zero: drop the night sounds entirely. Birds and chimes are
        // still in the mix, so the engine never sees an empty mix and keeps
        // playing — that gentle chorus is the alarm.
        val live = AudioEngine.mix.value
        for (sound in nightMix.keys) {
            if (live.containsKey(sound)) AudioEngine.toggle(sound)
        }
        AudioEngine.setVolume(Sound.BIRDS, BIRDS_END)
        AudioEngine.setVolume(Sound.CHIMES, CHIMES_END)

        _armed.value = false
        _wakeAtMillis.value = null
        prefs?.edit()?.remove(KEY_WAKE_AT)?.apply()
        job = null
    }

    /** Adds [sound] if absent (toggle uses a default volume) and sets [volume]. */
    private fun ensureOn(sound: Sound, volume: Float) {
        if (!AudioEngine.mix.value.containsKey(sound)) AudioEngine.toggle(sound)
        AudioEngine.setVolume(sound, volume)
    }

    private fun nextOccurrence(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        cal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
