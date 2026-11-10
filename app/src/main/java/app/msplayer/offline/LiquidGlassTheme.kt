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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

fun liquidSpecularBrush(accentGlow: Float = 0.28f, accentColor: Color = Accent): Brush = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.56f),
        Color.White.copy(alpha = 0.16f),
        Color.White.copy(alpha = 0.06f),
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
    tint: Color = Color(0x7E0B131F),
    fallbackColor: Color = Color.Black.copy(alpha = 0.62f),
    borderWidth: Dp = 1.dp,
): Modifier = if (enabled) {
    this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.13f).compositeOver(tint),
                    tint,
                    Color(0xFF04080F).copy(alpha = (tint.alpha + 0.14f).coerceIn(0.45f, 0.96f)).compositeOver(tint),
                )
            ),
            shape,
        )
        .drawWithContent {
            drawContent()
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.26f),
                        Color.White.copy(alpha = 0.08f),
                        Color.Transparent,
                    )
                ),
                start = Offset(size.width * 0.08f, 1.dp.toPx()),
                end = Offset(size.width * 0.92f, 1.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
        }
        .border(borderWidth, liquidSpecularBrush(), shape)
} else {
    this
        .clip(shape)
        .background(fallbackColor, shape)
        .border(borderWidth, Color.White.copy(alpha = 0.18f), shape)
}

@Composable
fun LiquidGlassPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val glassEnabled by rememberGlassEnabled()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .frostedGlassChrome(
                enabled = glassEnabled,
                shape = RoundedCornerShape(18.dp),
                tint = Color(0xEB070D17),
                fallbackColor = Color(0xFF131922),
            ),
        offset = offset,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = null,
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            content()
        }
    }
}

@Composable
fun LiquidGlassPopupMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(min = 168.dp)
            .clip(RoundedCornerShape(10.dp))
            .iosLiquidPress(interactionSource, enabled = enabled, scaleDown = 0.97f, sheenAlpha = 0.10f)
            .clickable(enabled = enabled, interactionSource = interactionSource, indication = androidx.compose.material3.ripple(), onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Box(Modifier.padding(end = 10.dp)) { leadingIcon() }
        }
        Box(Modifier.weight(1f)) {
            ProvideTextStyle(
                TextStyle(
                    color = if (enabled) Color.White else Color.White.copy(alpha = 0.42f),
                    fontSize = 14.sp,
                )
            ) {
                text()
            }
        }
        if (trailingIcon != null) {
            Box(Modifier.padding(start = 10.dp)) { trailingIcon() }
        }
    }
}

@Composable
fun LiquidGlassAlertDialog(
    onDismissRequest: () -> Unit,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    immersiveBlur: Boolean = false,
) {
    val glassEnabled by rememberGlassEnabled()
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        if (immersiveBlur) ImmersivePlayerDialog()
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val boundedHeight = (maxHeight - 16.dp).coerceAtLeast(220.dp)
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(0.94f)
                    .heightIn(max = boundedHeight)
                    .frostedGlassChrome(
                        enabled = glassEnabled,
                        shape = RoundedCornerShape(24.dp),
                        tint = Color(0x940B131F),
                        fallbackColor = Color(0xFF141A24),
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    if (title != null) {
                        ProvideTextStyle(TextStyle(color = Color.White, fontSize = 18.sp)) {
                            Box(Modifier.padding(bottom = 10.dp)) { title() }
                        }
                    }
                    if (text != null) {
                        ProvideTextStyle(TextStyle(color = Color.White.copy(alpha = 0.88f), fontSize = 14.sp)) {
                            Box(Modifier.weight(1f, fill = false)) { text() }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.weight(1f))
                        if (dismissButton != null) {
                            dismissButton()
                            Spacer(Modifier.width(6.dp))
                        }
                        confirmButton()
                    }
                }
            }
        }
    }
}
