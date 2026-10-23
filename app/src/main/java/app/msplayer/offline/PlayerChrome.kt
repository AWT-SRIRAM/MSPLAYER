package app.msplayer.offline

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.videolan.libvlc.MediaPlayer

sealed interface PlayerFeedback {
    data class Level(val brightness: Boolean, val percent: Int) : PlayerFeedback
    data class Seek(val target: String, val difference: String) : PlayerFeedback
    data class Zoom(val percent: Int) : PlayerFeedback
    data class Speed(val rate: Float) : PlayerFeedback
    data class SubtitlePosition(val percent: Int) : PlayerFeedback
    data class SubtitleScale(val percent: Int) : PlayerFeedback
}

@Composable
fun PlayerFeedbackOverlay(value: PlayerFeedback, modifier: Modifier = Modifier) {
    when (value) {
        is PlayerFeedback.Level -> {
            val alignment = if (value.brightness) Alignment.CenterEnd else Alignment.CenterStart
            val maximum = if (value.brightness) 100 else 200
            val progress by animateFloatAsState(value.percent.toFloat(), tween(90), label = "Gesture level")
            Column(
                modifier.fillMaxSize().safeDrawingPadding().wrapContentSize(alignment).padding(horizontal = 25.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("${value.percent}%", color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(4.dp).height(100.dp).background(Color.White.copy(alpha = .3f))) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight((progress / maximum).coerceIn(0f, 1f)).align(Alignment.BottomCenter).background(Accent))
                    if (!value.brightness && progress > 100) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(((progress - 100) / 200).coerceIn(0f, .5f)).align(Alignment.BottomCenter).offset(y = (-50).dp).background(Color(0xFFFFAB40)))
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
        is PlayerFeedback.Speed -> SpeedFeedback(value.rate, modifier)
        is PlayerFeedback.Seek -> Row(
            modifier.wrapContentSize(Alignment.Center)
                .background(Color.Black.copy(alpha = .72f), RoundedCornerShape(8.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value.target, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(12.dp))
            Text(value.difference, color = Accent, fontSize = 16.sp)
        }
        is PlayerFeedback.Zoom -> Text(
            "Zoom ${value.percent}%",
            modifier = modifier.wrapContentSize(Alignment.Center)
                .background(Color.Black.copy(alpha = .68f), RoundedCornerShape(7.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            color = Color.White,
            fontSize = 15.sp,
        )
        is PlayerFeedback.SubtitlePosition -> Row(
            modifier.wrapContentSize(Alignment.Center)
                .background(Color.Black.copy(alpha = .75f), RoundedCornerShape(8.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Subtitles, null, tint = Accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Subtitle Position: ${value.percent}%", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        is PlayerFeedback.SubtitleScale -> Row(
            modifier.wrapContentSize(Alignment.Center)
                .background(Color.Black.copy(alpha = .75f), RoundedCornerShape(8.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.FormatSize, null, tint = Accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Subtitle Size: ${value.percent}%", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
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
    Dialog(onDismissRequest = onDismissRequest) {
        ImmersivePlayerDialog()
        Column(
            Modifier.widthIn(max = 400.dp).fillMaxWidth()
                .background(Color(0xFF292B30), RoundedCornerShape(6.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            ProvideTextStyle(androidx.compose.ui.text.TextStyle(color = Color.White)) {
                Box(Modifier.padding(bottom = 12.dp)) { title() }
                Box(Modifier.weight(1f, fill = false).heightIn(max = 440.dp)) { text() }
                Box(Modifier.align(Alignment.End)) { confirmButton() }
            }
        }
    }
}

@Composable
fun PlayerSidePanel(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersivePlayerDialog()
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .12f))) {
            val width = if (maxWidth > maxHeight) (maxWidth * .43f).coerceAtMost(480.dp) else maxWidth
            Box(Modifier.fillMaxSize().clickable(onClick = dismiss))
            Column(
                Modifier.align(Alignment.CenterEnd).width(width).fillMaxHeight()
                    .background(Color(0xBF111418)).clickable { }.padding(horizontal = 20.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

@Composable
fun TrackChoice(name: String, selected: Boolean, subtitle: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(if (subtitle && selected) Modifier.border(1.dp, Color.White.copy(alpha = .45f), RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (subtitle) Checkbox(checked = selected, onCheckedChange = { onClick() })
        else RadioButton(selected = selected, onClick = onClick)
        Text(name, color = Color.White, modifier = Modifier.padding(start = 6.dp).weight(1f), fontSize = 16.sp)
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
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        Box(Modifier.align(if (tab == 0) Alignment.CenterStart else Alignment.CenterEnd).fillMaxWidth(.5f).fillMaxHeight().background(Accent))
    }
    Text(outputs, color = Color.LightGray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
    if (tab == 0) {
        AudioEffectSettings.presets.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { name ->
                    OutlinedButton(
                        onClick = { service.effects(settings.copy(preset = name)) },
                        modifier = Modifier.weight(1f).heightIn(min = 76.dp),
                        shape = RoundedCornerShape(8.dp),
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
private fun ImmersivePlayerDialog() {
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }
}

@Composable
fun SpeedFeedback(rate: Float, modifier: Modifier = Modifier) {
    val stops = listOf(.25f, .5f, 1f, 1.5f, 2f, 2.5f, 3f, 4f)
    Column(
        modifier.fillMaxSize().safeDrawingPadding().wrapContentSize(Alignment.TopCenter).padding(top = 24.dp)
            .widthIn(max = 560.dp).fillMaxWidth(.78f).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(42.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            stops.forEach { Text("${it}×", color = if (kotlin.math.abs(it - rate) < .06f) Accent else Color.White, fontSize = 11.sp) }
        }
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            val y = size.height / 2
            drawLine(Color.Gray, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            stops.indices.forEach { i -> drawCircle(Color.White, 2.5.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * i / 7, y)) }
            drawCircle(Accent, 5.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * GestureRules.fraction(rate), y))
        }
        Text("${String.format(java.util.Locale.ROOT, "%.2f", rate).trimEnd('0').trimEnd('.')}× Speed Playing", color = Color.White, fontSize = 14.sp)
    }
}

@Composable
fun PlayerFeedbackHost(value: PlayerFeedback?) {
    var last by remember { mutableStateOf<PlayerFeedback?>(null) }
    if (value != null) SideEffect { last = value }
    AnimatedVisibility(visible = value != null, enter = fadeIn(tween(90)), exit = fadeOut(tween(180))) {
        (value ?: last)?.let { PlayerFeedbackOverlay(it, Modifier.fillMaxSize()) }
    }
}
