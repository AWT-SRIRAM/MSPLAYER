package app.msplayer.offline

import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

object GestureRules {
    val rates = floatArrayOf(.25f, .5f, 1f, 1.5f, 2f, 2.5f, 3f, 4f)
    val rulerStops = floatArrayOf(.25f, .5f, 1f, 1.5f, 2f, 2.5f, 3f, 4f)

    fun fraction(rate: Float): Float {
        val value = rate.coerceIn(.25f, 4f)
        val lastIdx = rulerStops.lastIndex
        val i = (0 until lastIdx).firstOrNull { value <= rulerStops[it + 1] } ?: (lastIdx - 1)
        val span = (rulerStops[i + 1] - rulerStops[i]).coerceAtLeast(0.001f)
        return ((i + (value - rulerStops[i]) / span) / lastIdx.toFloat()).coerceIn(0f, 1f)
    }

    fun snappedRate(base: Float, deltaX: Float, width: Float, deadZonePx: Float = 0f): Float {
        if (abs(deltaX) <= deadZonePx) {
            val baseIndex = rates.indices.minByOrNull { abs(rates[it] - base) } ?: 4
            return rates[baseIndex]
        }
        val effectiveDelta = deltaX - sign(deltaX) * deadZonePx
        val baseIndex = rates.indices.minByOrNull { abs(rates[it] - base) } ?: 4
        val stepPx = (width.coerceAtLeast(240f) * 0.24f) / rates.lastIndex.coerceAtLeast(1)
        val offsetIndex = kotlin.math.round(effectiveDelta / stepPx).toInt()
        val targetIndex = (baseIndex + offsetIndex).coerceIn(0, rates.lastIndex)
        return rates[targetIndex]
    }

    fun rate(base: Float, delta: Float, width: Float): Float =
        (base + delta / width * 2f).coerceIn(.25f, 4f)

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
        var lastZone: PlaybackRules.TapZone? = null
        var seekBurst = PlaybackRules.TapSeekBurst()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            val start = down.uptimeMillis
            val baseTime = service.timeline.value.position
            val baseRate = service.state.value.rate
            val baseVolume = service.volumePercent()
            val baseBrightness = activity.window.attributes.screenBrightness.takeIf { it >= 0 }
                ?: (android.provider.Settings.System.getInt(activity.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
            val hasSubtitles = service.state.value.subtitleTrack >= 0 && service.state.value.plainSubtitle
            val inSubArea = hasSubtitles && down.position.y > size.height * 0.74f
            val baseSubBottom = if (inSubArea) SubtitleStyle.load(activity).bottomMargin else 0
            val baseSubScale = if (inSubArea) SubtitleStyle.load(activity).scale else 100
            var currentSubBottom = baseSubBottom
            var currentSubScale = baseSubScale.toFloat()
            var x = 0f; var y = 0f; var mode = 0; var target = baseTime
            var gestureZoom = zoomNow
            var speedStartX = 0f
            var speedAnchorRate = 2.0f
            var currentSpeedRate = 2.0f
            var lastSpeedChangeAt = 0L
            val speedDeadZonePx = 12.dp.toPx()
            try {
                while (true) {
                    val remaining = (400 - (android.os.SystemClock.uptimeMillis() - start)).coerceAtLeast(1)
                    val event = if (mode == 0 && service.state.value.playing) withTimeoutOrNull(remaining) { awaitPointerEvent() } else awaitPointerEvent()
                    if (event == null) {
                        if (service.beginTemporarySpeed()) {
                            mode = 5
                            speedStartX = x
                            speedAnchorRate = if (baseRate >= 2.0f) (baseRate + 0.5f).coerceAtMost(4.0f) else 2.0f
                            currentSpeedRate = speedAnchorRate
                            lastSpeedChangeAt = android.os.SystemClock.uptimeMillis()
                            service.temporarySpeed(currentSpeedRate)
                            feedbackNow(PlayerFeedback.Speed(currentSpeedRate, lastSpeedChangeAt))
                            try {
                                activity.window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            } catch (_: Exception) {}
                        }
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
                                val fillMode = aspectNow == PlaybackRules.AspectOption.CROP.label || aspectNow == PlaybackRules.AspectOption.STRETCH.label
                                service.player.currentVideoTrack?.let { t ->
                                    val sar = if (t.sarNum > 0 && t.sarDen > 0) t.sarNum.toFloat() / t.sarDen else 1f
                                    service.player.setScale(PlaybackRules.videoScale(t.width * sar, t.height.toFloat(), size.width.toFloat(), size.height.toFloat(), gestureZoom, fillMode))
                                }
                                feedbackNow(PlayerFeedback.Zoom((gestureZoom * 100).toInt()))
                            }
                        }
                    } else {
                        if (mode == 0 && (abs(x) > viewConfiguration.touchSlop || abs(y) > viewConfiguration.touchSlop))
                            mode = if (abs(x) > abs(y)) 1 else if (inSubArea) 6 else if (down.position.x < size.width / 2) 2 else 3
                        when (mode) {
                            1 -> {
                                target = PlaybackRules.safeSeek(baseTime + (x / size.width * 120000).toLong(), service.timeline.value.duration)
                                dragNow(target)
                                val delta = (target - baseTime) / 1000
                                feedbackNow(PlayerFeedback.Seek(durationText(target), "${if (delta >= 0) "+" else ""}${delta}s"))
                            }
                            2 -> {
                                val value = (baseBrightness - y / size.height).coerceIn(.02f, 1f)
                                activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }
                                feedbackNow(PlayerFeedback.Level(true, (value * 100).toInt()))
                            }
                            3 -> feedbackNow(PlayerFeedback.Level(false, service.setVolumePercent(GestureRules.volume(baseVolume, y, size.height.toFloat()))))
                            5 -> {
                                // A pause/focus loss/media change ends the service-side temporary session.
                                if (!service.state.value.playing) break
                                val deltaX = x - speedStartX
                                val newRate = GestureRules.snappedRate(speedAnchorRate, deltaX, size.width.toFloat(), deadZonePx = speedDeadZonePx)
                                if (newRate != currentSpeedRate) {
                                    currentSpeedRate = newRate
                                    lastSpeedChangeAt = android.os.SystemClock.uptimeMillis()
                                    service.temporarySpeed(newRate)
                                    feedbackNow(PlayerFeedback.Speed(newRate, lastSpeedChangeAt))
                                    try {
                                        activity.window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                    } catch (_: Exception) {}
                                }
                            }
                            6 -> {
                                val deltaBottom = (-y / (size.height * 0.35f) * 40).toInt()
                                currentSubBottom = (baseSubBottom + deltaBottom).coerceIn(0, 40)
                                service.updateSubtitlePosition(currentSubBottom)
                                feedbackNow(PlayerFeedback.SubtitlePosition(currentSubBottom))
                            }
                        }
                    }
                    if (mode != 0) event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) {
                        if (mode == 1) service.seek(target)
                        if (mode == 5) { service.endTemporarySpeed(); feedbackNow(null) }
                        if (mode == 6) SubtitleStyle.load(activity).copy(bottomMargin = currentSubBottom).save(activity)
                        if (mode == 7) { val s = SubtitleStyle.load(activity).copy(scale = currentSubScale.toInt()); service.subtitleStyle(s) }
                        if (mode == 0) {
                            val stepSec = Store.prefs(activity).getInt("double_tap", 10)
                            val xFraction = if (size.width > 0) down.position.x / size.width.toFloat() else 0.5f
                            val zone = PlaybackRules.tapZoneForFraction(xFraction)
                            val outcome = PlaybackRules.resolveTapSeek(
                                nowMs = start,
                                distanceFromLastTapPx = if (lastTap > 0L) (down.position - lastPoint).getDistance() else Float.MAX_VALUE,
                                lastTapMs = lastTap,
                                doubleTapMaxGapMs = 280L,
                                doubleTapMaxSlopPx = 56.dp.toPx(),
                                stepSec = stepSec,
                                currentPositionMs = service.timeline.value.position,
                                durationMs = service.timeline.value.duration,
                                currentBurst = seekBurst,
                                burstWindowMs = 1100L,
                                zone = zone,
                                lastZone = lastZone,
                            )
                            seekBurst = outcome.nextBurst
                            when (outcome.action) {
                                PlaybackRules.TapAction.SEEK_BACK,
                                PlaybackRules.TapAction.SEEK_FORWARD -> {
                                    service.seek(outcome.destinationMs)
                                    val deltaPrefix = if (outcome.totalDeltaSec >= 0) "+" else ""
                                    val side = if (outcome.action == PlaybackRules.TapAction.SEEK_BACK) -1 else 1
                                    feedbackNow(
                                        PlayerFeedback.Seek(
                                            target = durationText(outcome.destinationMs),
                                            difference = "$deltaPrefix${outcome.totalDeltaSec}s",
                                            side = side,
                                            tapCount = outcome.tapCount,
                                        )
                                    )
                                    try {
                                        activity.window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                    } catch (_: Exception) {}
                                    lastTap = 0L
                                    lastZone = null
                                }
                                PlaybackRules.TapAction.TOGGLE_PLAY_PAUSE -> {
                                    val nextPlaying = !service.state.value.playing
                                    service.toggle()
                                    feedbackNow(PlayerFeedback.PlayPause(nextPlaying))
                                    try {
                                        activity.window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                    } catch (_: Exception) {}
                                    lastTap = 0L
                                    lastZone = null
                                }
                                PlaybackRules.TapAction.NONE -> {
                                    tapNow()
                                    lastTap = start
                                    lastZone = zone
                                    lastPoint = down.position
                                }
                            }
                        }
                        break
                    }
                }
            } finally {
                service.endTemporarySpeed()
                dragNow(null)
                if (mode == 5) feedbackNow(null)
            }
        }
    }
}
