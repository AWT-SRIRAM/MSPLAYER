package app.msplayer.offline

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Html
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Authentic MX Player-style dual-layer StrokeView.
 * Uses a background TextView with Paint.Style.STROKE for the sharp dark outline,
 * and a foreground TextView with Paint.Style.FILL and drop shadow for the text.
 */
class StrokeView(context: Context) : FrameLayout(context) {
    val strokeText = TextView(context)
    val fillText = TextView(context)

    init {
        // Configure stroke (outline) view
        strokeText.paint.style = Paint.Style.STROKE
        strokeText.paint.strokeJoin = Paint.Join.ROUND
        strokeText.paint.strokeCap = Paint.Cap.ROUND
        strokeText.setTextColor(Color.BLACK)
        strokeText.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        strokeText.includeFontPadding = false
        addView(strokeText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM))

        // Configure fill view
        fillText.paint.style = Paint.Style.FILL
        fillText.setTextColor(Color.WHITE)
        fillText.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        fillText.includeFontPadding = false
        addView(fillText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM))

        // Default side padding matching MX Player (36dp)
        val hPad = (36f * resources.displayMetrics.density).toInt()
        setPadding(hPad, 0, hPad, 0)
    }

    fun applyStyle(style: SubtitleStyle, isControlsVisible: Boolean) {
        val density = resources.displayMetrics.density
        val baseSizeSp = style.size.toFloat()
        val scaleFactor = (style.scale / 100f).coerceIn(0.5f, 3.0f)
        val effectiveSizeSp = baseSizeSp * scaleFactor
        val effectiveSizePx = effectiveSizeSp * density

        strokeText.textSize = effectiveSizeSp
        fillText.textSize = effectiveSizeSp

        // Typeface
        val tf = SubtitleFonts.resolve(style.font).family.let { fam ->
            if (fam.isNotBlank()) Typeface.create(fam, if (style.bold) Typeface.BOLD else Typeface.NORMAL)
            else if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        strokeText.typeface = tf
        fillText.typeface = tf

        // Colors
        fillText.setTextColor(style.color or (0xFF shl 24))
        strokeText.setTextColor(style.borderColor or (style.borderOpacity shl 24))

        // Outline Stroke Width: MX Player formula = 8% of text size px
        val strokeWidthPx = if (style.outline > 0) {
            effectiveSizePx * 0.08f * (style.outline / 2f)
        } else 0f
        strokeText.paint.strokeWidth = strokeWidthPx

        // Shadow: MX Player formula = offset = textSize / 20.0f, radius = 1.0f
        if (style.shadow > 0) {
            val shadowOffset = (effectiveSizePx / 20f) * (style.shadowOffset / 2f)
            val shadowAlpha = style.shadow.coerceIn(0, 255)
            val shadowColor = Color.argb(shadowAlpha, 0, 0, 0)
            fillText.setShadowLayer(1.0f * density, shadowOffset, shadowOffset, shadowColor)
        } else {
            fillText.setShadowLayer(0f, 0f, 0f, 0)
        }

        // Background box
        if (style.background) {
            val bgAlpha = style.backgroundOpacity.coerceIn(0, 255)
            val bgColor = (style.backgroundColor and 0x00FFFFFF) or (bgAlpha shl 24)
            setBackgroundColor(bgColor)
        } else {
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Margin & Lift
        val bottomMarginPercent = style.bottomMargin.coerceIn(0, 40)
        val liftFraction = if (isControlsVisible) 0.12f else 0f
        val totalLift = (bottomMarginPercent / 100f) + liftFraction
        translationY = -totalLift * (resources.displayMetrics.heightPixels * 0.35f)
    }

    fun setSubtitleText(text: CharSequence?) {
        if (text.isNullOrBlank()) {
            visibility = GONE
            strokeText.text = ""
            fillText.text = ""
        } else {
            visibility = VISIBLE
            val formatted: CharSequence = if (text.contains('<')) {
                Html.fromHtml(text.toString().replace("\n", "<br/>"), Html.FROM_HTML_MODE_LEGACY)
            } else text
            strokeText.text = formatted
            fillText.text = formatted
        }
    }
}

