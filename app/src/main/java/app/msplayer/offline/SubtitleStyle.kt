package app.msplayer.offline

import android.content.Context
import kotlin.math.roundToInt

/** Plain-text appearance only. Authored ASS and bitmap subtitles remain native. */
data class SubtitleStyle(
    val size: Int = 23, val bold: Boolean = true, val outline: Int = 2,
    val shadow: Int = 140, val shadowOffset: Int = 2, val lineSpacing: Int = 100,
    val bottomMargin: Int = 0, val legacyMargin: Int? = null,
    val font: String = "Default", val scale: Int = 100,
    val color: Int = 0xFFFFFF, val background: Boolean = false,
    val backgroundColor: Int = 0, val backgroundOpacity: Int = 128,
    val borderColor: Int = 0, val borderOpacity: Int = 204,
    val encoding: String = "", val compensateNativeInset: Boolean = true, val legacyFontFamily: String? = null,
    val secondaryTop: Boolean = false,
) {
    val textScale: Int get() = (scale.coerceIn(50, 300) * size.coerceIn(10, 60) / 23f).roundToInt().coerceIn(10, 500)
    fun options(): List<String> = renderOptions(SubtitleFonts.resolve(font).let { resolved -> legacyFontFamily?.let { resolved.copy(family=it) } ?: resolved })
    // Geometry is deliberately absent: moving subtitles must not recreate the renderer.
    fun renderOptions(resolved: SubtitleFont): List<String> = listOf(
        "--freetype-rel-fontsize=17", "--sub-text-scale=$textScale",
        if (bold || resolved.forceBold) "--freetype-bold" else "--no-freetype-bold",
        "--freetype-color=${color and 0xFFFFFF}", "--freetype-opacity=255",
        "--freetype-font=${resolved.family.ifEmpty { "sans-serif" }}",
        "--freetype-outline-thickness=${outline.coerceIn(0, 6)}",
        "--freetype-outline-color=${borderColor and 0xFFFFFF}",
        "--freetype-outline-opacity=${borderOpacity.coerceIn(0, 255)}",
        "--freetype-shadow-color=0", "--freetype-shadow-opacity=${shadow.coerceIn(0, 255)}",
        "--freetype-shadow-distance=${shadowOffset.coerceIn(0, 10)}",
        "--freetype-shadow-angle=45",
        "--freetype-background-color=${backgroundColor and 0xFFFFFF}",
        "--freetype-background-opacity=${if (background) backgroundOpacity.coerceIn(0, 255) else 0}",
        "--sub-margin=${legacyMargin?.coerceIn(0, 360) ?: 0}",
        // VLC's subtitle demuxer converts BOM-marked UTF-16 lines to UTF-8 first.
        // Asking subsdec to decode those lines as UTF-16 would decode them twice.
        "--subsdec-encoding=${if (encoding == "UTF-16") "UTF-8" else encoding.takeIf { it in encodings.values } ?: ""}",
    )
    fun save(context: Context) {
        Store.prefs(context).edit().putInt("subtitle_style_version", 6)
            .putBoolean("subtitle_compensate_inset",compensateNativeInset).putString("subtitle_legacy_font_family",legacyFontFamily)
            .putString("subtitle_font", font).putInt("subtitle_size", size.coerceIn(10, 60))
            .putInt("subtitle_scale", scale.coerceIn(50, 300)).putBoolean("subtitle_bold", bold)
            .putInt("subtitle_outline", outline.coerceIn(0, 6)).putInt("subtitle_shadow", shadow.coerceIn(0, 255))
            .putInt("subtitle_shadow_offset", shadowOffset.coerceIn(0, 10))
            .putInt("subtitle_line_spacing", lineSpacing.coerceIn(80, 200))
            .putInt("subtitle_bottom_percent", bottomMargin.coerceIn(0, 40))
            .putInt("subtitle_legacy_margin", legacyMargin ?: -1)
            .putInt("subtitle_color", color and 0xFFFFFF).putBoolean("subtitle_background", background)
            .putInt("subtitle_background_color", backgroundColor and 0xFFFFFF)
            .putInt("subtitle_background_opacity", backgroundOpacity.coerceIn(0, 255))
            .putInt("subtitle_border_color", borderColor and 0xFFFFFF)
            .putInt("subtitle_border_opacity", borderOpacity.coerceIn(0, 255))
            .putString("subtitle_encoding", encoding.takeIf { it in encodings.values } ?: "")
            .putBoolean("subtitle_secondary_top", secondaryTop).apply()
    }
    companion object {
        val encodings = linkedMapOf("Auto" to "", "UTF-8" to "UTF-8", "UTF-16" to "UTF-16",
            "Western (Windows-1252)" to "Windows-1252", "Central European (Windows-1250)" to "Windows-1250",
            "Cyrillic (Windows-1251)" to "Windows-1251", "Greek (Windows-1253)" to "Windows-1253",
            "Turkish (Windows-1254)" to "Windows-1254", "Arabic (Windows-1256)" to "Windows-1256",
            "Western (ISO-8859-15)" to "ISO-8859-15")
        fun migrateV4(size: Int, bold: Boolean, outline: Int, shadow: Int, bottom: Int,
                      legacy: Int?, font: String): SubtitleStyle {
            if (size == 2 && bold && outline == 1 && shadow == 64 && bottom == 6 && legacy == null)
                return SubtitleStyle(font = font)
            val rel = (if (legacy == null) intArrayOf(24,22,20,16,12) else intArrayOf(20,18,16,12,6))[size.coerceIn(0,4)]
            return SubtitleStyle(bold=bold, outline=outline.coerceIn(0,6), shadow=shadow.coerceIn(0,255),
                bottomMargin=bottom.coerceIn(0,40), legacyMargin=legacy, font=font,
                scale=(1700f/rel).roundToInt(), borderOpacity=220,compensateNativeInset=false)
        }
        fun migrated(size: Int, bold: Boolean, outline: Int, shadow: Int, margin: Int): SubtitleStyle =
            if (size == 2 && bold && outline == 4 && shadow == 128 && margin == 320) SubtitleStyle()
            else migrateV4(size,bold,outline,shadow,6,margin,"Default")
        fun load(context: Context): SubtitleStyle {
            val p = Store.prefs(context)
            val version = p.getInt("subtitle_style_version",0)
            val font = p.getString("subtitle_font", "Default") ?: "Default"
            if (version < 5) {
                val result = when {
                    !p.contains("subtitle_size") -> SubtitleStyle()
                    version < 3 -> migrated(p.getInt("subtitle_size",2),p.getBoolean("subtitle_bold",true),
                        p.getInt("subtitle_outline",4),p.getInt("subtitle_shadow",128),p.getInt("subtitle_margin",320)).copy(font=font)
                    else -> migrateV4(p.getInt("subtitle_size",2),p.getBoolean("subtitle_bold",true),
                        p.getInt("subtitle_outline",1),p.getInt("subtitle_shadow",64),p.getInt("subtitle_bottom_percent",6),
                        p.getInt("subtitle_legacy_margin",-1).takeIf {it>=0},font)
                }
                val preserved = if (!result.compensateNativeInset && font == "Roboto")
                    result.copy(legacyFontFamily=SubtitleFonts.legacyRobotoFamily()) else result
                preserved.save(context)
                return preserved
            }
            return SubtitleStyle(size=p.getInt("subtitle_size",23).coerceIn(10,60),bold=p.getBoolean("subtitle_bold",true),
                outline=p.getInt("subtitle_outline",2).coerceIn(0,6),shadow=p.getInt("subtitle_shadow",140).coerceIn(0,255),
                shadowOffset=p.getInt("subtitle_shadow_offset",2).coerceIn(0,10),
                lineSpacing=p.getInt("subtitle_line_spacing",100).coerceIn(80,200),
                bottomMargin=p.getInt("subtitle_bottom_percent",0).coerceIn(0,40),
                legacyMargin=p.getInt("subtitle_legacy_margin",-1).takeIf {it>=0},font=font,
                scale=p.getInt("subtitle_scale",100).coerceIn(50,300),color=p.getInt("subtitle_color",0xFFFFFF),
                background=p.getBoolean("subtitle_background",false), backgroundColor=p.getInt("subtitle_background_color",0),
                backgroundOpacity=p.getInt("subtitle_background_opacity",128),borderColor=p.getInt("subtitle_border_color",0),
                borderOpacity=p.getInt("subtitle_border_opacity",204),encoding=p.getString("subtitle_encoding","") ?: "",
                compensateNativeInset=p.getBoolean("subtitle_compensate_inset",true),
                legacyFontFamily=p.getString("subtitle_legacy_font_family",null),
                secondaryTop=p.getBoolean("subtitle_secondary_top",false))
        }
    }
}
