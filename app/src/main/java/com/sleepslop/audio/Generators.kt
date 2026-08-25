package com.sleepslop.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A generator writes one buffer of stereo audio (overwriting, not mixing).
 * All sounds are synthesized — nothing is sampled or recorded.
 */
interface SoundGenerator {
    fun render(left: FloatArray, right: FloatArray, frames: Int)

    /**
     * Live parameter update from the UI thread. Implementations store values
     * in fields read by the render thread; float writes are atomic on the
     * JVM, so no locking is needed.
     */
    fun setParam(id: String, value: Float) {}
}

private fun Random.bipolar(): Float = nextFloat() * 2f - 1f

// ---------------------------------------------------------------------------
// Core noise colors
// ---------------------------------------------------------------------------

class WhiteNoise : SoundGenerator {
    private val rl = Random(41)
    private val rr = Random(97)

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            left[i] = rl.bipolar() * 0.28f
            right[i] = rr.bipolar() * 0.28f
        }
    }
}

class PinkNoise : SoundGenerator {
    private val rl = Random(11)
    private val rr = Random(23)
    private val fl = PinkFilter()
    private val fr = PinkFilter()

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            left[i] = fl.next(rl.bipolar()) * 0.75f
            right[i] = fr.next(rr.bipolar()) * 0.75f
        }
    }
}

class BrownNoise : SoundGenerator {
    private val rl = Random(5)
    private val rr = Random(17)
    private val fl = BrownFilter()
    private val fr = BrownFilter()

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            left[i] = fl.next(rl.bipolar()) * 0.9f
            right[i] = fr.next(rr.bipolar()) * 0.9f
        }
    }
}

/**
 * Binaural tones: the carrier plays in the left ear and carrier + beat in
 * the right; the brain perceives the difference frequency. Delta (1–4 Hz)
 * tracks deep sleep, theta (4–8 Hz) drowsiness, alpha (~10 Hz) relaxation.
 * "Descend" mode glides the beat from its set value down to 2.5 Hz over
 * twenty minutes, shepherding the listener down with it.
 */
class DeepTones : SoundGenerator {
    @Volatile private var beat = 4f
    @Volatile private var carrier = 110f
    @Volatile private var descend = 0f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "beat" -> beat = value
            "carrier" -> carrier = value
            "descend" -> {
                if (value >= 0.5f && descend < 0.5f) descendElapsed = 0L
                descend = value
            }
        }
    }

    private var descendElapsed = 0L
    private var carrierS = 110f
    private var beatS = 4f
    private var incL = 0.0
    private var incR = 0.0
    private var phaseL = 0.0
    private var phaseR = 0.0
    private var swellPhase = 0.0
    private val incSwell = 2.0 * PI * 0.08 / SAMPLE_RATE
    private var blockCountdown = 0

    private fun updateBlock() {
        carrierS += (carrier - carrierS) * 0.05f
        var targetBeat = beat
        if (descend >= 0.5f) {
            val t = (descendElapsed / (20f * 60f * SAMPLE_RATE)).coerceAtMost(1f)
            targetBeat = beat + (2.5f - beat) * t
            descendElapsed += 256
        }
        beatS += (targetBeat - beatS) * 0.05f
        incL = 2.0 * PI * carrierS / SAMPLE_RATE
        incR = 2.0 * PI * (carrierS + beatS) / SAMPLE_RATE
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (--blockCountdown <= 0) {
                blockCountdown = 256
                updateBlock()
            }
            val swell = 0.85f + 0.15f * sin(swellPhase).toFloat()
            left[i] = sin(phaseL).toFloat() * 0.25f * swell
            right[i] = sin(phaseR).toFloat() * 0.25f * swell
            phaseL += incL
            phaseR += incR
            swellPhase += incSwell
            if (phaseL > 2 * PI) phaseL -= 2 * PI
            if (phaseR > 2 * PI) phaseR -= 2 * PI
            if (swellPhase > 2 * PI) swellPhase -= 2 * PI
        }
    }
}

// ---------------------------------------------------------------------------
// Textured scenes (still 100% synthesized)
// ---------------------------------------------------------------------------

// Wind was replaced by WindV2 in Nature.kt (multi-band gust engine).

// ForestNight was replaced by ForestNightV2 in Nature.kt (layered distances).

// Campfire was replaced by CampfireV2 in Nature.kt (clustered crackle taxonomy).

/**
 * Box fan: a soft motor hum (fundamental + harmonics), a gentle blade
 * wobble, and broadband moving air.
 */
class BoxFan : SoundGenerator {
    private val rnd = Random(29)
    private var phase = 0.0
    private var wobble = 0.0
    private val inc = 2.0 * PI * 53.0 / SAMPLE_RATE
    private val incWobble = 2.0 * PI * 11.3 / SAMPLE_RATE
    private val airL = Biquad().lowpass(720f)
    private val airR = Biquad().lowpass(720f)

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            phase += inc
            wobble += incWobble
            if (phase > 2 * PI) phase -= 2 * PI
            if (wobble > 2 * PI) wobble -= 2 * PI
            val hum = (sin(phase) * 0.5 + sin(2 * phase) * 0.20 + sin(3 * phase) * 0.08)
                .toFloat() * 0.30f * (1f + 0.05f * sin(wobble).toFloat())
            left[i] = hum + airL.process(rnd.bipolar()) * 0.40f
            right[i] = hum + airR.process(rnd.bipolar()) * 0.40f
        }
    }
}

// NightTrain was replaced by NightTrainV2 in Train.kt (bogie-geometry model).

// ---------------------------------------------------------------------------
// Catalog
// ---------------------------------------------------------------------------

/** A user-tweakable parameter of a generator. */
class Param(
    val id: String,
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = "",
    val integer: Boolean = false,
) {
    fun format(value: Float): String = when {
        integer -> value.roundToInt().toString()
        unit.isNotEmpty() -> "${value.roundToInt()} $unit"
        min >= 0f && max <= 1f -> "${(value * 100).roundToInt()}%"
        else -> value.roundToInt().toString()
    }
}

/** MIX sounds live on the main grid; ELEMENTs on the components tab. */
enum class Category { MIX, ELEMENT }

enum class Sound(
    val label: String,
    val emoji: String,
    val blurb: String,
    val category: Category,
    val params: List<Param>,
    val create: () -> SoundGenerator,
) {
    WHITE("White noise", "🌫️", "Even energy across every frequency", Category.MIX, emptyList(), ::WhiteNoise),
    PINK("Pink noise", "🌸", "Softer, naturally balanced hiss", Category.MIX, emptyList(), ::PinkNoise),
    BROWN("Brown noise", "🟤", "Deep, gentle low-frequency rumble", Category.MIX, emptyList(), ::BrownNoise),
    DEEP(
        "Deep tones", "🌀", "Binaural beat — delta to alpha, or descend",
        Category.MIX,
        listOf(
            Param("beat", "Beat", 1f, 12f, 4f, "Hz"),
            Param("carrier", "Carrier", 80f, 220f, 110f, "Hz"),
            Param("descend", "Descend (20 min)", 0f, 1f, 0f, integer = true),
        ),
        ::DeepTones,
    ),
    RAIN(
        "Rain", "🌧️", "Layered rainfall — patter, wash, and gusts",
        Category.MIX,
        listOf(
            Param("intensity", "Intensity", 0f, 1f, 0.5f),
            Param("surface", "Surface", 0f, 1f, 0.35f),
            Param("drips", "Gutter drips", 0f, 1f, 0.25f),
        ),
        ::RainV2,
    ),
    OCEAN(
        "Ocean", "🌊", "Overlapping waves washing a gentle shore",
        Category.MIX,
        listOf(
            Param("swell", "Swell", 0f, 1f, 0.5f),
            Param("period", "Wave period", 8f, 22f, 14f, "s"),
            Param("foam", "Foam", 0f, 1f, 0.4f),
        ),
        ::OceanV2,
    ),
    WIND(
        "Wind", "🍃", "Buffet, whoosh, and foliage riding one gust engine",
        Category.MIX,
        listOf(
            Param("strength", "Strength", 0f, 1f, 0.5f),
            Param("gustiness", "Gustiness", 0f, 1f, 0.5f),
            Param("foliage", "Foliage", 0f, 1f, 0.4f),
        ),
        ::WindV2,
    ),
    FOREST(
        "Forest night", "🌲", "Crickets, peepers, a far owl, a small brook",
        Category.MIX,
        listOf(
            Param("life", "Life", 0f, 1f, 0.5f),
            Param("breeze", "Breeze", 0f, 1f, 0.35f),
            Param("voices", "Night voices", 0f, 1f, 0.4f),
            Param("water", "Stream", 0f, 1f, 0.25f),
        ),
        ::ForestNightV2,
    ),
    FIRE(
        "Campfire", "🔥", "Breathing flames, clustered crackles, log settles",
        Category.MIX,
        listOf(
            Param("size", "Fire size", 0f, 1f, 0.5f),
            Param("crackle", "Crackle", 0f, 1f, 0.5f),
        ),
        ::CampfireV2,
    ),
    FAN("Box fan", "💨", "Low motor hum and moving air", Category.MIX, emptyList(), ::BoxFan),
    FANSIM(
        "Simulated fan", "🌪️", "Physically modeled fan — tap Tune to shape it",
        Category.MIX,
        listOf(
            Param("rpm", "Speed", 500f, 1600f, 1050f, "rpm"),
            Param("blades", "Blades", 3f, 7f, 5f, integer = true),
            Param("hum", "Motor hum", 0f, 1f, 0.35f),
            Param("buzz", "Motor buzz", 0f, 1f, 0.3f),
            Param("size", "Fan size", 0f, 1f, 0.5f),
            Param("distance", "Distance", 0f, 1f, 0.35f),
            Param("sway", "Oscillation", 0f, 1f, 0f),
        ),
        ::SimulatedFan,
    ),
    TRAIN(
        "Night train", "🚂", "A real consist rolling over real rail joints",
        Category.MIX,
        listOf(
            Param("speed", "Speed", 0f, 1f, 0.5f),
            Param("distance", "Distance", 0f, 1f, 0.4f),
            Param("rails", "Jointed rail", 0f, 1f, 0.6f),
        ),
        ::NightTrainV2,
    ),

    // ------------------------------------------------------------ elements
    CRICKETS(
        "Crickets", "🦗", "A meadow of chirps you can herd",
        Category.ELEMENT,
        listOf(
            Param("rate", "Chirp rate", 0f, 1f, 0.5f),
            Param("voices", "Swarm size", 1f, 6f, 3f, integer = true),
            Param("pitch", "Pitch", 3200f, 5200f, 4200f, "Hz"),
        ),
        ::CricketsElement,
    ),
    FROGS(
        "Frogs", "🐸", "Croaks from the pond's edge",
        Category.ELEMENT,
        listOf(
            Param("rate", "Croak rate", 0f, 1f, 0.45f),
            Param("pitch", "Pitch", 250f, 620f, 380f, "Hz"),
        ),
        ::FrogsElement,
    ),
    OWL(
        "Owl", "🦉", "The occasional hoot from a far tree",
        Category.ELEMENT,
        listOf(
            Param("rate", "Hoot rate", 0f, 1f, 0.4f),
            Param("pitch", "Pitch", 280f, 440f, 350f, "Hz"),
        ),
        ::OwlElement,
    ),
    THUNDER(
        "Distant thunder", "⛈️", "Strikes that roll and resurge across the sky",
        Category.ELEMENT,
        listOf(
            Param("rate", "Storm activity", 0f, 1f, 0.4f),
            Param("distance", "Distance", 0f, 1f, 0.6f),
        ),
        ::ThunderV2,
    ),
    CHIMES(
        "Wind chimes", "🎐", "Pentatonic strikes on a breeze",
        Category.ELEMENT,
        listOf(
            Param("rate", "Breeze", 0f, 1f, 0.4f),
            Param("shimmer", "Shimmer", 0f, 1f, 0.5f),
        ),
        ::ChimesElement,
    ),
    DRIPS(
        "Water drops", "💧", "Plinks and bloops in an echoing cave",
        Category.ELEMENT,
        listOf(
            Param("rate", "Drip rate", 0f, 1f, 0.4f),
            Param("tone", "Tone", 0f, 1f, 0.5f),
            Param("space", "Echo", 0f, 1f, 0.6f),
            Param("trickle", "Trickle", 0f, 1f, 0.35f),
        ),
        ::WaterDropsV2,
    ),
    HEARTBEAT(
        "Heartbeat", "💓", "A slow, soft lub-dub",
        Category.ELEMENT,
        listOf(
            Param("bpm", "Tempo", 45f, 80f, 58f, "bpm"),
            Param("soft", "Softness", 0f, 1f, 0.6f),
        ),
        ::HeartbeatElement,
    ),
    PURR(
        "Cat purr", "🐈", "A contented cat on the pillow",
        Category.ELEMENT,
        listOf(
            Param("rate", "Purr rate", 20f, 32f, 25f, "Hz"),
            Param("breath", "Breathiness", 0f, 1f, 0.5f),
        ),
        ::CatPurrElement,
    ),
    CLOCK(
        "Clock tick", "🕰️", "A patient pendulum in the hall",
        Category.ELEMENT,
        listOf(
            Param("speed", "Tempo", 0f, 1f, 0.5f),
            Param("wood", "Mellowness", 0f, 1f, 0.5f),
        ),
        ::ClockTickElement,
    ),
    CAFE(
        "Café murmur", "☕", "Soft unintelligible chatter and clinks",
        Category.ELEMENT,
        listOf(
            Param("crowd", "Crowd", 0f, 1f, 0.5f),
            Param("clatter", "Clatter", 0f, 1f, 0.3f),
        ),
        ::CafeMurmurElement,
    ),
    FOGHORN(
        "Foghorn", "🚢", "A far horn across still water",
        Category.ELEMENT,
        listOf(
            Param("rate", "Frequency", 0f, 1f, 0.4f),
            Param("distance", "Distance", 0f, 1f, 0.6f),
        ),
        ::FoghornElement,
    ),
    BIRDS(
        "Dawn chorus", "🐦", "Songbirds greeting first light",
        Category.ELEMENT,
        listOf(
            Param("activity", "Activity", 0f, 1f, 0.5f),
            Param("variety", "Variety", 0f, 1f, 0.5f),
        ),
        ::BirdsongV2,
    ),
}
