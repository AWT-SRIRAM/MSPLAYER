package app.msplayer.offline

import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.round

object GestureRules {
    private val rates = floatArrayOf(.25f, .5f, 1f, 1.5f, 2f, 2.5f, 3f, 4f)
    fun fraction(rate: Float): Float {
        val value = rate.coerceIn(.25f, 4f)
        val i = (0..6).firstOrNull { value <= rates[it + 1] } ?: 6
        return (i + (value - rates[i]) / (rates[i + 1] - rates[i])) / 7f
    }
    fun rate(base: Float, delta: Float, width: Float): Float {
        val position = (fraction(base) + delta / (width * .4f).coerceAtLeast(1f)).coerceIn(0f, 1f) * 7
        val i = position.toInt().coerceAtMost(6)
        return (round((rates[i] + (rates[i + 1] - rates[i]) * (position - i)) * 20) / 20).coerceIn(.25f, 4f)
    }
    fun volume(base: Int, dy: Float, height: Float): Int {
        val result = (base - dy / height.coerceAtLeast(1f) * 200).toInt().coerceIn(0, 200)
        return if (base < 100 && result > 100) 100 else result
    }
}

/** One recognizer owns a gesture after slop or hold, so seeking cannot also change speed. */
@Composable
fun Modifier.videoGestures(
    activity: MainActivity, service: PlaybackService, locked: Boolean, enabled: Boolean,
    aspect: String, zoom: Float, onZoom: (Float) -> Unit, onTap: () -> Unit,
    onDrag: (Long?) -> Unit, onFeedback: (PlayerFeedback?) -> Unit,
): Modifier {
    val zoomNow by rememberUpdatedState(zoom)
    val aspectNow by rememberUpdatedState(aspect)
    val tapNow by rememberUpdatedState(onTap)
    val feedbackNow by rememberUpdatedState(onFeedback)
    val dragNow by rememberUpdatedState(onDrag)
    val zoomCallback by rememberUpdatedState(onZoom)
    return pointerInput(locked, enabled, service) {
        if (locked || !enabled) return@pointerInput
        var lastTap = 0L
        var lastPoint = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            val start = down.uptimeMillis
            val baseTime = service.timeline.value.position
            val baseRate = service.state.value.rate
            val baseVolume = service.volumePercent()
            val baseBrightness = activity.window.attributes.screenBrightness.takeIf { it >= 0 }
                ?: (android.provider.Settings.System.getInt(activity.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS,128) / 255f)
            val hasSubtitles = service.state.value.subtitleTrack >= 0 && service.state.value.plainSubtitle
            val inSubArea = hasSubtitles && down.position.y > size.height * 0.74f
            val baseSubBottom = if (inSubArea) SubtitleStyle.load(activity).bottomMargin else 0
            val baseSubScale = if (inSubArea) SubtitleStyle.load(activity).scale else 100
            var currentSubBottom = baseSubBottom
            var currentSubScale = baseSubScale.toFloat()
            var x = 0f; var y = 0f; var mode = 0; var target = baseTime
            var gestureZoom = zoomNow
            try {
                while (true) {
                    val remaining = (400 - (android.os.SystemClock.uptimeMillis() - start)).coerceAtLeast(1)
                    val event = if (mode == 0 && service.state.value.playing) withTimeoutOrNull(remaining) { awaitPointerEvent() } else awaitPointerEvent()
                    if (event == null) {
                        if (service.beginTemporarySpeed()) { mode = 5; feedbackNow(PlayerFeedback.Speed(baseRate)) }
                        continue
                    }
                    if (event.changes.any { it.isConsumed }) break
                    val pan = event.calculatePan(); x += pan.x; y += pan.y
                    if (event.changes.count { it.pressed } > 1) {
                        if (mode == 5) { service.endTemporarySpeed(); feedbackNow(null) }
                        if (inSubArea) {
                            mode = 7
                            currentSubScale = (currentSubScale * event.calculateZoom()).coerceIn(50f, 300f)
                            service.updateSubtitleScale(currentSubScale.toInt())
                            feedbackNow(PlayerFeedback.SubtitleScale(currentSubScale.toInt()))
                        } else {
                            mode = 4
                            gestureZoom = (gestureZoom * event.calculateZoom()).coerceIn(.5f, 3f)
                            if (abs(gestureZoom - zoomNow) >= .03f) {
                                zoomCallback(gestureZoom)
                                service.player.currentVideoTrack?.let { t ->
                                    val sar = if(t.sarNum > 0 && t.sarDen > 0) t.sarNum.toFloat()/t.sarDen else 1f
                                    service.player.setScale(PlaybackRules.videoScale(t.width*sar,t.height.toFloat(),size.width.toFloat(),size.height.toFloat(),gestureZoom,aspectNow=="Fill"))
                                }
                                feedbackNow(PlayerFeedback.Zoom((gestureZoom*100).toInt()))
                            }
                        }
                    } else {
                        if (mode == 0 && (abs(x)>viewConfiguration.touchSlop || abs(y)>viewConfiguration.touchSlop))
                            mode = if(abs(x)>abs(y)) 1 else if(inSubArea) 6 else if(down.position.x<size.width/2) 2 else 3
                        when(mode) {
                            1 -> {
                                target=PlaybackRules.safeSeek(baseTime+(x/size.width*120000).toLong(),service.timeline.value.duration)
                                dragNow(target)
                                val delta=(target-baseTime)/1000
                                feedbackNow(PlayerFeedback.Seek(durationText(target),"${if(delta>=0) "+" else ""}${delta}s"))
                            }
                            2 -> {
                                val value=(baseBrightness-y/size.height).coerceIn(.02f,1f)
                                activity.window.attributes=activity.window.attributes.apply { screenBrightness=value }
                                feedbackNow(PlayerFeedback.Level(true,(value*100).toInt()))
                            }
                            3 -> feedbackNow(PlayerFeedback.Level(false,service.setVolumePercent(GestureRules.volume(baseVolume,y,size.height.toFloat()))))
                            5 -> {
                                // A pause/focus loss/media change ends the service-side temporary session.
                                if(!service.state.value.playing) break
                                feedbackNow(PlayerFeedback.Speed(service.temporarySpeed(GestureRules.rate(baseRate,x,size.width.toFloat()))))
                            }
                            6 -> {
                                val deltaBottom = (-y / (size.height * 0.35f) * 40).toInt()
                                currentSubBottom = (baseSubBottom + deltaBottom).coerceIn(0, 40)
                                service.updateSubtitlePosition(currentSubBottom)
                                feedbackNow(PlayerFeedback.SubtitlePosition(currentSubBottom))
                            }
                        }
                    }
                    if(mode!=0) event.changes.forEach { it.consume() }
                    if(event.changes.none { it.pressed }) {
                        if(mode==1) service.seek(target)
                        if(mode==6) SubtitleStyle.load(activity).copy(bottomMargin=currentSubBottom).save(activity)
                        if(mode==7) { val s = SubtitleStyle.load(activity).copy(scale=currentSubScale.toInt()); service.subtitleStyle(s) }
                        if(mode==0) {
                            if(start-lastTap<300 && (down.position-lastPoint).getDistance()<80.dp.toPx()) {
                                val seconds=Store.prefs(activity).getInt("double_tap",10)*(if(down.position.x<size.width/2) -1 else 1)
                                val destination=service.timeline.value.position+seconds*1000
                                service.seek(destination)
                                feedbackNow(PlayerFeedback.Seek(durationText(destination),"${if(seconds>=0) "+" else ""}${seconds}s"))
                                lastTap=0
                            } else { tapNow(); lastTap=start; lastPoint=down.position }
                        }
                        break
                    }
                }
            } finally {
                service.endTemporarySpeed()
                dragNow(null)
                if(mode==5) feedbackNow(null)
            }
        }
    }
}
