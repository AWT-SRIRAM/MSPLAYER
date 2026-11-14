package app.msplayer.offline

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

sealed interface PlayerFeedback {
    data class Level(val brightness: Boolean, val percent: Int) : PlayerFeedback
    data class Seek(val target: String, val difference: String, val side: Int = 0, val tapCount: Int = 1) : PlayerFeedback
    data class PlayPause(val playing: Boolean) : PlayerFeedback
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
            val pulse = remember { Animatable(1f) }
            val chevronSlide = remember { Animatable(0f) }
            LaunchedEffect(value.tapCount, value.difference, value.side) {
                pulse.snapTo(if (value.side != 0) 0.84f else 1.13f)
                chevronSlide.snapTo(if (value.side > 0) 10f else if (value.side < 0) -10f else 0f)
                launch { pulse.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = 420f)) }
                launch { chevronSlide.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 380f)) }
            }
            if (value.side != 0) {
                val isForward = value.side > 0
                val shape = if (isForward) {
                    RoundedCornerShape(topStartPercent = 100, bottomStartPercent = 100)
                } else {
                    RoundedCornerShape(topEndPercent = 100, bottomEndPercent = 100)
                }
                val align = if (isForward) Alignment.CenterEnd else Alignment.CenterStart
                val rawSec = value.difference.filter { it.isDigit() }.ifBlank { "10" }

                Box(
                    modifier = modifier.fillMaxSize(),
                    contentAlignment = align,
                ) {
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = pulse.value
                                scaleY = pulse.value
                                transformOrigin = TransformOrigin(if (isForward) 1f else 0f, 0.5f)
                            }
                            .clip(shape)
                            .frostedGlassChrome(
                                enabled = glassEnabled,
                                shape = shape,
                                tint = Color(0x35FFFFFF),
                                fallbackColor = Color.White.copy(alpha = 0.18f),
                            )
                            .background(
                                Brush.horizontalGradient(
                                    if (isForward) listOf(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.24f))
                                    else listOf(Color.White.copy(alpha = 0.24f), Color.White.copy(alpha = 0.04f))
                                )
                            )
                            .width(180.dp)
                            .height(320.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        ) {
                            Icon(
                                if (isForward) Icons.Default.FastForward else Icons.Default.FastRewind,
                                contentDescription = if (isForward) "Fast Forward" else "Fast Rewind",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(36.dp)
                                    .graphicsLayer {
                                        translationX = chevronSlide.value
                                    },
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "${rawSec}s",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        Modifier
                            .graphicsLayer {
                                scaleX = pulse.value
                                scaleY = pulse.value
                            }
                            .frostedGlassChrome(glassEnabled, RoundedCornerShape(18.dp), fallbackColor = Color.Black.copy(alpha = .74f))
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(value.difference, color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(value.target, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        is PlayerFeedback.PlayPause -> {
            Row(
                modifier.wrapContentSize(Alignment.Center)
                    .frostedGlassChrome(glassEnabled, RoundedCornerShape(18.dp), fallbackColor = Color.Black.copy(alpha = .74f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    if (value.playing) Icons.Default.PlayArrow else Icons.Default.Pause,
                    null,
                    tint = Accent,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    if (value.playing) "Playing" else "Paused",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
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
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Accent,
                secondary = Cyan,
                background = Color.Black,
                surface = Color(0xFF131A26),
                onBackground = Color.White,
                onSurface = Color.White,
            )
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.92f, animationSpec = MotionSpec.MorphSpring),
                ) {
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val maxH = (maxHeight - 24.dp).coerceAtLeast(220.dp)
                        Column(
                            Modifier.widthIn(max = 400.dp).fillMaxWidth().heightIn(max = maxH)
                                .frostedGlassChrome(glassEnabled, RoundedCornerShape(if (glassEnabled) 22.dp else 10.dp))
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                        ) {
                            ProvideTextStyle(androidx.compose.ui.text.TextStyle(color = Color.White)) {
                                Box(Modifier.padding(bottom = 12.dp)) { title() }
                                Box(Modifier.weight(1f, fill = false)) { text() }
                                Box(Modifier.align(Alignment.End)) { confirmButton() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerSidePanel(
    title: String,
    dismiss: () -> Unit,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glassEnabled by rememberGlassEnabled()
    val animationsOn = rememberAnimationsEnabled()
    var visible by remember { mutableStateOf(!animationsOn) }
    val dragOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { visible = true }

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersivePlayerDialog()
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Accent,
                secondary = Cyan,
                background = Color.Black,
                surface = Color(0xFF131A26),
                onBackground = Color.White,
                onSurface = Color.White,
            )
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f))) {
                    val isPortrait = maxHeight >= maxWidth
                    val panelWidth = if (isPortrait) maxWidth else (maxWidth * .44f).coerceIn(300.dp, 460.dp)
                    val maxSheetHeight = (maxHeight * 0.72f).coerceAtLeast(240.dp)
                    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = dismiss))
                    val panelShape = if (isPortrait) {
                        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    } else {
                        RoundedCornerShape(topStart = if (glassEnabled) 24.dp else 16.dp, bottomStart = if (glassEnabled) 24.dp else 16.dp)
                    }
                    AnimatedVisibility(
                        visible = visible,
                        modifier = Modifier.align(if (isPortrait) Alignment.BottomCenter else Alignment.CenterEnd),
                        enter = if (isPortrait) {
                            slideInVertically(animationSpec = MotionSpec.PanelOffsetSpring) { it } + fadeIn(tween(150))
                        } else {
                            slideInHorizontally(animationSpec = MotionSpec.PanelOffsetSpring) { it } + fadeIn(tween(150))
                        },
                    ) {
                        val sizeModifier = if (isPortrait) {
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = maxSheetHeight)
                        } else {
                            Modifier
                                .width(panelWidth)
                                .fillMaxHeight()
                        }
                        val dragModifier = if (isPortrait) {
                            Modifier
                                .offset { IntOffset(0, dragOffset.value.roundToInt()) }
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures(
                                        onDragEnd = {
                                            scope.launch {
                                                if (dragOffset.value > 72.dp.toPx()) dismiss()
                                                else dragOffset.animateTo(0f, MotionSpec.PressSpring)
                                            }
                                        },
                                        onDragCancel = {
                                            scope.launch { dragOffset.animateTo(0f, MotionSpec.PressSpring) }
                                        },
                                        onVerticalDrag = { _, dragAmount ->
                                            val next = (dragOffset.value + dragAmount).coerceAtLeast(0f)
                                            scope.launch { dragOffset.snapTo(next) }
                                        },
                                    )
                                }
                        } else {
                            Modifier
                                .offset { IntOffset(dragOffset.value.roundToInt(), 0) }
                                .pointerInput(panelWidth) {
                                    detectHorizontalDragGestures(
                                        onDragEnd = {
                                            scope.launch {
                                                if (dragOffset.value > 84.dp.toPx()) dismiss()
                                                else dragOffset.animateTo(0f, MotionSpec.PressSpring)
                                            }
                                        },
                                        onDragCancel = {
                                            scope.launch { dragOffset.animateTo(0f, MotionSpec.PressSpring) }
                                        },
                                        onHorizontalDrag = { _, dragAmount ->
                                            val next = (dragOffset.value + dragAmount).coerceAtLeast(0f)
                                            scope.launch { dragOffset.snapTo(next) }
                                        },
                                    )
                                }
                        }
                        val insetsModifier = if (isPortrait) {
                            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).imePadding()
                        } else {
                            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End))
                        }
                        Column(
                            Modifier
                                .then(sizeModifier)
                                .then(dragModifier)
                                .frostedGlassChrome(
                                    enabled = glassEnabled,
                                    shape = panelShape,
                                    tint = Color(0xC40C1420),
                                    fallbackColor = Color(0xEB101622),
                                    borderWidth = if (glassEnabled) 1.dp else 0.8.dp,
                                    fallbackBorderColor = Color.White.copy(alpha = 0.14f),
                                )
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                                .then(insetsModifier)
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        ) {
                            if (isPortrait) {
                                Box(
                                    Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .padding(bottom = 10.dp)
                                        .width(38.dp)
                                        .height(4.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.34f)),
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                IconButton(onClick = dismiss, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, "Close $title", tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(18.dp))
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            if (scrollable) {
                                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    content()
                                }
                            } else {
                                Column(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                                    content()
                                }
                            }
                        }
                    }
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
    val glassEnabled by rememberGlassEnabled()
    var tab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val manager = remember { context.getSystemService(AudioManager::class.java) }
    var outputs by remember { mutableStateOf("") }
    DisposableEffect(manager) {
        fun update() {
            val names = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET) }
                .map { it.productName.toString() }.distinct()
            outputs = if (names.isEmpty()) "Built-in Speaker" else names.joinToString()
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = update()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = update()
        }
        update()
        manager.registerAudioDeviceCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { manager.unregisterAudioDeviceCallback(callback) }
    }

    Row(Modifier.fillMaxWidth()) {
        TextButton(onClick = { tab = 0 }, modifier = Modifier.weight(1f)) { Text("Audio Effect", color = if (tab == 0) Color.White else Muted, maxLines = 1) }
        TextButton(onClick = { tab = 1 }, modifier = Modifier.weight(1f)) { Text("Equalizer", color = if (tab == 1) Color.White else Muted, maxLines = 1) }
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
    Text(outputs, color = Muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))

    if (tab == 0) {
        AudioEffectSettings.presets.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { name ->
                    val pressSource = remember { MutableInteractionSource() }
                    val selected = settings.preset == name && settings.enabled
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 62.dp)
                            .iosLiquidPress(pressSource, scaleDown = 0.93f)
                            .frostedGlassChrome(
                                enabled = glassEnabled,
                                shape = RoundedCornerShape(14.dp),
                                tint = if (selected) Accent.copy(alpha = 0.34f) else Color(0x520B131F),
                                fallbackColor = if (selected) Color(0xFF1756A8) else Color(0xFF1A222E),
                            )
                            .clickable(interactionSource = pressSource, indication = ripple()) {
                                service.effects(settings.copy(preset = name, enabled = true))
                            }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                when (name) {
                                    "Clarity", "Earbuds Vocal" -> Icons.Default.Mic
                                    "Bass Boost", "Deep Bass" -> Icons.Default.Speaker
                                    "Treble Boost", "Cinematic Spatial" -> Icons.Default.SurroundSound
                                    "Headphones" -> Icons.Default.Headphones
                                    "Night Mode" -> Icons.Default.Brightness6
                                    "Movie" -> Icons.Default.Movie
                                    "Music" -> Icons.Default.MusicNote
                                    else -> Icons.Default.GraphicEq
                                },
                                null,
                                tint = if (selected) Accent else Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(name, fontSize = 11.5.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(7.dp))
        }
    } else {
        // Equalizer Header + 10-Band Toggle + Enable Switch
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Equalizer", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .frostedGlassChrome(
                        enabled = glassEnabled,
                        shape = RoundedCornerShape(12.dp),
                        tint = if (settings.tenBandMode) Accent.copy(alpha = 0.30f) else Color(0x480B131F),
                    )
                    .clickable { service.effects(settings.copy(tenBandMode = !settings.tenBandMode)) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    if (settings.tenBandMode) "10-Band" else "5-Band",
                    color = if (settings.tenBandMode) Accent else Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(if (settings.enabled) "On" else "Off", color = if (settings.enabled) Accent else Muted, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = settings.enabled,
                onCheckedChange = { service.effects(settings.copy(enabled = it)) },
                modifier = Modifier.graphicsLayer { scaleX = 0.78f; scaleY = 0.78f },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = Color.LightGray,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.16f),
                ),
            )
        }

        // Scrollable Preset Strip matching native vertical EQ reference
        val stripPresets = remember {
            listOf("Custom", "Normal", "Classical", "Dance", "Flat", "Rock", "Clarity", "Bass Boost", "Treble Boost", "Movie", "Music")
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            stripPresets.forEach { name ->
                val active = settings.preset.equals(name, ignoreCase = true) ||
                    (name == "Normal" && settings.preset == "Original")
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) Accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
                        .border(
                            0.8.dp,
                            if (active) Accent.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(12.dp),
                        )
                        .clickable {
                            if (name == "Custom") {
                                service.effects(settings.copy(preset = "Custom", enabled = true))
                            } else {
                                val mapped = if (name == "Normal") "Normal" else name
                                service.effects(settings.copy(preset = mapped, enabled = true))
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        name,
                        color = if (active) Accent else Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }

        // Vertical Faders Glass Card (5-band default or 10-band expanded)
        Box(
            Modifier
                .fillMaxWidth()
                .frostedGlassChrome(
                    enabled = glassEnabled,
                    shape = RoundedCornerShape(18.dp),
                    tint = Color(0x6409121E),
                    fallbackColor = Color(0xCC121720),
                )
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            if (!settings.tenBandMode) {
                val bandValues = settings.fiveBandGains()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AudioEffectSettings.fiveBandLabels.forEachIndexed { idx, freq ->
                        val v = bandValues.getOrElse(idx) { 0f }
                        VerticalEqSlider(
                            freqLabel = freq,
                            dbValue = v,
                            enabled = settings.enabled,
                            onValueChange = { nextDb ->
                                service.effects(settings.withFiveBandGain(idx, nextDb).copy(enabled = true))
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                val bandValues = settings.gains()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AudioEffectSettings.tenBandLabels.forEachIndexed { idx, freq ->
                        val v = bandValues.getOrElse(idx) { 0f }
                        VerticalEqSlider(
                            freqLabel = freq,
                            dbValue = v,
                            enabled = settings.enabled,
                            onValueChange = { nextDb ->
                                service.effects(settings.withTenBandGain(idx, nextDb).copy(enabled = true))
                            },
                            modifier = Modifier.width(34.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Reverb Dropdown Row (Crystal Liquid Glass Menu)
        var reverbExpanded by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Reverb", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .frostedGlassChrome(
                            enabled = glassEnabled,
                            shape = RoundedCornerShape(12.dp),
                            tint = Color(0x5A0C1421),
                        )
                        .clickable { reverbExpanded = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        settings.reverb,
                        color = if (settings.reverb != "None") Accent else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Default.ArrowDropDown, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                LiquidGlassPopupMenu(expanded = reverbExpanded, onDismissRequest = { reverbExpanded = false }, forceDark = true) {
                    AudioEffectSettings.reverbPresets.forEach { presetName ->
                        LiquidGlassPopupMenuItem(
                            text = {
                                Text(
                                    presetName,
                                    color = if (settings.reverb == presetName) Accent else Color.White,
                                    fontWeight = if (settings.reverb == presetName) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                reverbExpanded = false
                                service.effects(settings.copy(reverb = presetName, enabled = true))
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Circular Rotary Knobs: Bass Boost & Virtualizer
        var bassDrag by remember(settings.bassBoost) { mutableIntStateOf(settings.bassBoost) }
        var virtDrag by remember(settings.virtualizer) { mutableIntStateOf(settings.virtualizer) }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RotaryEffectKnob(
                title = "Bass Boost",
                percent = bassDrag,
                enabled = settings.enabled,
                onValueChange = { next ->
                    bassDrag = next
                    service.effects(settings.copy(bassBoost = next, enabled = true))
                },
                onValueChangeFinished = { },
            )
            RotaryEffectKnob(
                title = "Virtualizer",
                percent = virtDrag,
                enabled = settings.enabled,
                onValueChange = { next -> virtDrag = next },
                onValueChangeFinished = {
                    service.effects(settings.copy(virtualizer = virtDrag, enabled = true))
                },
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${settings.preamp().toInt()} dB headroom", color = Muted, fontSize = 11.5.sp)
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .frostedGlassChrome(glassEnabled, RoundedCornerShape(12.dp), tint = Color(0x4E0C1420))
                    .clickable {
                        bassDrag = 0
                        virtDrag = 0
                        service.effects(AudioEffectSettings())
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Reset", color = Accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun VerticalEqSlider(
    freqLabel: String,
    dbValue: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sliderHeight = if (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 520) 104.dp else 136.dp
    val fraction = ((dbValue + 12f) / 24f).coerceIn(0f, 1f)
    val dbText = when {
        dbValue > 0.4f -> "+${dbValue.roundToInt()}"
        dbValue < -0.4f -> "${dbValue.roundToInt()}"
        else -> "0"
    }
    Column(
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${dbText}dB",
            color = if (kotlin.math.abs(dbValue) >= 0.5f) Accent else Color.White.copy(alpha = 0.72f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(34.dp)
                .height(sliderHeight)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { pos ->
                        val frac = (1f - pos.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        onValueChange(((frac * 24f) - 12f).roundToInt().toFloat())
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    var dragFraction = fraction
                    detectVerticalDragGestures(
                        onDragStart = { start ->
                            dragFraction = (1f - start.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            onValueChange(((dragFraction * 24f) - 12f).roundToInt().toFloat())
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragFraction = (dragFraction - dragAmount / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            onValueChange(((dragFraction * 24f) - 12f).roundToInt().toFloat())
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val pad = 8.dp.toPx()
                val usableH = (size.height - pad * 2f).coerceAtLeast(1f)
                val bottomY = size.height - pad
                val centerY = size.height / 2f
                val thumbY = bottomY - usableH * fraction

                // Track background
                drawLine(
                    color = Color.White.copy(alpha = 0.22f),
                    start = Offset(cx, pad),
                    end = Offset(cx, bottomY),
                    strokeWidth = 3.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                // 0 dB center notch
                drawLine(
                    color = Color.White.copy(alpha = 0.38f),
                    start = Offset(cx - 5.dp.toPx(), centerY),
                    end = Offset(cx + 5.dp.toPx(), centerY),
                    strokeWidth = 1.2.dp.toPx(),
                )
                // Filled active bar from bottom to thumb (classic player EQ visual)
                drawLine(
                    brush = Brush.verticalGradient(listOf(Accent, Cyan.copy(alpha = 0.72f))),
                    start = Offset(cx, bottomY),
                    end = Offset(cx, thumbY),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                // Soft glow & circular thumb
                drawCircle(Accent.copy(alpha = 0.28f), radius = 10.dp.toPx(), center = Offset(cx, thumbY))
                drawCircle(Accent, radius = 6.5.dp.toPx(), center = Offset(cx, thumbY))
                drawCircle(Color.White, radius = 2.2.dp.toPx(), center = Offset(cx, thumbY))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            freqLabel,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun RotaryEffectKnob(
    title: String,
    percent: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val clamped = percent.coerceIn(0, 100)
    val currentPercent by rememberUpdatedState(clamped)
    val density = LocalDensity.current
    Column(
        Modifier.alpha(if (enabled) 1f else 0.48f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(92.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    var dragAccum = 0f
                    var startVal = 0
                    val stepPx = with(density) { 2.4.dp.toPx() }
                    detectVerticalDragGestures(
                        onDragStart = {
                            startVal = currentPercent
                            dragAccum = 0f
                        },
                        onDragEnd = { onValueChangeFinished() },
                        onDragCancel = { onValueChangeFinished() },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragAccum -= dragAmount
                            val deltaPercent = (dragAccum / stepPx).roundToInt()
                            val next = (startVal + deltaPercent).coerceIn(0, 100)
                            onValueChange(next)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = size.minDimension * 0.45f
                val innerRadius = size.minDimension * 0.34f
                val startDeg = 135f
                val sweepDeg = 270f
                val ticks = 18

                // Dotted radial tick arc (lit up to current %)
                for (i in 0..ticks) {
                    val tFrac = i.toFloat() / ticks
                    val angleRad = Math.toRadians((startDeg + tFrac * sweepDeg).toDouble())
                    val dotPos = Offset(
                        center.x + (outerRadius * cos(angleRad)).toFloat(),
                        center.y + (outerRadius * sin(angleRad)).toFloat(),
                    )
                    val lit = tFrac <= (clamped / 100f) + 0.01f && clamped > 0
                    drawCircle(
                        color = if (lit) Accent else Color.White.copy(alpha = 0.25f),
                        radius = if (lit) 2.2.dp.toPx() else 1.5.dp.toPx(),
                        center = dotPos,
                    )
                }

                // Brushed dark cobalt Crystal Glass dial body
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF1A3A64), Color(0xFF0E1D34), Color(0xFF080E18)),
                        center = Offset(center.x, center.y - innerRadius * 0.25f),
                        radius = innerRadius * 1.4f,
                    ),
                    radius = innerRadius,
                    center = center,
                )
                drawCircle(
                    brush = liquidSpecularBrush(accentGlow = if (clamped > 0) 0.45f else 0.16f),
                    radius = innerRadius,
                    center = center,
                    style = Stroke(width = 1.4.dp.toPx()),
                )

                // Active dial pointer dot
                val activeRad = Math.toRadians((startDeg + (clamped / 100f) * sweepDeg).toDouble())
                val ptrRadius = innerRadius * 0.72f
                val ptrCenter = Offset(
                    center.x + (ptrRadius * cos(activeRad)).toFloat(),
                    center.y + (ptrRadius * sin(activeRad)).toFloat(),
                )
                drawCircle(Accent, radius = 3.2.dp.toPx(), center = ptrCenter)
            }
            Text(
                "${clamped}%",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            title,
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
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
    val rateText = if ((rate * 100).roundToInt() % 50 == 0) {
        String.format(java.util.Locale.ROOT, "%.1f", rate)
    } else {
        String.format(java.util.Locale.ROOT, "%.2f", rate)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(top = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier
                .frostedGlassChrome(
                    enabled = glassEnabled,
                    shape = RoundedCornerShape(50),
                    tint = Color(0xCC09121E),
                    fallbackColor = Color(0xDE0B121D),
                    borderWidth = 1.dp,
                    fallbackBorderColor = Color.White.copy(alpha = 0.20f),
                )
                .padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Accent.copy(alpha = 0.45f), Cyan.copy(alpha = 0.35f)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (rate > 1f) Icons.Default.FastForward else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Cyan,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = "${rateText}×",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Speed",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
fun PlayerSpeedBar(
    rate: Float,
    onRateChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val glassEnabled by rememberGlassEnabled()
    var editOpen by remember { mutableStateOf(false) }
    var customInput by remember(rate) {
        mutableStateOf(String.format(java.util.Locale.ROOT, "%.2f", rate))
    }

    fun applyRate(target: Float) {
        val rounded = ((target.coerceIn(0.25f, 4.0f) * 100f).roundToInt() / 100f)
        onRateChange(rounded)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ImmersivePlayerDialog()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val barWidth = (maxWidth - 28.dp).coerceAtMost(540.dp)
            Row(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(bottom = 18.dp)
                    .width(barWidth)
                    .frostedGlassChrome(
                        enabled = glassEnabled,
                        shape = RoundedCornerShape(24.dp),
                        tint = Color(0xCC111926),
                        fallbackColor = Color(0xDC121822),
                        borderWidth = 1.dp,
                        fallbackBorderColor = Color.White.copy(alpha = 0.16f),
                    )
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = { applyRate(rate - 0.05f) },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f)),
                ) {
                    Icon(Icons.Default.Remove, "Decrease speed", tint = Color.White, modifier = Modifier.size(18.dp))
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF070C14).copy(alpha = 0.85f))
                            .border(0.7.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                            .clickable {
                                customInput = String.format(java.util.Locale.ROOT, "%.2f", rate)
                                editOpen = true
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = String.format(java.util.Locale.ROOT, "%.2fx", rate),
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit speed",
                            tint = Cyan.copy(alpha = 0.9f),
                            modifier = Modifier.size(12.dp),
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    val ticks = listOf(0.25f, 0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f)
                    val rangeMin = 0.25f
                    val rangeMax = 4.0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val frac = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                                    val raw = rangeMin + frac * (rangeMax - rangeMin)
                                    val snapped = ((raw / 0.05f).roundToInt() * 0.05f).coerceIn(rangeMin, rangeMax)
                                    applyRate(snapped)
                                }
                            }
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures { change, _ ->
                                    change.consume()
                                    val frac = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                                    val raw = rangeMin + frac * (rangeMax - rangeMin)
                                    val snapped = ((raw / 0.05f).roundToInt() * 0.05f).coerceIn(rangeMin, rangeMax)
                                    applyRate(snapped)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                            val cy = size.height / 2f
                            val trackPad = 6.dp.toPx()
                            val usableW = (size.width - trackPad * 2).coerceAtLeast(1f)
                            val curFrac = ((rate - rangeMin) / (rangeMax - rangeMin)).coerceIn(0f, 1f)
                            val thumbX = trackPad + usableW * curFrac
                            drawLine(
                                color = Color.White.copy(alpha = 0.24f),
                                start = Offset(trackPad, cy),
                                end = Offset(trackPad + usableW, cy),
                                strokeWidth = 3.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                            drawLine(
                                brush = Brush.horizontalGradient(listOf(Accent, Cyan)),
                                start = Offset(trackPad, cy),
                                end = Offset(thumbX, cy),
                                strokeWidth = 3.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                            ticks.forEach { stop ->
                                val f = (stop - rangeMin) / (rangeMax - rangeMin)
                                val x = trackPad + usableW * f
                                drawCircle(
                                    color = if (f <= curFrac) Color.White else Color.White.copy(alpha = 0.55f),
                                    radius = if (stop == 1.0f) 2.2.dp.toPx() else 1.5.dp.toPx(),
                                    center = Offset(x, cy),
                                )
                            }
                            drawCircle(
                                color = Color.White.copy(alpha = 0.28f),
                                radius = 9.dp.toPx(),
                                center = Offset(thumbX, cy),
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 6.dp.toPx(),
                                center = Offset(thumbX, cy),
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { applyRate(rate + 0.05f) },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f)),
                ) {
                    Icon(Icons.Default.Add, "Increase speed", tint = Color.White, modifier = Modifier.size(18.dp))
                }

                IconButton(
                    onClick = { applyRate(1.0f) },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f)),
                ) {
                    Icon(Icons.Default.Refresh, "Reset speed to 1.0x", tint = Cyan, modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    if (editOpen) {
        AlertDialog(
            onDismissRequest = { editOpen = false },
            containerColor = Color(0xFF141C28),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = { Text("Playback Speed (0.25× – 4.00×)", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = customInput,
                        onValueChange = { customInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        singleLine = true,
                        label = { Text("Speed multiplier") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f).forEach { preset ->
                            FilterChip(
                                selected = kotlin.math.abs(rate - preset) < 0.02f,
                                onClick = {
                                    applyRate(preset)
                                    editOpen = false
                                },
                                label = { Text("${preset}×") },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = customInput.toFloatOrNull()
                        if (parsed != null) applyRate(parsed)
                        editOpen = false
                    },
                ) { Text("Apply", color = Cyan) }
            },
            dismissButton = {
                TextButton(onClick = { editOpen = false }) { Text("Cancel", color = Muted) }
            },
        )
    }
}

@Composable
fun MediaInformationContent(
    info: MediaDetailedInfo,
    inPlayer: Boolean = true,
) {
    val labelColor = if (inPlayer) Color(0xFF9FB0C7) else LocalAppPalette.current.textSecondary
    val valueColor = if (inPlayer) Color.White else LocalAppPalette.current.textPrimary
    val sectionColor = if (inPlayer) Cyan else LocalAppPalette.current.accent
    val dividerColor = if (inPlayer) Color.White.copy(alpha = 0.10f) else LocalAppPalette.current.divider

    @Composable
    fun InfoSection(title: String, rows: List<Pair<String, String>>) {
        val valid = rows.filter { it.second.isNotBlank() }
        if (valid.isEmpty()) return
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                color = sectionColor,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            valid.forEach { (k, v) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = k,
                        color = labelColor,
                        fontSize = 13.sp,
                        modifier = Modifier.width(104.dp),
                    )
                    Text(
                        text = v,
                        color = valueColor,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 6.dp), color = dividerColor)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        InfoSection(
            title = "File",
            rows = listOf(
                "File" to info.fileName,
                "Location" to info.location,
                "Size" to formatDetailedBytes(info.sizeBytes),
                "Date" to formatDetailedDate(info.modifiedAtMs),
                "Subtitle" to info.subtitleSummary,
            ),
        )
        InfoSection(
            title = "Media",
            rows = listOf(
                "Format" to info.format,
                "Resolution" to (if (info.width > 0 && info.height > 0) "${info.width} × ${info.height}" else ""),
                "Length" to (if (info.durationMs > 0) durationText(info.durationMs) else ""),
                "Encoding SW" to info.encodingSoftware,
            ),
        )
        info.streams.forEach { stream ->
            val streamRows = buildList {
                add("Type" to stream.type)
                if (stream.title.isNotBlank()) add("Title" to stream.title)
                if (stream.codec.isNotBlank()) add("Codec" to stream.codec)
                if (stream.profile.isNotBlank()) add("Profile" to stream.profile)
                if (stream.width > 0 && stream.height > 0) add("Resolution" to "${stream.width} × ${stream.height}")
                if (stream.frameRate.isNotBlank()) add("Frame rate" to stream.frameRate)
                if (stream.sampleRateHz > 0) add("Sample rate" to "${stream.sampleRateHz} Hz")
                if (stream.channels > 0) add("Channels" to "${stream.channels} ch")
                if (stream.language.isNotBlank()) add("Language" to stream.language)
            }
            InfoSection(title = "Stream #${stream.index}", rows = streamRows)
        }
    }
}

@Composable
fun PlayerFeedbackHost(value: PlayerFeedback?) {
    var last by remember { mutableStateOf<PlayerFeedback?>(null) }
    if (value != null) SideEffect { last = value }
    AnimatedVisibility(
        visible = value != null,
        enter = fadeIn(tween(80)) + scaleIn(initialScale = 0.92f, animationSpec = MotionSpec.MorphSpring),
        exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.95f),
    ) {
        (value ?: last)?.let { PlayerFeedbackOverlay(it, Modifier.fillMaxSize()) }
    }
}
