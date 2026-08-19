package com.sleepslop.data

import android.content.Context
import android.content.SharedPreferences
import com.sleepslop.audio.AudioEngine
import com.sleepslop.audio.Sound
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A saved scene: which sounds are on, how loud, their tweaked generator
 * parameters, and the master volume at save time.
 *
 * [builtIn] presets ship with the app; they are never persisted and cannot be
 * deleted or overwritten by the user.
 */
data class Preset(
    val name: String,
    val mix: Map<Sound, Float>,
    val params: Map<Sound, Map<String, Float>>,
    val master: Float,
    val builtIn: Boolean = false,
)

/**
 * Holds the curated built-in scenes plus the user's own, persisted as JSON in
 * their own `SharedPreferences` file so the engine's state file stays untouched.
 *
 * Storage layout (key `user_presets` of `sleepslop_presets`):
 * ```json
 * {
 *   "version": 1,
 *   "presets": [
 *     {
 *       "name": "Storm porch",
 *       "master": 0.8,
 *       "mix":    { "RAIN": 0.7, "THUNDER": 0.35 },
 *       "params": { "THUNDER": { "distance": 0.75 } }
 *     }
 *   ]
 * }
 * ```
 * Sounds are keyed by enum name; names this build does not know are skipped on
 * load, so a preset saved by a newer version degrades instead of crashing.
 */
object PresetStore {

    private const val PREFS_NAME = "sleepslop_presets"
    private const val KEY_PRESETS = "user_presets"
    private const val SCHEMA_VERSION = 1

    /** Default master volume used by the built-ins. */
    private const val BUILT_IN_MASTER = 0.8f

    /**
     * Curated scenes, in the order they are shown. Declared before the state
     * below because object initializers run top to bottom.
     */
    private val BUILT_INS: List<Preset> = listOf(
        Preset(
            name = "Rainy night",
            mix = mapOf(Sound.RAIN to 0.7f, Sound.THUNDER to 0.35f, Sound.WIND to 0.25f),
            params = mapOf(Sound.THUNDER to mapOf("distance" to 0.75f)),
            master = BUILT_IN_MASTER,
            builtIn = true,
        ),
        Preset(
            name = "Seaside",
            mix = mapOf(Sound.OCEAN to 0.65f, Sound.WIND to 0.3f),
            params = emptyMap(),
            master = BUILT_IN_MASTER,
            builtIn = true,
        ),
        Preset(
            name = "Deep focus",
            mix = mapOf(Sound.BROWN to 0.7f, Sound.DEEP to 0.35f),
            params = emptyMap(),
            master = BUILT_IN_MASTER,
            builtIn = true,
        ),
        Preset(
            name = "Summer meadow",
            mix = mapOf(Sound.CRICKETS to 0.55f, Sound.FROGS to 0.3f, Sound.CHIMES to 0.2f),
            params = emptyMap(),
            master = BUILT_IN_MASTER,
            builtIn = true,
        ),
        Preset(
            name = "Night train home",
            mix = mapOf(Sound.TRAIN to 0.65f, Sound.RAIN to 0.35f),
            params = emptyMap(),
            master = BUILT_IN_MASTER,
            builtIn = true,
        ),
        Preset(
            name = "Cozy cabin",
            mix = mapOf(Sound.FIRE to 0.6f, Sound.RAIN to 0.45f, Sound.WIND to 0.3f),
            params = emptyMap(),
            master = BUILT_IN_MASTER,
            builtIn = true,
        ),
    )

    private var prefs: SharedPreferences? = null

    /** User presets in insertion order; [presets] publishes them sorted. */
    private var userPresets: List<Preset> = emptyList()

    private val _presets = MutableStateFlow(BUILT_INS)

    /** Built-ins first (curated order), then user presets alphabetically. */
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    /** Reads the stored presets. Safe to call more than once. */
    fun init(context: Context) {
        val store = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = store
        userPresets = decode(store.getString(KEY_PRESETS, null))
        publish()
    }

    // ------------------------------------------------------------------ API

    /**
     * Captures the engine's current mix, the parameter overrides of the sounds
     * in that mix, and the master volume under [name].
     *
     * An existing user preset with the same name is replaced. Built-in names are
     * protected: a collision is suffixed (`"Seaside"` -> `"Seaside (2)"`).
     * Does nothing when the name is blank or nothing is playing.
     */
    fun saveCurrent(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return

        val mix = AudioEngine.mix.value
        if (mix.isEmpty()) return

        val allParams = AudioEngine.paramValues.value
        val params = mutableMapOf<Sound, Map<String, Float>>()
        for (sound in mix.keys) {
            val per = allParams[sound]
            if (per != null && per.isNotEmpty()) params[sound] = per.toMap()
        }

        val preset = Preset(
            name = uniqueName(trimmed),
            mix = mix.toMap(),
            params = params,
            master = AudioEngine.masterVolume.value,
            builtIn = false,
        )
        userPresets = userPresets.filterNot { it.name.equals(preset.name, ignoreCase = true) } +
            preset
        persist()
        publish()
    }

    /** Removes a user preset by name. Built-ins are ignored. */
    fun delete(name: String) {
        val remaining = userPresets.filterNot { it.name.equals(name, ignoreCase = true) }
        if (remaining.size == userPresets.size) return
        userPresets = remaining
        persist()
        publish()
    }

    /**
     * Pushes a preset into the engine. Playback state is left alone — applying
     * while paused simply arms the scene for the next play.
     */
    fun apply(preset: Preset) {
        AudioEngine.applyPreset(preset.mix, preset.params, preset.master)
    }

    // -------------------------------------------------------------- internal

    /**
     * Returns [name] unless a built-in already claims it, in which case a
     * `" (n)"` suffix is appended until the name is free of built-ins.
     */
    private fun uniqueName(name: String): String {
        if (BUILT_INS.none { it.name.equals(name, ignoreCase = true) }) return name
        var n = 2
        while (true) {
            val candidate = "$name ($n)"
            if (BUILT_INS.none { it.name.equals(candidate, ignoreCase = true) }) return candidate
            n++
        }
    }

    private fun publish() {
        val sorted = userPresets.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        _presets.value = BUILT_INS + sorted
    }

    private fun persist() {
        prefs?.edit()?.putString(KEY_PRESETS, encode(userPresets))?.apply()
    }

    // ----------------------------------------------------------- (de)serialize

    private fun encode(list: List<Preset>): String {
        val array = JSONArray()
        for (preset in list) {
            val mix = JSONObject()
            for ((sound, volume) in preset.mix) mix.put(sound.name, volume.toDouble())

            val params = JSONObject()
            for ((sound, values) in preset.params) {
                if (values.isEmpty()) continue
                val per = JSONObject()
                for ((id, value) in values) per.put(id, value.toDouble())
                params.put(sound.name, per)
            }

            array.put(
                JSONObject()
                    .put("name", preset.name)
                    .put("master", preset.master.toDouble())
                    .put("mix", mix)
                    .put("params", params)
            )
        }
        return JSONObject()
            .put("version", SCHEMA_VERSION)
            .put("presets", array)
            .toString()
    }

    private fun decode(raw: String?): List<Preset> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONObject(raw).optJSONArray("presets") ?: return emptyList()
            val result = mutableListOf<Preset>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isEmpty()) continue
                if (result.any { it.name.equals(name, ignoreCase = true) }) continue

                val mix = readMix(obj.optJSONObject("mix"))
                if (mix.isEmpty()) continue

                result += Preset(
                    name = name,
                    mix = mix,
                    params = readParams(obj.optJSONObject("params"), mix.keys),
                    master = obj.optDouble("master", BUILT_IN_MASTER.toDouble())
                        .toFloat()
                        .coerceIn(0f, 1f),
                    builtIn = false,
                )
            }
            result
        } catch (e: JSONException) {
            // Corrupt store: start over rather than crash on launch.
            emptyList()
        }
    }

    /** Enum names this build does not know are skipped (forward compatibility). */
    private fun readMix(obj: JSONObject?): Map<Sound, Float> {
        if (obj == null) return emptyMap()
        val mix = mutableMapOf<Sound, Float>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val sound = Sound.entries.firstOrNull { it.name == key } ?: continue
            val volume = obj.optDouble(key, Double.NaN)
            if (volume.isNaN()) continue
            mix[sound] = volume.toFloat().coerceIn(0f, 1f)
        }
        return mix
    }

    /** Parameter overrides, restricted to the sounds that survived [readMix]. */
    private fun readParams(obj: JSONObject?, known: Set<Sound>): Map<Sound, Map<String, Float>> {
        if (obj == null) return emptyMap()
        val params = mutableMapOf<Sound, Map<String, Float>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val sound = Sound.entries.firstOrNull { it.name == key } ?: continue
            if (sound !in known) continue
            val per = obj.optJSONObject(key) ?: continue
            val values = mutableMapOf<String, Float>()
            val ids = per.keys()
            while (ids.hasNext()) {
                val id = ids.next()
                val value = per.optDouble(id, Double.NaN)
                if (value.isNaN()) continue
                values[id] = value.toFloat()
            }
            if (values.isNotEmpty()) params[sound] = values
        }
        return params
    }
}
