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
    val audioDelay: Long = 0,
    val subtitleDelay: Long = 0,
)

class PlaybackService : Service() {
    inner class LocalBinder : Binder() {
        val service
            get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    val state = MutableStateFlow(PlayerState())
    val timeline = MutableStateFlow(PlaybackTimeline())
    private var released = false
    private var controlsVisible = true
    private val audioEffects = AudioEffects()
    val effectSettings = MutableStateFlow(AudioEffectSettings())
    private val diagnostics = PlaybackDiagnostics()

    fun effects(value: AudioEffectSettings) {
        if (released) return
        if (audioEffects.apply(player, value)) {
            effectSettings.value = value
            value.save(this)
        }
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
            val fraction = if (state.value.plainSubtitle) {
                (if (style.legacyMargin == null) style.bottomMargin / 100f else 0f) + if (visible) .20f else 0f
            } else 0f
            surface.translationY = -surface.height * fraction
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
        return true
    }
    fun temporarySpeed(value: Float): Float {
        if (temporaryBaseRate == null || released) return state.value.rate
        player.rate = value.coerceIn(.25f, 4f)
        return player.rate.also { state.value = state.value.copy(rate = it) }
    }
    fun endTemporarySpeed() {
        val base = temporaryBaseRate ?: return
        temporaryBaseRate = null
        if (!released) { player.rate = base; state.value = state.value.copy(rate = player.rate) }
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
            if ((elapsed > 100 && kotlin.math.abs(time - target) <= 1500) || elapsed > 4000) {
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

    fun detachVideo() {
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
        vlc = createVlc()
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
        effects(AudioEffectSettings.load(this))
        diagnostics.enabled = Store.prefs(this).getBoolean("diagnostics", false)
        ticker =
            scope.launch {
                var ticks = 0
                while (isActive) {
                    delay(500)
                    if (state.value.item != null && !finished) {
                        if (state.value.videoWidth == 0 && state.value.item?.audio == false) {
                            player.currentVideoTrack?.let { video ->
                                val dimensions = metadataDimensions ?: PlaybackRules.displayDimensions(video.width, video.height, video.orientation, video.sarNum, video.sarDen)
                                state.value = state.value.copy(videoWidth = dimensions.first, videoHeight = dimensions.second)
                            }
                        }
                        state.value =
                            state.value.copy(
                                position = nativePosition(),
                                duration = player.length.coerceAtLeast(0),
                                seekable = player.isSeekable,
                            )
                        timeline.value = PlaybackTimeline(state.value.position, state.value.duration, state.value.seekable, seekTarget != null)
                        Library.videoPlaybackActive.value = videoAttached && state.value.item?.audio == false
                        if (repeatB != null && state.value.position >= repeatB!! && player.isPlaying) seek(repeatA ?: 0, false)
                        updateSession()
                        if (++ticks % 2 == 0) diagnostics.sample(player, state.value)
                        if (ticks % 20 == 0) savePosition()
                    }
                }
            }
    }

    private fun createVlc(): LibVLC = LibVLC(
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
                        externalSubtitles.forEach { path ->
                            player.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, path, false)
                        }
                        refreshTracks()
                        // IDs belong to the same reopened media, never a sorted-track index.
                        if (state.value.audioTracks.any { it.first == prior.audioTrack }) player.setAudioTrack(prior.audioTrack)
                        if (state.value.subtitles.any { it.first == prior.subtitleTrack }) player.setSpuTrack(prior.subtitleTrack)
                        state.value = state.value.copy(audioTrack=player.audioTrack, subtitleTrack=player.spuTrack)
                        updateSubtitleKind()
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
                MediaPlayer.Event.EndReached -> {
                    Library.videoPlaybackActive.value = false
                    finished = true
                    savePosition(true)
                    endTemporarySpeed()
                    if (shuffleEnabled.value && queue.size > 1 && repeatMode.value != "one") { next(); return@setEventListener }
                    when (Store.prefs(this).getString("repeat", "off")) {
                        "one" -> openCurrent(0)
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
                        state.value =
                            state.value.copy(
                                error = if (PlaybackRules.temporaryFile(state.value.item?.title ?: ""))
                                    "This temporary download is incomplete or unsupported. Refresh and reopen after the download finishes."
                                    else "Unable to play this file. Check file access and format support.",
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
        queue = items.ifEmpty { listOf(selected) }
        queueItems.value = queue
        index = queue.indexOfFirst { it.uri == selected.uri }.coerceAtLeast(0)
        retried = false
        decoderMode = Store.prefs(this).getString("decoder", "Auto") ?: "Auto"
        openCurrent(null)
    }

    private fun openCurrent(position: Long?, restore: Boolean = true) {
        endTemporarySpeed()
        repeatA = null; repeatB = null; abPoints.value = null to null
        val item = queue.getOrNull(index) ?: return
        opening?.cancel()
        dimensionProbe?.cancel()
        clearSeek()
        finished = false
        requestedPause = false
        Library.videoPlaybackActive.value = item.audio.not()
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

    fun rate(value: Float) {
        endTemporarySpeed()
        player.rate = value.coerceIn(.25f, 4f)
        state.value = state.value.copy(rate = player.rate)
    }

    fun decoder(mode: String) {
        Store.prefs(this).edit().putString("decoder", mode).apply()
        decoderMode = mode
        retried = mode == "Software"
        openCurrent(state.value.position, false)
    }

    fun refreshTracks(restore: Boolean = true) {
        player.currentVideoTrack?.let { video ->
            val dimensions = metadataDimensions ?: PlaybackRules.displayDimensions(video.width, video.height, video.orientation, video.sarNum, video.sarDen)
            state.value = state.value.copy(videoWidth = dimensions.first, videoHeight = dimensions.second)
        }
        state.value =
            state.value.copy(
                audioTracks = player.audioTracks?.map { it.id to it.name }.orEmpty(),
                subtitles = player.spuTracks?.map { it.id to it.name }.orEmpty(),
                audioTrack = player.audioTrack,
                subtitleTrack = player.spuTrack,
            )
        updateSubtitleKind()
        val prefs = Store.prefs(this)
        val preferredAudio = prefs.getString("audio_name", null)
        val preferredSub = prefs.getString("subtitle_name", null)
        if (restore) {
            preferredAudio?.let { n ->
                state.value.audioTracks
                    .firstOrNull { it.second == n }
                    ?.let { player.setAudioTrack(it.first) }
            }
            preferredSub?.let { n ->
                state.value.subtitles
                    .firstOrNull { it.second == n }
                    ?.let { player.setSpuTrack(it.first) }
            }
            state.value =
                state.value.copy(audioTrack = player.audioTrack, subtitleTrack = player.spuTrack)
        }
        updateSubtitleKind()
    }

    fun audioTrack(id: Int, name: String) {
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
            val externalPath = externalSubtitles.getOrNull(externalIds.indexOf(player.spuTrack))
            val authoredExternal = track == null && externalPath?.substringAfterLast('.')?.lowercase() in listOf("ass", "ssa", "sup", "sub", "idx")
            state.value = state.value.copy(plainSubtitle = !authoredExternal && codec !in listOf("ssa", "ass", "pgs", "pgss", "bdpg", "spu", "dvbs", "dvd"))
        } finally { media?.release() }
        updateSubtitlePlacement(controlsVisible)
    }

    fun subtitleTrack(id: Int, name: String) {
        val changed = player.spuTrack != id
        val position = player.time
        player.setSpuTrack(id)
        // Re-demux the current cue when switching after its start timestamp.
        if (changed && player.isSeekable && position > 100) player.setTime(position, false)
        Store.prefs(this).edit().putString("subtitle_name", name).apply()
        state.value = state.value.copy(subtitleTrack = id)
        updateSubtitleKind()
    }

    fun audioDelay(ms: Long) {
        player.setAudioDelay(ms * 1000)
        state.value = state.value.copy(audioDelay = ms)
    }

    fun subtitleDelay(ms: Long) {
        player.setSpuDelay(ms * 1000)
        state.value = state.value.copy(subtitleDelay = ms)
    }

    fun addSubtitle(path: String): Boolean {
        val result =
            player.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, path, true)
        refreshTracks()
        if (result && path !in externalSubtitles) externalSubtitles.add(path)
        return result
    }

    /** FreeType is configured at LibVLC construction, so reopen the current item in place. */
    fun subtitleStyle(style: SubtitleStyle) {
        val previous = SubtitleStyle.load(this)
        if (previous == style) return
        style.save(this)
        if (previous.options() == style.options()) {
            updateSubtitlePlacement(controlsVisible)
            return
        }
        endTemporarySpeed()
        val prior = state.value.copy(playing = state.value.playing && !requestedPause)
        if (prior.item == null) return
        opening?.cancel()
        pendingStyleRestore = prior
        player.setEventListener(null)
        player.stop()
        if (videoAttached) player.detachViews()
        activeDescriptor?.close()
        activeDescriptor = null
        player.release()
        vlc.release()
        vlc = createVlc()
        player = MediaPlayer(vlc)
        audioEffects.apply(player, effectSettings.value)
        applyVolume()
        bindPlayerEvents()
        videoLayout?.let { player.attachViews(it, null, true, false) }
        openCurrent(prior.position, false)
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
