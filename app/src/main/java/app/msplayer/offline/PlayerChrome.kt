package app.msplayer.offline

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.MediaPlayer
import kotlin.math.roundToInt

sealed interface PlayerFeedback {
    data class Level(val brightness: Boolean, val percent: Int) : PlayerFeedback
    data class Seek(val target: String, val difference: String) : PlayerFeedback
    data class Zoom(val percent: Int) : PlayerFeedback
    data class AspectRatio(val label: String) : PlayerFeedback
    data class Speed(val rate: Float, val changedAt: Long = 0L) : PlayerFeedback
    data class SubtitlePosition(val percent: Int) : PlayerFeedback
    data class SubtitleScale(val percent: Int) : PlayerFeedback
}

@Composable
fun PlayerFeedbackOverlay(value: PlayerFeedback, modifier: Modifier = Modifier) {
    val glassEnabled by rememberGlassEnabled()
    when (value) {
        is PlayerFeedback.Level -> {
            val alignment = if (value.brightness) Alignment.CenterEnd else Alignment.CenterStart
            val maximum = if (value.brightness) 100 else 200
            val progress by animateFloatAsState(value.percent.toFloat(), MotionSpec.PressSpring, label = "Gesture level")
            Column(
                modifier.fillMaxSize().safeDrawingPadding().wrapContentSize(alignment).padding(horizontal = 24.dp)
                    .frostedGlassChrome(glassEnabled, RoundedCornerShape(20.dp), tint = Color(0x6E0B121D), fallbackColor = Color.Black.copy(alpha = .58f))
                    .padding(horizontal = 11.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("${value.percent}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(5.dp).height(104.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .24f))) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight((progress / maximum).coerceIn(0f, 1f)).align(Alignment.BottomCenter).background(Accent))
                    if (!value.brightness && progress > 100) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(((progress - 100) / 200).coerceIn(0f, .5f)).align(Alignment.BottomCenter).offset(y = (-52).dp).background(Color(0xFFFFAB40)))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Icon(
                    if (value.brightness) Icons.Default.Brightness6 else Icons.Default.VolumeUp,
                    if (value.brightness) "Brightness" else "Volume",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        is PlayerFeedback.Speed -> SpeedFeedback(value.rate, value.changedAt, modifier)
        is PlayerFeedback.Seek -> {
            Row(
                modifier.wrapContentSize(Alignment.Center)
                    .frostedGlassChrome(glassEnabled, RoundedCornerShape(18.dp), fallbackColor = Color.Black.copy(alpha = .72f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value.target, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(12.dp))
                Text(value.difference, color = Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        is PlayerFeedback.Zoom -> {
            Text(
                "Zoom ${value.percent}%",
                modifier = modifier.wrapContentSize(Alignment.Center)
                    .frostedGlassChrome(glassEnabled, RoundedCornerShape(16.dp), fallbackColor = Color.Black.copy(alpha = .68f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        is PlayerFeedback.AspectRatio -> {
            Row(
                modifier.wrapContentSize(Alignment.Center)
                    .frostedGlassChrome(glassEnabled, RoundedCornerShape(18.dp), fallbackColor = Color.Black.copy(alpha = .74f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.AspectRatio, null, tint = Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(value.label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        is PlayerFeedback.SubtitlePosition -> {
            Row(
                modifier.wrapContentSize(Alignment.Center)
                    .frostedGlassChrome(glassEnabled, RoundedCornerShape(18.dp), fallbackColor = Color.Black.copy(alpha = .75f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Subtitles, null, tint = Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Subtitle Position: ${value.percent}%", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
        is PlayerFeedback.SubtitleScale -> {
            Row(
                modifier.wrapContentSize(Alignment.Center)
                    .frostedGlassChrome(glassEnabled, RoundedCornerShape(18.dp), fallbackColor = Color.Black.copy(alpha = .75f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.FormatSize, null, tint = Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Subtitle Size: ${value.percent}%", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun PlayerMenuDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
) {
    val glassEnabled by rememberGlassEnabled()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Dialog(onDismissRequest = onDismissRequest) {
        ImmersivePlayerDialog()
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.92f, animationSpec = MotionSpec.MorphSpring),
        ) {
            Column(
                Modifier.widthIn(max = 400.dp).fillMaxWidth()
                    .frostedGlassChrome(glassEnabled, RoundedCornerShape(if (glassEnabled) 22.dp else 10.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                ProvideTextStyle(androidx.compose.ui.text.TextStyle(color = Color.White)) {
                    Box(Modifier.padding(bottom = 12.dp)) { title() }
                    Box(Modifier.weight(1f, fill = false).heightIn(max = 440.dp)) { text() }
                    Box(Modifier.align(Alignment.End)) { confirmButton() }
                }
            }
        }
    }
}

@Composable
fun PlayerSidePanel(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val glassEnabled by rememberGlassEnabled()
    val animationsOn = rememberAnimationsEnabled()
    var visible by remember { mutableStateOf(!animationsOn) }
    val dragOffsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { visible = true }

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersivePlayerDialog()
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Transparent)) {
            val width = if (maxWidth > maxHeight) (maxWidth * .42f).coerceAtMost(440.dp) else maxWidth
            Box(Modifier.fillMaxSize().clickable(onClick = dismiss))
            val panelShape = RoundedCornerShape(topStart = if (glassEnabled) 24.dp else 0.dp, bottomStart = if (glassEnabled) 24.dp else 0.dp)
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally(animationSpec = MotionSpec.PanelOffsetSpring) { it } + fadeIn(tween(150)),
            ) {
                Column(
                    Modifier
                        .width(width)
                        .fillMaxHeight()
                        .offset { IntOffset(dragOffsetX.value.roundToInt(), 0) }
                        .pointerInput(width) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    scope.launch {
                                        if (dragOffsetX.value > 84.dp.toPx()) {
                                            dismiss()
                                        } else {
                                            dragOffsetX.animateTo(0f, MotionSpec.PressSpring)
                                        }
                                    }
                                },
                                onDragCancel = {
                                    scope.launch { dragOffsetX.animateTo(0f, MotionSpec.PressSpring) }
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    val next = (dragOffsetX.value + dragAmount).coerceAtLeast(0f)
                                    scope.launch { dragOffsetX.snapTo(next) }
                                },
                            )
                        }
                        .frostedGlassChrome(
                            enabled = glassEnabled,
                            shape = panelShape,
                            tint = Color(0x660C1420),
                            fallbackColor = Color.Black.copy(alpha = 0.28f),
                            borderWidth = if (glassEnabled) 1.dp else 0.dp,
                        )
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    content()
                }
            }
        }
    }
}

@Composable
fun TrackChoice(name: String, selected: Boolean, subtitle: Boolean = false, onClick: () -> Unit) {
    val pressSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .iosLiquidPress(pressSource, scaleDown = 0.97f, sheenAlpha = 0.10f)
            .clickable(interactionSource = pressSource, indication = ripple(), onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (subtitle) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF2196F3),
                    uncheckedColor = Color.White.copy(alpha = 0.6f),
                    checkmarkColor = Color.White
                )
            )
        } else {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = Color(0xFF2196F3),
                    unselectedColor = Color.White.copy(alpha = 0.6f)
                )
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun AudioEffectsControls(service: PlaybackService) {
    val settings by service.effectSettings.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val manager = remember { context.getSystemService(AudioManager::class.java) }
    var outputs by remember { mutableStateOf("") }
    DisposableEffect(manager) {
        fun update() {
            val names = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET) }
                .map { it.productName.toString() }.distinct()
            outputs = if (names.isEmpty()) "Built-in output" else "Connected: ${names.joinToString()}"
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = update()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = update()
        }
        update()
        manager.registerAudioDeviceCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { manager.unregisterAudioDeviceCallback(callback) }
    }
    Row {
        TextButton(onClick = { tab = 0 }, modifier = Modifier.weight(1f)) { Text("Audio Effect", color = if (tab == 0) Color.White else Muted) }
        TextButton(onClick = { tab = 1 }, modifier = Modifier.weight(1f)) { Text("Equalizer", color = if (tab == 1) Color.White else Muted) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(2.5.dp)) {
        val halfWidth = maxWidth / 2f
        val offset by animateDpAsState(if (tab == 0) 0.dp else halfWidth, MotionSpec.DpSpring, label = "effectTabIndicator")
        Box(
            Modifier
                .offset(x = offset)
                .width(halfWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(Accent)
        )
    }
    Text(outputs, color = Color.LightGray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
    if (tab == 0) {
        AudioEffectSettings.presets.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { name ->
                    val pressSource = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = { service.effects(settings.copy(preset = name)) },
                        interactionSource = pressSource,
                        modifier = Modifier.weight(1f).heightIn(min = 76.dp).iosLiquidPress(pressSource, scaleDown = 0.93f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (settings.preset == name) Color(0xBB1756A8) else Color.Transparent),
                        contentPadding = PaddingValues(6.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                when (name) {
                                    "Clarity" -> Icons.Default.Mic
                                    "Bass Boost" -> Icons.Default.Speaker
                                    "Treble Boost" -> Icons.Default.SurroundSound
                                    "Movie" -> Icons.Default.Movie
                                    "Music" -> Icons.Default.MusicNote
                                    else -> Icons.Default.GraphicEq
                                },
                                null,
                                tint = Color.White,
                            )
                            Spacer(Modifier.height(7.dp))
                            Text(name, fontSize = 13.sp, color = Color.White)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    } else {
        Text("${settings.preset} · ${settings.preamp().toInt()} dB headroom", color = Muted, fontSize = 13.sp)
        repeat(MediaPlayer.Equalizer.getBandCount()) { index ->
            val gains = settings.gains()
            val value = gains.getOrElse(index) { 0f }
            val frequency = MediaPlayer.Equalizer.getBandFrequency(index)
            Text("${if (frequency >= 1000) "${frequency / 1000} kHz" else "${frequency.toInt()} Hz"}   ${value.toInt()} dB", color = Color.White, fontSize = 13.sp)
            Slider(value = value, valueRange = -12f..12f, onValueChange = { gain ->
                service.effects(AudioEffectSettings("Custom", gains.toMutableList().also { it[index] = gain }))
            })
        }
        TextButton(onClick = { service.effects(AudioEffectSettings()) }) { Text("Reset to Original") }
    }
}

@Composable
fun ImmersivePlayerDialog() {
    val glassEnabled by rememberGlassEnabled()
    val view = LocalView.current
    DisposableEffect(view, glassEnabled) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (glassEnabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.setDimAmount(0.18f)
                    window.attributes = window.attributes.apply { blurBehindRadius = 36 }
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                }
            }
        }
        onDispose { }
    }
}

@Composable
fun SpeedFeedback(rate: Float, changedAt: Long = 0L, modifier: Modifier = Modifier) {
    val glassEnabled by rememberGlassEnabled()
    val innerStops = listOf(.5f, 1f, 1.5f, 2f, 2.5f, 3f)
    val animatedFraction by animateFloatAsState(
        targetValue = GestureRules.fraction(rate),
        animationSpec = MotionSpec.PressSpring,
        label = "speedFraction",
    )

    var showRuler by remember { mutableStateOf(true) }
    LaunchedEffect(rate, changedAt) {
        showRuler = true
        delay(1300L)
        showRuler = false
    }

    val animationsOn = rememberAnimationsEnabled()
    val rawMorph by animateFloatAsState(
        targetValue = if (showRuler) 1f else 0f,
        animationSpec = if (animationsOn) spring(dampingRatio = 0.92f, stiffness = 340f) else tween(1),
        label = "speedCapsuleMorph",
    )
    val morph = rawMorph.coerceIn(0f, 1f)
    val density = LocalDensity.current

    val rateText = if ((rate * 100).roundToInt() % 25 == 0 && (rate * 100).roundToInt() % 50 != 0) {
        String.format(java.util.Locale.ROOT, "%.2f", rate)
    } else {
        String.format(java.util.Locale.ROOT, "%.1f", rate)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(top = 20.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val availableWidth = maxWidth
        val expandedWidthDp = if (availableWidth > 500.dp) {
            (availableWidth * 0.58f).coerceIn(400.dp, 500.dp)
        } else {
            availableWidth.coerceAtMost(420.dp)
        }
        val expandedWidthPx = with(density) { expandedWidthDp.roundToPx() }
        val expandedHPadPx = with(density) { 18.dp.roundToPx() }
        val expandedTopPadPx = with(density) { 10.dp.roundToPx() }
        val expandedBottomPadPx = with(density) { 8.dp.roundToPx() }
        val rulerStatusGapPx = with(density) { 4.dp.roundToPx() }
        val compactHPadPx = with(density) { 18.dp.roundToPx() }
        val compactVPadPx = with(density) { 9.dp.roundToPx() }

        val capsuleCornerDp = (22f + (30f - 22f) * morph).dp
        val tintColor = androidx.compose.ui.graphics.lerp(
            Color(0x88101622),
            Color(0x740B121C),
            morph,
        )
        val rulerAlpha = ((morph - 0.52f) / 0.48f).coerceIn(0f, 1f)
        val rulerScale = 0.88f + 0.12f * rulerAlpha
        val rulerShiftPx = (morph - 1f) * with(density) { 8.dp.toPx() }

        Layout(
            modifier = Modifier.frostedGlassChrome(
                enabled = glassEnabled,
                shape = RoundedCornerShape(capsuleCornerDp),
                tint = tintColor,
                fallbackColor = Color.Black.copy(alpha = 0.70f),
            ),
            content = {
                val endGapPx = with(density) { 10.dp.roundToPx() }
                val labelToTrackGapPx = with(density) { 4.dp.roundToPx() }
                val trackHeightPx = with(density) { 20.dp.roundToPx() }
                Layout(
                    content = {
                        innerStops.forEach { stop ->
                            val isActive = kotlin.math.abs(stop - rate) < 0.08f
                            Text(
                                text = "${String.format(java.util.Locale.ROOT, "%.1f", stop)}x",
                                color = if (isActive) Accent else Color.White,
                                fontSize = if (isActive) 12.sp else 11.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        val leftActive = kotlin.math.abs(0.25f - rate) < 0.08f
                        Text(
                            text = "0.25x",
                            color = if (leftActive) Accent else Color.White,
                            fontSize = if (leftActive) 11.5f.sp else 11.sp,
                            fontWeight = if (leftActive) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                        )
                        val rightActive = kotlin.math.abs(4.0f - rate) < 0.08f
                        Text(
                            text = "4.0x",
                            color = if (rightActive) Accent else Color.White,
                            fontSize = if (rightActive) 11.5f.sp else 11.sp,
                            fontWeight = if (rightActive) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Canvas(Modifier) {
                            val y = size.height / 2f
                            drawLine(
                                color = Color.White.copy(alpha = 0.36f),
                                start = androidx.compose.ui.geometry.Offset(0f, y),
                                end = androidx.compose.ui.geometry.Offset(size.width, y),
                                strokeWidth = 2.5.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            )
                            GestureRules.rulerStops.forEach { stop ->
                                val cx = size.width * GestureRules.fraction(stop)
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.90f),
                                    radius = 2.dp.toPx(),
                                    center = androidx.compose.ui.geometry.Offset(cx, y),
                                )
                            }
                            val thumbX = size.width * animatedFraction.coerceIn(0f, 1f)
                            drawCircle(
                                color = Accent.copy(alpha = 0.32f),
                                radius = 9.dp.toPx(),
                                center = androidx.compose.ui.geometry.Offset(thumbX, y),
                            )
                            drawCircle(
                                color = Accent,
                                radius = 5.5.dp.toPx(),
                                center = androidx.compose.ui.geometry.Offset(thumbX, y),
                            )
                        }
                    },
                    modifier = Modifier.graphicsLayer {
                        alpha = rulerAlpha
                        translationY = rulerShiftPx
                        scaleX = rulerScale
                        scaleY = rulerScale
                    },
                ) { measurables, constraints ->
                    val labelCs = constraints.copy(minWidth = 0, minHeight = 0)
                    val innerPlaceables = measurables.subList(0, innerStops.size).map { it.measure(labelCs) }
                    val leftLabel = measurables[innerStops.size].measure(labelCs)
                    val rightLabel = measurables[innerStops.size + 1].measure(labelCs)

                    val trackLeft = leftLabel.width + endGapPx
                    val trackRight = (constraints.maxWidth - rightLabel.width - endGapPx).coerceAtLeast(trackLeft + 48)
                    val trackWidth = trackRight - trackLeft

                    val canvasPlaceable = measurables[innerStops.size + 2].measure(
                        Constraints.fixed(trackWidth, trackHeightPx)
                    )

                    val topBandHeight = innerPlaceables.maxOfOrNull { it.height } ?: 0
                    val bottomBandHeight = maxOf(canvasPlaceable.height, leftLabel.height, rightLabel.height)
                    val totalW = constraints.maxWidth
                    val totalH = topBandHeight + labelToTrackGapPx + bottomBandHeight

                    layout(totalW, totalH) {
                        innerPlaceables.forEachIndexed { idx, placeable ->
                            val stop = innerStops[idx]
                            val dotX = trackLeft + trackWidth * GestureRules.fraction(stop)
                            val x = (dotX - placeable.width / 2f).roundToInt()
                                .coerceIn(0, (totalW - placeable.width).coerceAtLeast(0))
                            val y = (topBandHeight - placeable.height) / 2
                            placeable.placeRelative(x, y)
                        }
                        val trackTop = topBandHeight + labelToTrackGapPx + (bottomBandHeight - canvasPlaceable.height) / 2
                        val trackCenterY = trackTop + canvasPlaceable.height / 2
                        leftLabel.placeRelative(0, trackCenterY - leftLabel.height / 2)
                        rightLabel.placeRelative(totalW - rightLabel.width, trackCenterY - rightLabel.height / 2)
                        canvasPlaceable.placeRelative(trackLeft, trackTop)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(
                        if (rate > 1f) Icons.Default.FastForward else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "${rateText}x Speed Playing",
                        color = Color.White,
                        fontSize = 13.5f.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            },
        ) { measurables, constraints ->
            val maxRulerWidth = (expandedWidthPx - expandedHPadPx * 2).coerceAtLeast(180)
            val rulerPlaceable = measurables[0].measure(
                Constraints(
                    minWidth = maxRulerWidth,
                    maxWidth = maxRulerWidth,
                    minHeight = 0,
                    maxHeight = constraints.maxHeight,
                )
            )
            val statusPlaceable = measurables[1].measure(
                constraints.copy(minWidth = 0, minHeight = 0)
            )

            val expandedW = maxOf(expandedWidthPx, statusPlaceable.width + expandedHPadPx * 2)
            val expandedH = expandedTopPadPx + rulerPlaceable.height + rulerStatusGapPx + statusPlaceable.height + expandedBottomPadPx
            val compactW = statusPlaceable.width + compactHPadPx * 2
            val compactH = statusPlaceable.height + compactVPadPx * 2

            val currentW = (compactW + (expandedW - compactW) * morph).roundToInt().coerceAtLeast(1)
            val currentH = (compactH + (expandedH - compactH) * morph).roundToInt().coerceAtLeast(1)

            layout(currentW, currentH) {
                if (rulerAlpha > 0.01f) {
                    rulerPlaceable.placeRelative(
                        x = (currentW - rulerPlaceable.width) / 2,
                        y = expandedTopPadPx,
                    )
                }
                val compactStatusY = (currentH - statusPlaceable.height) / 2f
                val expandedStatusY = (expandedH - expandedBottomPadPx - statusPlaceable.height).toFloat()
                val statusY = (compactStatusY + (expandedStatusY - compactStatusY) * morph).roundToInt()
                statusPlaceable.placeRelative(
                    x = (currentW - statusPlaceable.width) / 2,
                    y = statusY,
                )
            }
        }
    }
}

@Composable
fun PlayerFeedbackHost(value: PlayerFeedback?) {
    var last by remember { mutableStateOf<PlayerFeedback?>(null) }
    if (value != null) SideEffect { last = value }
    AnimatedVisibility(
        visible = value != null,
        enter = fadeIn(tween(110)) + scaleIn(initialScale = 0.94f, animationSpec = MotionSpec.MorphSpring),
        exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.96f),
    ) {
        (value ?: last)?.let { PlayerFeedbackOverlay(it, Modifier.fillMaxSize()) }
    }
}
