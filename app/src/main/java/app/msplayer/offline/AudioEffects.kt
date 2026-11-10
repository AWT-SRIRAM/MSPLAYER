package app.msplayer.offline

import android.content.Context
import org.videolan.libvlc.MediaPlayer
import java.util.Locale

data class AudioEffectSettings(
    val preset: String = "Original",
    val bands: List<Float> = List(10) { 0f },
    val enabled: Boolean = true,
    val bassBoost: Int = 0,
    val virtualizer: Int = 0,
    val reverb: String = "None",
    val tenBandMode: Boolean = false,
) {
    private fun basePresetGains(): List<Float> = when (preset) {
        "Clarity" -> listOf(-3f, -2f, -1f, 0f, 0f, 0f, 0f, 0f, -1f, -2f)
        "Bass Boost" -> listOf(5f, 4f, 3f, 1f, 0f, -1f, -1f, 0f, 0f, 0f)
        "Treble Boost" -> listOf(0f, 0f, -1f, -1f, 0f, 1f, 2f, 3f, 4f, 4f)
        "Movie" -> listOf(3f, 2f, 0f, -1f, 1f, 3f, 2f, 1f, 2f, 2f)
        "Music" -> listOf(3f, 2f, 0f, -1f, -1f, 0f, 1f, 2f, 3f, 2f)
        "Classical" -> listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f)
        "Dance" -> listOf(5f, 4f, 1f, 0f, 0f, -2f, 2f, 3f, 4f, 3f)
        "Rock" -> listOf(4f, 3f, 2f, 1f, -1f, -1f, 1f, 3f, 4f, 4f)
        "Custom" -> List(10) { bands.getOrElse(it) { 0f }.coerceIn(-12f, 12f) }
        else -> List(10) { 0f } // "Original", "Normal", "Flat"
    }

    fun gains(): List<Float> {
        val base = basePresetGains()
        val boost = bassBoost.coerceIn(0, 100)
        if (boost == 0) return base
        val extra = 6f * (boost / 100f)
        return base.mapIndexed { idx, v ->
            val add = when (idx) {
                0 -> extra
                1 -> extra * 0.65f
                2 -> extra * 0.30f
                else -> 0f
            }
            (v + add).coerceIn(-12f, 12f)
        }
    }

    fun fiveBandGains(): List<Float> {
        val g = basePresetGains()
        return List(5) { idx ->
            val a = g.getOrElse(idx * 2) { 0f }
            val b = g.getOrElse(idx * 2 + 1) { 0f }
            ((a + b) / 2f).coerceIn(-12f, 12f)
        }
    }

    fun withFiveBandGain(bandIdx: Int, db: Float): AudioEffectSettings {
        val safeIdx = bandIdx.coerceIn(0, 4)
        val clamped = db.coerceIn(-12f, 12f)
        val updated = gainsWithoutBassToCustom().toMutableList()
        updated[safeIdx * 2] = clamped
        updated[safeIdx * 2 + 1] = clamped
        return copy(preset = "Custom", bands = updated)
    }

    fun withTenBandGain(bandIdx: Int, db: Float): AudioEffectSettings {
        val safeIdx = bandIdx.coerceIn(0, 9)
        val clamped = db.coerceIn(-12f, 12f)
        val updated = gainsWithoutBassToCustom().toMutableList()
        updated[safeIdx] = clamped
        return copy(preset = "Custom", bands = updated)
    }

    private fun gainsWithoutBassToCustom(): List<Float> =
        if (preset == "Custom") List(10) { bands.getOrElse(it) { 0f }.coerceIn(-12f, 12f) } else basePresetGains()

    fun preamp(): Float = -(gains().maxOrNull() ?: 0f).coerceAtLeast(0f)

    fun spatializerActive(): Boolean = enabled && (reverb != "None" || virtualizer > 0)

    fun spatializerOptions(): List<String> {
        if (!spatializerActive()) return emptyList()
        val (room, damp, wet, dry) = when (reverb) {
            "Small Room" -> floatArrayOf(0.35f, 0.45f, 0.8f, 1.0f)
            "Medium Room" -> floatArrayOf(0.60f, 0.40f, 1.8f, 0.8f)
            "Large Room" -> floatArrayOf(0.82f, 0.35f, 2.8f, 0.5f)
            "Medium Hall" -> floatArrayOf(0.92f, 0.25f, 3.6f, 0.4f)
            "Large Hall" -> floatArrayOf(1.05f, 0.18f, 4.6f, 0.2f)
            "Plate" -> floatArrayOf(0.75f, 0.05f, 3.2f, 0.8f)
            else -> floatArrayOf(0.45f, 0.50f, -6.0f, 2.0f)
        }
        val width = 1.0f + (virtualizer.coerceIn(0, 100) / 100f) * 7.5f
        return listOf(
            "--audio-filter=spatializer",
            String.format(Locale.US, "--spatializer-roomsize=%.2f", room),
            String.format(Locale.US, "--spatializer-width=%.2f", width),
            String.format(Locale.US, "--spatializer-wet=%.2f", wet),
            String.format(Locale.US, "--spatializer-dry=%.2f", dry),
            String.format(Locale.US, "--spatializer-damp=%.2f", damp),
        )
    }

    fun save(context: Context) {
        Store.prefs(context).edit()
            .putString("effect_preset", preset)
            .putString("effect_bands", bands.joinToString(","))
            .putBoolean("effect_enabled", enabled)
            .putInt("effect_bass_boost", bassBoost.coerceIn(0, 100))
            .putInt("effect_virtualizer", virtualizer.coerceIn(0, 100))
            .putString("effect_reverb", reverb)
            .putBoolean("effect_ten_band", tenBandMode)
            .apply()
    }

    companion object {
        val presets = listOf(
            "Original", "Clarity", "Bass Boost", "Treble Boost",
            "Movie", "Music", "Normal", "Classical", "Dance", "Flat", "Rock"
        )
        val reverbPresets = listOf(
            "None", "Small Room", "Medium Room", "Large Room", "Medium Hall", "Large Hall", "Plate"
        )
        val fiveBandLabels = listOf("60Hz", "230Hz", "910Hz", "3600Hz", "14kHz")
        val tenBandLabels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

        fun load(context: Context): AudioEffectSettings {
            val p = Store.prefs(context)
            val name = p.getString("effect_preset", "Original") ?: "Original"
            val bands = p.getString("effect_bands", "")!!.split(',').map { it.toFloatOrNull()?.takeIf(Float::isFinite) ?: 0f }
            val reverb = p.getString("effect_reverb", "None") ?: "None"
            return AudioEffectSettings(
                preset = name.takeIf { it in presets || it == "Custom" } ?: "Original",
                bands = List(10) { bands.getOrElse(it) { 0f }.coerceIn(-12f, 12f) },
                enabled = p.getBoolean("effect_enabled", true),
                bassBoost = p.getInt("effect_bass_boost", 0).coerceIn(0, 100),
                virtualizer = p.getInt("effect_virtualizer", 0).coerceIn(0, 100),
                reverb = reverb.takeIf { it in reverbPresets } ?: "None",
                tenBandMode = p.getBoolean("effect_ten_band", false),
            )
        }
    }
}

/** One reusable native EQ object; no native allocation on each slider event. */
class AudioEffects {
    private var equalizer: MediaPlayer.Equalizer? = null
    fun apply(player: MediaPlayer, settings: AudioEffectSettings): Boolean {
        if (!settings.enabled || (settings.preset in setOf("Original", "Normal", "Flat") && settings.bassBoost == 0)) {
            return player.setEqualizer(null)
        }
        val eq = equalizer ?: MediaPlayer.Equalizer.create().also { equalizer = it }
        eq.setPreAmp(settings.preamp())
        val gains = settings.gains()
        repeat(MediaPlayer.Equalizer.getBandCount()) { eq.setAmp(it, gains.getOrElse(it) { 0f }) }
        return player.setEqualizer(eq)
    }
}
