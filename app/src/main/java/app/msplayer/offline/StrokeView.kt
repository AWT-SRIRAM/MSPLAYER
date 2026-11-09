package app.msplayer.offline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Html
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Single-layout dual-pass Canvas subtitle renderer.
 * Uses ONE shared TextPaint + StaticLayout coordinate pass so outer borders,
 * drop shadows, and foreground glyph fills stay pixel-locked with zero drift.
 */
class StrokeView(context: Context) : View(context) {

    private var currentStyle = SubtitleStyle()
    private var controlsVisible = false
    private var dockTop = false
    private var liftAbovePrimarySpu = false
    private var controlsBottomReservedPx = 0f

    private var formattedText: CharSequence = ""
    private var strokeText: CharSequence = ""

    private var fillLayout: StaticLayout? = null
    private var strokeLayout: StaticLayout? = null
    private var cachedWidth = -1

    private val textPaint = TextPaint().apply {
        flags = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.DITHER_FLAG
        isFilterBitmap = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeMiter = 10f
        letterSpacing = 0.02f
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val lineRect = RectF()

    private var strokeWidthPx = 0f
    private var shadowBlurPx = 0f
    private var shadowOffsetPx = 0f
    private var shadowAlpha = 0
    private var edgeGuardPx = 0
    private var lineSpacingFactor = 1.15f
    private var effectiveSizePx = 0f

    init {
        setWillNotDraw(false)
    }

    fun applyStyle(
        style: SubtitleStyle,
        isControlsVisible: Boolean,
        dockTop: Boolean = style.secondaryTop,
        liftAbovePrimarySpu: Boolean = false,
        controlsBottomReservedPx: Float = 0f
    ) {
        val layoutChanged = this.currentStyle != style || this.dockTop != dockTop
        this.currentStyle = style
        this.controlsVisible = isControlsVisible
        this.dockTop = dockTop
        this.liftAbovePrimarySpu = liftAbovePrimarySpu
        this.controlsBottomReservedPx = controlsBottomReservedPx

        val metrics = resources.displayMetrics
        val density = metrics.density
        val scaleFactor = (style.scale / 100f).coerceIn(0.5f, 3.0f)
        val effectiveSizeSp = style.size.toFloat() * scaleFactor
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, effectiveSizeSp, metrics)
        effectiveSizePx = px
        textPaint.textSize = px

        lineSpacingFactor = (style.lineSpacing / 100f).coerceIn(0.8f, 2.0f) * 1.15f

        val baseResolved = SubtitleFonts.resolve(style.font)
        val resolved = style.legacyFontFamily?.let { baseResolved.copy(family = it) } ?: baseResolved
        val wantBold = style.bold || resolved.forceBold
        val tf = if (resolved.family.isNotBlank()) {
            Typeface.create(resolved.family, if (wantBold) Typeface.BOLD else Typeface.NORMAL)
        } else {
            if (wantBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        textPaint.typeface = tf
        // Only apply synthetic faux bold if the resolved Typeface isn't natively bold,
        // avoiding double-bold glyph width mismatches during Canvas stroke/fill passes.
        textPaint.isFakeBoldText = wantBold && !tf.isBold

        strokeWidthPx = if (style.outline > 0) {
            val visibleBorder = ((px * 0.030f) * style.outline).coerceAtLeast(1.3f * density)
            visibleBorder * 2f
        } else 0f

        shadowAlpha = style.shadow.coerceIn(0, 255)
        if (shadowAlpha > 0) {
            shadowBlurPx = 2.2f * density
            shadowOffsetPx = (px / 20f) * (style.shadowOffset / 2f)
        } else {
            shadowBlurPx = 0f
            shadowOffsetPx = 0f
        }

        edgeGuardPx = ceil(strokeWidthPx / 2f + shadowBlurPx + abs(shadowOffsetPx) + 4f * density).toInt()

        if (layoutChanged) {
            cachedWidth = -1
            fillLayout = null
            strokeLayout = null
        }
        invalidate()
    }

    fun setSubtitleText(text: CharSequence?) {
        val trimmed = text?.toString()?.trim()
        if (trimmed.isNullOrBlank() ||
            trimmed.matches(Regex("""^(?:[mnlbspc]\s+-?\d+(?:\s+-?\d+)*\s*)+$""", RegexOption.IGNORE_CASE))
        ) {
            if (visibility != GONE) visibility = GONE
            if (formattedText.isNotEmpty()) {
                formattedText = ""
                strokeText = ""
                fillLayout = null
                strokeLayout = null
                cachedWidth = -1
                invalidate()
            }
            return
        }

        val formatted: CharSequence = if (trimmed.contains('<')) {
            Html.fromHtml(trimmed.replace("\n", "<br/>"), Html.FROM_HTML_MODE_LEGACY).trim()
        } else {
            trimmed
        }

        if (visibility != VISIBLE) visibility = VISIBLE
        if (formatted.toString() != formattedText.toString() || formattedText.javaClass != formatted.javaClass) {
            formattedText = formatted
            strokeText = stripColorSpans(formatted)
            fillLayout = null
            strokeLayout = null
            cachedWidth = -1
            invalidate()
        }
    }

    private fun stripColorSpans(source: CharSequence): CharSequence {
        if (source !is Spanned) return source
        val fg = source.getSpans(0, source.length, ForegroundColorSpan::class.java)
        val bg = source.getSpans(0, source.length, BackgroundColorSpan::class.java)
        if (fg.isEmpty() && bg.isEmpty()) return source
        val ssb = SpannableStringBuilder(source)
        for (s in ssb.getSpans(0, ssb.length, ForegroundColorSpan::class.java)) ssb.removeSpan(s)
        for (s in ssb.getSpans(0, ssb.length, BackgroundColorSpan::class.java)) ssb.removeSpan(s)
        return ssb
    }

    private fun ensureLayouts(availWidth: Int) {
        if (fillLayout != null && cachedWidth == availWidth) return
        cachedWidth = availWidth
        val text = formattedText
        if (text.isEmpty()) {
            fillLayout = null
            strokeLayout = null
            return
        }
        val mainLayout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, availWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, lineSpacingFactor)
            .setIncludePad(false)
            .setMaxLines(12)
            .build()
        fillLayout = mainLayout
        strokeLayout = if (strokeText === formattedText) {
            mainLayout
        } else {
            StaticLayout.Builder.obtain(strokeText, 0, strokeText.length, textPaint, availWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, lineSpacingFactor)
                .setIncludePad(false)
                .setMaxLines(12)
                .build()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cachedWidth = -1
        fillLayout = null
        strokeLayout = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (formattedText.isEmpty() || width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val hPad = (24f * density).toInt() + edgeGuardPx
        val availWidth = (width - hPad * 2).coerceAtLeast(1)
        ensureLayouts(availWidth)

        val fLayout = fillLayout ?: return
        val sLayout = strokeLayout ?: fLayout

        val baseBottomPad = (18f * density) + edgeGuardPx
        val userMarginLift = (currentStyle.bottomMargin.coerceIn(0, 40) / 100f) * (height * 0.42f)

        val yStart = if (dockTop) {
            val topReserve = if (controlsVisible) (76f * density) else (16f * density)
            val maxY = (height - baseBottomPad - fLayout.height).coerceAtLeast(edgeGuardPx.toFloat())
            (topReserve + (currentStyle.bottomMargin.coerceIn(0, 40) / 100f) * (height * 0.25f) + edgeGuardPx)
                .coerceIn(edgeGuardPx.toFloat(), maxY)
        } else {
            val controlsLift = when {
                controlsBottomReservedPx > 0f && controlsVisible ->
                    (height - controlsBottomReservedPx + 10f * density).coerceIn(0f, height * 0.35f)
                controlsVisible -> height * 0.14f
                else -> 0f
            }
            val primarySpuLift = if (liftAbovePrimarySpu) {
                (effectiveSizePx * 2.15f).coerceAtLeast(52f * density)
            } else 0f
            val bottomReserved = baseBottomPad + maxOf(controlsLift, 0f) + userMarginLift + primarySpuLift
            val maxY = (height - baseBottomPad - fLayout.height).coerceAtLeast(edgeGuardPx.toFloat())
            (height - bottomReserved - fLayout.height).coerceIn(edgeGuardPx.toFloat(), maxY)
        }

        val saveCount = canvas.save()
        canvas.translate(hPad.toFloat(), yStart)

        // Pass 0: Tight rounded background boxes around non-empty lines
        if (currentStyle.background) {
            val bgAlpha = currentStyle.backgroundOpacity.coerceIn(0, 255)
            bgPaint.color = (currentStyle.backgroundColor and 0x00FFFFFF) or (bgAlpha shl 24)
            val padX = (10f * density) + (strokeWidthPx / 2f)
            val padY = (4f * density) + (strokeWidthPx / 2f)
            val corner = 6f * density
            for (i in 0 until fLayout.lineCount) {
                val left = fLayout.getLineLeft(i)
                val right = fLayout.getLineRight(i)
                if (right > left) {
                    lineRect.set(
                        left - padX,
                        fLayout.getLineTop(i) - padY,
                        right + padX,
                        fLayout.getLineBottom(i) + padY
                    )
                    canvas.drawRoundRect(lineRect, corner, corner, bgPaint)
                }
            }
        }

        // Pass 1: Drop shadow + outer stroke border on identical StaticLayout coordinates
        if (shadowAlpha > 0) {
            textPaint.setShadowLayer(
                shadowBlurPx,
                shadowOffsetPx,
                shadowOffsetPx,
                Color.argb(shadowAlpha, 0, 0, 0)
            )
        } else {
            textPaint.clearShadowLayer()
        }

        if (strokeWidthPx > 0f) {
            textPaint.style = Paint.Style.STROKE
            textPaint.strokeWidth = strokeWidthPx
            textPaint.color = (currentStyle.borderColor and 0x00FFFFFF) or
                    (currentStyle.borderOpacity.coerceIn(0, 255) shl 24)
            sLayout.draw(canvas)
        } else if (shadowAlpha > 0) {
            textPaint.style = Paint.Style.FILL
            textPaint.color = (currentStyle.color and 0x00FFFFFF) or (0xFF shl 24)
            fLayout.draw(canvas)
        }

        // Pass 2: Crisp foreground fill at identical pixel positions with shadow cleared
        textPaint.clearShadowLayer()
        textPaint.style = Paint.Style.FILL
        textPaint.color = (currentStyle.color and 0x00FFFFFF) or (0xFF shl 24)
        fLayout.draw(canvas)

        canvas.restoreToCount(saveCount)
    }
}
