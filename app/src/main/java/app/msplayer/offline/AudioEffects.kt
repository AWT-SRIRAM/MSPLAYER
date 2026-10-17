package app.msplayer.offline

import android.content.Context
import org.videolan.libvlc.MediaPlayer

data class AudioEffectSettings(val preset: String = "Original", val bands: List<Float> = List(10) { 0f }) {
    fun gains(): List<Float> = when (preset) {
        "Clarity" -> listOf(-3f, -2f, -1f, 0f, 0f, 0f, 0f, 0f, -1f, -2f)
        "Bass Boost" -> listOf(5f, 4f, 3f, 1f, 0f, -1f, -1f, 0f, 0f, 0f)
        "Treble Boost" -> listOf(0f, 0f, -1f, -1f, 0f, 1f, 2f, 3f, 4f, 4f)
        "Movie" -> listOf(3f, 2f, 0f, -1f, 1f, 3f, 2f, 1f, 2f, 2f)
        "Music" -> listOf(3f, 2f, 0f, -1f, -1f, 0f, 1f, 2f, 3f, 2f)
        "Custom" -> List(10) { bands.getOrElse(it) { 0f }.coerceIn(-12f, 12f) }
        else -> List(10) { 0f }
    }
    fun preamp(): Float = -(gains().maxOrNull() ?: 0f).coerceAtLeast(0f)
    fun save(context: Context) {
        Store.prefs(context).edit().putString("effect_preset", preset)
            .putString("effect_bands", bands.joinToString(",")).apply()
    }
    companion object {
        val presets = listOf("Original", "Clarity", "Bass Boost", "Treble Boost", "Movie", "Music")
        fun load(context: Context): AudioEffectSettings {
            val p = Store.prefs(context)
            val name = p.getString("effect_preset", "Original") ?: "Original"
            val bands = p.getString("effect_bands", "")!!.split(',').map { it.toFloatOrNull()?.takeIf(Float::isFinite) ?: 0f }
            return AudioEffectSettings(name.takeIf { it in presets || it == "Custom" } ?: "Original", List(10) { bands.getOrElse(it) { 0f }.coerceIn(-12f, 12f) })
        }
    }
}

/** One reusable native EQ object; no native allocation on each slider event. */
class AudioEffects {
    private var equalizer: MediaPlayer.Equalizer? = null
    fun apply(player: MediaPlayer, settings: AudioEffectSettings): Boolean {
        if (settings.preset == "Original") return player.setEqualizer(null)
        val eq = equalizer ?: MediaPlayer.Equalizer.create().also { equalizer = it }
        eq.setPreAmp(settings.preamp())
        val gains = settings.gains()
        repeat(MediaPlayer.Equalizer.getBandCount()) { eq.setAmp(it, gains.getOrElse(it) { 0f }) }
        return player.setEqualizer(eq)
    }
}
