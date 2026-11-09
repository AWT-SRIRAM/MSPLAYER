package app.msplayer.offline

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.MutableStateFlow

object GlassTheme {
    val enabled = MutableStateFlow(true)

    fun init(context: Context) {
        enabled.value = Store.prefs(context).getBoolean("liquid_glass", true)
    }

    fun setEnabled(context: Context, value: Boolean) {
        Store.prefs(context).edit().putBoolean("liquid_glass", value).apply()
        enabled.value = value
    }
}

object MotionSpec {
    val PressSpring = spring<Float>(dampingRatio = 0.58f, stiffness = 460f)
    val AlphaSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 420f)
    val MorphSpring = spring<Float>(dampingRatio = 0.80f, stiffness = 360f)
    val MorphRectSpring = spring<androidx.compose.ui.geometry.Rect>(dampingRatio = 0.82f, stiffness = 360f)
    val PanelOffsetSpring = spring<IntOffset>(dampingRatio = 0.84f, stiffness = 380f)
    val DpSpring = spring<Dp>(dampingRatio = 0.72f, stiffness = 420f)
    val ColorSpring = spring<Color>(dampingRatio = 0.88f, stiffness = 240f)

    fun animationsEnabled(context: Context): Boolean =
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }.getOrDefault(true)
}

@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) { MotionSpec.animationsEnabled(context) }
}

data class AdaptiveMoodColors(
    val primary: Color = Accent,
    val secondary: Color = Cyan,
)

/**
 * Samples embedded cover art on a downscaled 24x24 grid in <1ms to tint Liquid Glass glows cleanly.
 */
fun extractAdaptiveMoodColors(bitmap: Bitmap?): AdaptiveMoodColors {
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return AdaptiveMoodColors()
    return runCatching {
        val scaled = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        val hsv = FloatArray(3)
        var bestScore = -1f
        var bestColor = android.graphics.Color.rgb(0, 168, 255)
        var secondScore = -1f
        var secondColor = android.graphics.Color.rgb(44, 230, 210)
        var bestHue = -1f

        for (y in 0 until scaled.height) {
            for (x in 0 until scaled.width) {
                val px = scaled.getPixel(x, y)
                if (android.graphics.Color.alpha(px) < 180) continue
                android.graphics.Color.colorToHSV(px, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]
                if (sat < 0.22f || value < 0.25f) continue
                val vibrancy = sat * 1.4f + value * 0.7f - kotlin.math.abs(value - 0.68f) * 0.5f
                if (vibrancy > bestScore) {
                    secondScore = bestScore
                    secondColor = bestColor
                    bestScore = vibrancy
                    bestColor = px
                    bestHue = hue
                } else if (vibrancy > secondScore && kotlin.math.abs(hue - bestHue) > 28f) {
                    secondScore = vibrancy
                    secondColor = px
                }
            }
        }
        if (scaled !== bitmap) scaled.recycle()
        if (bestScore <= 0f) AdaptiveMoodColors()
        else {
            val outHsv = FloatArray(3)
            android.graphics.Color.colorToHSV(bestColor, outHsv)
            outHsv[1] = outHsv[1].coerceIn(0.55f, 0.92f)
            outHsv[2] = outHsv[2].coerceIn(0.68f, 1.0f)
            val p = Color(android.graphics.Color.HSVToColor(outHsv))

            android.graphics.Color.colorToHSV(secondColor, outHsv)
            outHsv[1] = outHsv[1].coerceIn(0.45f, 0.90f)
            outHsv[2] = outHsv[2].coerceIn(0.65f, 1.0f)
            val s = if (secondScore > 0f) Color(android.graphics.Color.HSVToColor(outHsv)) else p.copy(alpha = 0.85f)
            AdaptiveMoodColors(primary = p, secondary = s)
        }
    }.getOrDefault(AdaptiveMoodColors())
}

@Composable
fun rememberGlassEnabled(): State<Boolean> = GlassTheme.enabled.collectAsState()

fun liquidSpecularBrush(accentGlow: Float = 0.24f, accentColor: Color = Accent): Brush = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.46f),
        Color.White.copy(alpha = 0.12f),
        accentColor.copy(alpha = accentGlow),
    )
)

/**
 * Apple iOS–style spring press response + subtle specular light bloom while held down.
 */
fun Modifier.iosLiquidPress(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    scaleDown: Float = 0.94f,
    sheenAlpha: Float = 0.14f,
): Modifier = composed {
    val animationsOn = rememberAnimationsEnabled()
    val pressed by interactionSource.collectIsPressedAsState()
    val targetScale = if (enabled && pressed) scaleDown else 1f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (animationsOn) MotionSpec.PressSpring else spring(stiffness = Spring.StiffnessHigh),
        label = "iosPressScale",
    )
    val sheen by animateFloatAsState(
        targetValue = if (enabled && pressed) sheenAlpha else 0f,
        animationSpec = MotionSpec.AlphaSpring,
        label = "iosPressSheen",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (sheen > 0.004f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = sheen * 1.15f),
                            Color.White.copy(alpha = sheen * 0.35f),
                        )
                    )
                )
            }
        }
}

@Composable
fun LiquidGlassBackdropBackground(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    primaryGlow: Color = Accent,
    secondaryGlow: Color = Color(0xFF2CE6D2),
) {
    val pColor by animateColorAsState(primaryGlow, MotionSpec.ColorSpring, label = "primaryGlow")
    val sColor by animateColorAsState(secondaryGlow, MotionSpec.ColorSpring, label = "secondaryGlow")

    if (enabled) {
        Box(
            modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF040810),
                            Color(0xFF020408),
                            Color(0xFF050B14),
                        ),
                        start = Offset.Zero,
                        end = Offset(1200f, 2400f),
                    )
                )
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                pColor.copy(alpha = 0.28f),
                                pColor.copy(alpha = 0.09f),
                                Color.Transparent,
                            ),
                            center = Offset(900f, 340f),
                            radius = 960f,
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                sColor.copy(alpha = 0.22f),
                                sColor.copy(alpha = 0.07f),
                                Color.Transparent,
                            ),
                            center = Offset(160f, 1950f),
                            radius = 920f,
                        )
                    )
            )
        }
    } else {
        Box(modifier.fillMaxSize().background(Color.Black))
    }
}

fun Modifier.liquidGlassSurface(
    backdrop: LayerBackdrop?,
    enabled: Boolean,
    shape: CornerBasedShape = RoundedCornerShape(22.dp),
    tint: Color = Color(0x5E0D1520),
    blurRadius: Dp = 14.dp,
    refractionHeight: Dp = 12.dp,
    refractionAmount: Dp = 24.dp,
    depthEffect: Boolean = true,
    chromaticAberration: Boolean = false,
    shadow: Shadow? = Shadow(radius = 18.dp, color = Color.Black.copy(alpha = 0.42f)),
    fallbackColor: Color = Color(0xFF101216),
    fallbackBorderColor: Color = Color.White.copy(alpha = 0.14f),
): Modifier {
    if (enabled && backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blurRadius.toPx())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    lens(
                        refractionHeight = refractionHeight.toPx(),
                        refractionAmount = refractionAmount.toPx(),
                        depthEffect = depthEffect,
                        chromaticAberration = chromaticAberration,
                    )
                }
            },
            highlight = {
                Highlight(
                    width = 0.9.dp,
                    blurRadius = 0.6.dp,
                    alpha = 0.85f,
                    style = HighlightStyle.Default,
                )
            },
            shadow = { shadow },
            onDrawSurface = { drawRect(tint) },
        )
    }
    return this
        .clip(shape)
        .background(if (enabled) tint else fallbackColor, shape)
        .border(
            width = 0.85.dp,
            brush = if (enabled) liquidSpecularBrush() else Brush.linearGradient(listOf(fallbackBorderColor, fallbackBorderColor)),
            shape = shape,
        )
}

fun Modifier.frostedGlassChrome(
    enabled: Boolean,
    shape: Shape = RoundedCornerShape(16.dp),
    tint: Color = Color(0x780C121C),
    fallbackColor: Color = Color.Black.copy(alpha = 0.50f),
    borderWidth: Dp = 1.dp,
): Modifier = if (enabled) {
    this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.10f).compositeOver(tint),
                    tint,
                    Color(0x94070B11),
                )
            ),
            shape,
        )
        .border(borderWidth, liquidSpecularBrush(), shape)
} else {
    this
        .clip(shape)
        .background(fallbackColor, shape)
        .border(borderWidth, Color.White.copy(alpha = 0.18f), shape)
}
