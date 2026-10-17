package app.msplayer.offline

import android.content.Context

/** Plain-text appearance; authored ASS and bitmap subtitles stay native. */
data class SubtitleStyle(
    val size: Int = 2, val bold: Boolean = true, val outline: Int = 1,
    val shadow: Int = 64, val bottomMargin: Int = 6, val legacyMargin: Int? = null,
    val font: String = "Default",
) {
    fun options(): List<String> = listOf(
        "--freetype-rel-fontsize=${(if (legacyMargin != null) intArrayOf(20, 18, 16, 12, 6) else intArrayOf(24, 22, 20, 16, 12))[size.coerceIn(0, 4)]}",
        if (bold || SubtitleFonts.resolve(font).forceBold) "--freetype-bold" else "--no-freetype-bold",
        "--freetype-color=16777215",
        "--freetype-font=${SubtitleFonts.resolve(font).family.ifEmpty { "sans-serif" }}",
        "--freetype-outline-thickness=${outline.coerceIn(0, 6)}",
        "--freetype-outline-opacity=220",
        "--freetype-shadow-opacity=${shadow.coerceIn(0, 255)}",
        "--sub-margin=${legacyMargin?.coerceIn(0, 360) ?: 0}",
    )
    fun save(context: Context) {
        Store.prefs(context).edit().putInt("subtitle_style_version", 4)
            .putString("subtitle_font", font)
            .putInt("subtitle_size", size.coerceIn(0, 4)).putBoolean("subtitle_bold", bold)
            .putInt("subtitle_outline", outline.coerceIn(0, 6)).putInt("subtitle_shadow", shadow.coerceIn(0, 255))
            .putInt("subtitle_bottom_percent", bottomMargin.coerceIn(0, 25))
            .putInt("subtitle_legacy_margin", legacyMargin ?: -1).apply()
    }
    companion object {
        fun migrated(size: Int, bold: Boolean, outline: Int, shadow: Int, margin: Int): SubtitleStyle =
            if (size == 2 && bold && outline == 4 && shadow == 128 && margin == 320) SubtitleStyle()
            else SubtitleStyle(size, bold, outline, shadow, 6, margin)
        fun load(context: Context): SubtitleStyle {
            val p = Store.prefs(context)
            if (p.getInt("subtitle_style_version", 0) < 3) {
                val result = if (!p.contains("subtitle_size")) SubtitleStyle() else migrated(
                    p.getInt("subtitle_size", 2), p.getBoolean("subtitle_bold", true),
                    p.getInt("subtitle_outline", 4), p.getInt("subtitle_shadow", 128), p.getInt("subtitle_margin", 320))
                result.save(context)
                return result
            }
            return SubtitleStyle(p.getInt("subtitle_size", 2).coerceIn(0, 4), p.getBoolean("subtitle_bold", true),
                p.getInt("subtitle_outline", 1).coerceIn(0, 6), p.getInt("subtitle_shadow", 64).coerceIn(0, 255),
                p.getInt("subtitle_bottom_percent", 6).coerceIn(0, 25), p.getInt("subtitle_legacy_margin", -1).takeIf { it >= 0 }, p.getString("subtitle_font", "Default") ?: "Default")
        }
    }
}
