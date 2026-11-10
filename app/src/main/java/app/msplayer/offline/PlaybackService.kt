package app.msplayer.offline

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

data class PlaybackTimeline(val position: Long = 0, val duration: Long = 0, val seekable: Boolean = false, val seeking: Boolean = false)

sealed class AutoSubStatus {
    object Idle : AutoSubStatus()
    data class Processing(val stage: String, val percent: Int) : AutoSubStatus()
    data class Success(val file: java.io.File?, val count: Int, val provider: String = "", val note: String? = null) : AutoSubStatus()
    data class Error(val message: String) : AutoSubStatus()
}

data class PlayerState(
    val item: MediaItem? = null,
    val closed: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val plainSubtitle: Boolean = true,
    val playing: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val seekable: Boolean = false,
    val buffering: Boolean = false,
    val error: String? = null,
    val rate: Float = 1f,
    val decoder: String = "Auto",
    val audioTracks: List<Pair<Int, String>> = emptyList(),
    val subtitles: List<Pair<Int, String>> = emptyList(),
    val audioTrack: Int = -1,
    val subtitleTrack: Int = -1,
    val selectedSubtitleTracks: Set<Int> = emptySet(),
    val audioDelay: Long = 0,
    val subtitleDelay: Long = 0,
    val videoCodec: String = "",
    val hasPrimaryHwSubtitle: Boolean = false,
)

class PlaybackService : Service() {
    inner class LocalBinder : Binder() {
        val service
            get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    val state = MutableStateFlow(PlayerState())
    val timeline = MutableStateFlow(PlaybackTimeline())
    val activeSubtitleText = MutableStateFlow<String?>(null)
    val subtitleStyleRevision = MutableStateFlow(0)
    val autoSubStatus = MutableStateFlow<AutoSubStatus>(AutoSubStatus.Idle)
    private var parsedCues: List<PlaybackRules.SubtitleCue> = emptyList()
    private val selectedSubTracks = LinkedHashSet<Int>()
    private var userModifiedSubtitles = false
    private var initialSubRestored = false
    private var autoSubCues: List<PlaybackRules.SubtitleCue> = emptyList()
    private var embeddedCuesByTrack: Map<Int, List<PlaybackRules.SubtitleCue>> = emptyMap()
    private var embeddedTracks: Map<Int, ContainerDemuxer.EmbeddedTrack> = emptyMap()
    private var internalSpuIdsOrdered: List<Int> = emptyList()
    private data class ExternalAddRequest(
        val path: String,
        val label: String,
        val cues: List<PlaybackRules.SubtitleCue>,
        val selectExtra: Boolean,
    )
    private val pendingExternalAdds = ArrayDeque<ExternalAddRequest>()
    private val externalSpuPathsById = LinkedHashMap<Int, String>()
    private val externalCuesBySpuId = LinkedHashMap<Int, List<PlaybackRules.SubtitleCue>>()
    private val externalLabelsBySpuId = LinkedHashMap<Int, String>()
    private var released = false
    private var controlsVisible = true
    private var subtitleControlsTop: Float? = null
    val subtitleControlsTopPx = MutableStateFlow(0f)
    fun subtitleControlsTop(top: Float) {
        if (subtitleControlsTop == top) return
        subtitleControlsTop = top
        subtitleControlsTopPx.value = top
        updateSubtitlePlacement(controlsVisible)
    }
    private val audioEffects = AudioEffects()
    val effectSettings = MutableStateFlow(AudioEffectSettings())
    private val diagnostics = PlaybackDiagnostics()

    fun effects(value: AudioEffectSettings) {
        if (released) return
        val previous = effectSettings.value
        val needVlcRebuild = ::vlc.isInitialized &&
            previous.spatializerOptions() != value.spatializerOptions()
        effectSettings.value = value
        value.save(this)
        if (needVlcRebuild && state.value.item != null) {
            rebuildVlcKeepingPosition(value)
        } else {
            audioEffects.apply(player, value)
        }
    }

    private fun rebuildVlcKeepingPosition(settings: AudioEffectSettings) {
        endTemporarySpeed()
        val prior = state.value.copy(playing = state.value.playing && !requestedPause)
        if (prior.item == null) return
        opening?.cancel()
        pendingStyleRestore = prior
        pendingStyleTracks = prior
        player.setEventListener(null)
        player.stop()
        if (videoAttached) player.detachViews()
        activeDescriptor?.close()
        activeDescriptor = null
        player.release()
        vlc.release()
        vlc = createVlc(settings)
        player = MediaPlayer(vlc)
        audioEffects.apply(player, settings)
        applyVolume()
        bindPlayerEvents()
        videoLayout?.let { player.attachViews(it, null, true, false) }
        openCurrent(prior.position, restore = false, keepSubtitleChoice = true)
    }

    fun diagnosticsEnabled(enabled: Boolean) {
        Store.prefs(this).edit().putBoolean("diagnostics", enabled).apply()
        diagnostics.enabled = enabled
    }

    fun exportDiagnostics(): java.io.File = diagnostics.export(this, state.value, effectSettings.value)

    fun updateSubtitlePlacement(visible: Boolean) {
        controlsVisible = visible
        val layout = videoLayout ?: return
        layout.post {
            val surface = layout.findViewById<android.view.SurfaceView>(org.videolan.R.id.surface_subtitles) ?: return@post
            val style = SubtitleStyle.load(this)
            val location = IntArray(2)
            surface.getLocationInWindow(location)
            val controlsLift = if (visible) subtitleControlsTop?.let { top ->
                ((location[1] - surface.translationY + surface.height - top + 8 * resources.displayMetrics.density) / surface.height.coerceAtLeast(1)).coerceIn(0f,1f)
            } ?: .28f else 0f
            val fraction = if (state.value.plainSubtitle) {
                (if (style.legacyMargin == null) style.bottomMargin / 100f else 0f) + controlsLift
            } else 0f
            // VLC 3.x substext.h adds a 4% intrinsic margin before sub-margin.
            // New presets remove it; migrated custom layouts retain their exact offset.
            val nativeInset = if (state.value.plainSubtitle && style.compensateNativeInset && style.legacyMargin == null) .04f else 0f
            surface.translationY = surface.height * (nativeInset - fraction)
            surface.scaleX = 1f
            surface.scaleY = 1f
        }
    }

    lateinit var vlc: LibVLC
        private set

    lateinit var player: MediaPlayer
        private set

    private lateinit var session: MediaSession
    val mediaSessionToken: MediaSession.Token
        get() = session.sessionToken

    private lateinit var audioManager: AudioManager
    private lateinit var focus: AudioFocusRequest
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var queue = emptyList<MediaItem>()
    val queueItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val shuffleEnabled = MutableStateFlow(false)
    val repeatMode = MutableStateFlow("off")
    val volumeLevel = MutableStateFlow(100)
    private var boostedVolume = 100
    private var ducked = false
    private var temporaryBaseRate: Float? = null
    private var lastTempSpeedWriteAt = 0L
    private var repeatA: Long? = null
    private var repeatB: Long? = null
    val abPoints = MutableStateFlow<Pair<Long?, Long?>>(null to null)

    fun volumePercent(): Int {
        if (boostedVolume > 100) return boostedVolume
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }
    fun setVolumePercent(value: Int): Int {
        if (released) return 0
        val target = value.coerceIn(0, 200)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, if (target >= 100) max else kotlin.math.round(target * max / 100f).toInt(), 0)
        boostedVolume = target.coerceAtLeast(100)
        applyVolume()
        if (player.volume < boostedVolume && !ducked) { boostedVolume = 100; applyVolume() }
        return volumePercent().also { volumeLevel.value = it }
    }
    private fun applyVolume() { if (!released) player.volume = if (ducked) boostedVolume / 4 else boostedVolume }
    fun volumeKey(up: Boolean) {
        val current = volumePercent()
        if (current > 100) setVolumePercent(current + if (up) 5 else -5)
        else {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            volumeLevel.value = volumePercent()
        }
    }
    private val routeCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out android.media.AudioDeviceInfo>) { resetBoost() }
        override fun onAudioDevicesRemoved(devices: Array<out android.media.AudioDeviceInfo>) { resetBoost() }
    }
    private fun resetBoost() { boostedVolume = 100; if (::player.isInitialized && !released) { applyVolume(); volumeLevel.value = volumePercent() } }
    fun beginTemporarySpeed(): Boolean {
        if (released || !player.isPlaying) return false
        if (temporaryBaseRate == null) temporaryBaseRate = player.rate
        lastTempSpeedWriteAt = 0L
        return true
    }
    fun temporarySpeed(value: Float): Float {
        if (temporaryBaseRate == null || released) return state.value.rate
        val target = value.coerceIn(.25f, 4f)
        val delta = kotlin.math.abs(player.rate - target)
        val now = SystemClock.elapsedRealtime()
        if (delta >= 0.04f && (now - lastTempSpeedWriteAt >= 45L || delta >= 0.24f)) {
            player.rate = target
            lastTempSpeedWriteAt = now
        }
        if (kotlin.math.abs(state.value.rate - target) > 0.005f) {
            state.value = state.value.copy(rate = target)
        }
        return state.value.rate
    }
    fun endTemporarySpeed() {
        val base = temporaryBaseRate ?: return
        temporaryBaseRate = null
        lastTempSpeedWriteAt = 0L
        if (!released && kotlin.math.abs(player.rate - base) > 0.01f) {
            player.rate = base
            state.value = state.value.copy(rate = player.rate)
        }
    }
    fun setRepeat(value: String) {
        repeatMode.value = value
        Store.prefs(this).edit().putString("repeat", value).apply()
    }
    fun toggleShuffle() { shuffleEnabled.value = !shuffleEnabled.value }
    fun playQueueItem(item: MediaItem) {
        val next = queue.indexOfFirst { it.uri == item.uri }
        if (next >= 0) { savePosition(); index = next; retried = false; openCurrent(null) }
    }
    fun markAB() {
        val time = player.time.coerceAtLeast(0)
        when {
            repeatA == null -> repeatA = time
            repeatB == null && time > repeatA!! + 500 -> repeatB = time
            else -> { repeatA = null; repeatB = null }
        }
        abPoints.value = repeatA to repeatB
    }
    private var index = 0
    private var retried = false
    private var finished = false
    private var decoderMode = "Auto"
    private var metadataDimensions: Pair<Int, Int>? = null
    private var resumeAnchor: Long? = null
    private var seekTarget: Long? = null
    private var seekStarted = 0L
    private var seekOrigin = 0L
    private var seekCorrection: Job? = null
    private var dimensionProbe: Job? = null

    private fun clearSeek() { seekTarget = null; seekStarted = 0L; seekCorrection?.cancel(); seekCorrection = null }
    private fun nativePosition(): Long {
        val time = player.time.coerceAtLeast(0)
        val target = seekTarget
        if (target != null) {
            val elapsed = SystemClock.elapsedRealtime() - seekStarted
            if ((elapsed > 150 && (kotlin.math.abs(time - seekOrigin) > 500 || kotlin.math.abs(time - target) <= 1500)) || elapsed > 1800) {
                diagnostics.event("seek-settled target=$target actual=$time elapsedMs=$elapsed")
                clearSeek()
            } else return target
        }
        return time.let { if (it > 0) { resumeAnchor = null; it } else resumeAnchor ?: it }
    }
    private var focusHeld = false
    private var sessionMediaKey: String? = null
    private var sessionDuration = -1L
    private var sessionStatus = -1
    private var sessionRate = -1f
    private var sessionSentAt = 0L
    private var sessionPosition = -1L
    private var resumeOnFocus = false
    private var ticker: Job? = null
    private var opening: Job? = null
    private var activeDescriptor: android.os.ParcelFileDescriptor? = null
    private var videoAttached = false
    private var requestedPause = false
    private var pendingPlayback = false
    private var videoLayout: org.videolan.libvlc.util.VLCVideoLayout? = null
    private var pendingStyleRestore: PlayerState? = null
    private var pendingStyleTracks: PlayerState? = null
    private val externalSubtitles = mutableListOf<String>()

    fun attachVideo(layout: org.videolan.libvlc.util.VLCVideoLayout) {
        if (released) return
        videoLayout = layout
        player.attachViews(layout, null, true, false)
        layout.findViewById<android.view.SurfaceView>(org.videolan.R.id.surface_subtitles)
            ?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSubtitlePlacement(controlsVisible) }
        videoAttached = true
        layout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSubtitlePlacement(controlsVisible) }
        updateSubtitlePlacement(controlsVisible)
        if (pendingPlayback) {
            pendingPlayback = false
            if (requestFocus()) player.play()
        }
    }

    fun detachVideo(layout: org.videolan.libvlc.util.VLCVideoLayout? = null) {
        if (layout != null && videoLayout != null && videoLayout !== layout) return
        videoAttached = false
        Library.videoPlaybackActive.value = state.value.playing && state.value.item?.audio == false
        videoLayout = null
        if (!released) player.detachViews()
    }

    private val noisy =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                pause()
            }
        }
    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            if (released) return@OnAudioFocusChangeListener
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeOnFocus = false
                    pause()
                    focusHeld = false
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeOnFocus = player.isPlaying
                    pause(false)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { ducked = true; endTemporarySpeed(); applyVolume() }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    ducked = false
                    applyVolume()
                    if (resumeOnFocus) {
                        resumeOnFocus = false
                        play()
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel("playback", "Playback", NotificationManager.IMPORTANCE_LOW)
            )
        val initialEffects = AudioEffectSettings.load(this)
        effectSettings.value = initialEffects
        vlc = createVlc(initialEffects)
        player = MediaPlayer(vlc)
        audioManager = getSystemService(AudioManager::class.java)
        audioManager.registerAudioDeviceCallback(routeCallback, Handler(Looper.getMainLooper()))
        repeatMode.value = Store.prefs(this).getString("repeat", "off") ?: "off"
        focus =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        session = MediaSession(this, "MS PLAYER")
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() = play()

                override fun onPause() = pause()

                override fun onStop() = stopPlayback()

                override fun onSeekTo(pos: Long) { seek(pos) }

                override fun onSkipToNext() = next()

                override fun onSkipToPrevious() = previous()
            }
        )
        session.isActive = true
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(
                noisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                RECEIVER_NOT_EXPORTED,
            )
        else
            @Suppress("DEPRECATION")
            registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        bindPlayerEvents()
        audioEffects.apply(player, initialEffects)
        diagnostics.enabled = Store.prefs(this).getBoolean("diagnostics", false)
        ticker =
            scope.launch {
                var lastTimelineAt = 0L
                var lastDiagAt = 0L
                var lastSaveAt = SystemClock.elapsedRealtime()
                while (isActive) {
                    val hasSubOverlay = (selectedSubTracks.isNotEmpty() || parsedCues.isNotEmpty() || legacyFolderCues.isNotEmpty())
                    delay(if (hasSubOverlay && player.isPlaying) 16L else 48L)
                    if (state.value.item != null && !finished) {
                        val posNow = nativePosition()
                        if (hasSubOverlay) {
                            updateSubtitleTextAt(posNow)
                        } else if (activeSubtitleText.value != null) {
                            activeSubtitleText.value = null
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastTimelineAt >= 480L) {
                            lastTimelineAt = now
                            if (state.value.videoWidth == 0 && state.value.item?.audio == false) {
                                player.currentVideoTrack?.let { video ->
                                    val dimensions = metadataDimensions ?: PlaybackRules.displayDimensions(video.width, video.height, video.orientation, video.sarNum, video.sarDen)
                                    state.value = state.value.copy(videoWidth = dimensions.first, videoHeight = dimensions.second)
                                }
                            }
                            state.value =
                                state.value.copy(
                                    position = posNow,
                                    duration = player.length.coerceAtLeast(0),
                                    seekable = player.isSeekable,
                                )
                            timeline.value = PlaybackTimeline(state.value.position, state.value.duration, state.value.seekable, seekTarget != null)
                            Library.videoPlaybackActive.value = videoAttached && state.value.item?.audio == false
                            if (repeatB != null && state.value.position >= repeatB!! && player.isPlaying) seek(repeatA ?: 0, false)
                            updateSession()
                        }
                        if (now - lastDiagAt >= 1000L) {
                            lastDiagAt = now
                            diagnostics.sample(player, state.value)
                        }
                        if (now - lastSaveAt >= 10000L) {
                            lastSaveAt = now
                            savePosition()
                        }
                    }
                }
            }
    }

    private fun createVlc(eff: AudioEffectSettings = effectSettings.value): LibVLC = LibVLC(
        this,
        arrayListOf(
            "--no-video-title-show",
            "--audio-time-stretch",
            // Native surfaces keep text subtitles independently positionable.
            // GLES remains available for projections the Android output cannot handle.
            "--vout=android-display,android-opaque,gles2",
            "--avcodec-skiploopfilter=1",
        ).apply {
            addAll(SubtitleStyle.load(this@PlaybackService).options())
            addAll(eff.spatializerOptions())
            if (BuildConfig.DEBUG && Store.prefs(this@PlaybackService).getBoolean("native_verbose", false)) add("--verbose=2")
        },
    )

    private fun bindPlayerEvents() {
        player.setEventListener { event ->
            if (released) return@setEventListener
            if (event.type == MediaPlayer.Event.EncounteredError || event.type == MediaPlayer.Event.Buffering)
                diagnostics.event("event=${event.type} buffering=${event.buffering} mode=$decoderMode")
            if (
                event.type in
                    listOf(
                        MediaPlayer.Event.Playing,
                        MediaPlayer.Event.Paused,
                        MediaPlayer.Event.EncounteredError,
                        MediaPlayer.Event.Opening,
                    )
            )
                android.util.Log.i("MSPlayer", "Playback event ${event.type}")
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    Library.videoPlaybackActive.value = state.value.item?.audio == false
                    state.value = state.value.copy(playing = true, buffering = false, error = null)
                    refreshTracks()
                    player.currentVideoTrack?.let { video ->
                        val dimensions = metadataDimensions ?: PlaybackRules.displayDimensions(video.width, video.height, video.orientation, video.sarNum, video.sarDen)
                        state.value = state.value.copy(videoWidth = dimensions.first, videoHeight = dimensions.second)
                    }
                    updateSubtitlePlacement(controlsVisible)
                    pendingStyleRestore?.let { prior ->
                        pendingStyleRestore = null
                        val savedExternals = externalSubtitles.toList()
                        externalSubtitles.clear()
                        savedExternals.forEach { path -> addSubtitle(path, selectExtra = false) }
                        refreshTracks(false)
                        player.rate = prior.rate
                        player.setAudioDelay(prior.audioDelay * 1000)
                        player.setSpuDelay(prior.subtitleDelay * 1000)
                        state.value = state.value.copy(rate = prior.rate, audioDelay = prior.audioDelay, subtitleDelay = prior.subtitleDelay)
                        if (!prior.playing) pause(false)
                    }
                    updateNotification()
                }
                MediaPlayer.Event.Paused -> {
                    Library.videoPlaybackActive.value = videoAttached && state.value.item?.audio == false
                    state.value = state.value.copy(playing = false, buffering = false)
                    updateNotification()
                }
                MediaPlayer.Event.ESAdded,
                MediaPlayer.Event.ESDeleted ->
                    scope.launch {
                        delay(150)
                        refreshTracks(false)
                    }
                MediaPlayer.Event.Buffering ->
                    if (!requestedPause) state.value = state.value.copy(buffering = event.buffering < 100f)
                MediaPlayer.Event.TimeChanged ->
                    if (!finished && (selectedSubTracks.isNotEmpty() || parsedCues.isNotEmpty() || legacyFolderCues.isNotEmpty())) {
                        updateSubtitleTextAt(nativePosition())
                    }
                MediaPlayer.Event.EndReached -> {
                    Library.videoPlaybackActive.value = false
                    finished = true
                    savePosition(true)
                    endTemporarySpeed()
                    if (shuffleEnabled.value && queue.size > 1 && repeatMode.value != "one") { next(); return@setEventListener }
                    when (Store.prefs(this).getString("repeat", "off")) {
                        "one" -> {
                            val prior = state.value.copy(playing = true)
                            pendingStyleRestore = prior
                            pendingStyleTracks = prior
                            openCurrent(0, restore = false, keepSubtitleChoice = true)
                        }
                        "all" -> {
                            index = (index + 1) % queue.size.coerceAtLeast(1)
                            openCurrent(0)
                        }
                        else ->
                            if (index + 1 < queue.size) {
                                index++
                                openCurrent(0)
                            } else {
                                state.value =
                                    state.value.copy(
                                        playing = false,
                                        position = state.value.duration,
                                    )
                                timeline.value = PlaybackTimeline(state.value.duration, state.value.duration, state.value.seekable)
                                updateNotification()
                                abandonFocus()
                                stopForeground(STOP_FOREGROUND_DETACH)
                            }
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    if (!retried && decoderMode != "Software") {
                        retried = true
                        decoderMode = "Software"
                        openCurrent(state.value.position, false)
                    } else {
                        Library.videoPlaybackActive.value = false
                        clearSeek()
                        val itemUri = state.value.item?.uri ?: ""
                        state.value =
                            state.value.copy(
                                error = when {
                                    PlaybackRules.isStreamUrl(itemUri) ->
                                        "Unable to play stream URL. Verify it is a direct media or manifest link (.mp4, .mkv, .m3u8, .mpd, rtsp://)."
                                    PlaybackRules.temporaryFile(state.value.item?.title ?: "") ->
                                        "This temporary download is incomplete or unsupported. Refresh and reopen after the download finishes."
                                    else -> "Unable to play this file. Check file access and format support."
                                },
                                playing = false,
                                buffering = false,
                            )
                        abandonFocus()
                        updateNotification()
                        stopForeground(STOP_FOREGROUND_DETACH)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?) = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "play" -> play()
            "pause" -> pause()
            "next" -> next()
            "previous" -> previous()
            "stop" -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    fun open(items: List<MediaItem>, selected: MediaItem) {
        endTemporarySpeed()
        resetBoost()
        savePosition()
        externalSubtitles.clear()
        pendingStyleTracks = null
        queue = items.ifEmpty { listOf(selected) }
        queueItems.value = queue
        index = queue.indexOfFirst { it.uri == selected.uri }.coerceAtLeast(0)
        retried = false
        decoderMode = Store.prefs(this).getString("decoder", "Auto") ?: "Auto"
        openCurrent(null)
    }

    private fun openCurrent(position: Long?, restore: Boolean = true, keepSubtitleChoice: Boolean = false) {
        endTemporarySpeed()
        repeatA = null; repeatB = null; abPoints.value = null to null
        val item = queue.getOrNull(index) ?: return
        opening?.cancel()
        dimensionProbe?.cancel()
        clearSeek()
        seekThumbCache.evictAll()
        finished = false
        activeSubtitleText.value = null
        selectedSubTracks.clear()
        userModifiedSubtitles = keepSubtitleChoice
        initialSubRestored = keepSubtitleChoice && pendingStyleTracks?.selectedSubtitleTracks.isNullOrEmpty()
        autoSubCues = emptyList()
        parsedCues = emptyList()
        activeCues = emptyList()
        embeddedCuesByTrack = emptyMap()
        embeddedTracks = emptyMap()
        internalSpuIdsOrdered = emptyList()
        pendingExternalAdds.clear()
        externalSpuPathsById.clear()
        externalCuesBySpuId.clear()
        externalLabelsBySpuId.clear()
        requestedPause = false
        Library.videoPlaybackActive.value = item.audio.not()
        // Auto-detect embedded MKV subtitle cues, saved AI subtitles, and sidecar files
        scope.launch(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(item.uri)
                val localFile = resolveLocalFile(uri)
                val extLower = (localFile?.extension ?: item.title.substringAfterLast('.', "")).lowercase()

                if (extLower == "mkv" || extLower == "webm") {
                    val onBatch: (Map<Int, ContainerDemuxer.EmbeddedTrack>, Map<Int, List<PlaybackRules.SubtitleCue>>) -> Unit = { batchTracks, batchCues ->
                        scope.launch(Dispatchers.Main) {
                            if (state.value.item?.uri == item.uri) {
                                embeddedTracks = batchTracks
                                embeddedCuesByTrack = batchCues
                                refreshTracks(restore = false)
                                if (selectedSubTracks.isNotEmpty()) {
                                    syncSubtitleTracks(persistName = false)
                                } else {
                                    maybeAutoSelectPrimarySubtitle()
                                }
                            }
                        }
                    }
                    val demux = if (localFile != null) {
                        ContainerDemuxer.parseMkv(localFile, onBatch)
                    } else {
                        ContainerDemuxer.parseMkv(this@PlaybackService, uri, onBatch)
                    }
                    withContext(Dispatchers.Main) {
                        if (state.value.item?.uri == item.uri) {
                            embeddedTracks = demux.tracks
                            embeddedCuesByTrack = demux.cuesByTrack
                            refreshTracks(restore = false)
                            if (selectedSubTracks.isNotEmpty()) {
                                syncSubtitleTracks(persistName = false)
                            } else {
                                maybeAutoSelectPrimarySubtitle()
                            }
                        }
                    }
                }

                // Discover saved AI subtitles or matching sidecar SRT/ASS/VTT in folder
                val savedCustomSub = Store.prefs(this@PlaybackService).getString("custom_sub_${item.uri}", null)
                val baseName = localFile?.nameWithoutExtension ?: item.title.substringBeforeLast('.')
                val sanitizedBase = baseName.replace(Regex("""[^a-zA-Z0-9._ -]"""), "_").trim()
                val baseCandidates = listOf(baseName, sanitizedBase).filter { it.isNotBlank() }.distinct()
                val aiSrtCandidates = buildList {
                    savedCustomSub?.let { add(java.io.File(it)) }
                    for (name in baseCandidates) {
                        localFile?.parentFile?.let { add(java.io.File(it, "$name.srt")) }
                        add(java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "$name.srt"))
                        add(java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "MSPlayer/$name.srt"))
                    }
                }
                val matchingAiSrt = aiSrtCandidates.firstOrNull { it.exists() && it.isFile && it.length() > 0 }
                if (matchingAiSrt != null) {
                    val parsedAi = runCatching { PlaybackRules.parseSubtitleCues(readSubtitleText(matchingAiSrt)) }.getOrDefault(emptyList())
                    if (parsedAi.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (state.value.item?.uri == item.uri) {
                                autoSubCues = parsedAi.sortedBy { it.startMs }
                                // Auto-select saved AI / exact-stem subtitles on initial open, preserving user uncheck on decoder/style reload
                                if (!userModifiedSubtitles && pendingStyleTracks == null) {
                                    selectedSubTracks.clear()
                                    selectedSubTracks.add(9999)
                                    initialSubRestored = true
                                }
                                refreshTracks(false)
                                if (9999 in selectedSubTracks) {
                                    syncSubtitleTracks("AI Subtitles", persistName = false)
                                }
                            }
                        }
                    }
                }

                // Expose other external sidecar subtitle files in the video's folder (excluding app-private test dir)
                val parentDir = localFile?.parentFile
                val isPrivateAppFilesDir = localFile?.absolutePath?.startsWith(filesDir.absolutePath) == true
                if (parentDir != null && parentDir.isDirectory && !isPrivateAppFilesDir) {
                    val aiPath = matchingAiSrt?.absolutePath
                    val sidecars = parentDir.listFiles()
                        ?.filter { f ->
                            val stem = f.nameWithoutExtension
                            f.isFile &&
                                f.absolutePath != aiPath &&
                                f.extension.lowercase() in listOf("srt", "vtt", "ass", "ssa") &&
                                baseCandidates.any { base ->
                                    stem.equals(base, ignoreCase = true) ||
                                        stem.startsWith("$base.", ignoreCase = true) ||
                                        stem.startsWith("$base ", ignoreCase = true) ||
                                        stem.startsWith("${base}_", ignoreCase = true)
                                }
                        }
                        ?.sortedBy { it.name.lowercase() }
                        ?.take(8)
                        .orEmpty()
                    if (sidecars.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (state.value.item?.uri == item.uri) {
                                sidecars.forEach { sidecar ->
                                    addSubtitle(sidecar.absolutePath, selectExtra = false)
                                }
                            }
                        }
                    }
                }

                // Restore previously imported OpenSubtitles / picked subtitle files for this item
                val savedImports = Store.prefs(this@PlaybackService)
                    .getStringSet("imported_subs_${item.uri}", emptySet())
                    .orEmpty()
                    .map { java.io.File(it) }
                    .filter { it.exists() && it.isFile && it.length() > 0 && it.absolutePath != matchingAiSrt?.absolutePath }
                if (savedImports.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (state.value.item?.uri == item.uri) {
                            savedImports.forEach { imp ->
                                addSubtitle(imp.absolutePath, selectExtra = false)
                            }
                        }
                    }
                }
            }
        }
        opening =
            scope.launch {
                val saved =
                    if (restore && Store.prefs(this@PlaybackService).getBoolean("resume", true))
                        withContext(Dispatchers.IO) {
                            Store.db(this@PlaybackService).dao().saved(item.uri)
                        }
                    else null
                try {
                    metadataDimensions = null
                    clearSeek()
                    dimensionProbe?.cancel()
                    player.stop()
                    activeDescriptor?.close()
                    activeDescriptor = null
                    // Parcel file descriptors preserve provider permissions and support local
                    // seekable content.
                    val uri = Uri.parse(item.uri)
                    val media =
                        if (uri.scheme == "content") {
                            activeDescriptor =
                                contentResolver.openFileDescriptor(uri, "r")
                                    ?: throw IllegalStateException("File unavailable")
                            Media(vlc, activeDescriptor!!.fileDescriptor)
                        } else Media(vlc, uri)
                    media.setHWDecoderEnabled(decoderMode != "Software", decoderMode == "Hardware")
                    if (PlaybackRules.isStreamUrl(item.uri)) {
                        media.addOption(":network-caching=1500")
                        media.addOption(":http-reconnect")
                        media.addOption(":http-forward-cookies")
                        media.addOption(":http-user-agent=Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 MSPLAYER/0.6.2")
                        Store.recordStream(this@PlaybackService, item.uri)
                    }
                    val start =
                        position
                            ?: saved?.let {
                                PlaybackRules.resume(it.position, item.duration, it.watched)
                            }
                            ?: 0
                    if (start > 0) media.addOption(":start-time=${start / 1000.0}")
                    resumeAnchor = start.takeIf { it > 0 }
                    state.value =
                        PlayerState(
                            item = item,
                            videoWidth = metadataDimensions?.first ?: 0,
                            videoHeight = metadataDimensions?.second ?: 0,
                            buffering = true,
                            decoder = decoderMode,
                            position = start,
                            duration = item.duration,
                        )
                    timeline.value = PlaybackTimeline(start, item.duration, false)
                    player.media = media
                    player.rate = 1f
                    media.release()
                    foreground()
                    // Native rotation/SAR is available with the first track event. Avoid
                    // a second demux/metadata reader before high-bitrate playback starts.
                    dimensionProbe = scope.launch {
                        delay(2000)
                        if (!item.audio && state.value.videoWidth == 0) {
                            val dimensions = Library.videoDimensions(this@PlaybackService, item.uri)
                            if (state.value.item?.uri == item.uri && dimensions != null) {
                                metadataDimensions = dimensions
                                state.value = state.value.copy(videoWidth = dimensions.first, videoHeight = dimensions.second)
                            }
                        }
                    }
                    if (requestFocus()) {
                        if (item.audio || videoAttached) player.play() else pendingPlayback = true
                    } else
                        state.value =
                            state.value.copy(
                                buffering = false,
                                error = "Audio focus is currently unavailable. Tap play to retry.",
                            )
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    Library.videoPlaybackActive.value = false
                    state.value = PlayerState(item = item, error = if (PlaybackRules.temporaryFile(item.title))
                        "This download is unavailable or still private to its downloader. Open a completed copy or choose an accessible folder."
                        else "Cannot open file: ${e.message}")
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            }
    }

    fun play() {
        if (released) return
        requestedPause = false
        pendingStyleRestore = pendingStyleRestore?.copy(playing = true)
        Library.videoPlaybackActive.value = state.value.item?.audio == false
        if (state.value.item == null) return
        if (finished) {
            openCurrent(0, false)
            return
        }
        foreground()
        if (requestFocus()) player.play()
    }

    fun pause(abandon: Boolean = true) {
        endTemporarySpeed()
        if (released) return
        Library.videoPlaybackActive.value = videoAttached && state.value.item?.audio == false
        requestedPause = true
        pendingStyleRestore = pendingStyleRestore?.copy(playing = false)
        if (player.isPlaying) player.pause()
        state.value = state.value.copy(buffering = false)
        savePosition()
        if (abandon) abandonFocus()
    }

    fun toggle() {
        if (player.isPlaying) pause() else play()
    }

    fun next() {
        if (shuffleEnabled.value && queue.size > 1) {
            savePosition(); index = queue.indices.filter { it != index }.random(); retried = false; openCurrent(null); return
        }
        if (index + 1 >= queue.size && repeatMode.value == "all" && queue.isNotEmpty()) { savePosition(); index = 0; openCurrent(null); return }
        if (index + 1 < queue.size) {
            savePosition()
            index++
            retried = false
            openCurrent(null)
        }
    }

    fun previous() {
        if (player.time > 3000 && player.isSeekable) seek(0)
        else if (index > 0) {
            savePosition()
            index--
            retried = false
            openCurrent(null)
        }
    }

    fun seek(position: Long, fast: Boolean = Store.prefs(this).getBoolean("fast_seek", true)): Boolean {
        if (released || !player.isSeekable || player.length <= 0) return false
        resumeAnchor = null
        seekCorrection?.cancel()
        val target = PlaybackRules.safeSeek(position, player.length)
        seekOrigin = player.time.coerceAtLeast(0)
        seekTarget = target
        seekStarted = SystemClock.elapsedRealtime()
        // Accurate seeking may decode a whole HEVC GOP before displaying a frame.
        // User scrubbing uses the engine's keyframe-friendly fast path.
        val result = player.setTime(target, fast)
        if (result < 0) { clearSeek(); return false }
        state.value = state.value.copy(position = target)
        timeline.value = PlaybackTimeline(target, player.length, true, true)
        if (parsedCues.isNotEmpty() || selectedSubTracks.isNotEmpty()) {
            updateSubtitleTextAt(target)
        }
        diagnostics.event("seek-request from=$seekOrigin target=$target fast=$fast")
        if (fast) seekCorrection = scope.launch {
            delay(900)
            if (!released && seekTarget == target) {
                val actual = player.time
                // A long GOP can map a tap to the same keyframe it started from.
                // Correct that one no-movement case once, without a retry loop.
                if (kotlin.math.abs(actual - target) > 2000 && kotlin.math.abs(actual - seekOrigin) < 1500) {
                    diagnostics.event("seek-precise-correction target=$target actual=$actual")
                    player.setTime(target, false)
                }
            }
        }
        updateSession()
        return true
    }

    private val seekThumbCache = object : android.util.LruCache<Long, android.graphics.Bitmap>(16) {}

    fun seekThumbnail(position: Long): android.graphics.Bitmap? {
        if (released) return null
        val item = state.value.item ?: return null
        if (item.audio) return null
        val bucket = (position / 1000L) * 1000L
        seekThumbCache.get(bucket)?.let { return it }
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                val uri = Uri.parse(item.uri)
                var frame: android.graphics.Bitmap? = null
                if (uri.scheme == "content") {
                    contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                        retriever.setDataSource(fd.fileDescriptor)
                        frame = if (Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(position * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90)
                        } else {
                            retriever.getFrameAtTime(position * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                    }
                } else if (uri.scheme == "file") {
                    retriever.setDataSource(uri.path)
                    frame = if (Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(position * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90)
                    } else {
                        retriever.getFrameAtTime(position * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                }
                if (frame != null) {
                    seekThumbCache.put(bucket, frame)
                }
                frame
            } finally {
                retriever.release()
            }
        } catch (_: Exception) { null }
    }

    fun rate(value: Float) {
        endTemporarySpeed()
        player.rate = value.coerceIn(.25f, 4f)
        state.value = state.value.copy(rate = player.rate)
    }

    fun setAspectMode(option: PlaybackRules.AspectOption) {
        if (released) return
        player.setScale(0f)
        player.setAspectRatio(null)
        val scaleType = when (option) {
            PlaybackRules.AspectOption.BEST_FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            PlaybackRules.AspectOption.CROP -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            PlaybackRules.AspectOption.STRETCH -> MediaPlayer.ScaleType.SURFACE_FILL
            PlaybackRules.AspectOption.RATIO_16_9 -> MediaPlayer.ScaleType.SURFACE_16_9
            PlaybackRules.AspectOption.RATIO_4_3 -> MediaPlayer.ScaleType.SURFACE_4_3
            PlaybackRules.AspectOption.ORIGINAL -> MediaPlayer.ScaleType.SURFACE_ORIGINAL
        }
        player.setVideoScale(scaleType)
    }

    fun decoder(mode: String) {
        val prior = state.value.copy(playing = state.value.playing && !requestedPause)
        Store.prefs(this).edit().putString("decoder", mode).apply()
        decoderMode = mode
        retried = mode == "Software"
        state.value = state.value.copy(decoder = mode)
        pendingStyleRestore = prior
        pendingStyleTracks = prior
        openCurrent(state.value.position, restore = false, keepSubtitleChoice = true)
    }

    private fun maybeAutoSelectPrimarySubtitle() {
        if (userModifiedSubtitles || initialSubRestored || selectedSubTracks.isNotEmpty() || pendingStyleTracks != null) return
        val preferredSub = Store.prefs(this).getString("subtitle_name", null)
        val target = PlaybackRules.choosePrimarySubtitle(state.value.subtitles, preferredSub) ?: return
        initialSubRestored = true
        selectedSubTracks.clear()
        selectedSubTracks.add(target.first)
        syncSubtitleTracks(target.second, persistName = false)
    }

    fun refreshTracks(restore: Boolean = true) {
        player.currentVideoTrack?.let { video ->
            val dimensions = metadataDimensions ?: PlaybackRules.displayDimensions(video.width, video.height, video.orientation, video.sarNum, video.sarDen)
            state.value = state.value.copy(videoWidth = dimensions.first, videoHeight = dimensions.second)
        }
        val media = player.media
        val nativeTextIds = try {
            media?.let { m ->
                (0 until m.trackCount).mapNotNull { m.getTrack(it) }
                    .filter { it.type == org.videolan.libvlc.interfaces.IMedia.Track.Type.Text }
                    .map { it.id }
                    .toSet()
            }.orEmpty()
        } finally {
            media?.release()
        }

        val spuRawList = player.spuTracks?.map { it.id to it.name }.orEmpty().toMutableList()
        val subTracks = embeddedTracks.values.filter { it.type == 17 }.sortedBy { it.trackNumber }

        // Associate any newly registered external LibVLC slave SPU IDs with queued addSubtitle() requests
        var autoSelectedExternal = false
        for ((id, _) in spuRawList) {
            if (id < 0 || id == 9999 || id in externalSpuPathsById) continue
            val isExternalSlave = if (nativeTextIds.isNotEmpty()) {
                id !in nativeTextIds
            } else {
                pendingExternalAdds.isNotEmpty() && id !in internalSpuIdsOrdered
            }
            if (isExternalSlave && pendingExternalAdds.isNotEmpty()) {
                val req = pendingExternalAdds.removeFirst()
                externalSpuPathsById[id] = req.path
                externalLabelsBySpuId[id] = req.label
                externalCuesBySpuId[id] = req.cues
                if (req.selectExtra) {
                    userModifiedSubtitles = true
                    selectedSubTracks.add(id)
                    autoSelectedExternal = true
                }
            }
        }

        internalSpuIdsOrdered = spuRawList.map { it.first }.filter { id ->
            id >= 0 && id != 9999 && id !in externalSpuPathsById &&
                (nativeTextIds.isEmpty() || id in nativeTextIds)
        }

        if (autoSubCues.isNotEmpty() && spuRawList.none { it.first == 9999 }) {
            spuRawList.add(9999 to "AI Subtitles")
        }

        val mappedList = spuRawList.map { (id, name) ->
            when {
                id < 0 -> id to name
                id == 9999 -> id to "AI Subtitles"
                id in externalLabelsBySpuId -> id to externalLabelsBySpuId[id]!!
                else -> {
                    val internalIdx = internalSpuIdsOrdered.indexOf(id)
                    val trackInfo = if (internalIdx in subTracks.indices) subTracks[internalIdx] else null
                    val cleanName = when {
                        trackInfo != null && trackInfo.name.isNotBlank() -> trackInfo.name
                        name.isNotBlank() && !name.startsWith("Track ") && name != "Disable" -> name
                        trackInfo != null -> "Track ${internalIdx + 1} [${trackInfo.language}]"
                        else -> name.ifBlank { "Track $id" }
                    }
                    id to cleanName
                }
            }
        }

        state.value =
            state.value.copy(
                audioTracks = player.audioTracks?.map { it.id to it.name }.orEmpty(),
                subtitles = mappedList,
                audioTrack = player.audioTrack,
                subtitleTrack = selectedSubTracks.firstOrNull() ?: player.spuTrack,
                selectedSubtitleTracks = selectedSubTracks.toSet()
            )
        updateSubtitleKind()

        val prefs = Store.prefs(this)
        val preferredAudio = prefs.getString("audio_name", null)
        if (restore) {
            preferredAudio?.let { n ->
                state.value.audioTracks
                    .firstOrNull { it.second == n }
                    ?.let { player.setAudioTrack(it.first) }
            }
            state.value =
                state.value.copy(audioTrack = player.audioTrack, subtitleTrack = selectedSubTracks.firstOrNull() ?: player.spuTrack)
        }
        pendingStyleTracks?.let { prior ->
            if (prior.item?.uri != state.value.item?.uri) pendingStyleTracks = null
            else {
                val audioReady = prior.audioTrack < 0 || state.value.audioTracks.any { it.first == prior.audioTrack }
                val targetSubs = prior.selectedSubtitleTracks
                val targetNames = prior.subtitles.filter { it.first in targetSubs }.map { it.second }.toSet()
                val resolvedSubIds = LinkedHashSet<Int>().apply {
                    for (oldId in targetSubs) {
                        if (state.value.subtitles.any { it.first == oldId }) {
                            add(oldId)
                        } else {
                            val oldName = prior.subtitles.firstOrNull { it.first == oldId }?.second
                            val byName = oldName?.let { n ->
                                state.value.subtitles.firstOrNull { it.first >= 0 && it.second == n }?.first
                            }
                            if (byName != null) add(byName)
                        }
                    }
                }
                val subsReady = targetSubs.all { oldId ->
                    state.value.subtitles.any { it.first == oldId } ||
                        prior.subtitles.firstOrNull { it.first == oldId }?.second in state.value.subtitles.filter { it.first >= 0 }.map { it.second }
                }
                if (audioReady) player.setAudioTrack(prior.audioTrack)
                if (resolvedSubIds.isNotEmpty()) {
                    selectedSubTracks.clear()
                    selectedSubTracks.addAll(resolvedSubIds)
                    syncSubtitleTracks(persistName = false)
                } else if (prior.subtitleTrack < 0 || state.value.subtitles.any { it.first == prior.subtitleTrack }) {
                    if (prior.subtitleTrack >= 0) {
                        selectedSubTracks.clear()
                        selectedSubTracks.add(prior.subtitleTrack)
                    } else if (targetSubs.isEmpty() && targetNames.isEmpty()) {
                        selectedSubTracks.clear()
                    }
                    syncSubtitleTracks(persistName = false)
                }
                state.value = state.value.copy(
                    audioTrack = player.audioTrack,
                    subtitleTrack = selectedSubTracks.firstOrNull() ?: player.spuTrack,
                    selectedSubtitleTracks = selectedSubTracks.toSet()
                )
                if (audioReady && (subsReady || targetSubs.isEmpty())) pendingStyleTracks = null
            }
        }
        if (autoSelectedExternal && pendingStyleTracks == null) {
            syncSubtitleTracks(persistName = false)
        }
        maybeAutoSelectPrimarySubtitle()
        updateSubtitleKind()
    }

    fun rememberImportedSubtitle(mediaUri: String, path: String) {
        val prefs = Store.prefs(this)
        val updated = (prefs.getStringSet("imported_subs_$mediaUri", emptySet()).orEmpty() + path).take(12).toSet()
        prefs.edit().putStringSet("imported_subs_$mediaUri", updated).apply()
    }

    fun currentLocalMediaFile(): java.io.File? =
        state.value.item?.uri?.let { runCatching { resolveLocalFile(Uri.parse(it)) }.getOrNull() }

    fun audioTrack(id: Int, name: String) {
        pendingStyleTracks = pendingStyleTracks?.copy(audioTrack=id)
        pendingStyleRestore = pendingStyleRestore?.copy(audioTrack=id)
        player.setAudioTrack(id)
        Store.prefs(this).edit().putString("audio_name", name).apply()
        state.value = state.value.copy(audioTrack = id)
    }

    private fun updateSubtitleKind() {
        val media = player.media
        try {
            val nativeTracks = media?.let { m -> (0 until m.trackCount).mapNotNull { m.getTrack(it) }.filter { it.type == org.videolan.libvlc.interfaces.IMedia.Track.Type.Text } }.orEmpty()
            val track = nativeTracks.firstOrNull { it.id == player.spuTrack }
            val codec = track?.let { PlaybackRules.fourcc(it.fourcc).lowercase() } ?: ""
            val nativeIds = nativeTracks.map { it.id }.toSet()
            val externalIds = player.spuTracks.orEmpty().map { it.id }.filter { it >= 0 && it !in nativeIds }.sorted()
            val externalPath = externalSpuPathsById[player.spuTrack] ?: externalSubtitles.getOrNull(externalIds.indexOf(player.spuTrack))
            val authoredExternal = track == null && externalPath?.substringAfterLast('.')?.lowercase() in listOf("ass", "ssa", "sup", "sub", "idx")
            val vTrack = media?.let { m -> (0 until m.trackCount).mapNotNull { m.getTrack(it) }.filterIsInstance<org.videolan.libvlc.interfaces.IMedia.VideoTrack>().firstOrNull() }
            val vCodec = vTrack?.let { PlaybackRules.fourcc(it.fourcc).uppercase() } ?: ""
            state.value = state.value.copy(
                plainSubtitle = !authoredExternal && codec !in listOf("ssa", "ass", "pgs", "pgss", "bdpg", "spu", "dvbs", "dvd"),
                videoCodec = vCodec.takeIf { it.isNotBlank() } ?: state.value.videoCodec
            )
        } finally { media?.release() }
        updateSubtitlePlacement(controlsVisible)
    }

    private fun cuesForTrackId(tId: Int): List<PlaybackRules.SubtitleCue> {
        if (tId == 9999) return autoSubCues
        externalCuesBySpuId[tId]?.let { if (it.isNotEmpty()) return it }
        val subTracks = embeddedTracks.values.filter { it.type == 17 }.sortedBy { it.trackNumber }
        val internalIdx = internalSpuIdsOrdered.indexOf(tId)
        if (internalIdx in subTracks.indices) {
            return embeddedCuesByTrack[subTracks[internalIdx].trackNumber].orEmpty()
        }
        return emptyList()
    }

    fun toggleSubtitleTrack(id: Int, name: String) {
        userModifiedSubtitles = true
        if (id < 0) {
            selectedSubTracks.clear()
        } else {
            if (id in selectedSubTracks) {
                selectedSubTracks.remove(id)
            } else {
                selectedSubTracks.add(id)
            }
        }
        syncSubtitleTracks(name, persistName = id >= 0 && selectedSubTracks.size == 1)
    }

    fun subtitleTrack(id: Int, name: String) {
        userModifiedSubtitles = true
        pendingStyleTracks = pendingStyleTracks?.copy(subtitleTrack = id)
        pendingStyleRestore = pendingStyleRestore?.copy(subtitleTrack = id)
        selectedSubTracks.clear()
        if (id >= 0) selectedSubTracks.add(id)
        syncSubtitleTracks(name, persistName = true)
    }

    private fun isAuthoredOrBitmapTrack(tId: Int): Boolean {
        if (tId == 9999 || tId < 0) return false
        val cues = cuesForTrackId(tId)
        if (cues.isEmpty()) return true
        val ext = externalSpuPathsById[tId]?.substringAfterLast('.')?.lowercase()
        if (ext in listOf("ass", "ssa", "sup", "sub", "idx")) return true
        val subTracks = embeddedTracks.values.filter { it.type == 17 }.sortedBy { it.trackNumber }
        val internalIdx = internalSpuIdsOrdered.indexOf(tId)
        val codec = if (internalIdx in subTracks.indices) subTracks[internalIdx].codecId.lowercase() else ""
        return codec.contains("ass") || codec.contains("ssa") || codec.contains("pgs") ||
            codec.contains("vob") || codec.contains("dvb") || codec.contains("spu")
    }

    private fun resolvePrimaryHwTrack(): Int {
        // Route plain-text SRT/VTT/AI tracks through StrokeView Canvas so fill, border,
        // and shadow stay pixel-locked with zero LibVLC FreeType drift. Keep bitmap
        // PGS/VobSub and authored ASS/SSA tracks on LibVLC native SPU.
        return selectedSubTracks.firstOrNull { isAuthoredOrBitmapTrack(it) } ?: -1
    }

    private fun syncSubtitleTracks(name: String = "", persistName: Boolean = true) {
        val position = nativePosition()
        val primaryHwTrack = resolvePrimaryHwTrack()
        player.setSpuTrack(primaryHwTrack)

        val secondaryTracks = selectedSubTracks.filter { it != primaryHwTrack }
        val secondaryCues = mutableListOf<PlaybackRules.SubtitleCue>()
        for (tId in secondaryTracks) {
            secondaryCues.addAll(cuesForTrackId(tId))
        }
        if (secondaryCues.isEmpty() && selectedSubTracks.isEmpty() && legacyFolderCues.isNotEmpty()) {
            secondaryCues.addAll(legacyFolderCues)
        }

        parsedCues = secondaryCues.sortedBy { it.startMs }
        val jumpTimestamps = mutableListOf<Long>()
        for (tId in selectedSubTracks) {
            cuesForTrackId(tId).forEach { jumpTimestamps.add(it.startMs) }
        }
        activeCues = jumpTimestamps.sorted().distinct()

        updateSubtitleTextAt(position)

        if (persistName && name.isNotBlank()) {
            Store.prefs(this).edit().putString("subtitle_name", name).apply()
        }
        state.value = state.value.copy(
            subtitleTrack = selectedSubTracks.firstOrNull() ?: -1,
            selectedSubtitleTracks = selectedSubTracks.toSet(),
            hasPrimaryHwSubtitle = primaryHwTrack >= 0
        )
        updateSubtitleKind()
    }

    fun updateSubtitleTextAt(position: Long) {
        val effectivePos = position - state.value.subtitleDelay
        val primaryHwTrack = resolvePrimaryHwTrack()
        val secondaryTracks = selectedSubTracks.filter { it != primaryHwTrack }
        val activeLines = mutableListOf<String>()

        for (tId in secondaryTracks) {
            val cues = cuesForTrackId(tId)
            if (cues.isNotEmpty()) {
                for (line in PlaybackRules.findActiveCueLines(cues, effectivePos)) {
                    if (line !in activeLines) activeLines.add(line)
                }
            }
        }

        if (secondaryTracks.isEmpty() && selectedSubTracks.isEmpty() && legacyFolderCues.isNotEmpty()) {
            for (line in PlaybackRules.findActiveCueLines(legacyFolderCues, effectivePos)) {
                if (line !in activeLines) activeLines.add(line)
            }
        }

        val combined = if (activeLines.isEmpty()) null else activeLines.joinToString("\n")
        if (activeSubtitleText.value != combined) {
            activeSubtitleText.value = combined
        }
    }

    fun activeSubtitleCues(): List<PlaybackRules.SubtitleCue> {
        for (tId in selectedSubTracks) {
            val cues = cuesForTrackId(tId)
            if (cues.isNotEmpty()) return cues
        }
        if (autoSubCues.isNotEmpty()) return autoSubCues
        if (parsedCues.isNotEmpty()) return parsedCues
        if (legacyFolderCues.isNotEmpty()) return legacyFolderCues
        externalCuesBySpuId.values.firstOrNull { it.isNotEmpty() }?.let { return it }
        embeddedCuesByTrack.values.firstOrNull { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    fun exportSubtitleShareUri(context: Context, file: java.io.File): Uri? {
        if (!file.exists()) return null
        val authority = "${context.packageName}.fileprovider"
        return runCatching {
            androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        }.getOrElse {
            runCatching {
                val staged = java.io.File(context.cacheDir, file.name)
                file.copyTo(staged, overwrite = true)
                androidx.core.content.FileProvider.getUriForFile(context, authority, staged)
            }.getOrNull()
        }
    }

    fun audioDelay(ms: Long) {
        player.setAudioDelay(ms * 1000)
        state.value = state.value.copy(audioDelay = ms)
    }

    fun subtitleDelay(ms: Long) {
        player.setSpuDelay(ms * 1000)
        state.value = state.value.copy(subtitleDelay = ms)
        updateSubtitleTextAt(nativePosition())
    }

    private var activeCues: List<Long> = emptyList()
    private var legacyFolderCues: List<PlaybackRules.SubtitleCue> = emptyList()

    fun loadCues(text: String) {
        legacyFolderCues = PlaybackRules.parseSubtitleCues(text)
        activeCues = legacyFolderCues.map { it.startMs }
        if (legacyFolderCues.isNotEmpty()) {
            player.spuTrack = -1
        }
        updateSubtitleTextAt(nativePosition())
    }

    fun loadAutoSubCues(cues: List<PlaybackRules.SubtitleCue>, userTriggered: Boolean = true) {
        autoSubCues = cues.sortedBy { it.startMs }
        if (userTriggered) {
            userModifiedSubtitles = true
            selectedSubTracks.add(9999)
        } else if (!userModifiedSubtitles || 9999 in selectedSubTracks) {
            selectedSubTracks.add(9999)
        }
        refreshTracks(false)
        if (9999 in selectedSubTracks) {
            syncSubtitleTracks("AI Subtitles", persistName = false)
        }
    }

    private fun resolveLocalFile(uri: Uri): java.io.File? {
        if (uri.scheme == "file") {
            return uri.path?.let { java.io.File(it) }?.takeIf { it.exists() }
        }
        if (uri.scheme == "content" && uri.authority == "media") {
            runCatching {
                @Suppress("DEPRECATION")
                contentResolver.query(
                    uri,
                    arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val path = cursor.getString(0)
                        if (!path.isNullOrBlank()) {
                            val file = java.io.File(path)
                            if (file.exists()) return file
                        }
                    }
                }
            }
        }
        val path = uri.path
        if (!path.isNullOrBlank()) {
            val file = java.io.File(path)
            if (file.exists()) return file
        }
        return null
    }

    private fun readSubtitleText(file: java.io.File): String {
        val bytes = file.readBytes()
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
        }
        val offset = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val encoding = SubtitleStyle.load(this).encoding
        val charset = if (encoding.isNotBlank() && encoding != "UTF-16") {
            runCatching { java.nio.charset.Charset.forName(encoding) }.getOrDefault(Charsets.UTF_8)
        } else Charsets.UTF_8
        return String(bytes, offset, bytes.size - offset, charset)
    }

    fun startAutoSubtitles(context: Context, translateToEnglish: Boolean = false) {
        val item = state.value.item ?: return
        if (autoSubStatus.value is AutoSubStatus.Processing) return

        val groqKey = SpeechToTextClient.activeGroqKey(context)
        val deepgramKey = SpeechToTextClient.activeDeepgramKey(context)
        if (translateToEnglish && groqKey.isBlank()) {
            autoSubStatus.value = AutoSubStatus.Error("Add AI Primary key for Direct English mode")
            return
        }
        if (groqKey.isBlank() && deepgramKey.isBlank()) {
            autoSubStatus.value = AutoSubStatus.Error("Configure an AI Subtitles API key in Settings or AI Subtitles")
            return
        }

        val validAudioTracks = state.value.audioTracks.filter { it.first >= 0 }
        val englishOrdinal = validAudioTracks.indexOfFirst { (_, label) ->
            val lower = label.lowercase(java.util.Locale.ROOT)
            lower.contains("english") || Regex("""\b(en|eng)\b""").containsMatchIn(lower)
        }
        val selectedOrdinal = validAudioTracks.indexOfFirst { it.first == state.value.audioTrack }
        val preferredOrdinal = if (translateToEnglish) selectedOrdinal else if (englishOrdinal >= 0) englishOrdinal else selectedOrdinal

        val targetUri = item.uri
        val targetTitle = item.title
        autoSubStatus.value = AutoSubStatus.Processing("Preparing audio...", 5)
        scope.launch(Dispatchers.IO) {
            val shownPartial = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                val uri = Uri.parse(targetUri)
                val result = SpeechToTextClient.extractAndTranscribeCascade(
                    context = context,
                    uri = uri,
                    cacheDir = context.cacheDir,
                    preferredAudioOrdinal = preferredOrdinal,
                    preferEnglishFirst = !translateToEnglish,
                    groqKey = groqKey,
                    deepgramKey = deepgramKey,
                    translateToEnglish = translateToEnglish,
                    fallbackChunkProvider = {
                        val fallbackFile = resolveLocalFile(uri)?.takeIf { it.length() <= SpeechToTextClient.MAX_GROQ_CHUNK_BYTES }
                        fallbackFile?.let { SpeechToTextClient.ExtractedChunk(it, 0L, "audio/m4a") }
                    },
                    isCancelled = { released || state.value.item?.uri != targetUri },
                    onStageProgress = { label, pct ->
                        if (!released && state.value.item?.uri == targetUri) {
                            autoSubStatus.value = AutoSubStatus.Processing(label, pct)
                        }
                    },
                    onPartialCues = { partialCues ->
                        if (partialCues.isNotEmpty() && !released && state.value.item?.uri == targetUri) {
                            val firstLive = shownPartial.compareAndSet(false, true)
                            scope.launch(Dispatchers.Main) {
                                if (!released && state.value.item?.uri == targetUri) {
                                    loadAutoSubCues(partialCues, userTriggered = firstLive)
                                }
                            }
                        }
                    }
                )

                if (released || state.value.item?.uri != targetUri) return@launch
                autoSubStatus.value = AutoSubStatus.Processing("Syncing subtitle timestamps...", 94)
                val cues = SpeechToTextClient.buildSubtitleCues(result.words)
                val videoFile = resolveLocalFile(uri)
                val savedFile = SpeechToTextClient.saveSrtFile(
                    context = context,
                    videoTitle = targetTitle,
                    cues = cues,
                    videoFile = videoFile,
                    langSuffix = if (translateToEnglish) "en" else null,
                )
                if (savedFile != null && savedFile.exists()) {
                    Store.prefs(this@PlaybackService).edit().putString("custom_sub_$targetUri", savedFile.absolutePath).apply()
                }

                withContext(Dispatchers.Main) {
                    if (released || state.value.item?.uri != targetUri) return@withContext
                    autoSubStatus.value = AutoSubStatus.Success(
                        file = savedFile,
                        count = cues.size,
                        provider = result.provider,
                        note = if (translateToEnglish) "Direct English" else null
                    )
                    loadAutoSubCues(cues, userTriggered = !shownPartial.get() || 9999 in selectedSubTracks)
                    android.widget.Toast.makeText(
                        context,
                        "AI Subtitles ready (${cues.size} lines)",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                if (released || state.value.item?.uri != targetUri) {
                    autoSubStatus.value = AutoSubStatus.Idle
                    return@launch
                }
                val cleanErr = SpeechToTextClient.sanitizeErrorMessage(e.message)
                autoSubStatus.value = AutoSubStatus.Error(cleanErr)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "AI Subtitles error: $cleanErr", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun translateActiveSubtitles(context: Context, target: SubtitleTranslator.TargetLanguage) {
        val item = state.value.item ?: return
        if (autoSubStatus.value is AutoSubStatus.Processing) return
        val sourceCues = activeSubtitleCues()
        if (sourceCues.isEmpty()) {
            autoSubStatus.value = AutoSubStatus.Error("Load or generate subtitles first")
            return
        }
        val targetUri = item.uri
        val targetTitle = item.title
        autoSubStatus.value = AutoSubStatus.Processing("Translating to ${target.label}...", 5)
        scope.launch(Dispatchers.IO) {
            val shownPartial = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                val uri = Uri.parse(targetUri)
                val videoFile = resolveLocalFile(uri)
                val res = SubtitleTranslator.translateCues(
                    context = context,
                    cues = sourceCues,
                    target = target,
                    videoTitle = targetTitle,
                    videoFile = videoFile,
                    isCancelled = { released || state.value.item?.uri != targetUri },
                    onProgress = { label, pct ->
                        if (!released && state.value.item?.uri == targetUri) {
                            autoSubStatus.value = AutoSubStatus.Processing(label, pct)
                        }
                    },
                    onPartialCues = { liveCues ->
                        if (liveCues.isNotEmpty() && !released && state.value.item?.uri == targetUri) {
                            val first = shownPartial.compareAndSet(false, true)
                            scope.launch(Dispatchers.Main) {
                                if (!released && state.value.item?.uri == targetUri) {
                                    loadAutoSubCues(liveCues, userTriggered = first)
                                }
                            }
                        }
                    },
                )
                if (released || state.value.item?.uri != targetUri) return@launch
                withContext(Dispatchers.Main) {
                    if (released || state.value.item?.uri != targetUri) return@withContext
                    val savedFile = res.savedFile
                    if (savedFile != null && savedFile.exists()) {
                        rememberImportedSubtitle(targetUri, savedFile.absolutePath)
                        selectedSubTracks.remove(9999)
                        addSubtitle(savedFile.absolutePath, selectExtra = true)
                    } else {
                        loadAutoSubCues(res.cues, userTriggered = true)
                    }
                    autoSubStatus.value = AutoSubStatus.Success(
                        file = savedFile,
                        count = res.cues.size,
                        provider = res.engineLabel,
                        note = "${target.label} SRT",
                    )
                    android.widget.Toast.makeText(
                        context,
                        "${target.label} subtitles ready (${res.cues.size})",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                if (released || state.value.item?.uri != targetUri) {
                    autoSubStatus.value = AutoSubStatus.Idle
                    return@launch
                }
                val cleanErr = SpeechToTextClient.sanitizeErrorMessage(e.message)
                autoSubStatus.value = AutoSubStatus.Error(cleanErr)
            }
        }
    }

    fun seekCue(forward: Boolean): Boolean {
        val current = timeline.value.position
        val target = if (forward) {
            PlaybackRules.findNextCue(activeCues, current) ?: (current + 5000L)
        } else {
            PlaybackRules.findPrevCue(activeCues, current) ?: (current - 5000L)
        }
        val safe = PlaybackRules.safeSeek(target, timeline.value.duration)
        seek(safe)
        return true
    }

    fun addSubtitle(path: String, selectExtra: Boolean = false): Boolean {
        if (path in externalSubtitles) {
            if (selectExtra) {
                val existingId = externalSpuPathsById.entries.firstOrNull { it.value == path }?.key
                if (existingId != null) {
                    userModifiedSubtitles = true
                    selectedSubTracks.add(existingId)
                    syncSubtitleTracks(persistName = false)
                }
            }
            return true
        }

        val parsed = runCatching {
            val file = java.io.File(path)
            if (file.isFile && file.length() < 5 * 1024 * 1024) {
                PlaybackRules.parseSubtitleCues(readSubtitleText(file))
            } else emptyList()
        }.getOrDefault(emptyList())

        val req = ExternalAddRequest(
            path = path,
            label = java.io.File(path).nameWithoutExtension,
            cues = parsed,
            selectExtra = selectExtra
        )
        pendingExternalAdds.addLast(req)

        val result = player.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, path, false)
        if (!result) {
            pendingExternalAdds.remove(req)
            return false
        }

        externalSubtitles.add(path)
        refreshTracks(false)
        if (selectExtra) syncSubtitleTracks(persistName = false)
        return true
    }

    /** FreeType is configured at LibVLC construction, so reopen the current item in place. */
    fun subtitleStyle(style: SubtitleStyle) {
        val previous = SubtitleStyle.load(this)
        if (previous == style) return
        style.save(this)
        subtitleStyleRevision.value++
        if (previous.options() == style.options() || (resolvePrimaryHwTrack() < 0 && previous.encoding == style.encoding)) {
            updateSubtitlePlacement(controlsVisible)
            return
        }
        endTemporarySpeed()
        val prior = state.value.copy(playing = state.value.playing && !requestedPause)
        if (prior.item == null) return
        opening?.cancel()
        pendingStyleRestore = prior
        pendingStyleTracks = prior
        player.setEventListener(null)
        player.stop()
        if (videoAttached) player.detachViews()
        activeDescriptor?.close()
        activeDescriptor = null
        player.release()
        vlc.release()
        val initialEffects = AudioEffectSettings.load(this)
        effectSettings.value = initialEffects
        vlc = createVlc(initialEffects)
        player = MediaPlayer(vlc)
        audioEffects.apply(player, effectSettings.value)
        applyVolume()
        bindPlayerEvents()
        videoLayout?.let { player.attachViews(it, null, true, false) }
        openCurrent(prior.position, restore = false, keepSubtitleChoice = true)
    }

    fun updateSubtitlePosition(bottomPercent: Int) {
        val style = SubtitleStyle.load(this).copy(bottomMargin = bottomPercent.coerceIn(0, 40), legacyMargin = null)
        style.save(this)
        subtitleStyleRevision.value++
        updateSubtitlePlacement(controlsVisible)
    }

    fun updateSubtitleScale(scalePercent: Int) {
        val style = SubtitleStyle.load(this).copy(scale = scalePercent.coerceIn(50, 300))
        style.save(this)
        subtitleStyleRevision.value++
        val layout = videoLayout ?: return
        layout.post {
            val surface = layout.findViewById<android.view.SurfaceView>(org.videolan.R.id.surface_subtitles) ?: return@post
            val scaleFactor = (scalePercent / 100f).coerceIn(0.5f, 3.0f)
            surface.pivotX = surface.width / 2f
            surface.pivotY = surface.height.toFloat()
            surface.scaleX = scaleFactor
            surface.scaleY = scaleFactor * (style.lineSpacing / 100f).coerceIn(0.8f, 2.0f)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopPlayback()
        super.onTaskRemoved(rootIntent)
    }

    fun stopPlayback() {
        if (released) return
        saveFinalPosition()
        releasePlayback()
        state.value = PlayerState(closed = true)
        timeline.value = PlaybackTimeline()
        stopSelf()
    }

    private fun saveFinalPosition() {
        val value = state.value
        val item = value.item ?: return
        val done = finished || PlaybackRules.completed(value.position, value.duration)
        Thread {
            Store.db(applicationContext).dao().save(SavedMedia().apply {
                uri = item.uri; title = item.title; watched = done
                position = if (done) 0 else value.position; updated = System.currentTimeMillis()
            })
        }.start()
    }

    private fun releasePlayback() {
        if (released) return
        endTemporarySpeed()
        released = true
        audioManager.unregisterAudioDeviceCallback(routeCallback)
        Library.videoPlaybackActive.value = false
        opening?.cancel()
        pendingStyleRestore = null
        pendingPlayback = false
        clearSeek()
        dimensionProbe?.cancel()
        scope.cancel()
        player.setEventListener(null)
        player.stop()
        player.detachViews()
        player.release()
        vlc.release()
        activeDescriptor?.close()
        activeDescriptor = null
        abandonFocus()
        session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(10)
    }

    private fun requestFocus(): Boolean {
        if (focusHeld) return true
        focusHeld = audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focus)
        focusHeld = false
    }

    private fun savePosition(watched: Boolean = false) {
        val current = state.value
        val item = current.item ?: return
        val complete =
            watched || finished || PlaybackRules.completed(current.position, current.duration)
        val row =
            SavedMedia().apply {
                uri = item.uri
                title = item.title
                position = if (complete) 0 else current.position
                updated = System.currentTimeMillis()
                this.watched = complete
            }
        scope.launch(Dispatchers.IO) { Store.db(this@PlaybackService).dao().save(row) }
    }

    private fun action(action: String) =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun notification(): android.app.Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).putExtra("show_player", true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return android.app.Notification.Builder(this, "playback")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(state.value.item?.title ?: "MS PLAYER")
            .setContentText(
                if (state.value.playing) "Playing · MS PLAYER" else "Paused · MS PLAYER"
            )
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSound(null)
            .setOngoing(state.value.playing)
            .addAction(android.R.drawable.ic_media_previous, "Previous", action("previous"))
            .addAction(
                if (state.value.playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                if (state.value.playing) "Pause" else "Play",
                action(if (state.value.playing) "pause" else "play"),
            )
            .addAction(android.R.drawable.ic_media_next, "Next", action("next"))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action("stop"))
            .setStyle(
                android.app.Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun foreground() {
        session.isActive = true
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(
                10,
                notification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        else startForeground(10, notification())
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(10, notification())
        updateSession()
    }

    private fun updateSession() {
        val value = state.value
        if (sessionMediaKey != value.item?.uri || sessionDuration != value.duration) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, value.item?.title)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, value.duration)
                    .build()
            )
            sessionMediaKey = value.item?.uri
            sessionDuration = value.duration
        }
        val status =
            if (value.item == null) PlaybackState.STATE_STOPPED
            else if (value.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val now = SystemClock.elapsedRealtime()
        // Android extrapolates the position from speed and timestamp. Avoid
        // rebroadcasting unchanged metadata/state on every UI timeline tick.
        if (
            status == sessionStatus &&
                value.rate == sessionRate &&
                kotlin.math.abs(value.position - sessionPosition) < 2000 &&
                now - sessionSentAt < 2000
        )
            return
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_STOP
                )
                .setState(status, value.position, value.rate)
                .build()
        )
        sessionStatus = status
        sessionRate = value.rate
        sessionSentAt = now
        sessionPosition = value.position
    }

    override fun onDestroy() {
        if (!released) {
            saveFinalPosition()
            releasePlayback()
        }
        unregisterReceiver(noisy)
        session.release()
        super.onDestroy()
    }
}
