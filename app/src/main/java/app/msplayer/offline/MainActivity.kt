@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
)

package app.msplayer.offline

import android.Manifest
import android.app.PictureInPictureParams
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.database.ContentObserver
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

val Accent = Color(0xFF00A8FF)
val Cyan = Color(0xFF66D8F3)
val Muted = Color(0xFF8D96A7)
val Panel = Color(0xFF15191F)

class MainActivity : ComponentActivity() {
    var playerControlsVisible by mutableStateOf(true)
    var service by mutableStateOf<PlaybackService?>(null)
    var showPlayer by mutableStateOf(false)
    var inPip by mutableStateOf(false)
    var videos by mutableStateOf<List<MediaItem>>(emptyList())
    var audios by mutableStateOf<List<MediaItem>>(emptyList())
    var scanning by mutableStateOf(false)
    var libraryError by mutableStateOf<String?>(null)
    var pendingStreamPrompt by mutableStateOf<String?>(null)
    private var bound = false
    var scanJob: Job? = null
    private var externalQueue = emptyList<MediaItem>()
    private var pendingUri: Uri? = null
    private var wasRestricted = true
    val emptyState = MutableStateFlow(PlayerState())
    val permission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }
    val openFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                persist(uri)
                lifecycleScope.launch {
                    val item = Library.opened(this@MainActivity, uri)
                    startMedia(listOf(item), item)
                }
            }
        }
    val openFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                persist(uri)
                Store.prefs(this).edit().putString("tree", uri.toString()).apply()
                reload()
            }
        }
    val subtitleFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val name = Library.opened(this@MainActivity, uri).title
                                val currentItem = service?.state?.value?.item
                                val destDir = OpenSubtitlesClient.chooseDestinationDir(
                                    this@MainActivity,
                                    service?.currentLocalMediaFile(),
                                )
                                val prefix = currentItem?.title?.substringBeforeLast('.')
                                val extracted = contentResolver.openInputStream(uri)?.use { input ->
                                    OpenSubtitlesClient.extractSubtitleFromStream(
                                        input = input,
                                        originalName = name,
                                        destDir = destDir,
                                        preferredPrefix = prefix,
                                    )
                                } ?: error("Cannot read subtitle")
                                if (extracted.isEmpty()) error("No supported subtitle (.srt/.ass/.vtt) found")
                                extracted
                            }
                        }
                    result.fold(
                        { files ->
                            val itemUri = service?.state?.value?.item?.uri
                            var loadedAny = false
                            files.take(5).forEachIndexed { idx, file ->
                                if (service?.addSubtitle(file.absolutePath, selectExtra = idx == 0) == true) {
                                    loadedAny = true
                                    if (itemUri != null) {
                                        service?.rememberImportedSubtitle(itemUri, file.absolutePath)
                                    }
                                }
                            }
                            if (loadedAny) {
                                val msg = if (files.size > 1) {
                                    "Loaded ${files.first().name} (+${files.size - 1} from archive)"
                                } else {
                                    "Loaded ${files.first().name}"
                                }
                                toast(msg)
                            } else {
                                toast("The engine could not load this subtitle.")
                            }
                        },
                        { toast(it.message ?: "Subtitle unavailable") },
                    )
                }
            }
        }
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as PlaybackService.LocalBinder).service
                bound = true
                pendingUri?.let { uri ->
                    pendingUri = null
                    lifecycleScope.launch {
                        val item = Library.opened(this@MainActivity, uri)
                        startMedia(listOf(item), item)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                bound = false
            }
        }

    var pipSourceRect by mutableStateOf<android.graphics.Rect?>(null)
    private var closing = false
    private var manualOrientationFor: String? = null
    val diagnosticDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) lifecycleScope.launch {
            val source = service?.exportDiagnostics() ?: return@launch
            withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } } }
            toast("Diagnostics saved")
        }
    }

    // ---- Safe deletion: the system confirms, and we report the real outcome. ----
    private sealed interface DeleteOutcome {
        object Deleted : DeleteOutcome
        object Failed : DeleteOutcome
        class NeedsConsent(val sender: IntentSender, val retryUri: Uri?) : DeleteOutcome
    }

    private var pendingDelete: Pair<Uri?, () -> Unit>? = null
    private val deleteRequest =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val pending = pendingDelete
            pendingDelete = null
            if (pending == null) return@registerForActivityResult
            if (result.resultCode != RESULT_OK) {
                toast("Delete cancelled")
                return@registerForActivityResult
            }
            val (retryUri, done) = pending
            if (retryUri == null) {
                done() // Android 11+: the system already deleted the file.
            } else {
                // Android 10: consent only grants access; delete again.
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { contentResolver.delete(retryUri, null, null) > 0 }.getOrDefault(false)
                    }
                    if (ok) done() else toast("Could not delete this file.")
                }
            }
        }

    private fun tryDelete(uri: Uri): DeleteOutcome {
        if (uri.scheme == "file") {
            return if (runCatching { File(uri.path ?: "").delete() }.getOrDefault(false)) DeleteOutcome.Deleted
            else DeleteOutcome.Failed
        }
        if (uri.authority == MediaStore.AUTHORITY) {
            if (Build.VERSION.SDK_INT >= 30) {
                return runCatching {
                    DeleteOutcome.NeedsConsent(MediaStore.createDeleteRequest(contentResolver, listOf(uri)).intentSender, null)
                }.getOrDefault(DeleteOutcome.Failed)
            }
            return try {
                if (contentResolver.delete(uri, null, null) > 0) DeleteOutcome.Deleted else DeleteOutcome.Failed
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= 29 && e is android.app.RecoverableSecurityException)
                    DeleteOutcome.NeedsConsent(e.userAction.actionIntent.intentSender, uri)
                else DeleteOutcome.Failed
            } catch (_: Exception) {
                DeleteOutcome.Failed
            }
        }
        // Storage Access Framework document (a folder added in Settings).
        return if (runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }.getOrDefault(false))
            DeleteOutcome.Deleted else DeleteOutcome.Failed
    }

    /** Deletes [item]; [onDeleted] runs only if the file was really removed. */
    fun deleteMedia(item: MediaItem, onDeleted: () -> Unit) {
        val uri = Uri.parse(item.uri)
        lifecycleScope.launch {
            when (val outcome = withContext(Dispatchers.IO) { tryDelete(uri) }) {
                DeleteOutcome.Deleted -> onDeleted()
                DeleteOutcome.Failed -> toast("Could not delete this file.")
                is DeleteOutcome.NeedsConsent -> {
                    pendingDelete = outcome.retryUri to onDeleted
                    deleteRequest.launch(IntentSenderRequest.Builder(outcome.sender).build())
                }
            }
        }
    }

    /** Deletes multiple selected [items]; batches MediaStore prompts on API 30+. */
    fun deleteMediaItems(items: List<MediaItem>, onDeleted: (Int) -> Unit) {
        if (items.isEmpty()) return
        if (items.size == 1) {
            deleteMedia(items.first()) { onDeleted(1) }
            return
        }
        val uris = items.map { Uri.parse(it.uri) }
        val mediaStoreUris = uris.filter { it.scheme != "file" && it.authority == MediaStore.AUTHORITY }
        val directUris = uris.filter { it.scheme == "file" || it.authority != MediaStore.AUTHORITY }
        lifecycleScope.launch {
            var directCount = 0
            for (u in directUris) {
                val res = withContext(Dispatchers.IO) { tryDelete(u) }
                if (res == DeleteOutcome.Deleted) directCount++
            }
            if (mediaStoreUris.isEmpty()) {
                if (directCount > 0) onDeleted(directCount) else toast("Could not delete selected files.")
                return@launch
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val sender = withContext(Dispatchers.IO) {
                    runCatching {
                        MediaStore.createDeleteRequest(contentResolver, mediaStoreUris).intentSender
                    }.getOrNull()
                }
                if (sender == null) {
                    if (directCount > 0) onDeleted(directCount) else toast("Could not delete selected files.")
                } else {
                    pendingDelete = null to { onDeleted(directCount + mediaStoreUris.size) }
                    deleteRequest.launch(IntentSenderRequest.Builder(sender).build())
                }
            } else {
                fun processSequential(remaining: ArrayDeque<Uri>, deletedSoFar: Int) {
                    if (remaining.isEmpty()) {
                        if (deletedSoFar > 0) onDeleted(deletedSoFar) else toast("Could not delete selected files.")
                        return
                    }
                    val next = remaining.removeFirst()
                    lifecycleScope.launch {
                        when (val outcome = withContext(Dispatchers.IO) { tryDelete(next) }) {
                            DeleteOutcome.Deleted -> processSequential(remaining, deletedSoFar + 1)
                            DeleteOutcome.Failed -> processSequential(remaining, deletedSoFar)
                            is DeleteOutcome.NeedsConsent -> {
                                pendingDelete = outcome.retryUri to {
                                    processSequential(remaining, deletedSoFar + 1)
                                }
                                deleteRequest.launch(IntentSenderRequest.Builder(outcome.sender).build())
                            }
                        }
                    }
                }
                processSequential(ArrayDeque(mediaStoreUris), directCount)
            }
        }
    }

    // ---- Keep the library current when files are added or removed. ----
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rescanRunnable = Runnable { if (hasLibraryPermission()) reload() }
    private val mediaObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                mainHandler.removeCallbacks(rescanRunnable)
                mainHandler.postDelayed(rescanRunnable, 1500) // debounce bursts of changes
            }
        }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (showPlayer && keyCode in listOf(android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            service?.volumeKey(keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun closeApp() {
        if (closing) return
        closing = true
        service?.stopPlayback()
        finishAndRemoveTask()
    }

    fun videoOrientation(state: PlayerState) {
        if (manualOrientationFor == state.item?.uri || state.item?.audio != false || inPip) return
        when (PlaybackRules.videoOrientation(state.videoWidth, state.videoHeight)) {
            2 -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            1 -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    fun rotateVideo() {
        manualOrientationFor = service?.state?.value?.item?.uri
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        enableHighRefreshRate()
    }

    private fun enableHighRefreshRate() {
        fun applyHighestMode(supportedModes: Array<out android.view.Display.Mode>) {
            val maxRefreshMode = supportedModes.maxByOrNull { it.refreshRate } ?: return
            if (maxRefreshMode.refreshRate > 60f) {
                val params = window.attributes
                var changed = false
                if (params.preferredDisplayModeId != maxRefreshMode.modeId) {
                    params.preferredDisplayModeId = maxRefreshMode.modeId
                    changed = true
                }
                if (params.preferredRefreshRate != maxRefreshMode.refreshRate) {
                    params.preferredRefreshRate = maxRefreshMode.refreshRate
                    changed = true
                }
                if (changed) {
                    window.attributes = params
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.post {
                applyHighestMode(window.decorView.display?.supportedModes ?: return@post)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            applyHighestMode(windowManager.defaultDisplay?.supportedModes ?: return)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GlassTheme.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableHighRefreshRate()
        val (cachedV, cachedA) = LibraryCache.load(this)
        if (cachedV.isNotEmpty()) videos = cachedV
        if (cachedA.isNotEmpty()) audios = cachedA
        showPlayer =
            savedInstanceState?.getBoolean("player") ?: intent.getBooleanExtra("show_player", false)
        extractIncomingMedia(intent)
        startService(Intent(this, PlaybackService::class.java))
        bindService(Intent(this, PlaybackService::class.java), connection, BIND_AUTO_CREATE)
        reload()
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        setContent {
            val palette = rememberAppPalette()
            val scheme = if (palette.isLight) {
                lightColorScheme(
                    primary = palette.accent,
                    secondary = palette.accentSecondary,
                    background = palette.background,
                    surface = palette.surface,
                    onBackground = palette.textPrimary,
                    onSurface = palette.textPrimary,
                    surfaceVariant = palette.chipBackground,
                    onSurfaceVariant = palette.textSecondary,
                    outline = palette.cardBorder,
                )
            } else {
                darkColorScheme(
                    primary = palette.accent,
                    secondary = palette.accentSecondary,
                    background = palette.background,
                    surface = palette.surface,
                    onBackground = palette.textPrimary,
                    onSurface = palette.textPrimary,
                    surfaceVariant = palette.chipBackground,
                    onSurfaceVariant = palette.textSecondary,
                    outline = palette.cardBorder,
                )
            }
            CompositionLocalProvider(
                LocalAppPalette provides palette,
                LocalContentColor provides palette.textPrimary,
            ) {
                MaterialTheme(colorScheme = scheme) {
                    Surface(
                        color = palette.background,
                        contentColor = palette.textPrimary,
                    ) { App(this) }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("player", showPlayer)
        super.onSaveInstanceState(outState)
    }

    private fun extractIncomingMedia(incoming: Intent?) {
        if (incoming == null) return
        if (incoming.data != null) {
            pendingUri = incoming.data
            return
        }
        if (incoming.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val streamUri = if (Build.VERSION.SDK_INT >= 33) {
                incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                incoming.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (streamUri != null) {
                pendingUri = streamUri
                return
            }
            if (incoming.type == "text/plain" || incoming.hasExtra(Intent.EXTRA_TEXT)) {
                val shared = incoming.getStringExtra(Intent.EXTRA_TEXT) ?: return
                val normalized = PlaybackRules.normalizeStreamUrl(shared)
                if (PlaybackRules.isStreamUrl(normalized)) {
                    pendingUri = Uri.parse(normalized)
                } else {
                    pendingStreamPrompt = shared.trim()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uriData = intent.data ?: run {
            if (intent.action == Intent.ACTION_SEND) {
                @Suppress("DEPRECATION")
                val streamUri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (streamUri != null) return@run streamUri
                if (intent.type == "text/plain" || intent.hasExtra(Intent.EXTRA_TEXT)) {
                    val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                    val normalized = PlaybackRules.normalizeStreamUrl(shared)
                    if (PlaybackRules.isStreamUrl(normalized)) Uri.parse(normalized)
                    else {
                        pendingStreamPrompt = shared.trim()
                        null
                    }
                } else null
            } else null
        }
        if (uriData != null) {
            lifecycleScope.launch {
                val item = Library.opened(this@MainActivity, uriData)
                startMedia(listOf(item), item)
            }
        } else if (pendingStreamPrompt != null) {
            showPlayer = false
            immersive(false)
        } else if (intent.getBooleanExtra("show_player", false)) {
            showPlayer = true
        }
    }

    private var wasAllFilesGranted = false

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < 30) return
        val pkgIntent = Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(pkgIntent) }.onFailure {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }.onFailure {
                toast("Open Settings -> Apps -> MS PLAYER -> Permissions to allow all files access.")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableHighRefreshRate()
        if (showPlayer) service?.resumeVideoSurface()
        val nowAllFiles = hasAllFilesAccess()
        if (service?.requiresAllFilesPermission?.value == true && nowAllFiles) {
            service?.retryCurrent()
        }
        if (nowAllFiles != wasAllFilesGranted) reload(forceScan = true)
        wasRestricted = !hasLibraryPermission()
        wasAllFilesGranted = nowAllFiles
        immersive(showPlayer)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        service?.endTemporarySpeed()
        super.onConfigurationChanged(newConfig)
        service?.updateSubtitlePlacement(playerControlsVisible && !inPip)
        service?.refreshVideoLayoutAfterPip()
    }

    override fun onPause() { service?.endTemporarySpeed(); super.onPause() }

    override fun onStart() {
        super.onStart()
        // Give VLC a fresh surface after returning from the background (prevents a black video).
        service?.resumeVideoSurface()
    }

    override fun onStop() {
        super.onStop()
        val inPipNow = inPip || isInPictureInPictureMode
        if (!isChangingConfigurations && !inPipNow) service?.suspendVideoSurface()
        if (
            !isChangingConfigurations &&
                !inPipNow &&
                service?.state?.value?.item?.audio != true &&
                !Store.prefs(this).getBoolean("background", false)
        )
            service?.pause()
    }

    override fun onDestroy() {
        if (isFinishing) service?.stopPlayback()
        contentResolver.unregisterContentObserver(mediaObserver)
        mainHandler.removeCallbacks(rescanRunnable)
        if (bound) { unbindService(connection); bound = false }
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (
            !closing && showPlayer &&
                service?.state?.value?.playing == true &&
                Store.prefs(this).getBoolean("auto_pip", true)
        )
            enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        service?.endTemporarySpeed()
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        service?.refreshVideoLayoutAfterPip()
        service?.updateSubtitlePlacement(playerControlsVisible && !isInPictureInPictureMode)
    }

    private fun buildPipParams(autoEnter: Boolean): PictureInPictureParams {
        val video = service?.state?.value
        val ratio =
            if (video != null && video.videoWidth > 0 && video.videoHeight > 0)
                video.videoWidth.toFloat() / video.videoHeight
            else 16f / 9f
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio.coerceIn(0.42f, 2.39f) * 1000).toInt(), 1000))
        pipSourceRect?.takeIf { !it.isEmpty }?.let { rect ->
            builder.setSourceRectHint(rect)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(false)
        }
        return builder.build()
    }

    fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val stateVal = service?.state?.value
        val canAutoEnter = !closing &&
            showPlayer &&
            stateVal?.item != null &&
            stateVal.item.audio.not() &&
            stateVal.playing &&
            Store.prefs(this).getBoolean("auto_pip", true)
        runCatching {
            setPictureInPictureParams(buildPipParams(autoEnter = canAutoEnter))
        }
    }

    fun enterPip() {
        if (
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                service?.state?.value?.item != null
        ) {
            val autoEnter = Store.prefs(this).getBoolean("auto_pip", true)
            runCatching {
                    enterPictureInPictureMode(buildPipParams(autoEnter = autoEnter))
                }
                .onFailure { toast("Picture-in-picture is unavailable.") }
        }
    }

    fun immersive(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun startMedia(items: List<MediaItem>, item: MediaItem) {
        val svc = service ?: return toast("Player is initializing. Try again in a moment.")
        externalQueue = items
        playerControlsVisible = true
        manualOrientationFor = null
        svc.open(items, item)
        showPlayer = true
        immersive(true)
    }

    fun exitPlayer() {
        service?.endTemporarySpeed()
        if (
            service?.state?.value?.item?.audio != true &&
                !Store.prefs(this).getBoolean("background", false)
        )
            service?.pause()
        Library.videoPlaybackActive.value = false
        showPlayer = false
        immersive(false)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.attributes = window.attributes.apply { screenBrightness = -1f }
    }

    fun requestAccess() {
        val permissions =
            if (Build.VERSION.SDK_INT >= 34)
                arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            else if (Build.VERSION.SDK_INT >= 33)
                arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permission.launch(permissions)
    }

    fun hasLibraryPermission() =
        ContextCompat.checkSelfPermission(
            this,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
            else Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED || hasAllFilesAccess()

    fun reload(forceScan: Boolean = false): Job? {
        if (!forceScan && scanJob?.isActive == true) return scanJob
        scanJob?.cancel()
        val job =
            lifecycleScope.launch {
                if (videos.isEmpty() || forceScan) scanning = true
                libraryError = null
                try {
                    if (forceScan) {
                        Library.invalidateScanCache()
                        Library.retryThumbnails()
                        Library.triggerSystemMediaScan(this@MainActivity)
                        delay(280)
                    }
                    val v = Library.scan(this@MainActivity, false, forceFull = forceScan)
                    val a = Library.scan(this@MainActivity, true, forceFull = forceScan)
                    val tree = Store.prefs(this@MainActivity).getString("tree", null)
                    val extra = if (tree != null) scanTree(Uri.parse(tree)) else emptyList()
                    val newVideos = (v + extra.filter { !it.audio }).distinctBy { it.uri }
                    val newAudios = (a + extra.filter { it.audio }).distinctBy { it.uri }
                    videos = newVideos
                    audios = newAudios
                    LibraryCache.save(this@MainActivity, newVideos, newAudios)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    libraryError = "Some files could not be scanned. ${e.message}"
                } finally {
                    scanning = false
                }
            }
        scanJob = job
        return job
    }

    private suspend fun scanTree(tree: Uri): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val items = ArrayList<MediaItem>()
            val folders = ArrayDeque<Pair<String, String>>()
            folders.add(DocumentsContract.getTreeDocumentId(tree) to "Selected folder")
            var visited = 0
            while (folders.isNotEmpty() && visited++ < 10000 && items.size < 20000) {
                ensureActive()
                val (id, path) = folders.removeFirst()
                contentResolver
                    .query(
                        DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
                        arrayOf(
                            "document_id",
                            "_display_name",
                            "mime_type",
                            "_size",
                            "last_modified",
                        ),
                        null,
                        null,
                        null,
                    )
                    ?.use { c ->
                        while (c.moveToNext()) {
                            ensureActive()
                            val doc = c.getString(0)
                            val name = c.getString(1) ?: "Media"
                            val mime = c.getString(2) ?: ""
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR)
                                folders.add(doc to "$path/$name")
                            else {
                                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, doc)
                                val detectedMime = if (!PlaybackRules.mediaFile(mime, name) && PlaybackRules.temporaryFile(name))
                                    Library.sniff(this@MainActivity, uri) else mime
                                if (PlaybackRules.mediaFile(detectedMime, name)) items += MediaItem(
                                        DocumentsContract.buildDocumentUriUsingTree(tree, doc)
                                            .toString(),
                                        name,
                                        path,
                                        c.getLong(3),
                                        added = c.getLong(4) / 1000,
                                        audio = PlaybackRules.audioFile(detectedMime, name),
                                        modified = c.getLong(4),
                                    )
                            }
                        }
                    }
            }
            items
        }

    private fun persist(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}

private enum class AppScreenMode { LIBRARY, MUSIC, VIDEO }

@Composable
fun App(activity: MainActivity) {
    val service = activity.service
    val metadata = remember(service) { (service?.state ?: activity.emptyState).map { it.copy(position = 0) }.distinctUntilChanged() }
    val state by metadata.collectAsState(initial = service?.state?.value ?: PlayerState())
    LaunchedEffect(state.closed) { if (state.closed) activity.closeApp() }

    var tab by rememberSaveable { mutableStateOf("Folders") }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("Name") }
    var refresh by remember { mutableIntStateOf(0) }
    var activePlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    var streamDialog by remember { mutableStateOf(false) }
    var streamUrl by remember { mutableStateOf("") }
    var recentStreams by remember { mutableStateOf(Store.recentStreams(activity)) }

    LaunchedEffect(activity.pendingStreamPrompt) {
        val incoming = activity.pendingStreamPrompt
        if (incoming != null) {
            activity.pendingStreamPrompt = null
            streamUrl = incoming
            recentStreams = Store.recentStreams(activity)
            streamDialog = true
        }
    }

    LaunchedEffect(streamDialog) {
        if (streamDialog) {
            recentStreams = Store.recentStreams(activity)
        }
    }

    val palette = LocalAppPalette.current
    val screenMode = when {
        activity.showPlayer && service != null && state.item?.audio == true -> AppScreenMode.MUSIC
        activity.showPlayer && service != null && state.item != null -> AppScreenMode.VIDEO
        else -> AppScreenMode.LIBRARY
    }

    LaunchedEffect(palette.isLight, screenMode) {
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        val useDarkIcons = palette.isLight && screenMode == AppScreenMode.LIBRARY
        controller.isAppearanceLightStatusBars = useDarkIcons
        controller.isAppearanceLightNavigationBars = useDarkIcons
    }

    SharedTransitionLayout {
        AnimatedContent(
            targetState = screenMode,
            transitionSpec = {
                when {
                    targetState != AppScreenMode.LIBRARY && initialState == AppScreenMode.LIBRARY ->
                        (fadeIn(tween(150)) + scaleIn(initialScale = 0.96f, animationSpec = MotionSpec.MorphSpring)) togetherWith
                            fadeOut(tween(130))
                    targetState == AppScreenMode.LIBRARY && initialState != AppScreenMode.LIBRARY ->
                        fadeIn(tween(150)) togetherWith
                            (fadeOut(tween(130)) + scaleOut(targetScale = 0.96f))
                    else ->
                        fadeIn(tween(150)) togetherWith fadeOut(tween(130))
                }
            },
            label = "appScreenTransition",
        ) { mode ->
            when (mode) {
                AppScreenMode.VIDEO -> {
                    val activeService = activity.service
                    if (activeService != null && state.item != null) {
                        PlayerScreen(activity, activeService, state)
                    }
                }
                AppScreenMode.MUSIC -> {
                    val activeService = activity.service
                    if (activeService != null && state.item != null) {
                        MusicPlayerScreen(
                            activity = activity,
                            service = activeService,
                            state = state,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@AnimatedContent,
                        )
                    }
                }
                AppScreenMode.LIBRARY -> {
                    LibraryHome(
                        activity = activity,
                        service = service,
                        state = state,
                        tab = tab,
                        onTabChange = { tab = it },
                        folder = folder,
                        onFolderChange = { folder = it },
                        search = search,
                        onSearchChange = { search = it },
                        searching = searching,
                        onSearchingChange = { searching = it },
                        grid = grid,
                        onGridChange = { grid = it },
                        sort = sort,
                        onSortChange = { sort = it },
                        refresh = refresh,
                        onBumpRefresh = { refresh++ },
                        activePlaylist = activePlaylist,
                        onActivePlaylistChange = { activePlaylist = it },
                        streamDialog = streamDialog,
                        onStreamDialogChange = { streamDialog = it },
                        streamUrl = streamUrl,
                        onStreamUrlChange = { streamUrl = it },
                        recentStreams = recentStreams,
                        onRecentStreamsChange = { recentStreams = it },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedContent,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryHome(
    activity: MainActivity,
    service: PlaybackService?,
    state: PlayerState,
    tab: String,
    onTabChange: (String) -> Unit,
    folder: String?,
    onFolderChange: (String?) -> Unit,
    search: String,
    onSearchChange: (String) -> Unit,
    searching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    grid: Boolean,
    onGridChange: (Boolean) -> Unit,
    sort: String,
    onSortChange: (String) -> Unit,
    refresh: Int,
    onBumpRefresh: () -> Unit,
    activePlaylist: String?,
    onActivePlaylistChange: (String?) -> Unit,
    streamDialog: Boolean,
    onStreamDialogChange: (Boolean) -> Unit,
    streamUrl: String,
    onStreamUrlChange: (String) -> Unit,
    recentStreams: List<String>,
    onRecentStreamsChange: (List<String>) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var detail by remember { mutableStateOf<MediaItem?>(null) }
    var playlistItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var playlistDialog by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("Favorites") }
    var folderAction by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<MediaItem?>(null) }
    var deleteSelectedDialog by remember { mutableStateOf(false) }
    var manualRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(activity.scanning) {
        if (!activity.scanning && manualRefreshing) {
            manualRefreshing = false
            onBumpRefresh()
        }
    }
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<SavedMedia>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<String>>(emptyList()) }
    var playlistContent by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var dismissedRecent by remember { mutableStateOf(Store.dismissedRecentAdded(activity)) }
    val recentRowState = rememberLazyListState()
    LaunchedEffect(dismissedRecent) {
        if (dismissedRecent.isEmpty()) recentRowState.scrollToItem(0)
    }
    LaunchedEffect(tab, refresh, activePlaylist) {
        dismissedRecent = Store.dismissedRecentAdded(activity)
        withContext(Dispatchers.IO) {
            val dao = Store.db(activity).dao()
            val h = dao.history()
            val p = dao.playlists()
            val entries =
                activePlaylist
                    ?.let { dao.entries(it).map { row -> Library.opened(activity, Uri.parse(row.uri)).copy(title = row.title) } }
                    .orEmpty()
            withContext(Dispatchers.Main) {
                history = h
                playlists = p
                playlistContent = entries
            }
        }
    }
    BackHandler(folder != null || activePlaylist != null || searching || selected.isNotEmpty()) {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            searching -> {
                onSearchingChange(false)
                onSearchChange("")
            }
            folder != null -> onFolderChange(null)
            else -> onActivePlaylistChange(null)
        }
    }
    val hidden = Store.prefs(activity).getStringSet("hidden", emptySet()).orEmpty()
    val raw =
        when (tab) {
            "Audio" -> activity.audios
            "History" -> history.map { saved ->
                (activity.audios + activity.videos).firstOrNull { it.uri == saved.uri }
                    ?: MediaItem(
                        saved.uri,
                        saved.title,
                        folder = if (PlaybackRules.isStreamUrl(saved.uri)) "Network Streams" else "Opened files",
                        duration = saved.position,
                        audio = PlaybackRules.audioFile(null, saved.title) || PlaybackRules.audioFile(null, Uri.parse(saved.uri).lastPathSegment ?: ""),
                    )
            }
            "Playlists" -> playlistContent
            else ->
                activity.videos.filter {
                    it.folder !in hidden && (folder == null || it.folder == folder)
                }
        }
    val filtered =
        raw.filter {
            search.isBlank() || it.title.contains(search, true) || it.folder.contains(search, true)
        }
    val items =
        when (sort) {
            "Date" -> filtered.sortedByDescending { it.added }
            "Size" -> filtered.sortedByDescending { it.size }
            "Duration" -> filtered.sortedByDescending { it.duration }
            else -> filtered.sortedBy { it.title.lowercase() }
        }
    fun play(item: MediaItem) {
        activity.startMedia(items, item)
    }
    fun overflow(item: MediaItem, action: String) {
        when (action) {
            "Details" -> detail = item
            "Playlist" -> {
                playlistItems = listOf(item)
                playlistDialog = true
            }
            "Share" ->
                runCatching {
                        activity.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND)
                                    .setType(if (item.audio) "audio/*" else "video/*")
                                    .putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                "Share media",
                            )
                        )
                    }
                    .onFailure { activity.toast("No app can share this file.") }
            "Remove" ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        Store.db(activity).dao().remove(activePlaylist ?: "", item.uri)
                    }
                    onBumpRefresh()
                }
            "Delete" -> deleteTarget = item
        }
    }
    val palette = LocalAppPalette.current
    val glassEnabled = palette.isGlass
    val showContinueWatching = remember(refresh, palette.mode) { Store.continueWatchingEnabled(activity) }
    val appBackdrop = rememberLayerBackdrop()
    val listBottomPad = if (glassEnabled) 124.dp else 88.dp
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(appBackdrop)) {
            LiquidGlassBackdropBackground(glassEnabled, mode = palette.mode)
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (folder != null || activePlaylist != null) {
                        val backSrc = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = {
                                onFolderChange(null)
                                onActivePlaylistChange(null)
                            },
                            interactionSource = backSrc,
                            modifier = Modifier.size(38.dp).iosLiquidPress(backSrc, scaleDown = 0.88f),
                        ) {
                            Icon(Icons.Default.ArrowBack, "Back", tint = palette.textPrimary)
                        }
                    }
                    if (searching)
                        OutlinedTextField(
                            search,
                            { onSearchChange(it) },
                            Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("Search") },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        onSearchingChange(false)
                                        onSearchChange("")
                                    }
                                ) {
                                    Icon(Icons.Default.Close, "Close search")
                                }
                            },
                        )
                    else
                        Text(
                            if (selected.isNotEmpty()) "${selected.size} selected"
                            else folder?.substringAfterLast('/') ?: activePlaylist ?: tab,
                            color = palette.textPrimary,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    if (selected.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                playlistItems = items.filter { it.uri in selected }
                                playlistDialog = true
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.PlaylistAdd, "Add selected to playlist", tint = palette.textPrimary)
                        }
                        IconButton(
                            onClick = {
                                activity.startMedia(
                                    items.filter { it.uri in selected },
                                    items.first { it.uri in selected },
                                )
                                selected = emptySet()
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, "Play selected", tint = palette.textPrimary)
                        }
                        IconButton(
                            onClick = { deleteSelectedDialog = true },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.DeleteOutline, "Delete selected", tint = Color(0xFFE53935))
                        }
                        IconButton(onClick = { selected = emptySet() }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Close, "Clear selection", tint = palette.textPrimary)
                        }
                    } else if (tab != "Settings") {
                        IconButton(onClick = { onSearchingChange(true) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Search, "Search", tint = palette.textPrimary, modifier = Modifier.size(21.dp))
                        }
                        IconButton(onClick = { onGridChange(!grid) }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                if (grid) Icons.Default.ViewList else Icons.Default.GridView,
                                "Change layout",
                                tint = palette.textPrimary,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.MoreVert, "Library options", tint = palette.textPrimary, modifier = Modifier.size(21.dp))
                            }
                            LiquidGlassPopupMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                listOf("Name", "Date", "Size", "Duration").forEach { label ->
                                    LiquidGlassPopupMenuItem(
                                        text = {
                                            Text(
                                                "Sort by $label",
                                                color = if (sort == label) palette.accent else palette.textPrimary,
                                                fontWeight = if (sort == label) FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                        },
                                        trailingIcon = if (sort == label) {
                                            { Icon(Icons.Default.Check, null, tint = palette.accent, modifier = Modifier.size(16.dp)) }
                                        } else null,
                                        onClick = {
                                            onSortChange(label)
                                            sortMenu = false
                                        },
                                    )
                                }
                                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = palette.divider)
                                LiquidGlassPopupMenuItem(
                                    text = { Text("Stream URL") },
                                    leadingIcon = { Icon(Icons.Default.CloudQueue, null, tint = palette.accentSecondary, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        onStreamDialogChange(true)
                                        sortMenu = false
                                    },
                                )
                                LiquidGlassPopupMenuItem(
                                    text = { Text("Rescan") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, null, tint = palette.textPrimary, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        activity.reload(forceScan = true)
                                        onBumpRefresh()
                                        sortMenu = false
                                    },
                                )
                                LiquidGlassPopupMenuItem(
                                    text = { Text("Open file") },
                                    leadingIcon = { Icon(Icons.Default.FileOpen, null, tint = palette.textPrimary, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        activity.openFile.launch(arrayOf("*/*"))
                                        sortMenu = false
                                    },
                                )
                                LiquidGlassPopupMenuItem(
                                    text = { Text("Choose folder") },
                                    leadingIcon = { Icon(Icons.Default.FolderOpen, null, tint = palette.textPrimary, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        activity.openFolder.launch(null)
                                        sortMenu = false
                                    },
                                )
                                LiquidGlassPopupMenuItem(
                                    text = { Text("Media permissions") },
                                    onClick = {
                                        activity.requestAccess()
                                        sortMenu = false
                                    },
                                )
                                LiquidGlassPopupMenuItem(
                                    text = { Text("Exit", color = Color(0xFFE53935)) },
                                    onClick = { activity.closeApp() },
                                )
                            }
                        }
                    }
                }
                if (activity.scanning && !manualRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = palette.accent)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(
                        targetState = Triple(tab, folder, activePlaylist),
                        transitionSpec = {
                            val drillingIn = (initialState.second == null && targetState.second != null) ||
                                (initialState.third == null && targetState.third != null)
                            val drillingOut = (initialState.second != null && targetState.second == null) ||
                                (initialState.third != null && targetState.third == null)
                            when {
                                drillingIn ->
                                    (slideInHorizontally(MotionSpec.PanelOffsetSpring) { it / 3 } + fadeIn(tween(150))) togetherWith
                                        (slideOutHorizontally(MotionSpec.PanelOffsetSpring) { -it / 6 } + fadeOut(tween(110)))
                                drillingOut ->
                                    (slideInHorizontally(MotionSpec.PanelOffsetSpring) { -it / 4 } + fadeIn(tween(150))) togetherWith
                                        (slideOutHorizontally(MotionSpec.PanelOffsetSpring) { it / 3 } + fadeOut(tween(110)))
                                else -> fadeIn(tween(150)) togetherWith fadeOut(tween(120))
                            }
                        },
                        label = "libraryContentTransition",
                    ) { (curTab, curFolder, curPlaylist) ->
                        if (curTab == "Settings") {
                            SettingsScreen(activity, onSettingsChanged = { onBumpRefresh() })
                        } else {
                            val pullState = rememberPullToRefreshState()
                            val pullHaptics = LocalHapticFeedback.current
                            val pullDensity = LocalDensity.current
                            var pullHapticFired by remember { mutableStateOf(false) }
                            LaunchedEffect(activity.scanning) {
                                if (!activity.scanning && manualRefreshing) {
                                    manualRefreshing = false
                                    runCatching { pullState.animateToHidden() }
                                    onBumpRefresh()
                                }
                            }
                            LaunchedEffect(pullState.distanceFraction) {
                                if (pullState.distanceFraction >= 1f && !pullHapticFired) {
                                    pullHapticFired = true
                                    pullHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                } else if (pullState.distanceFraction < 0.8f) {
                                    pullHapticFired = false
                                }
                            }
                            val pullOffsetPx = with(pullDensity) {
                                ((pullState.distanceFraction.coerceIn(0f, 1.35f)) * 44.dp.toPx()).roundToInt()
                            }
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clipToBounds()
                                    .pullToRefresh(
                                        isRefreshing = manualRefreshing && activity.scanning,
                                        state = pullState,
                                        enabled = true,
                                        threshold = 34.dp,
                                        onRefresh = {
                                            manualRefreshing = true
                                            scope.launch {
                                                activity.reload(forceScan = true)
                                                activity.scanJob?.join()
                                                manualRefreshing = false
                                                runCatching { pullState.animateToHidden() }
                                                onBumpRefresh()
                                            }
                                        },
                                    )
                            ) {
                                Box(Modifier.fillMaxSize().offset { IntOffset(0, pullOffsetPx) }) {
                                when {
                            curTab == "Playlists" && curPlaylist == null -> {
                                if (playlists.isEmpty())
                                    EmptyLibrary(
                                        "Your playlists",
                                        "Add a video or audio file using its menu.",
                                        Icons.Default.QueueMusic,
                                    )
                                else
                                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = listBottomPad)) {
                                        items(playlists, key = { it }) { name ->
                                            val plSrc = remember { MutableInteractionSource() }
                                            ListItem(
                                                headlineContent = { Text(name, color = palette.textPrimary) },
                                                leadingContent = {
                                                    Icon(Icons.Default.QueueMusic, null, tint = palette.accent)
                                                },
                                                modifier = Modifier
                                                    .animateItem(placementSpec = MotionSpec.ItemPlacementSpring)
                                                    .iosLiquidPress(plSrc, scaleDown = 0.98f, sheenAlpha = 0.10f)
                                                    .clickable(interactionSource = plSrc, indication = ripple()) {
                                                        onActivePlaylistChange(name)
                                                    },
                                                colors = ListItemDefaults.colors(
                                                    containerColor = if (glassEnabled) Color.Transparent else palette.background
                                                ),
                                            )
                                        }
                                    }
                            }
                            curTab == "Folders" && curFolder == null && !searching -> {
                                val groups = items.groupBy { it.folder }.map { MediaFolder(it.key, it.value) }
                                val folders =
                                    when (sort) {
                                        "Size" -> groups.sortedByDescending { it.size }
                                        "Date" ->
                                            groups.sortedByDescending {
                                                it.items.maxOfOrNull { i -> i.added } ?: 0
                                            }
                                        "Duration" ->
                                            groups.sortedByDescending { it.items.sumOf { i -> i.duration } }
                                        else -> groups.sortedBy { it.name.lowercase() }
                                    }
                                val isScanningLib by Library.isScanning.collectAsState()
                                if (folders.isEmpty()) {
                                    if (activity.scanning || isScanningLib) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                CircularProgressIndicator(color = palette.accent, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                                                Spacer(Modifier.height(14.dp))
                                                Text("Scanning media library...", color = palette.textSecondary, fontSize = 15.sp)
                                            }
                                        }
                                    } else {
                                        AccessEmpty(activity)
                                    }
                                }
                                else {
                                    val recentVideos = remember(activity.videos, hidden, dismissedRecent) {
                                        activity.videos
                                            .filter { !it.audio && it.folder !in hidden && it.uri !in dismissedRecent }
                                            .sortedByDescending { it.added.takeIf { a -> a > 0 } ?: it.modified }
                                            .take(8)
                                    }
                                    val continueHero = remember(history, activity.videos, hidden) {
                                        val byUri = activity.videos.associateBy { it.uri }
                                        history.firstNotNullOfOrNull { saved ->
                                            val candidate = byUri[saved.uri] ?: return@firstNotNullOfOrNull null
                                            if (!candidate.audio && candidate.folder !in hidden && !saved.watched && saved.position > 1500L) {
                                                saved to candidate
                                            } else null
                                        }
                                    }
                                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = listBottomPad)) {
                                        if (Build.VERSION.SDK_INT >= 30 && !activity.hasAllFilesAccess()) {
                                            item(key = "all_files_banner") {
                                                AllFilesAccessBanner(onGrant = { activity.openAllFilesAccessSettings() })
                                            }
                                        }
                                        item(key = "quick_actions") {
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .padding(horizontal = 14.dp, vertical = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                QuickAction("Music", Icons.Default.Headphones) { onTabChange("Audio") }
                                                QuickAction("Playlists", Icons.Default.PlaylistPlay) {
                                                    onTabChange("Playlists")
                                                }
                                                QuickAction("History", Icons.Default.History) {
                                                    onTabChange("History")
                                                }
                                                QuickAction("Stream", Icons.Default.CloudQueue) {
                                                    onStreamDialogChange(true)
                                                }
                                                QuickAction("Open", Icons.Default.FileOpen) {
                                                    activity.openFile.launch(arrayOf("*/*"))
                                                }
                                            }
                                        }
                                        if (showContinueWatching && continueHero != null) {
                                            item(key = "continue_watching_hero") {
                                                val (savedHero, heroVideo) = continueHero
                                                val heroSrc = remember(heroVideo.uri) { MutableInteractionSource() }
                                                val totalDur = heroVideo.duration.coerceAtLeast(1L)
                                                val progressFraction = (savedHero.position.toFloat() / totalDur).coerceIn(0.02f, 0.99f)
                                                val remMs = (heroVideo.duration - savedHero.position).coerceAtLeast(0L)
                                                Box(
                                                    Modifier
                                                        .animateItem(placementSpec = MotionSpec.ItemPlacementSpring)
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                                        .height(148.dp)
                                                        .iosLiquidPress(heroSrc, scaleDown = 0.97f, sheenAlpha = 0.14f)
                                                        .frostedGlassChrome(
                                                            enabled = glassEnabled,
                                                            shape = RoundedCornerShape(22.dp),
                                                            tint = Color(0x400A1424),
                                                            fallbackColor = Color(0xFF101826),
                                                            fallbackBorderColor = palette.cardBorder,
                                                        )
                                                        .clickable(interactionSource = heroSrc, indication = ripple()) {
                                                            play(heroVideo)
                                                        }
                                                ) {
                                                    Thumbnail(heroVideo, Modifier.fillMaxSize(), showDuration = false)
                                                    Box(
                                                        Modifier
                                                            .fillMaxSize()
                                                            .background(
                                                                Brush.verticalGradient(
                                                                    listOf(
                                                                        Color(0x2802050B),
                                                                        Color(0x9E040A15),
                                                                        Color(0xEB030710),
                                                                    )
                                                                )
                                                            )
                                                    )
                                                    Column(
                                                        Modifier
                                                            .fillMaxSize()
                                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                                        verticalArrangement = Arrangement.SpaceBetween,
                                                    ) {
                                                        Box(
                                                            Modifier
                                                                .clip(RoundedCornerShape(50))
                                                                .background(GlassTheme.CrystalCobalt.copy(alpha = 0.85f))
                                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                                        ) {
                                                            Text(
                                                                "CONTINUE WATCHING",
                                                                color = Color.White,
                                                                fontSize = 9.5.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                letterSpacing = 0.6.sp,
                                                            )
                                                        }
                                                        Row(
                                                            Modifier.fillMaxWidth(),
                                                            verticalAlignment = Alignment.Bottom,
                                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                        ) {
                                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                                Text(
                                                                    heroVideo.title.substringBeforeLast('.'),
                                                                    color = Color.White,
                                                                    fontSize = 15.5.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                )
                                                                Text(
                                                                    if (remMs > 0L) "${durationText(remMs)} left" else durationText(savedHero.position),
                                                                    color = Cyan,
                                                                    fontSize = 11.5.sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                )
                                                                LinearProgressIndicator(
                                                                    progress = { progressFraction },
                                                                    modifier = Modifier
                                                                        .fillMaxWidth()
                                                                        .height(3.5.dp)
                                                                        .clip(RoundedCornerShape(2.dp)),
                                                                    color = Accent,
                                                                    trackColor = Color.White.copy(alpha = 0.22f),
                                                                )
                                                            }
                                                            Box(
                                                                Modifier
                                                                    .size(40.dp)
                                                                    .clip(CircleShape)
                                                                    .background(Color.White.copy(alpha = 0.20f))
                                                                    .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape),
                                                                contentAlignment = Alignment.Center,
                                                            ) {
                                                                Icon(Icons.Default.PlayArrow, "Resume ${heroVideo.title}", tint = Color.White, modifier = Modifier.size(24.dp))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if (recentVideos.isNotEmpty()) {
                                            item(key = "recently_added") {
                                                Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
                                                    Row(
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .padding(start = 16.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                    ) {
                                                        Text(
                                                            "Recently Added",
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = palette.textPrimary,
                                                        )
                                                        Text(
                                                            "Clear",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = palette.accent,
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(8.dp))
                                                                .clickable {
                                                                    val allVisibleUris = activity.videos
                                                                        .filter { !it.audio && it.folder !in hidden }
                                                                        .map { it.uri }
                                                                    Store.dismissAllRecentAdded(activity, allVisibleUris)
                                                                    dismissedRecent = Store.dismissedRecentAdded(activity)
                                                                }
                                                                .padding(horizontal = 8.dp, vertical = 3.dp),
                                                        )
                                                    }
                                                    LazyRow(
                                                        state = recentRowState,
                                                        contentPadding = PaddingValues(horizontal = 14.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        modifier = Modifier.padding(top = 2.dp)
                                                    ) {
                                                        items(recentVideos, key = { "recent_${it.uri}" }) { video ->
                                                            val recSrc = remember { MutableInteractionSource() }
                                                            val rmSrc = remember(video.uri) { MutableInteractionSource() }
                                                            Column(
                                                                Modifier
                                                                    .animateItem(placementSpec = MotionSpec.ItemPlacementSpring)
                                                                    .width(106.dp)
                                                                    .clip(RoundedCornerShape(7.dp))
                                                                    .iosLiquidPress(recSrc, scaleDown = 0.94f, sheenAlpha = 0.14f)
                                                                    .clickable(interactionSource = recSrc, indication = ripple()) { play(video) }
                                                                    .padding(1.dp)
                                                            ) {
                                                                Box(Modifier.width(104.dp).height(58.dp)) {
                                                                    Thumbnail(video, Modifier.fillMaxSize())
                                                                    if (history.none { it.uri == video.uri }) {
                                                                        Box(
                                                                            Modifier.align(Alignment.TopStart).padding(3.dp)
                                                                                .background(Color(0xFFFF3B72), RoundedCornerShape(3.dp))
                                                                                .padding(horizontal = 3.5.dp, vertical = 1.dp)
                                                                        ) {
                                                                            Text("NEW", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                                                        }
                                                                    }
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .align(Alignment.TopEnd)
                                                                            .padding(3.dp)
                                                                            .size(20.dp)
                                                                            .clip(CircleShape)
                                                                            .background(Color.Black.copy(alpha = 0.72f))
                                                                            .border(0.75.dp, Color.White.copy(alpha = 0.36f), CircleShape)
                                                                            .iosLiquidPress(rmSrc, scaleDown = 0.85f, sheenAlpha = 0.16f)
                                                                            .clickable(interactionSource = rmSrc, indication = null) {
                                                                                Store.dismissRecentAdded(activity, video.uri)
                                                                                dismissedRecent = Store.dismissedRecentAdded(activity)
                                                                            },
                                                                        contentAlignment = Alignment.Center,
                                                                    ) {
                                                                        Icon(
                                                                            Icons.Default.Close,
                                                                            contentDescription = "Remove ${video.title} from recently added",
                                                                            tint = Color.White,
                                                                            modifier = Modifier.size(12.dp),
                                                                        )
                                                                    }
                                                                }
                                                                Text(
                                                                    video.title,
                                                                    fontSize = 11.sp,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    color = palette.textPrimary,
                                                                    modifier = Modifier.padding(top = 3.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 2.dp), color = palette.divider)
                                                }
                                            }
                                        }
                                        items(folders, key = { it.name }) { entry ->
                                            val folderSrc = remember { MutableInteractionSource() }
                                            val isCurrentFolder = entry.items.any { it.uri == state.item?.uri }
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .animateItem(placementSpec = MotionSpec.ItemPlacementSpring)
                                                    .let { base ->
                                                        when {
                                                            glassEnabled ->
                                                                base
                                                                    .padding(horizontal = 12.dp, vertical = 3.5.dp)
                                                                    .frostedGlassChrome(
                                                                        enabled = true,
                                                                        shape = RoundedCornerShape(18.dp),
                                                                        tint = if (isCurrentFolder) Color(0x460D2A4E) else Color(0x300B1424),
                                                                    )
                                                            palette.isLight ->
                                                                base
                                                                    .padding(horizontal = 12.dp, vertical = 3.5.dp)
                                                                    .clip(RoundedCornerShape(16.dp))
                                                                    .background(
                                                                        if (isCurrentFolder) palette.accent.copy(alpha = 0.12f) else palette.cardFallback,
                                                                        RoundedCornerShape(16.dp),
                                                                    )
                                                                    .border(1.dp, palette.cardBorder, RoundedCornerShape(16.dp))
                                                            else -> base
                                                        }
                                                    }
                                                    .iosLiquidPress(folderSrc, scaleDown = 0.98f, sheenAlpha = 0.10f)
                                                    .combinedClickable(
                                                        interactionSource = folderSrc,
                                                        indication = ripple(),
                                                        onClick = { onFolderChange(entry.name) },
                                                        onLongClick = { folderAction = entry.name },
                                                    )
                                                    .padding(
                                                        horizontal = if (glassEnabled || palette.isLight) 12.dp else 14.dp,
                                                        vertical = if (glassEnabled || palette.isLight) 9.dp else 6.dp,
                                                    ),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Box(Modifier.width(56.dp).height(44.dp)) {
                                                    FolderThumbnail(entry, Modifier.fillMaxSize())
                                                    val newCount =
                                                        entry.items.count { row ->
                                                            history.none { it.uri == row.uri }
                                                        }
                                                    if (newCount > 0)
                                                        Box(
                                                            Modifier.align(Alignment.TopEnd)
                                                                .offset(x = 3.dp, y = (-3).dp)
                                                                .size(18.dp)
                                                                .background(Color(0xFFFF3B72), CircleShape),
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            Text(
                                                                if (newCount > 99) "99+" else "$newCount",
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                lineHeight = 12.sp,
                                                                color = Color.White,
                                                            )
                                                        }
                                                }
                                                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                                                    Text(
                                                        entry.name.substringAfterLast('/'),
                                                        fontSize = 15.5.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = if (isCurrentFolder) palette.accent else palette.textPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Row(
                                                        Modifier.padding(top = 2.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    ) {
                                                        Text(
                                                            "${entry.items.size} ${if (entry.items.size == 1) "video" else "videos"}",
                                                            fontSize = 12.sp,
                                                            color = palette.textSecondary,
                                                        )
                                                        Text(
                                                            sizeText(entry.size),
                                                            color = palette.chipText,
                                                            fontSize = 10.5.sp,
                                                            lineHeight = 12.sp,
                                                            maxLines = 1,
                                                            softWrap = false,
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(4.dp))
                                                                .background(palette.chipBackground)
                                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            items.isEmpty() ->
                                if (curTab == "Audio") AccessEmpty(activity)
                                else
                                    EmptyLibrary(
                                        "No media here",
                                        if (searching) "Try a different search."
                                        else "Open a file or choose a folder from the menu.",
                                        Icons.Default.VideoLibrary,
                                    )
                            grid ->
                                LazyVerticalGrid(
                                    modifier = Modifier.fillMaxSize(),
                                    columns = GridCells.Adaptive(160.dp),
                                    contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, listBottomPad),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(items, key = { it.uri }) { item ->
                                        MediaTile(
                                            item,
                                            item.uri in selected,
                                            {
                                                if (selected.isEmpty()) play(item)
                                                else selected = selected.toggle(item.uri)
                                            },
                                            { selected = selected.toggle(item.uri) },
                                            { overflow(item, it) },
                                            curPlaylist != null,
                                            modifier = Modifier.animateItem(placementSpec = MotionSpec.ItemPlacementSpring),
                                        )
                                    }
                                }
                            else ->
                                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = listBottomPad)) {
                                    items(items, key = { it.uri }) { item ->
                                        MediaRow(
                                            item,
                                            history.any { it.uri == item.uri },
                                            item.uri in selected,
                                            {
                                                if (selected.isEmpty()) play(item)
                                                else selected = selected.toggle(item.uri)
                                            },
                                            { selected = selected.toggle(item.uri) },
                                            { overflow(item, it) },
                                            curPlaylist != null,
                                            modifier = Modifier.animateItem(placementSpec = MotionSpec.ItemPlacementSpring),
                                        )
                                    }
                                }
                        }
                                }
                                PullToRefreshDefaults.Indicator(
                                    state = pullState,
                                    isRefreshing = manualRefreshing && activity.scanning,
                                    modifier = Modifier.align(Alignment.TopCenter),
                                    color = palette.accent,
                                    containerColor = if (palette.isLight) Color.White else Color(0xFF141C28),
                                )
                            }
                        }
                    }
                    activity.libraryError?.let {
                        Text(
                            it,
                            color = if (palette.isLight) Color(0xFFC62828) else Color(0xFFFFB4AB),
                            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                        )
                    }
                }
                if (!glassEnabled) {
                    NavigationBar(containerColor = palette.navContainer, tonalElevation = 0.dp) {
                        listOf(
                                Triple("Folders", Icons.Default.Folder, "Local"),
                                Triple("Audio", Icons.Default.Headphones, "Audio"),
                                Triple("Playlists", Icons.Default.QueueMusic, "Playlists"),
                                Triple("Settings", Icons.Default.Settings, "Settings"),
                            )
                            .forEach { (name, icon, label) ->
                                NavigationBarItem(
                                    selected = tab == name,
                                    onClick = {
                                        onTabChange(name)
                                        onFolderChange(null)
                                        onActivePlaylistChange(null)
                                        onSearchChange("")
                                        onSearchingChange(false)
                                        selected = emptySet()
                                    },
                                    icon = { Icon(icon, label) },
                                    label = { Text(label) },
                                    colors =
                                        NavigationBarItemDefaults.colors(
                                            selectedIconColor = palette.accent,
                                            selectedTextColor = palette.accent,
                                            indicatorColor = if (palette.isLight) palette.accent.copy(alpha = 0.14f) else Color.Transparent,
                                            unselectedIconColor = palette.textSecondary,
                                            unselectedTextColor = palette.textSecondary,
                                        ),
                                )
                            }
                    }
                }
            }
        }
        val showResumeFab = state.item?.audio != true && tab != "Settings" &&
            !(tab == "Playlists" && activePlaylist == null) &&
            (activity.videos.isNotEmpty() || state.item != null)
        if (showResumeFab) {
            val fabSrc = remember { MutableInteractionSource() }
            val fabClick = {
                val current = state.item
                if (current != null) {
                    activity.showPlayer = true
                    activity.immersive(true)
                } else {
                    val last =
                        history.firstOrNull()?.let { h ->
                            (activity.videos + activity.audios).find { it.uri == h.uri }
                        }
                    val first = last ?: items.firstOrNull()
                    if (first != null) play(first)
                }
            }
            if (glassEnabled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .safeDrawingPadding()
                        .padding(end = 16.dp, bottom = 82.dp)
                        .size(54.dp)
                        .iosLiquidPress(fabSrc, scaleDown = 0.89f, sheenAlpha = 0.22f)
                        .liquidGlassSurface(
                            backdrop = appBackdrop,
                            enabled = true,
                            shape = CircleShape,
                            tint = Accent.copy(alpha = 0.56f),
                            refractionHeight = 12.dp,
                            refractionAmount = 22.dp,
                        )
                        .clickable(interactionSource = fabSrc, indication = null, onClick = fabClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        "Resume playback",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
            } else {
                FloatingActionButton(
                    onClick = fabClick,
                    interactionSource = fabSrc,
                    containerColor = palette.accent,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .safeDrawingPadding()
                        .padding(end = 16.dp, bottom = 84.dp)
                        .size(54.dp)
                        .iosLiquidPress(fabSrc, scaleDown = 0.90f),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        "Resume playback",
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
        if (state.item?.audio == true && service != null) {
            val miniSrc = remember { MutableInteractionSource() }
            val morphModifier = with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    sharedContentState = rememberSharedContentState("mini_to_music"),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> MotionSpec.MorphRectSpring },
                )
            }
            Row(
                Modifier.align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(horizontal = if (glassEnabled || palette.isLight) 16.dp else 0.dp)
                    .padding(bottom = if (glassEnabled) 82.dp else 72.dp)
                    .fillMaxWidth()
                    .then(morphModifier)
                    .iosLiquidPress(miniSrc, scaleDown = 0.96f, sheenAlpha = 0.14f)
                    .let {
                        when {
                            glassEnabled ->
                                it.liquidGlassSurface(
                                    backdrop = appBackdrop,
                                    enabled = true,
                                    shape = RoundedCornerShape(20.dp),
                                    tint = Color(0x6E0E1726),
                                )
                            palette.isLight ->
                                it
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(palette.surface, RoundedCornerShape(18.dp))
                                    .border(1.dp, palette.cardBorder, RoundedCornerShape(18.dp))
                            else -> it.background(Panel)
                        }
                    }
                    .clickable(interactionSource = miniSrc, indication = ripple()) {
                        activity.showPlayer = true
                        activity.immersive(true)
                    }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.MusicNote, null, tint = palette.accent)
                Text(
                    state.item!!.title,
                    Modifier.weight(1f).padding(horizontal = 12.dp),
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { service.toggle() }) {
                    Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", tint = palette.textPrimary)
                }
                IconButton(onClick = { service.next() }) {
                    Icon(Icons.Default.SkipNext, "Next track", tint = palette.textPrimary)
                }
            }
        }
        if (glassEnabled) {
            val dockTabs = listOf(
                Triple("Folders", Icons.Default.Folder, "Local"),
                Triple("Audio", Icons.Default.Headphones, "Audio"),
                Triple("Playlists", Icons.Default.QueueMusic, "Playlists"),
                Triple("Settings", Icons.Default.Settings, "Settings"),
            )
            val activeIdx = dockTabs.indexOfFirst { it.first == tab }.coerceAtLeast(0)
            val density = LocalDensity.current
            val haptics = LocalHapticFeedback.current
            fun switchDockTab(name: String) {
                onTabChange(name)
                onFolderChange(null)
                onActivePlaylistChange(null)
                onSearchChange("")
                onSearchingChange(false)
                selected = emptySet()
            }
            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .height(62.dp)
                    .liquidGlassSurface(
                        backdrop = appBackdrop,
                        enabled = true,
                        shape = CircleShape,
                        tint = Color(0x420C1524),
                        blurRadius = 14.dp,
                        refractionHeight = 14.dp,
                        refractionAmount = 24.dp,
                        chromaticAberration = true,
                    )
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                val tabWidth = maxWidth / dockTabs.size
                val tabWidthPx = with(density) { tabWidth.toPx().coerceAtLeast(1f) }
                var dragSlot by remember { mutableStateOf<Float?>(null) }
                var lastHapticIdx by remember(activeIdx) { mutableIntStateOf(activeIdx) }
                val animatedSlot by animateFloatAsState(
                    targetValue = dragSlot ?: activeIdx.toFloat(),
                    animationSpec = if (dragSlot != null) spring(stiffness = Spring.StiffnessHigh) else spring(dampingRatio = 0.72f, stiffness = 480f),
                    label = "crystalDockSlot",
                )
                val lensScale by animateFloatAsState(
                    targetValue = if (dragSlot != null) 1.06f else 1f,
                    animationSpec = MotionSpec.PressSpring,
                    label = "crystalLensScale",
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(dockTabs.size, tabWidthPx) {
                            detectHorizontalDragGestures(
                                onDragStart = { start ->
                                    dragSlot = (start.x / tabWidthPx - 0.5f).coerceIn(0f, (dockTabs.size - 1).toFloat())
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    val cur = dragSlot ?: activeIdx.toFloat()
                                    val next = (cur + dragAmount / tabWidthPx).coerceIn(0f, (dockTabs.size - 1).toFloat())
                                    dragSlot = next
                                    val nearest = next.roundToInt().coerceIn(0, dockTabs.lastIndex)
                                    if (nearest != lastHapticIdx) {
                                        lastHapticIdx = nearest
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                onDragEnd = {
                                    val idx = (dragSlot ?: activeIdx.toFloat()).roundToInt().coerceIn(0, dockTabs.lastIndex)
                                    dragSlot = null
                                    switchDockTab(dockTabs[idx].first)
                                },
                                onDragCancel = { dragSlot = null },
                            )
                        }
                ) {
                    val dropletShape = CircleShape
                    Box(
                        Modifier
                            .offset { IntOffset((animatedSlot * tabWidthPx).roundToInt(), 0) }
                            .width(tabWidth)
                            .fillMaxHeight()
                            .graphicsLayer {
                                scaleX = lensScale
                                scaleY = lensScale
                            }
                            .clip(dropletShape)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.24f),
                                        GlassTheme.CrystalCobalt.copy(alpha = 0.26f),
                                        Color(0xFF0A2444).copy(alpha = 0.34f),
                                    )
                                ),
                                dropletShape,
                            )
                            .border(0.9.dp, liquidSpecularBrush(0.55f, GlassTheme.CrystalCobalt), dropletShape)
                    )
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        dockTabs.forEachIndexed { idx, (name, icon, label) ->
                            val highlighted = (animatedSlot - idx).absoluteValue < 0.5f
                            val tabSrc = remember { MutableInteractionSource() }
                            val itemTint by animateColorAsState(
                                targetValue = if (highlighted) Color(0xFF5FB0FF) else Color.White.copy(alpha = 0.84f),
                                animationSpec = MotionSpec.ColorSpring,
                                label = "dockTabTint",
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .iosLiquidPress(tabSrc, scaleDown = 0.90f, sheenAlpha = 0.14f)
                                    .clickable(interactionSource = tabSrc, indication = null) {
                                        switchDockTab(name)
                                    }
                                    .padding(vertical = 3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    icon,
                                    label,
                                    tint = itemTint,
                                    modifier = Modifier.size(19.dp),
                                )
                                Text(
                                    label,
                                    color = itemTint,
                                    fontSize = 10.5.sp,
                                    fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    folderAction?.let { name ->
        LiquidGlassAlertDialog(
            onDismissRequest = { folderAction = null },
            title = { Text(name.substringAfterLast('/')) },
            text = { Text("Exclude folder from library?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        Store.prefs(activity).edit().putStringSet("hidden", hidden + name).apply()
                        folderAction = null
                        activity.reload()
                        onBumpRefresh()
                    }
                ) {
                    Text("Exclude", color = palette.accent)
                }
            },
            dismissButton = { TextButton(onClick = { folderAction = null }) { Text("Cancel", color = palette.textSecondary) } },
        )
    }
    if (detail != null) {
        val item = detail!!
        val detailedInfo by produceState<MediaDetailedInfo?>(initialValue = null, item.uri) {
            value = Library.inspectDetailed(activity, activity.service?.vlc, item)
        }
        LiquidGlassAlertDialog(
            onDismissRequest = { detail = null },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Info, null, tint = palette.accent, modifier = Modifier.size(20.dp))
                    Text("Information", fontWeight = FontWeight.SemiBold)
                }
            },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    val loaded = detailedInfo
                    if (loaded == null) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = palette.accent, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Reading media info…", color = palette.textSecondary, fontSize = 13.sp)
                        }
                    } else {
                        MediaInformationContent(info = loaded, inPlayer = false)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Close", color = palette.accent) } },
        )
    }
    if (playlistDialog)
        LiquidGlassAlertDialog(
            onDismissRequest = { playlistDialog = false },
            title = { Text("Add to playlist") },
            text = {
                OutlinedTextField(
                    playlistName,
                    { playlistName = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = playlistName.isNotBlank(),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                playlistItems.forEach { item ->
                                    Store.db(activity)
                                        .dao()
                                        .add(
                                            PlaylistEntry().apply {
                                                playlist = playlistName.trim()
                                                uri = item.uri
                                                title = item.title
                                                added = System.currentTimeMillis()
                                            }
                                        )
                                }
                            }
                            onBumpRefresh()
                            playlistDialog = false
                            selected = emptySet()
                            activity.toast("Added to ${playlistName.trim()}")
                        }
                    },
                ) {
                    Text("Add", color = palette.accent)
                }
            },
            dismissButton = { TextButton(onClick = { playlistDialog = false }) { Text("Cancel", color = palette.textSecondary) } },
        )
    if (deleteTarget != null) {
        val target = deleteTarget!!
        LiquidGlassAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete file") },
            text = { Text("Delete \"${target.title}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        activity.deleteMedia(target) {
                            Library.invalidateScanCache()
                            activity.reload()
                            onBumpRefresh()
                            activity.toast("File deleted")
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE53935))
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = palette.textSecondary) } }
        )
    }
    if (deleteSelectedDialog) {
        val targets = items.filter { it.uri in selected }
        if (targets.isEmpty()) {
            deleteSelectedDialog = false
        } else {
            LiquidGlassAlertDialog(
                onDismissRequest = { deleteSelectedDialog = false },
                title = { Text("Delete ${targets.size} file${if (targets.size == 1) "" else "s"}") },
                text = { Text("Delete ${targets.size} selected item${if (targets.size == 1) "" else "s"} permanently?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleteSelectedDialog = false
                            activity.deleteMediaItems(targets) { count ->
                                Library.invalidateScanCache()
                                activity.reload()
                                selected = emptySet()
                                onBumpRefresh()
                                activity.toast("Deleted $count file${if (count == 1) "" else "s"}")
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE53935)),
                    ) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { deleteSelectedDialog = false }) { Text("Cancel", color = palette.textSecondary) } },
            )
        }
    }
    if (streamDialog) {
        StreamUrlDialog(
            activity = activity,
            streamUrl = streamUrl,
            onStreamUrlChange = onStreamUrlChange,
            recentStreams = recentStreams,
            onRemoveRecent = { url ->
                Store.removeStream(activity, url)
                onRecentStreamsChange(Store.recentStreams(activity))
            },
            onDismiss = { onStreamDialogChange(false) },
            onPlayUrl = { cleanUrl ->
                onStreamDialogChange(false)
                onStreamUrlChange("")
                activity.lifecycleScope.launch {
                    val item = Library.opened(activity, Uri.parse(cleanUrl))
                    play(item)
                    onRecentStreamsChange(Store.recentStreams(activity))
                }
            },
        )
    }
}

@Composable
private fun StreamUrlDialog(
    activity: MainActivity,
    streamUrl: String,
    onStreamUrlChange: (String) -> Unit,
    recentStreams: List<String>,
    onRemoveRecent: (String) -> Unit,
    onDismiss: () -> Unit,
    onPlayUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val palette = LocalAppPalette.current
    val glassEnabled = palette.isGlass
    val useLight = palette.isLight
    var clipboardSuggestion by remember { mutableStateOf<String?>(null) }

    fun readClipboardUrl(): String? {
        val raw = runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull() ?: return null
        val normalized = PlaybackRules.normalizeStreamUrl(raw)
        return normalized.takeIf { PlaybackRules.isStreamUrl(it) }
    }

    LaunchedEffect(Unit) {
        clipboardSuggestion = readClipboardUrl()?.takeIf { it != streamUrl.trim() }
    }

    val normalized = PlaybackRules.normalizeStreamUrl(streamUrl)
    val isValid = PlaybackRules.isStreamUrl(normalized)
    val isWebpage = PlaybackRules.isWebpageWrapperUrl(normalized)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            val maxH = (maxHeight - 16.dp).coerceAtLeast(240.dp)
            Column(
                Modifier
                    .widthIn(min = 320.dp, max = 500.dp)
                    .heightIn(max = maxH)
                    .fillMaxWidth()
                    .frostedGlassChrome(
                        enabled = glassEnabled,
                        shape = RoundedCornerShape(if (glassEnabled) 22.dp else 16.dp),
                        tint = Color(0xBC0B121D),
                        fallbackColor = if (useLight) Color(0xFFFFFFFF) else Color(0xFF1B1F26),
                        fallbackBorderColor = if (useLight) palette.cardBorder else Color.White.copy(alpha = 0.18f),
                    )
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.CloudQueue, null, tint = palette.accent)
                    Text("Network Stream", color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "HTTP / HTTPS / HLS (.m3u8) / DASH / RTSP",
                        color = palette.textSecondary,
                        fontSize = 12.sp,
                    )
                    clipboardSuggestion?.let { clipUrl ->
                        val clipSrc = remember { MutableInteractionSource() }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.accent.copy(alpha = 0.14f))
                                .border(0.8.dp, palette.accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .iosLiquidPress(clipSrc, scaleDown = 0.97f)
                                .clickable(interactionSource = clipSrc, indication = ripple()) {
                                    onStreamUrlChange(clipUrl)
                                    clipboardSuggestion = null
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.ContentPaste, null, tint = palette.accent, modifier = Modifier.size(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Paste clipboard", color = palette.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(clipUrl, color = palette.textPrimary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = streamUrl,
                        onValueChange = onStreamUrlChange,
                        label = { Text("Stream URL") },
                        placeholder = { Text("https://example.com/video.mp4") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            Row {
                                IconButton(onClick = {
                                    val pasted = readClipboardUrl()
                                        ?: runCatching {
                                            context.getSystemService(ClipboardManager::class.java)
                                                ?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                                        }.getOrNull()
                                    if (!pasted.isNullOrBlank()) onStreamUrlChange(pasted)
                                    else activity.toast("Clipboard is empty")
                                }) {
                                    Icon(Icons.Default.ContentPaste, "Paste")
                                }
                                if (streamUrl.isNotEmpty()) {
                                    IconButton(onClick = { onStreamUrlChange("") }) {
                                        Icon(Icons.Default.Close, "Clear")
                                    }
                                }
                            }
                        },
                    )
                    if (isWebpage) {
                        Text(
                            "Webpage link detected. Enter a direct stream URL.",
                            color = if (useLight) Color(0xFFB45309) else Color(0xFFFFD54F),
                            fontSize = 12.sp,
                        )
                    }
                    if (recentStreams.isNotEmpty()) {
                        Text("Recent", color = palette.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        recentStreams.take(5).forEach { recent ->
                            val recSrc = remember { MutableInteractionSource() }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (useLight) palette.chipBackground else Color.White.copy(alpha = 0.05f))
                                    .iosLiquidPress(recSrc, scaleDown = 0.98f)
                                    .clickable(interactionSource = recSrc, indication = ripple()) {
                                        onPlayUrl(recent)
                                    }
                                    .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.History, null, tint = palette.accent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    recent,
                                    color = palette.textPrimary,
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { onRemoveRecent(recent) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(Icons.Default.Close, "Remove recent stream", tint = palette.textSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = palette.textSecondary) }
                    Spacer(Modifier.width(8.dp))
                    val playSrc = remember { MutableInteractionSource() }
                    Button(
                        enabled = isValid,
                        onClick = { onPlayUrl(normalized) },
                        interactionSource = playSrc,
                        modifier = Modifier.iosLiquidPress(playSrc, enabled = isValid, scaleDown = 0.93f),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = Color.White),
                    ) {
                        Text("Play", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

fun Set<String>.toggle(value: String) = if (value in this) this - value else this + value

@Composable
fun QuickAction(label: String, icon: ImageVector, action: () -> Unit) {
    val palette = LocalAppPalette.current
    val glassEnabled = palette.isGlass
    val pressSrc = remember { MutableInteractionSource() }
    Column(
        Modifier.width(58.dp)
            .iosLiquidPress(pressSrc, scaleDown = 0.89f, sheenAlpha = 0.18f)
            .clickable(interactionSource = pressSrc, indication = null, onClick = action),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(38.dp)
                .frostedGlassChrome(
                    enabled = glassEnabled,
                    shape = CircleShape,
                    tint = Color(0x520F2438),
                    fallbackColor = if (palette.isLight) palette.accent.copy(alpha = 0.12f) else Color(0xFF102331),
                    fallbackBorderColor = if (palette.isLight) palette.cardBorder else Color.White.copy(alpha = 0.18f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = palette.accent, modifier = Modifier.size(21.dp))
        }
        Text(
            label,
            fontSize = 11.sp,
            color = if (palette.isLight) palette.textPrimary else Color(0xFFD5DCE8),
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AllFilesAccessBanner(onGrant: () -> Unit) {
    val glassEnabled by rememberGlassEnabled()
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (dismissed) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .frostedGlassChrome(
                enabled = glassEnabled,
                shape = RoundedCornerShape(16.dp),
                tint = Color(0x3500B4D8),
                fallbackColor = Color(0xFF102538),
            )
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FolderSpecial, null, tint = Cyan, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Unlock In-Progress Downloads", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "Grant All Files Access to play .pending downloads and browse all folders.",
                    color = Color(0xFFB0C4DE),
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Enable", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            }
            IconButton(onClick = { dismissed = true }, modifier = Modifier.size(24.dp).padding(start = 4.dp)) {
                Icon(Icons.Default.Close, null, tint = Color.LightGray.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun AccessEmpty(activity: MainActivity) {
    val palette = LocalAppPalette.current
    BoxWithConstraints(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = maxHeight).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.FolderOpen, null, tint = palette.accent, modifier = Modifier.size(56.dp))
            Text(
                "No media found",
                color = palette.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "Grant access or open a file.",
                color = palette.textSecondary,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            Button(onClick = { activity.requestAccess() }) { Text("Allow access") }
            TextButton(onClick = { activity.openFile.launch(arrayOf("*/*")) }) { Text("Open file") }
            TextButton(onClick = { activity.openFolder.launch(null) }) { Text("Choose folder") }
        }
    }
}

@Composable
fun EmptyLibrary(title: String, description: String, icon: ImageVector) {
    val palette = LocalAppPalette.current
    BoxWithConstraints(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = maxHeight).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, tint = palette.textSecondary, modifier = Modifier.size(56.dp))
            Text(title, color = palette.textPrimary, fontSize = 22.sp, modifier = Modifier.padding(top = 16.dp))
            Text(description, color = palette.textSecondary, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun Badge(text: String, color: Color = Color(0xFF42464D), textColor: Color = Color.White) {
    Text(
        text,
        color = textColor,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier.clip(RoundedCornerShape(4.dp))
                .background(color)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun Thumbnail(item: MediaItem, modifier: Modifier, showDuration: Boolean = true) {
    val context = LocalContext.current
    val palette = LocalAppPalette.current
    var bitmap by remember(item.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val revision by Library.thumbnailRevision.collectAsState()
    var loading by remember(item.uri, revision) { mutableStateOf(true) }
    LaunchedEffect(item.uri, item.size, item.modified, item.added, revision) {
        loading = true
        bitmap = withTimeoutOrNull(5000L) { Library.thumbnail(context, item) }
        loading = false
    }
    Box(
        modifier.clip(RoundedCornerShape(5.dp)).background(if (palette.isLight) Color(0xFFE5EAF2) else Panel),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
            ?: Icon(
                if (item.audio) Icons.Default.Headphones else Icons.Default.Movie,
                null,
                tint = if (loading) palette.textSecondary.copy(alpha = 0.45f) else palette.textSecondary,
                modifier = Modifier.size(32.dp),
            )
        if (showDuration && item.duration > 0)
            Text(
                durationText(item.duration),
                color = Color.White,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .background(Color.Black.copy(alpha = .68f))
                        .padding(horizontal = 3.dp),
            )
    }
}

@Composable
fun FolderThumbnail(entry: MediaFolder, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val palette = LocalAppPalette.current
    var bitmap by remember(entry.name, entry.items.size) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(entry.name, entry.items.size) {
        bitmap = withTimeoutOrNull(2500L) { Library.folderThumbnail(context, entry) }
    }
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(if (palette.isLight) Color(0xFFDCE3EE) else Color(0xFF1E232B)),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))))
            Icon(Icons.Default.Folder, null, tint = Accent, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(20.dp))
        } ?: Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, h * .2f)
                cubicTo(0f, h * .08f, w * .04f, 0f, w * .12f, 0f)
                lineTo(w * .34f, 0f)
                lineTo(w * .46f, h * .18f)
                lineTo(w * .9f, h * .18f)
                cubicTo(w * .97f, h * .18f, w, h * .22f, w, h * .31f)
                lineTo(w, h * .88f)
                cubicTo(w, h * .96f, w * .97f, h, w * .9f, h)
                lineTo(w * .1f, h)
                cubicTo(w * .03f, h, 0f, h * .96f, 0f, h * .88f)
                close()
            }
            drawPath(path, if (palette.isLight) Color(0xFF64748B) else Color(0xFF45505D))
        }
    }
}

@Composable
fun MediaRow(
    item: MediaItem,
    seen: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit,
    onAction: (String) -> Unit,
    remove: Boolean,
    modifier: Modifier = Modifier,
) {
    val activity = LocalActivity.current as? MainActivity
    val palette = LocalAppPalette.current
    val glassEnabled = palette.isGlass
    val fallbackState = remember { MutableStateFlow(PlayerState()) }
    val activeServiceState by (activity?.service?.state ?: fallbackState).collectAsState()
    val isPlaying = activeServiceState.item?.uri == item.uri
    val pressSrc = remember { MutableInteractionSource() }
    var info by remember(item.uri) { mutableStateOf<MediaInfo?>(null) }
    LaunchedEffect(item.uri, activity?.service) {
        activity?.service?.let { info = Library.inspect(activity, it.vlc, item) }
    }
    val resolved =
        item.copy(
            duration = info?.duration?.takeIf { it > 0 } ?: item.duration,
            width = info?.width?.takeIf { it > 0 } ?: item.width,
            height = info?.height?.takeIf { it > 0 } ?: item.height,
        )
    Row(
        modifier.fillMaxWidth()
            .let { base ->
                when {
                    glassEnabled ->
                        base
                            .padding(horizontal = 12.dp, vertical = 3.5.dp)
                            .frostedGlassChrome(
                                enabled = true,
                                shape = RoundedCornerShape(16.dp),
                                tint = when {
                                    selected -> Color(0x5A123656)
                                    isPlaying -> Color(0x460D2A4E)
                                    else -> Color(0x2A0B1424)
                                },
                            )
                    palette.isLight ->
                        base
                            .padding(horizontal = 12.dp, vertical = 3.5.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                when {
                                    selected || isPlaying -> palette.accent.copy(alpha = 0.12f)
                                    else -> palette.cardFallback
                                },
                                RoundedCornerShape(16.dp),
                            )
                            .border(1.dp, palette.cardBorder, RoundedCornerShape(16.dp))
                    else -> base.background(if (selected) Color(0x55123044) else Color.Black)
                }
            }
            .iosLiquidPress(pressSrc, scaleDown = 0.98f, sheenAlpha = 0.09f)
            .combinedClickable(
                interactionSource = pressSrc,
                indication = ripple(),
                onClick = onClick,
                onLongClick = onLong,
            )
            .padding(start = if (glassEnabled || palette.isLight) 10.dp else 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(resolved, if (item.audio) Modifier.size(48.dp) else Modifier.width(84.dp).height(48.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    color = if (isPlaying) palette.accent else if (seen) palette.textSecondary else palette.textPrimary,
                    fontSize = 14.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!seen) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.background(Color(0xFFFF3B72), RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text("NEW", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (PlaybackRules.isStreamUrl(item.uri)) Badge("STREAM", Color(0xFF00838F))
                info?.subtitles?.firstOrNull()?.let { Badge(it, Color(0xFF00BE81)) }
                if (resolved.height > 0) Badge("${resolved.height}p", palette.chipBackground, palette.chipText)
                if (item.size > 0) Badge(sizeText(item.size), palette.chipBackground, palette.chipText)
            }
        }
        ItemMenu(onAction, remove)
    }
}

@Composable
fun MediaTile(
    item: MediaItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit,
    onAction: (String) -> Unit,
    remove: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    val glassEnabled = palette.isGlass
    val pressSrc = remember { MutableInteractionSource() }
    Column(
        modifier
            .let { base ->
                when {
                    glassEnabled ->
                        base
                            .frostedGlassChrome(
                                enabled = true,
                                shape = RoundedCornerShape(16.dp),
                                tint = if (selected) Color(0x5A123656) else Color(0x2C0B1424),
                            )
                            .padding(6.dp)
                    palette.isLight ->
                        base
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) palette.accent.copy(alpha = 0.12f) else palette.cardFallback, RoundedCornerShape(16.dp))
                            .border(1.dp, palette.cardBorder, RoundedCornerShape(16.dp))
                            .padding(6.dp)
                    else ->
                        base.background(
                            if (selected) Color(0x55123044) else Color.Black,
                            RoundedCornerShape(6.dp),
                        )
                }
            }
            .iosLiquidPress(pressSrc, scaleDown = 0.95f, sheenAlpha = 0.12f)
            .combinedClickable(
                interactionSource = pressSrc,
                indication = ripple(),
                onClick = onClick,
                onLongClick = onLong,
            )
    ) {
        Thumbnail(item, Modifier.fillMaxWidth().height(92.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.title,
                Modifier.weight(1f),
                color = palette.textPrimary,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ItemMenu(onAction, remove)
        }
    }
}

@Composable
fun ItemMenu(onAction: (String) -> Unit, remove: Boolean) {
    val palette = LocalAppPalette.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.MoreVert, "File options", tint = palette.textSecondary, modifier = Modifier.size(20.dp))
        }
        LiquidGlassPopupMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("Playlist", "Share", "Details", "Delete").forEach { action ->
                LiquidGlassPopupMenuItem(
                    text = {
                        Text(
                            when (action) {
                                "Playlist" -> "Add to playlist"
                                "Delete" -> "Delete"
                                else -> action
                            },
                            color = if (action == "Delete") Color(0xFFE53935) else palette.textPrimary,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            when (action) {
                                "Playlist" -> Icons.Default.PlaylistAdd
                                "Share" -> Icons.Default.Share
                                "Details" -> Icons.Default.Info
                                else -> Icons.Default.DeleteOutline
                            },
                            null,
                            tint = if (action == "Delete") Color(0xFFE53935) else palette.textPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {
                        expanded = false
                        onAction(action)
                    },
                )
            }
            if (remove) {
                LiquidGlassPopupMenuItem(
                    text = { Text("Remove", color = palette.textPrimary) },
                    leadingIcon = { Icon(Icons.Default.PlaylistRemove, null, tint = palette.textPrimary, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        expanded = false
                        onAction("Remove")
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsGlassCard(
    palette: AppPalette,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .frostedGlassChrome(
                enabled = palette.isGlass,
                shape = RoundedCornerShape(if (palette.isGlass) 18.dp else if (palette.isLight) 16.dp else 10.dp),
                tint = Color(0x4C0B1320),
                fallbackColor = palette.cardFallback,
                fallbackBorderColor = if (palette.isLight) palette.cardBorder else Color.White.copy(alpha = 0.14f),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}

private const val MSPLAYER_GITHUB_URL = "https://github.com/AWT-SRIRAM/MSPLAYER"

@Composable
fun SettingsScreen(activity: MainActivity, onSettingsChanged: () -> Unit = {}) {
    val prefs = remember { Store.prefs(activity) }
    val palette = LocalAppPalette.current
    val glassEnabled = palette.isGlass
    val currentThemeMode by rememberAppThemeMode()
    var epoch by remember { mutableIntStateOf(0) }
    var about by remember { mutableStateOf(false) }
    var showAdditional by remember { mutableStateOf(false) }
    var showFavAudioLangs by remember { mutableStateOf(false) }
    var showFavSubLangs by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    key(epoch) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = 14.dp,
                top = 8.dp,
                end = 14.dp,
                bottom = if (glassEnabled) 126.dp else 88.dp,
            ),
        ) {
            item(key = "appearance") {
                SettingsGlassCard(palette) {
                    Text("Appearance", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.accent)
                    Text("Theme", fontSize = 13.sp, color = palette.textSecondary, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        AppThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = currentThemeMode == mode,
                                onClick = {
                                    GlassTheme.setThemeMode(activity, mode)
                                    epoch++
                                },
                                label = { Text(mode.label, color = if (currentThemeMode == mode) palette.accent else palette.textPrimary) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                }
            }
            item(key = "languages") {
                SettingsGlassCard(palette) {
                    Text("Languages", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.accent)
                    val favAudioLangs = Store.favoriteAudioLanguages(activity)
                    val favAudioSummary = favAudioLangs.mapNotNull { SubtitleTranslator.findLanguage(it)?.label }
                        .ifEmpty { listOf("English") }
                        .joinToString(", ")
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Favorite audio languages", color = palette.textPrimary, fontSize = 14.5.sp)
                            Text(favAudioSummary, color = palette.accentSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { showFavAudioLangs = true }) { Text("Choose", color = palette.accent) }
                    }
                    val favSubLangs = Store.favoriteSubtitleLanguages(activity)
                    val favSubSummary = favSubLangs.mapNotNull { SubtitleTranslator.findLanguage(it)?.label }
                        .ifEmpty { listOf("English") }
                        .joinToString(", ")
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Favorite subtitle languages", color = palette.textPrimary, fontSize = 14.5.sp)
                            Text(favSubSummary, color = palette.accentSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { showFavSubLangs = true }) { Text("Choose", color = palette.accent) }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = palette.divider)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Continue Watching card", color = palette.textPrimary, fontSize = 14.5.sp)
                            Text("Show resume banner at top of Local videos", color = palette.textSecondary, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = Store.continueWatchingEnabled(activity),
                            onCheckedChange = {
                                Store.saveContinueWatchingEnabled(activity, it)
                                epoch++
                                onSettingsChanged()
                            },
                        )
                    }
                }
            }
            item(key = "additional") {
                val addSrc = remember { MutableInteractionSource() }
                SettingsGlassCard(palette) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .iosLiquidPress(addSrc, scaleDown = 0.98f, sheenAlpha = 0.08f)
                            .clickable(interactionSource = addSrc, indication = ripple()) { showAdditional = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Tune, null, tint = palette.accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Additional settings", color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Resume playback, Auto PiP, Background audio, All files access, Seek step, Repeat, Decoder",
                                color = palette.textSecondary,
                                fontSize = 11.5.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = palette.textSecondary, modifier = Modifier.size(20.dp))
                    }
                }
            }
            item(key = "about") {
                SettingsGlassCard(palette) {
                    Text("MS PLAYER ${BuildConfig.VERSION_NAME}", color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "By SRIRAM S & ANTIGRAVITY",
                        color = palette.accentSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text("LibVLC 3.7.7", color = palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = palette.divider)
                    val ghSrc = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .iosLiquidPress(ghSrc, scaleDown = 0.98f)
                            .clickable(interactionSource = ghSrc, indication = ripple()) {
                                runCatching {
                                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MSPLAYER_GITHUB_URL)))
                                }.onFailure {
                                    activity.toast("No browser app found.")
                                }
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.OpenInBrowser, null, tint = palette.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("GitHub Repository", color = palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("github.com/AWT-SRIRAM/MSPLAYER", color = palette.accentSecondary, fontSize = 11.5.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = palette.textSecondary, modifier = Modifier.size(18.dp))
                    }
                    val licSrc = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .iosLiquidPress(licSrc, scaleDown = 0.98f)
                            .clickable(interactionSource = licSrc, indication = ripple()) { about = true }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Info, null, tint = palette.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Open-source licenses", color = palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("Clear summary of libraries & legal notices", color = palette.textSecondary, fontSize = 11.5.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = palette.textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    if (showAdditional) {
        AdditionalSettingsDialog(
            activity = activity,
            prefs = prefs,
            onChanged = { epoch++ },
            onDismiss = { showAdditional = false },
        )
    }
    if (showFavAudioLangs) {
        var currentFavs by remember(showFavAudioLangs) { mutableStateOf(Store.favoriteAudioLanguages(activity)) }
        LanguagePickerDialog(
            title = "Favorite Audio Languages",
            selectedCodes = currentFavs.toSet(),
            multiSelect = true,
            inPlayer = false,
            onToggleFavorite = { spec ->
                Store.toggleFavoriteAudioLanguage(activity, spec.code)
                currentFavs = Store.favoriteAudioLanguages(activity)
            },
            onSelect = { spec ->
                Store.toggleFavoriteAudioLanguage(activity, spec.code)
                currentFavs = Store.favoriteAudioLanguages(activity)
            },
            onDismiss = {
                showFavAudioLangs = false
                epoch++
            },
        )
    }
    if (showFavSubLangs) {
        var currentFavs by remember(showFavSubLangs) { mutableStateOf(Store.favoriteSubtitleLanguages(activity)) }
        LanguagePickerDialog(
            title = "Favorite Subtitle Languages",
            selectedCodes = currentFavs.toSet(),
            multiSelect = true,
            inPlayer = false,
            onToggleFavorite = { spec ->
                val updated = if (spec.code in currentFavs) currentFavs - spec.code else listOf(spec.code) + currentFavs
                val clean = if (updated.isEmpty()) listOf("en") else updated
                Store.saveFavoriteSubtitleLanguages(activity, clean)
                currentFavs = Store.favoriteSubtitleLanguages(activity)
            },
            onSelect = { spec ->
                val updated = if (spec.code in currentFavs) currentFavs - spec.code else listOf(spec.code) + currentFavs
                val clean = if (updated.isEmpty()) listOf("en") else updated
                Store.saveFavoriteSubtitleLanguages(activity, clean)
                currentFavs = Store.favoriteSubtitleLanguages(activity)
            },
            onDismiss = {
                showFavSubLangs = false
                epoch++
            },
        )
    }
    if (about) {
        OpenSourceLicensesDialog(
            activity = activity,
            onDismiss = { about = false },
        )
    }
}

@Composable
private fun AdditionalSettingsDialog(
    activity: MainActivity,
    prefs: SharedPreferences,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalAppPalette.current
    var localEpoch by remember { mutableIntStateOf(0) }
    key(localEpoch) {
        LiquidGlassAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Additional settings", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 10.dp)) {
                            Text("Resume playback", color = palette.textPrimary, fontSize = 14.5.sp)
                            Text("Continue videos from your last saved timestamp", color = palette.textSecondary, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = prefs.getBoolean("resume", true),
                            onCheckedChange = {
                                prefs.edit().putBoolean("resume", it).apply()
                                localEpoch++
                                onChanged()
                            },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 10.dp)) {
                            Text("Auto PiP", color = palette.textPrimary, fontSize = 14.5.sp)
                            Text("Keep playing in a floating mini window when leaving", color = palette.textSecondary, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = prefs.getBoolean("auto_pip", true),
                            onCheckedChange = {
                                prefs.edit().putBoolean("auto_pip", it).apply()
                                localEpoch++
                                onChanged()
                            },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 10.dp)) {
                            Text("Background audio", color = palette.textPrimary, fontSize = 14.5.sp)
                            Text("Keep video sound playing after leaving player", color = palette.textSecondary, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = prefs.getBoolean("background", false),
                            onCheckedChange = {
                                prefs.edit().putBoolean("background", it).apply()
                                localEpoch++
                                onChanged()
                            },
                        )
                    }
                    if (Build.VERSION.SDK_INT >= 30) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                                Text("All files access (.pending downloads)", color = palette.textPrimary, fontSize = 14.5.sp)
                                Text(
                                    if (activity.hasAllFilesAccess()) "Granted — discovers .pending downloads automatically"
                                    else "Allow access so browser .pending downloads appear",
                                    color = palette.textSecondary,
                                    fontSize = 11.5.sp,
                                )
                            }
                            Switch(
                                checked = activity.hasAllFilesAccess(),
                                onCheckedChange = { activity.openAllFilesAccessSettings() },
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 10.dp)) {
                            Text("Per-folder playback memory", color = palette.textPrimary, fontSize = 14.5.sp)
                            Text("Remember audio, subtitle & speed per folder", color = palette.textSecondary, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = Store.folderMemoryEnabled(activity),
                            onCheckedChange = {
                                Store.saveFolderMemoryEnabled(activity, it)
                                localEpoch++
                                onChanged()
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = palette.divider)
                    Text("Double-tap seek step", fontSize = 12.5.sp, color = palette.textSecondary)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        listOf(5, 10, 15, 30).forEach { seconds ->
                            val selectedStep = prefs.getInt("double_tap", 10) == seconds
                            FilterChip(
                                selected = selectedStep,
                                onClick = {
                                    prefs.edit().putInt("double_tap", seconds).apply()
                                    localEpoch++
                                    onChanged()
                                },
                                label = { Text("${seconds}s", color = if (selectedStep) palette.accent else palette.textPrimary) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                    Text("Repeat", fontSize = 12.5.sp, color = palette.textSecondary, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        listOf("off", "one", "all").forEach { mode ->
                            val selectedRepeat = prefs.getString("repeat", "off") == mode
                            FilterChip(
                                selected = selectedRepeat,
                                onClick = {
                                    prefs.edit().putString("repeat", mode).apply()
                                    localEpoch++
                                    onChanged()
                                },
                                label = { Text(mode.replaceFirstChar { it.uppercase() }, color = if (selectedRepeat) palette.accent else palette.textPrimary) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                    Text("Decoder", fontSize = 12.5.sp, color = palette.textSecondary, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        listOf("Auto", "Hardware", "Software").forEach { mode ->
                            val selectedDecoder = prefs.getString("decoder", "Auto") == mode
                            FilterChip(
                                selected = selectedDecoder,
                                onClick = {
                                    prefs.edit().putString("decoder", mode).apply()
                                    localEpoch++
                                    onChanged()
                                },
                                label = { Text(mode, color = if (selectedDecoder) palette.accent else palette.textPrimary) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Done", color = palette.accent, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }
}

private data class LicenseSummaryEntry(
    val name: String,
    val author: String,
    val licenseBadge: String,
    val summary: String,
)

@Composable
private fun OpenSourceLicensesDialog(
    activity: MainActivity,
    onDismiss: () -> Unit,
) {
    val palette = LocalAppPalette.current
    var showFullTexts by remember { mutableStateOf(false) }
    var licenseChoice by remember { mutableStateOf("Notices") }
    val licenseText by produceState("", showFullTexts, licenseChoice) {
        if (showFullTexts) {
            value = withContext(Dispatchers.IO) {
                val file = when (licenseChoice) {
                    "LGPL" -> "LGPL-2.1.txt"
                    "Apache" -> "Apache-2.0.txt"
                    else -> "Third-party-notices.txt"
                }
                activity.assets.open("licenses/$file").bufferedReader().use { it.readText() }
            }
        }
    }
    val entries = remember {
        listOf(
            LicenseSummaryEntry(
                name = "MS PLAYER (App Source)",
                author = "SRIRAM S & ANTIGRAVITY",
                licenseBadge = "MIT",
                summary = "Ad-free offline multimedia player application source code hosted on GitHub.",
            ),
            LicenseSummaryEntry(
                name = "LibVLC Android 3.7.7",
                author = "VideoLAN & contributors",
                licenseBadge = "LGPL-2.1",
                summary = "Core video/audio decoding & playback engine. Unmodified ARM64 binary; source & build recipes are supplied for relinking.",
            ),
            LicenseSummaryEntry(
                name = "AndroidX, Compose & Room",
                author = "Android Open Source Project",
                licenseBadge = "Apache-2.0",
                summary = "Jetpack Compose UI toolkit, Material 3, Media & Room local storage.",
            ),
            LicenseSummaryEntry(
                name = "Kotlin & kotlinx.coroutines",
                author = "JetBrains & contributors",
                licenseBadge = "Apache-2.0",
                summary = "Programming language runtime and asynchronous coroutine library.",
            ),
            LicenseSummaryEntry(
                name = "AndroidLiquidGlass (Backdrop)",
                author = "Kyant0 & contributors",
                licenseBadge = "Apache-2.0",
                summary = "Real-time optical blur, refraction, and frosted Liquid Glass card surfaces.",
            ),
            LicenseSummaryEntry(
                name = "LLVM libc++ Runtime",
                author = "LLVM Project contributors",
                licenseBadge = "Apache-2.0 w/ LLVM",
                summary = "Standard C++ runtime library bundled inside the upstream LibVLC package.",
            ),
            LicenseSummaryEntry(
                name = "Icons & Reference Assets",
                author = "Third-party notices",
                licenseBadge = "Notice",
                summary = "Material UI icons come from AndroidX; the launcher icon reuses your requested reference logo (excluded from MIT).",
            ),
        )
    }

    LiquidGlassAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Info, null, tint = palette.accent, modifier = Modifier.size(20.dp))
                Text("Open-source licenses", fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "MS PLAYER is built with trusted open-source libraries. Here is a quick, readable summary of what each component does:",
                    color = palette.textSecondary,
                    fontSize = 12.5.sp,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.accent.copy(alpha = 0.12f))
                        .border(0.8.dp, palette.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .clickable {
                            runCatching {
                                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MSPLAYER_GITHUB_URL)))
                            }.onFailure {
                                activity.toast("No browser app found.")
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.OpenInBrowser, null, tint = palette.accent, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Source Code on GitHub", color = palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("github.com/AWT-SRIRAM/MSPLAYER", color = palette.textPrimary, fontSize = 11.5.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = palette.accent, modifier = Modifier.size(16.dp))
                }
                entries.forEach { entry ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (palette.isLight) palette.chipBackground.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.06f))
                            .border(0.7.dp, palette.cardBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                entry.name,
                                color = palette.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f).padding(end = 6.dp),
                            )
                            Text(
                                entry.licenseBadge,
                                color = palette.accent,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(palette.accent.copy(alpha = 0.14f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Text(entry.author, color = palette.accentSecondary, fontSize = 11.sp)
                        Text(entry.summary, color = palette.textSecondary, fontSize = 11.5.sp)
                    }
                }
                TextButton(
                    onClick = { showFullTexts = !showFullTexts },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Text(
                        if (showFullTexts) "Hide full legal texts ▴" else "View full legal license texts ▾",
                        color = palette.accent,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (showFullTexts) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Notices" to "Notices", "LGPL" to "LGPL 2.1", "Apache" to "Apache 2.0").forEach { (key, label) ->
                            FilterChip(
                                selected = licenseChoice == key,
                                onClick = { licenseChoice = key },
                                label = { Text(label, fontSize = 11.5.sp) },
                            )
                        }
                    }
                    Text(
                        licenseText.ifBlank { "Loading…" },
                        color = palette.textSecondary,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = palette.accent, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
fun PlayerScreen(activity: MainActivity, service: PlaybackService, state: PlayerState) {
    if (state.item?.audio == true) { MusicPlayerScreen(activity, service, state); return }
    val glassEnabled by rememberGlassEnabled()
    var controls by activity::playerControlsVisible
    var locked by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    var touched by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    fun touch() {
        touched = SystemClock.uptimeMillis()
        controls = true
    }
    var wasPlayingBeforeMenu by remember { mutableStateOf(false) }

    fun openPlayerMenu(name: String) {
        if (menu == null) {
            wasPlayingBeforeMenu = service.state.value.playing
            if (wasPlayingBeforeMenu) {
                service.pause(false)
            }
        }
        menu = name
        touch()
    }

    fun closePlayerMenu() {
        val wasPlaying = wasPlayingBeforeMenu
        menu = null
        wasPlayingBeforeMenu = false
        touch()
        if (wasPlaying) {
            service.play()
        }
    }
    var subtitleStyle by remember { mutableStateOf(SubtitleStyle.load(activity)) }
    var feedback by remember { mutableStateOf<PlayerFeedback?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    val aspectOption by service.aspectMode.collectAsState()
    val sleepMode by service.sleepTimerMode.collectAsState()
    val sleepRemaining by service.sleepTimerRemainingMs.collectAsState()
    val chapters by service.chapters.collectAsState()
    var dragPosition by remember { mutableStateOf<Long?>(null) }
    var attachedLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    val latest by rememberUpdatedState(state)
    LaunchedEffect(state.item?.uri) {
        zoom = 1f
        panX = 0f
        panY = 0f
    }
    LaunchedEffect(aspectOption) {
        zoom = 1f
        panX = 0f
        panY = 0f
    }
    LaunchedEffect(state.item?.uri, state.videoWidth, state.videoHeight, activity.inPip) { activity.videoOrientation(state) }
    LaunchedEffect(state.item?.uri, state.videoWidth, state.videoHeight, state.playing, activity.showPlayer, activity.pipSourceRect) {
        activity.updatePipParams()
    }
    LaunchedEffect(controls, state.subtitleTrack, state.plainSubtitle, subtitleStyle) { service.updateSubtitlePlacement(controls && !activity.inPip) }
    val audioManager = remember { activity.getSystemService(AudioManager::class.java) }
    val scope = rememberCoroutineScope()
    var feedbackJob by remember { mutableStateOf<Job?>(null) }
    val playerDarkScheme = remember {
        darkColorScheme(
            primary = Accent,
            secondary = Cyan,
            background = Color.Black,
            surface = Panel,
            onBackground = Color.White,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF232934),
            onSurfaceVariant = Color(0xFFD5DCE8),
        )
    }
    fun hint(value: PlayerFeedback) {
        feedback = value
        feedbackJob?.cancel()
        feedbackJob =
            scope.launch {
                delay(650L)
                feedback = null
            }
    }
    LaunchedEffect(state.playing) {
        if (!state.playing && !locked) {
            controls = true
        }
    }
    LaunchedEffect(touched, state.playing, menu, locked) {
        if (state.playing && menu == null && !locked) {
            delay(2500)
            controls = false
        }
    }
    BackHandler(!activity.inPip) {
        if (menu != null) { closePlayerMenu() } else if (locked) {
            locked = false
            controls = true
        } else activity.exitPlayer()
    }
    DisposableEffect(service) {
        activity.immersive(true)
        onDispose {
            service.endTemporarySpeed()
            service.detachVideo(attachedLayout)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    MaterialTheme(colorScheme = playerDarkScheme) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context -> VLCVideoLayout(context).also { attachedLayout = it; service.attachVideo(it) } },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = panX
                    translationY = panY
                    clip = false
                }
                .onGloballyPositioned { coords ->
                    val b = coords.boundsInWindow()
                    if (b.width > 0f && b.height > 0f) {
                        activity.pipSourceRect = android.graphics.Rect(
                            b.left.toInt(),
                            b.top.toInt(),
                            b.right.toInt(),
                            b.bottom.toInt(),
                        )
                    }
                },
            update = { service.updateSubtitlePlacement(controls && !activity.inPip) },
            onRelease = { layout -> service.detachVideo(layout) },
        )
        NativeSubtitleOverlay(
            service = service,
            controlsVisible = controls && !activity.inPip,
            modifier = Modifier.fillMaxSize(),
        )
        if (state.item?.audio == true)
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.GraphicEq, null, tint = Accent, modifier = Modifier.size(90.dp))
                Text("Audio playback", color = Muted)
            }
        if (!activity.inPip) {
            var showUnlockPrompt by remember { mutableStateOf(false) }
            var unlockTimerJob by remember { mutableStateOf<Job?>(null) }
            val lockTapSrc = remember { MutableInteractionSource() }
            Box(
                Modifier.fillMaxSize()
                    .then(
                        if (locked) {
                            Modifier.clickable(
                                interactionSource = lockTapSrc,
                                indication = null,
                            ) {
                                showUnlockPrompt = true
                                unlockTimerJob?.cancel()
                                unlockTimerJob = scope.launch {
                                    delay(1800L)
                                    showUnlockPrompt = false
                                }
                            }
                        } else {
                            Modifier.videoGestures(
                                activity, service, locked, menu == null && !activity.inPip,
                                aspectOption.label, zoom, { z ->
                                    zoom = z
                                    if (z <= 1.02f) {
                                        panX = 0f
                                        panY = 0f
                                    }
                                }, {
                                    controls = !controls
                                    touched = SystemClock.uptimeMillis()
                                }, { dragPosition = it }, { value ->
                                    feedbackJob?.cancel()
                                    feedback = value
                                    if (value != null && value !is PlayerFeedback.Speed) {
                                        feedbackJob = scope.launch {
                                            delay(650L)
                                            feedback = null
                                        }
                                    }
                                },
                                onPan = { dx, dy ->
                                    if (zoom > 1.02f) {
                                        panX += dx
                                        panY += dy
                                    }
                                },
                            )
                        }
                    )
            )
            AnimatedVisibility(
                visible = locked && showUnlockPrompt,
                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.85f),
                exit = fadeOut(tween(220)) + scaleOut(tween(220), targetScale = 0.85f),
                modifier = Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(start = 24.dp),
            ) {
                val unlockSrc = remember { MutableInteractionSource() }
                val haptics = LocalHapticFeedback.current
                Row(
                    modifier = Modifier
                        .iosLiquidPress(unlockSrc, scaleDown = 0.90f)
                        .frostedGlassChrome(
                            enabled = glassEnabled,
                            shape = RoundedCornerShape(50),
                            tint = Color(0xDE0B121D),
                            fallbackColor = Color.Black.copy(alpha = 0.75f),
                            borderWidth = 1.dp,
                            fallbackBorderColor = Color.White.copy(alpha = 0.30f),
                        )
                        .clickable(interactionSource = unlockSrc, indication = ripple()) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            locked = false
                            showUnlockPrompt = false
                            touch()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Lock, "Unlock", tint = Cyan, modifier = Modifier.size(20.dp))
                    Text("Tap to Unlock", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            AnimatedVisibility(
                visible = controls && !locked && feedback !is PlayerFeedback.Speed,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(180)),
            ) {
                Column(
                    Modifier.fillMaxSize()
                        .safeDrawingPadding()
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .animateEnterExit(
                                enter = slideInVertically(MotionSpec.PanelOffsetSpring) { -it / 2 },
                                exit = slideOutVertically(tween(160)) { -it / 2 },
                            )
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .85f), Color.Black.copy(alpha = .4f), Color.Transparent)))
                            .padding(bottom = 6.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 10.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { activity.exitPlayer() }) {
                                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                            }
                            val displayTitle = state.item?.title?.substringBeforeLast('.') ?: ""
                            Text(
                                displayTitle,
                                color = Color.White,
                                modifier = Modifier.weight(1f).padding(horizontal = 6.dp).padding(top = 4.dp),
                                fontSize = 17.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(
                                onClick = {
                                    activity.enterPip()
                                }
                            ) {
                                Icon(Icons.Default.PictureInPictureAlt, "Picture-in-picture", tint = Color.White)
                            }
                            IconButton(
                                onClick = {
                                    service.refreshTracks()
                                    openPlayerMenu("Audio")
                                }
                            ) {
                                Icon(Icons.Default.MusicNote, "Audio tracks", tint = Color.White)
                            }
                            IconButton(
                                onClick = {
                                    service.refreshTracks()
                                    openPlayerMenu("Subtitles")
                                }
                            ) {
                                Icon(Icons.Default.Subtitles, "Subtitles", tint = Color.White)
                            }
                            val decoderTag = if (state.decoder == "Software") "SW" else if (state.decoder == "Hardware") "HW" else "HW+"
                            TextButton(
                                onClick = {
                                    openPlayerMenu("Decoder")
                                }
                            ) {
                                Text(
                                    decoderTag,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            IconButton(
                                onClick = {
                                    openPlayerMenu("Options")
                                }
                            ) {
                                Icon(Icons.Default.MoreVert, "Player options", tint = Color.White)
                            }
                        }
                        Row(
                            Modifier.padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val spdSrc = remember { MutableInteractionSource() }
                            Box(
                                Modifier
                                    .iosLiquidPress(spdSrc, scaleDown = 0.92f)
                                    .frostedGlassChrome(
                                        enabled = glassEnabled,
                                        shape = RoundedCornerShape(16.dp),
                                        tint = Color(0x5A0D1828),
                                        fallbackColor = Color(0x9912161E),
                                    )
                                    .clickable(interactionSource = spdSrc, indication = ripple()) {
                                        openPlayerMenu("Speed")
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if ((state.rate * 100f).roundToInt() % 10 == 0)
                                        String.format(java.util.Locale.ROOT, "%.1f×", state.rate)
                                    else
                                        String.format(java.util.Locale.ROOT, "%.2f×", state.rate),
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            val fxSrc = remember { MutableInteractionSource() }
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .iosLiquidPress(fxSrc, scaleDown = 0.88f)
                                    .frostedGlassChrome(
                                        enabled = glassEnabled,
                                        shape = CircleShape,
                                        tint = Accent.copy(alpha = 0.42f),
                                        fallbackColor = Color(0xCC22579A),
                                    )
                                    .clickable(interactionSource = fxSrc, indication = ripple()) {
                                        openPlayerMenu("Audio effects")
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.GraphicEq, "Audio effects", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            if (chapters.isNotEmpty()) {
                                val chSrc = remember { MutableInteractionSource() }
                                Box(
                                    Modifier
                                        .iosLiquidPress(chSrc, scaleDown = 0.92f)
                                        .frostedGlassChrome(
                                            enabled = glassEnabled,
                                            shape = RoundedCornerShape(16.dp),
                                            tint = Color(0x520D1828),
                                            fallbackColor = Color(0x9912161E),
                                        )
                                        .clickable(interactionSource = chSrc, indication = ripple()) {
                                            openPlayerMenu("Chapters")
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("Chapters (${chapters.size})", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (sleepMode != "Off") {
                                val slpSrc = remember { MutableInteractionSource() }
                                Box(
                                    Modifier
                                        .iosLiquidPress(slpSrc, scaleDown = 0.92f)
                                        .frostedGlassChrome(
                                            enabled = glassEnabled,
                                            shape = RoundedCornerShape(16.dp),
                                            tint = GlassTheme.CrystalCobalt.copy(alpha = 0.42f),
                                            fallbackColor = Color(0x9912243A),
                                        )
                                        .clickable(interactionSource = slpSrc, indication = ripple()) {
                                            openPlayerMenu("Sleep")
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.Bedtime, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        Text(formatSleepCountdown(sleepRemaining, sleepMode), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Column(
                        Modifier.fillMaxWidth()
                            .animateEnterExit(
                                enter = slideInVertically(MotionSpec.PanelOffsetSpring) { it / 2 },
                                exit = slideOutVertically(tween(160)) { it / 2 },
                            )
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .5f), Color.Black.copy(alpha = .9f))))
                            .padding(top = 8.dp)
                    ) {
                        PlayerTimeline(
                            service = service,
                            dragPosition = dragPosition,
                            onDrag = { dragPosition = it },
                            onTouch = { touch() },
                            onOpenChapters = { openPlayerMenu("Chapters") },
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val lockSrc = remember { MutableInteractionSource() }
                            val prevSrc = remember { MutableInteractionSource() }
                            val playSrc = remember { MutableInteractionSource() }
                            val nextSrc = remember { MutableInteractionSource() }
                            val aspectSrc = remember { MutableInteractionSource() }
                            val rotSrc = remember { MutableInteractionSource() }
                            IconButton(
                                onClick = {
                                    locked = true
                                    controls = false
                                },
                                interactionSource = lockSrc,
                                modifier = Modifier.iosLiquidPress(lockSrc, scaleDown = 0.88f),
                            ) {
                                Icon(Icons.Default.LockOpen, "Lock controls", tint = Color.White)
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    service.previous()
                                    touch()
                                },
                                interactionSource = prevSrc,
                                modifier = Modifier.iosLiquidPress(prevSrc, scaleDown = 0.86f),
                            ) {
                                Icon(
                                    Icons.Default.SkipPrevious,
                                    "Previous",
                                    tint = Color.White,
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                            IconButton(
                                onClick = {
                                    service.toggle()
                                    touch()
                                },
                                interactionSource = playSrc,
                                modifier = Modifier
                                    .padding(horizontal = 14.dp)
                                    .size(60.dp)
                                    .iosLiquidPress(playSrc, scaleDown = 0.86f),
                            ) {
                                Icon(
                                    if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    if (state.playing) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(52.dp),
                                )
                            }
                            IconButton(
                                onClick = {
                                    service.next()
                                    touch()
                                },
                                interactionSource = nextSrc,
                                modifier = Modifier.iosLiquidPress(nextSrc, scaleDown = 0.86f),
                            ) {
                                Icon(Icons.Default.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(34.dp))
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    val nextAspect = aspectOption.next()
                                    zoom = 1f
                                    service.setAspectMode(nextAspect)
                                    touch()
                                    hint(PlayerFeedback.AspectRatio(nextAspect.label))
                                },
                                interactionSource = aspectSrc,
                                modifier = Modifier.iosLiquidPress(aspectSrc, scaleDown = 0.88f),
                            ) {
                                Icon(Icons.Default.AspectRatio, "Aspect ratio", tint = Color.White)
                            }
                            IconButton(
                                onClick = { activity.rotateVideo() },
                                interactionSource = rotSrc,
                                modifier = Modifier.iosLiquidPress(rotSrc, scaleDown = 0.88f),
                            ) {
                                Icon(Icons.Default.ScreenRotation, "Rotate screen", tint = Color.White)
                            }
                        }
                    }
                }
            }
            PlayerFeedbackHost(feedback)
        }
        if (state.buffering)
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
        state.error?.let { error ->
            val needsAllFiles by service.requiresAllFilesPermission.collectAsState()
            val isStorageIssue = needsAllFiles ||
                error.contains("pending", ignoreCase = true) ||
                error.contains("Only owner", ignoreCase = true) ||
                error.contains("All files access", ignoreCase = true)
            Column(
                Modifier.align(Alignment.Center)
                    .padding(24.dp)
                    .frostedGlassChrome(glassEnabled, RoundedCornerShape(18.dp), tint = Color(0x50101826), fallbackColor = Panel)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (isStorageIssue) {
                    Icon(Icons.Default.FolderSpecial, null, tint = Cyan, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("All Files Access Required", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Android requires 'All files access' permission to play in-progress downloads (.pending) and external files from Telegram or Chrome.",
                        color = Color(0xFFD0D7E2),
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { activity.openAllFilesAccessSettings() },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.LockOpen, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Grant All Files Access", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                } else {
                    Text(error, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { service.decoder("Software") }) {
                        Text("Use SW decoder", color = Accent)
                    }
                }
                TextButton(onClick = { activity.exitPlayer() }) { Text("Back", color = Color.LightGray) }
            }
        }
    }
    if (menu in listOf("Audio", "Subtitles", "Audio effects", "Sleep") && !activity.inPip) {
        val sideTitle = when (menu) {
            "Audio" -> "Audio Track"
            "Subtitles" -> "Subtitle"
            "Sleep" -> "Sleep Timer"
            else -> "Audio"
        }
        PlayerSidePanel(sideTitle, { closePlayerMenu() }) {
            var expanded by remember(menu) { mutableStateOf(false) }
            when (menu) {
                "Audio" -> {
                    state.audioTracks.filter { it.first >= 0 }.forEach { (id, name) ->
                        TrackChoice(name, id == state.audioTrack) { service.audioTrack(id, name) }
                    }
                    TrackChoice("Disable", state.audioTrack < 0) { service.audioTrack(-1, "Disable") }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val nextDecoder = if (state.decoder == "Software") "Hardware" else "Software"
                                service.decoder(nextDecoder)
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = state.decoder == "Software",
                            onCheckedChange = {
                                val nextDecoder = if (it) "Software" else "Hardware"
                                service.decoder(nextDecoder)
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF2196F3),
                                uncheckedColor = Color.White.copy(alpha = 0.6f)
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Use SW audio decoder", color = Color.White, fontSize = 15.sp)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Muted.copy(alpha = .3f))
                    DelayControls("Synchronization", state.audioDelay) { service.audioDelay(it) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Muted.copy(alpha = .3f))
                    TextButton(onClick = { openPlayerMenu("Audio effects") }) {
                        Icon(Icons.Default.GraphicEq, null)
                        Spacer(Modifier.width(12.dp))
                        Text("Equalizer & Effects")
                    }
                }
                "Subtitles" -> {
                    val context = LocalContext.current
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                            onClick = { activity.subtitleFile.launch(arrayOf("*/*")); closePlayerMenu() }
                        ) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(10.dp))
                            Text("Open (.srt / .zip)")
                        }
                    }
                    val validSubs = state.subtitles.filter { it.first >= 0 }
                    val favLangs = remember(menu) { Store.favoriteSubtitleLanguages(context) }
                    val sortedSubs = remember(validSubs, favLangs) {
                        validSubs.withIndex().sortedWith(
                            compareBy<IndexedValue<Pair<Int, String>>> { iv ->
                                val idx = favLangs.indexOfFirst { code ->
                                    SubtitleTranslator.matchesLanguage(iv.value.second, code)
                                }
                                if (idx >= 0) idx else favLangs.size
                            }.thenBy { iv -> iv.index }
                        ).map { it.value }
                    }
                    if (sortedSubs.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .frostedGlassChrome(
                                    enabled = glassEnabled,
                                    shape = RoundedCornerShape(12.dp),
                                    tint = Color(0x400A121D),
                                    fallbackColor = Color.White.copy(alpha = 0.04f),
                                )
                                .padding(vertical = 4.dp, horizontal = 4.dp)
                        ) {
                            Column {
                                sortedSubs.forEach { (id, name) ->
                                    val isSelected = id in state.selectedSubtitleTracks
                                    val isPrimary = state.selectedSubtitleTracks.firstOrNull() == id
                                    val isSecondary = state.selectedSubtitleTracks.drop(1).firstOrNull() == id
                                    val label = when {
                                        state.selectedSubtitleTracks.size > 1 && isPrimary -> "$name (Primary)"
                                        state.selectedSubtitleTracks.size > 1 && isSecondary -> "$name (Secondary)"
                                        else -> name
                                    }
                                    TrackChoice(label, isSelected, subtitle = true) {
                                        service.toggleSubtitleTrack(id, name)
                                    }
                                }
                            }
                        }
                    } else {
                        Text("No subtitle tracks", color = Muted, modifier = Modifier.padding(vertical = 6.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        colors = ButtonDefaults.textButtonColors(contentColor = Cyan),
                        onClick = { openPlayerMenu("OnlineSubtitles") }
                    ) {
                        Icon(Icons.Default.CloudDownload, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Online Subtitles")
                    }
                    val trSrc = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .frostedGlassChrome(
                                enabled = glassEnabled,
                                shape = RoundedCornerShape(12.dp),
                                tint = Color(0x5212243A),
                                fallbackColor = Color(0xFF1A222E),
                            )
                            .iosLiquidPress(trSrc, scaleDown = 0.96f)
                            .clickable(interactionSource = trSrc, indication = ripple()) {
                                openPlayerMenu("TranslateSubtitles")
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Default.Translate, null, tint = Cyan, modifier = Modifier.size(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Translate Subtitles", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                            Text("Full-length · ${SubtitleTranslator.supportedLanguages.size} languages", color = Cyan.copy(alpha = 0.85f), fontSize = 11.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Cyan, modifier = Modifier.size(18.dp))
                    }
                    TextButton(
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF64B5F6)),
                        onClick = { openPlayerMenu("AutoSub") }
                    ) {
                        Icon(Icons.Default.AutoAwesome, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Auto-Generate (AI)")
                    }
                    TextButton(
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        onClick = { expanded = !expanded }
                    ) {
                        Icon(Icons.Default.Settings, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Settings ${if (expanded) "▴" else "▾"}")
                    }
                    if (expanded) {
                        DelayControls("Delay", state.subtitleDelay) { service.subtitleDelay(it) }
                        TextButton(
                            onClick = {
                                val syncedMs = service.syncSubtitleToHeardLineNow()
                                if (syncedMs != null) {
                                    activity.toast(String.format(java.util.Locale.ROOT, "Synced %+.1fs", syncedMs / 1000.0))
                                } else {
                                    activity.toast("Load text subtitles first")
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = Cyan),
                        ) {
                            Icon(Icons.Default.Sync, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Instant Sync (Heard Line Now)")
                        }
                        TextButton(onClick = { openPlayerMenu("Subtitle appearance") }) { Text("Appearance") }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val updated = subtitleStyle.copy(secondaryTop = !subtitleStyle.secondaryTop)
                                    subtitleStyle = updated
                                    service.subtitleStyle(updated)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = subtitleStyle.secondaryTop,
                                onCheckedChange = { checked ->
                                    val updated = subtitleStyle.copy(secondaryTop = checked)
                                    subtitleStyle = updated
                                    service.subtitleStyle(updated)
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF2196F3),
                                    uncheckedColor = Color.White.copy(alpha = 0.6f),
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Top dock (2nd sub)", color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
                "Audio effects" -> AudioEffectsControls(service)
                "Sleep" -> SleepTimerPanelContent(service = service, onDismiss = { closePlayerMenu() })
            }
        }
    } else if (menu == "Subtitle appearance" && !activity.inPip) {
        SubtitleTextDialog(subtitleStyle, cancel = { closePlayerMenu() }, apply = { value ->
            service.subtitleStyle(value)
            subtitleStyle = value
            closePlayerMenu()
        })
    } else if (menu == "AutoSub" && !activity.inPip) {
        AutoSubDialog(
            service = service,
            activity = activity,
            onOpenTranslator = { openPlayerMenu("TranslateSubtitles") },
            onDismiss = { closePlayerMenu() }
        )
    } else if (menu == "TranslateSubtitles" && !activity.inPip) {
        TranslateSubtitlesDialog(
            service = service,
            activity = activity,
            onDismiss = { closePlayerMenu() }
        )
    } else if (menu == "OnlineSubtitles" && !activity.inPip) {
        OnlineSubtitlesDialog(
            service = service,
            activity = activity,
            onDismiss = { closePlayerMenu() }
        )
    } else if (menu == "Chapters" && !activity.inPip) {
        ChaptersDialog(
            chapters = chapters,
            currentPos = state.position,
            onSelect = { idx ->
                service.seekToChapter(idx)
                closePlayerMenu()
            },
            onDismiss = { closePlayerMenu() }
        )
    } else if (menu == "Decoder" && !activity.inPip) {
        SelectDecoderDialog(
            current = state.decoder,
            onSelect = { mode ->
                service.decoder(mode)
                closePlayerMenu()
            },
            onDismiss = { closePlayerMenu() }
        )
    } else if (menu == "Speed" && !activity.inPip) {
        PlayerSpeedBar(
            rate = state.rate,
            onRateChange = { service.rate(it) },
            onDismiss = { closePlayerMenu() },
        )
    } else if (menu == "Information" && !activity.inPip) {
        PlayerSidePanel("Information", { closePlayerMenu() }) {
            val info = remember(state.item?.uri, state.duration, state.audioTracks.size, state.subtitles.size) {
                service.currentMediaDetailedInfo()
            }
            if (info != null) {
                MediaInformationContent(info = info, inPlayer = true)
            } else {
                Text("Media information unavailable", color = Muted, fontSize = 13.sp)
            }
        }
    } else if (menu != null && !activity.inPip)
        PlayerMenuDialog(
            onDismissRequest = {
                closePlayerMenu()
            },
            title = { Text(menu!!, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    when (menu) {
                        "Options" -> {
                            LiquidGlassPopupMenuItem(
                                text = { Text("Information") },
                                leadingIcon = { Icon(Icons.Default.Info, null, tint = Cyan, modifier = Modifier.size(18.dp)) },
                                onClick = { openPlayerMenu("Information") },
                            )
                            LiquidGlassPopupMenuItem(
                                text = { Text("Online Subtitles") },
                                leadingIcon = { Icon(Icons.Default.CloudDownload, null, tint = Cyan, modifier = Modifier.size(18.dp)) },
                                onClick = { openPlayerMenu("OnlineSubtitles") },
                            )
                            LiquidGlassPopupMenuItem(
                                text = { Text("Load external subtitle") },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                                onClick = { closePlayerMenu(); activity.subtitleFile.launch(arrayOf("*/*")) },
                            )
                            LiquidGlassPopupMenuItem(
                                text = { Text("Translate Subtitles (Full-Length)") },
                                leadingIcon = { Icon(Icons.Default.Translate, null, tint = Cyan, modifier = Modifier.size(18.dp)) },
                                onClick = { openPlayerMenu("TranslateSubtitles") },
                            )
                            if (chapters.isNotEmpty()) {
                                LiquidGlassPopupMenuItem(
                                    text = { Text("Chapters (${chapters.size})") },
                                    leadingIcon = { Icon(Icons.Default.Menu, null, tint = Cyan, modifier = Modifier.size(18.dp)) },
                                    onClick = { openPlayerMenu("Chapters") },
                                )
                            }
                            LiquidGlassPopupMenuItem(
                                text = {
                                    Text(
                                        if (sleepMode == "Off") "Sleep Timer"
                                        else "Sleep Timer (${formatSleepCountdown(sleepRemaining, sleepMode)})"
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Bedtime, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                                onClick = { openPlayerMenu("Sleep") },
                            )
                            LiquidGlassPopupMenuItem(
                                text = { Text("Sync Subtitles (Heard Line)") },
                                leadingIcon = { Icon(Icons.Default.Sync, null, tint = Cyan, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    val syncedMs = service.syncSubtitleToHeardLineNow()
                                    closePlayerMenu()
                                    if (syncedMs != null) {
                                        activity.toast(String.format(java.util.Locale.ROOT, "Synced %+.1fs", syncedMs / 1000.0))
                                    } else {
                                        activity.toast("Load text subtitles first")
                                    }
                                },
                            )
                            LiquidGlassPopupMenuItem(
                                text = {
                                    Text(
                                        if (Store.prefs(activity).getBoolean("diagnostics", false)) "Disable diagnostics"
                                        else "Enable diagnostics"
                                    )
                                },
                                onClick = {
                                    closePlayerMenu()
                                    service.diagnosticsEnabled(!Store.prefs(activity).getBoolean("diagnostics", false))
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { closePlayerMenu() }) { Text("Close", color = Accent) } },
        )
        }
    }
}

@Composable
fun DelayControls(label: String, value: Long, onChange: (Long) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 15.sp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(Color.White.copy(alpha = 0.12f), CircleShape)
                    .clickable { onChange((value - 100).coerceAtLeast(-10000)) },
                contentAlignment = Alignment.Center
            ) {
                Text("−", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier = Modifier
                    .widthIn(min = 64.dp)
                    .height(34.dp)
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                    .clickable { onChange(0) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                val secStr = String.format(java.util.Locale.ROOT, "%.2fs", value / 1000.0)
                Text(secStr, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(Color.White.copy(alpha = 0.12f), CircleShape)
                    .clickable { onChange((value + 100).coerceAtMost(10000)) },
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PlayerTimeline(
    service: PlaybackService,
    dragPosition: Long?,
    onDrag: (Long?) -> Unit,
    onTouch: () -> Unit,
    onOpenChapters: (() -> Unit)? = null,
) {
    val state by service.timeline.collectAsState()
    val chapters by service.chapters.collectAsState()
    val glassEnabled by rememberGlassEnabled()
    // Own the commit value: a tap can finish before the caller recomposes.
    var sliderTarget by remember(service) { mutableStateOf<Long?>(null) }
    val moves = remember { intArrayOf(0) } // value changes in the current gesture
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var dragDistancePx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.seekable) { if (!state.seekable) { sliderTarget = null; onDrag(null) } }
    val seekPos = sliderTarget ?: dragPosition
    var previewBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(seekPos) {
        if (seekPos != null) {
            delay(50)
            val bmp = withContext(Dispatchers.IO) { service.seekThumbnail(seekPos) }
            previewBitmap = bmp
        } else {
            previewBitmap = null
        }
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = seekPos != null && previewBitmap != null,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(150)),
        ) {
            previewBitmap?.let { bitmap ->
                val previewChapter = seekPos?.let { pos -> chapters.lastOrNull { it.startMs <= pos } }
                Card(
                    modifier = Modifier.padding(bottom = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = .85f)),
                    border = BorderStroke(1.dp, Color(0x6600A8FF)),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(4.dp),
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Seek preview",
                            modifier = Modifier
                                .size(width = 128.dp, height = 72.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Text(
                            durationText(seekPos ?: 0),
                            fontSize = 11.sp,
                            color = Accent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        if (previewChapter != null) {
                            Text(
                                previewChapter.title,
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 128.dp).padding(top = 1.dp),
                            )
                        }
                    }
                }
            }
        }
        if (chapters.isNotEmpty()) {
            val currentPos = sliderTarget ?: dragPosition ?: state.position
            val activeIdx = chapters.indexOfLast { it.startMs <= currentPos }.coerceAtLeast(0)
            val activeChapter = chapters[activeIdx]
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .frostedGlassChrome(
                        enabled = glassEnabled,
                        shape = RoundedCornerShape(14.dp),
                        tint = Color(0x520B131F),
                        fallbackColor = Color(0x8810141C),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = { service.previousChapter(); onTouch() },
                    modifier = Modifier.size(26.dp),
                ) {
                    Icon(Icons.Default.ChevronLeft, "Previous chapter", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Text(
                    "${activeIdx + 1}/${chapters.size} · ${activeChapter.title}",
                    color = Color.White,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable(enabled = onOpenChapters != null) { onOpenChapters?.invoke() }
                        .padding(horizontal = 4.dp),
                )
                IconButton(
                    onClick = { service.nextChapter(); onTouch() },
                    modifier = Modifier.size(26.dp),
                ) {
                    Icon(Icons.Default.ChevronRight, "Next chapter", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).onGloballyPositioned { service.subtitleControlsTop(it.positionInWindow().y) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isScrubbing = sliderTarget != null || dragPosition != null
            val thumbSize by animateDpAsState(
                targetValue = if (isScrubbing) 24.dp else 18.dp,
                animationSpec = MotionSpec.DpSpring,
                label = "timelineThumbSize",
            )
            Text(durationText(sliderTarget ?: dragPosition ?: state.position), color = Color.White, fontSize = 14.sp)
            Slider(
                thumb = {
                    Box(
                        Modifier
                            .size(thumbSize)
                            .background(
                                if (isScrubbing && state.seekable) Accent.copy(alpha = 0.28f) else Color.Transparent,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(if (isScrubbing) 18.dp else thumbSize)
                                .background(
                                    if (state.seekable) Accent else Muted,
                                    CircleShape,
                                )
                        )
                    }
                },
                track = { slider ->
                    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                        val y = size.height / 2
                        drawLine(
                            Color.Gray,
                            Offset(0f, y),
                            Offset(size.width, y),
                            strokeWidth = 3.dp.toPx(),
                        )
                        val span = (slider.valueRange.endInclusive - slider.valueRange.start).coerceAtLeast(1f)
                        val fraction = ((slider.value - slider.valueRange.start) / span).coerceIn(0f, 1f)
                        drawLine(
                            Accent,
                            Offset(0f, y),
                            Offset(size.width * fraction, y),
                            strokeWidth = 3.dp.toPx(),
                        )
                        chapters.forEach { ch ->
                            if (ch.startMs > 0L) {
                                val chFrac = (ch.startMs.toFloat() / span).coerceIn(0f, 1f)
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.88f),
                                    radius = 2.4.dp.toPx(),
                                    center = Offset(size.width * chFrac, y),
                                )
                            }
                        }
                    }
                },
                value =
                    (sliderTarget ?: dragPosition ?: state.position)
                        .toFloat()
                        .coerceIn(0f, state.duration.coerceAtLeast(1).toFloat()),
                onValueChange = {
                    moves[0]++
                    sliderTarget = it.toLong()
                    onDrag(it.toLong())
                    onTouch()
                },
                onValueChangeFinished = {
                    val isTap = dragDistancePx <= touchSlop * 1.25f || moves[0] <= 1
                    moves[0] = 0
                    dragDistancePx = 0f
                    // A tap lands exactly where touched; dragging keeps the quick keyframe seek.
                    sliderTarget?.let { if (isTap) service.seek(it, false) else service.seek(it) }
                    sliderTarget = null
                    onDrag(null)
                },
                valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(),
                enabled = state.seekable,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .pointerInput(state.seekable, touchSlop) {
                        if (!state.seekable) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            dragDistancePx = 0f
                            var prev = down.position
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                dragDistancePx += (ch.position - prev).getDistance()
                                prev = ch.position
                            }
                        }
                    }
                    .semantics { contentDescription = "Playback timeline" },
                colors =
                    SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = Color.Gray,
                    ),
            )
            Text(durationText(state.duration), color = Color.White, fontSize = 14.sp)
        }
    }

}

@Composable
fun NativeSubtitleOverlay(
    service: PlaybackService,
    controlsVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val subtitleText by service.activeSubtitleText.collectAsState()
    val dualLines by service.dualSubtitleLines.collectAsState()
    val playerState by service.state.collectAsState()
    val controlsTopPx by service.subtitleControlsTopPx.collectAsState()
    val revision by service.subtitleStyleRevision.collectAsState()
    val context = LocalContext.current
    val style = remember(service, revision) { SubtitleStyle.load(context) }
    var totalDragX by remember { mutableFloatStateOf(0f) }
    val hasActiveSub = !subtitleText.isNullOrBlank() || !dualLines.primary.isNullOrBlank() || !dualLines.secondary.isNullOrBlank()

    Box(
        modifier = modifier
            .then(
                if (controlsVisible && hasActiveSub) {
                    Modifier.pointerInput(style) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (zoom != 1f) {
                                val newScale = (style.scale * zoom).toInt().coerceIn(50, 300)
                                if (newScale != style.scale) {
                                    val updated = style.copy(scale = newScale)
                                    updated.save(context)
                                    service.subtitleStyle(updated)
                                }
                            } else {
                                totalDragX += pan.x
                                if (kotlin.math.abs(totalDragX) > 80f) {
                                    service.seekCue(totalDragX > 0)
                                    totalDragX = 0f
                                }
                            }
                        }
                    }
                } else Modifier
            )
    ) {
        AndroidView(
            factory = { ctx ->
                StrokeView(ctx).apply {
                    applyStyle(
                        style = style,
                        isControlsVisible = controlsVisible,
                        dockTop = false,
                        liftAbovePrimarySpu = playerState.hasPrimaryHwSubtitle,
                        controlsBottomReservedPx = controlsTopPx,
                    )
                    setDualSubtitleText(dualLines.primary, dualLines.secondary)
                }
            },
            update = { view ->
                view.applyStyle(
                    style = style,
                    isControlsVisible = controlsVisible,
                    dockTop = false,
                    liftAbovePrimarySpu = playerState.hasPrimaryHwSubtitle,
                    controlsBottomReservedPx = controlsTopPx,
                )
                view.setDualSubtitleText(dualLines.primary, dualLines.secondary)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun OnlineSubtitlesDialog(
    service: PlaybackService,
    activity: MainActivity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val glassEnabled by rememberGlassEnabled()
    val playerState by service.state.collectAsState()
    val currentItem = playerState.item
    val isStream = currentItem?.uri?.let { PlaybackRules.isStreamUrl(it) } == true
    val initialHints = remember(currentItem?.uri) {
        OnlineSubtitlesClient.parseMediaHints(currentItem?.title ?: "")
    }
    var query by remember(currentItem?.uri) {
        val parts = listOfNotNull(
            initialHints.title.takeIf { it.isNotBlank() },
            initialHints.season?.let { s ->
                initialHints.episode?.let { e -> String.format(java.util.Locale.ROOT, "S%02dE%02d", s, e) }
            } ?: initialHints.episode?.let { e -> String.format(java.util.Locale.ROOT, "E%02d", e) },
            initialHints.year?.takeIf { initialHints.season == null }?.toString(),
        )
        mutableStateOf(parts.joinToString(" ").ifBlank { OpenSubtitlesClient.cleanMediaQuery(currentItem?.title ?: "") })
    }
    val initialLangFilter = remember(currentItem?.uri) {
        val favCode = Store.favoriteSubtitleLanguages(context).firstOrNull()
            ?: SubtitleTranslator.defaultOwnLanguage().code
        OpenSubtitlesClient.filterForLanguage(favCode)
    }
    var selectedLang by remember(currentItem?.uri) { mutableStateOf(initialLangFilter) }
    var showLangPicker by remember { mutableStateOf(false) }
    var favEpoch by remember { mutableIntStateOf(0) }
    var selectedProvider by remember { mutableStateOf<OnlineSubtitlesClient.OnlineProvider?>(null) }
    var movieHash by remember(currentItem?.uri) { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<OnlineSubtitlesClient.SearchSummary?>(null) }
    var searching by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val hasAnySubKey = remember {
        OpenSubtitlesClient.activeApiKey(context).isNotBlank() ||
            OnlineSubtitlesClient.activeSubDlKey(context).isNotBlank() ||
            OnlineSubtitlesClient.activeSubSourceKey(context).isNotBlank() ||
            OnlineSubtitlesClient.activeTmdbKey(context).isNotBlank() ||
            OnlineSubtitlesClient.activeWyzieKey(context).isNotBlank()
    }
    val scope = rememberCoroutineScope()

    fun runSearch(
        customHash: String? = movieHash,
        lang: OpenSubtitlesClient.LanguageFilter = selectedLang,
    ) {
        if (searching) return
        if (query.isBlank() && customHash.isNullOrBlank()) {
            errorMsg = "Enter title"
            return
        }
        searching = true
        errorMsg = null
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    OnlineSubtitlesClient.searchAll(
                        context = context,
                        rawTitleOrQuery = query,
                        languageCode2 = lang.code2,
                        movieHash = customHash,
                    )
                }
            }
            searching = false
            res.fold(
                { result ->
                    summary = result
                    if (result.candidates.isEmpty() && result.providerErrors.isNotEmpty()) {
                        errorMsg = result.providerErrors.entries.joinToString(" · ") { "${it.key.badge}: ${it.value}" }
                    }
                },
                { err ->
                    errorMsg = err.message ?: "Search failed"
                },
            )
        }
    }

    LaunchedEffect(currentItem?.uri) {
        if (currentItem != null) {
            val hash = if (!isStream) {
                withContext(Dispatchers.IO) {
                    service.currentLocalMediaFile()?.let { OpenSubtitlesClient.computeMovieHash(it) }
                        ?: OpenSubtitlesClient.computeMovieHash(context, Uri.parse(currentItem.uri))
                }
            } else null
            movieHash = hash
            if (hasAnySubKey && summary == null) {
                runSearch(hash, selectedLang)
            }
        }
    }

    val allCandidates = summary?.candidates.orEmpty()
    val visibleCandidates = remember(allCandidates, selectedProvider) {
        if (selectedProvider == null) allCandidates
        else allCandidates.filter { it.provider == selectedProvider }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ImmersivePlayerDialog()
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            val maxH = (maxHeight - 16.dp).coerceAtLeast(250.dp)
            Column(
                Modifier
                    .widthIn(min = 320.dp, max = 580.dp)
                    .heightIn(max = maxH)
                    .fillMaxWidth()
                    .frostedGlassChrome(
                        enabled = glassEnabled,
                        shape = RoundedCornerShape(if (glassEnabled) 22.dp else 12.dp),
                        tint = Color(0xCC0B121D),
                        fallbackColor = Color(0xFF1B1F26),
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.CloudDownload, null, tint = Cyan)
                    Text(
                        "Online Subtitles",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Fixed Search Bar
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Title / SxxExx", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        placeholder = { Text("Movie or Series S01E01") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, "Clear query")
                                }
                            }
                        },
                    )
                    val searchSrc = remember { MutableInteractionSource() }
                    Button(
                        onClick = { runSearch() },
                        enabled = !searching,
                        interactionSource = searchSrc,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .heightIn(min = 48.dp)
                            .iosLiquidPress(searchSrc, enabled = !searching, scaleDown = 0.93f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text("Search", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                }

                // Compact Single-Row Filter Strip (Language + Providers)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { showLangPicker = true },
                        label = { Text("🌐 ${selectedLang.label} ▾", fontSize = 11.5.sp, color = Cyan) },
                    )
                    FilterChip(
                        selected = selectedProvider == null,
                        onClick = { selectedProvider = null },
                        label = { Text("All (${allCandidates.size})", fontSize = 11.5.sp) },
                    )
                    OnlineSubtitlesClient.OnlineProvider.entries.forEach { prov ->
                        val count = allCandidates.count { it.provider == prov }
                        FilterChip(
                            selected = selectedProvider == prov,
                            onClick = { selectedProvider = if (selectedProvider == prov) null else prov },
                            label = { Text("${prov.badge} ($count)", fontSize = 11.5.sp) },
                        )
                    }
                }

                // Dedicated Scrollable Candidate List
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    errorMsg?.let { err ->
                        Text(err, color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }

                    if (searching) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
                            Text("Searching subtitle providers...", color = Color.White, fontSize = 13.sp)
                        }
                    }

                    if (!searching && visibleCandidates.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .border(0.8.dp, Cyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.OpenInBrowser, null, tint = Cyan, modifier = Modifier.size(20.dp))
                                    Text("Open External Subtitles", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                }
                                Text("Online search returned no files or requires API keys. Open pre-filled web search or community repositories directly.", color = Muted, fontSize = 11.5.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            val url = OpenSubtitlesClient.browserSearchUrl(query, selectedLang.code3)
                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Cyan.copy(alpha = 0.22f), contentColor = Cyan),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("OpenSubtitles Web", fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                                    }
                                    Button(
                                        onClick = {
                                            val q = java.net.URLEncoder.encode("$query subtitles", "UTF-8")
                                            val url = "https://github.com/search?q=$q&type=repositories"
                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.12f), contentColor = Color.White),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("GitHub Search", fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }

                    visibleCandidates.forEach { cand ->
                        val itemSrc = remember(cand.id) { MutableInteractionSource() }
                        val isBusy = downloadingId == cand.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(
                                    0.7.dp,
                                    Color.White.copy(alpha = if (cand.hashMatch) 0.45f else 0.14f),
                                    RoundedCornerShape(10.dp),
                                )
                                .iosLiquidPress(itemSrc, enabled = downloadingId == null, scaleDown = 0.98f)
                                .clickable(
                                    interactionSource = itemSrc,
                                    indication = ripple(),
                                    enabled = downloadingId == null,
                                ) {
                                    downloadingId = cand.id
                                    errorMsg = null
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            runCatching {
                                                OnlineSubtitlesClient.downloadCandidate(
                                                    context = context,
                                                    candidate = cand,
                                                    videoTitle = currentItem?.title ?: query,
                                                    videoFile = service.currentLocalMediaFile(),
                                                )
                                            }
                                        }
                                        downloadingId = null
                                        res.fold(
                                            { dl ->
                                                val itemUri = currentItem?.uri
                                                var loaded = false
                                                dl.allExtracted.take(5).forEachIndexed { idx, file ->
                                                    if (service.addSubtitle(file.absolutePath, selectExtra = idx == 0)) {
                                                        loaded = true
                                                        if (itemUri != null) {
                                                            service.rememberImportedSubtitle(itemUri, file.absolutePath)
                                                        }
                                                    }
                                                }
                                                if (loaded) {
                                                    activity.toast("Loaded ${dl.file.name}")
                                                    onDismiss()
                                                } else {
                                                    errorMsg = "Could not load ${dl.file.name}"
                                                }
                                            },
                                            { err ->
                                                errorMsg = err.message ?: "Download failed"
                                            },
                                        )
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    cand.release,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Badge(cand.provider.badge, color = Color(0xFF283546))
                                    if (cand.language.isNotBlank()) Badge(cand.language, color = Color(0xFF1976D2))
                                    if (cand.hearingImpaired) Badge("HI")
                                    if (cand.downloadCount > 0) Text("${cand.downloadCount} dl", color = Muted, fontSize = 11.sp)
                                }
                            }
                            if (isBusy) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
                            } else {
                                Icon(Icons.Default.Download, "Download & load", tint = Cyan, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            val url = OpenSubtitlesClient.browserSearchUrl(query, selectedLang.code3)
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }.onFailure {
                                activity.toast("No browser app found")
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Browser Search", color = Cyan, fontSize = 12.5.sp, maxLines = 1, softWrap = false)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = Color.LightGray, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
    if (showLangPicker) {
        LanguagePickerDialog(
            title = "Online Subtitle Language",
            selectedCodes = setOf(selectedLang.code2.ifBlank { "all" }),
            multiSelect = false,
            includeAll = true,
            favoriteCodes = Store.favoriteSubtitleLanguages(context).toSet(),
            onToggleFavorite = { spec ->
                Store.toggleFavoriteSubtitleLanguage(context, spec.code)
                favEpoch++
            },
            onSelect = { spec ->
                val filter = OpenSubtitlesClient.filterForLanguage(spec.code)
                selectedLang = filter
                showLangPicker = false
                runSearch(lang = filter)
            },
            onSelectSpecial = { special ->
                if (special == "all") {
                    val filter = OpenSubtitlesClient.filterForLanguage("all")
                    selectedLang = filter
                    showLangPicker = false
                    runSearch(lang = filter)
                }
            },
            onDismiss = { showLangPicker = false },
        )
    }
}

@Composable
fun AutoSubDialog(
    service: PlaybackService,
    activity: MainActivity,
    onOpenTranslator: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val glassEnabled by rememberGlassEnabled()
    val subStatus by service.autoSubStatus.collectAsState()
    val defaultOwn = remember {
        val fav = Store.favoriteSubtitleLanguages(context).firstOrNull()
        SubtitleTranslator.findLanguage(fav) ?: SubtitleTranslator.defaultOwnLanguage()
    }
    var mode by rememberSaveable { mutableStateOf("original") } // "original", "en", "translate_own"
    var spokenHintCode by rememberSaveable { mutableStateOf<String?>(null) }
    var ownTargetCode by rememberSaveable { mutableStateOf(defaultOwn.code) }
    var pickLangMode by remember { mutableStateOf<String?>(null) } // "spoken", "target", "translate_now"
    var favEpoch by remember { mutableIntStateOf(0) }
    val ownTargetSpec = remember(ownTargetCode) {
        SubtitleTranslator.findLanguage(ownTargetCode) ?: SubtitleTranslator.defaultOwnLanguage()
    }
    val spokenHintSpec = remember(spokenHintCode) {
        spokenHintCode?.let { SubtitleTranslator.findLanguage(it) }
    }
    val primaryActive = remember { SpeechToTextClient.activeGroqKey(context).isNotBlank() }
    val backupActive = remember { SpeechToTextClient.activeDeepgramKey(context).isNotBlank() }
    val ready = primaryActive || backupActive
    val isProcessing = subStatus is AutoSubStatus.Processing
    val progressPercent = (subStatus as? AutoSubStatus.Processing)?.percent ?: 0
    val progressStage = (subStatus as? AutoSubStatus.Processing)?.stage ?: "Ready"

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ImmersivePlayerDialog()
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            val maxH = (maxHeight - 16.dp).coerceAtLeast(240.dp)
            Column(
                Modifier
                    .widthIn(min = 320.dp, max = 520.dp)
                    .heightIn(max = maxH)
                    .fillMaxWidth()
                    .frostedGlassChrome(
                        enabled = glassEnabled,
                        shape = RoundedCornerShape(if (glassEnabled) 22.dp else 12.dp),
                        tint = Color(0xCC0B121D),
                        fallbackColor = Color(0xFF1B1F26),
                    )
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = Accent)
                    Text("AI Subtitles", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (onOpenTranslator != null && service.translatableSubtitleTracks().isNotEmpty()) {
                        TextButton(
                            onClick = onOpenTranslator,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("Translate Track", color = Cyan, fontSize = 12.sp)
                        }
                    }
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!isProcessing) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = mode == "original",
                                onClick = { mode = "original" },
                                label = { Text("Original speech", fontSize = 12.sp) },
                            )
                            FilterChip(
                                selected = mode == "en",
                                onClick = { mode = "en" },
                                label = { Text("Speech → English", fontSize = 12.sp) },
                            )
                            FilterChip(
                                selected = mode == "translate_own",
                                onClick = { mode = "translate_own" },
                                label = { Text("→ ${ownTargetSpec.label}", fontSize = 12.sp) },
                            )
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = spokenHintSpec != null,
                                onClick = { pickLangMode = "spoken" },
                                label = { Text("Spoken: ${spokenHintSpec?.label ?: "Auto"}", fontSize = 11.5.sp) },
                            )
                            FilterChip(
                                selected = mode == "translate_own",
                                onClick = { pickLangMode = "target" },
                                label = { Text("Target: ${ownTargetSpec.label}", fontSize = 11.5.sp) },
                            )
                        }
                        when (val currentStatus = subStatus) {
                            is AutoSubStatus.Success -> {
                                Text(
                                    "${currentStatus.count} lines · ${currentStatus.file?.name ?: "Saved"}",
                                    color = Color(0xFF81C784),
                                    fontSize = 13.sp,
                                )
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    currentStatus.file?.takeIf { it.exists() }?.let { srtFile ->
                                        val shareSrc = remember { MutableInteractionSource() }
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .frostedGlassChrome(
                                                    enabled = glassEnabled,
                                                    shape = RoundedCornerShape(10.dp),
                                                    tint = Accent.copy(alpha = 0.28f),
                                                )
                                                .iosLiquidPress(shareSrc, scaleDown = 0.94f)
                                                .clickable(interactionSource = shareSrc, indication = ripple()) {
                                                    val uri = service.exportSubtitleShareUri(context, srtFile)
                                                    if (uri == null) {
                                                        activity.toast("Cannot share file")
                                                    } else {
                                                        val send = Intent(Intent.ACTION_SEND).apply {
                                                            type = "*/*"
                                                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-subrip", "text/plain"))
                                                            putExtra(Intent.EXTRA_STREAM, uri)
                                                            putExtra(Intent.EXTRA_SUBJECT, srtFile.name)
                                                            clipData = ClipData.newUri(context.contentResolver, srtFile.name, uri)
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }
                                                        runCatching {
                                                            context.startActivity(Intent.createChooser(send, "Share .srt"))
                                                        }.onFailure {
                                                            activity.toast("No share app found")
                                                        }
                                                    }
                                                }
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                                Icon(Icons.Default.Share, null, tint = Color.White, modifier = Modifier.size(15.dp))
                                                Text("Share .srt", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                    val quickTargets = listOfNotNull(
                                        ownTargetSpec,
                                        SubtitleTranslator.findLanguage("en"),
                                        SubtitleTranslator.findLanguage("ta"),
                                    ).distinctBy { it.code }
                                    quickTargets.forEach { targetSpec ->
                                        val trSrc = remember(targetSpec.code) { MutableInteractionSource() }
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .frostedGlassChrome(
                                                    enabled = glassEnabled,
                                                    shape = RoundedCornerShape(10.dp),
                                                    tint = Color(0x4E12243A),
                                                )
                                                .iosLiquidPress(trSrc, scaleDown = 0.94f)
                                                .clickable(interactionSource = trSrc, indication = ripple()) {
                                                    service.translateSubtitleTrack(context, targetSpec)
                                                }
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                        ) {
                                            Text("→ ${targetSpec.label}", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                    val moreSrc = remember { MutableInteractionSource() }
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .frostedGlassChrome(
                                                enabled = glassEnabled,
                                                shape = RoundedCornerShape(10.dp),
                                                tint = Color(0x4E12243A),
                                            )
                                            .iosLiquidPress(moreSrc, scaleDown = 0.94f)
                                            .clickable(interactionSource = moreSrc, indication = ripple()) {
                                                pickLangMode = "translate_now"
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        Text("All (${SubtitleTranslator.supportedLanguages.size})...", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            is AutoSubStatus.Error -> {
                                Text(
                                    currentStatus.message,
                                    color = Color(0xFFE57373),
                                    fontSize = 13.sp,
                                )
                            }
                            else -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .background(
                                                if (ready) Color(0xFF4CAF50) else Color(0xFFFFB74D),
                                                CircleShape,
                                            )
                                    )
                                    Text(
                                        if (ready) "Cloud AI Ready" else "API key required",
                                        color = Color(0xFFE2E8F0),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    progressStage,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "$progressPercent%",
                                    color = Accent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { progressPercent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Accent,
                                trackColor = Color.White.copy(alpha = 0.15f),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isProcessing) {
                            TextButton(onClick = onDismiss) {
                                Text("Close", color = Color.LightGray)
                            }
                            val genSrc = remember { MutableInteractionSource() }
                            Button(
                                onClick = {
                                    val item = service.state.value.item
                                    if (item == null) {
                                        Toast.makeText(activity, "No active video", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    service.startAutoSubtitles(
                                        context = context,
                                        translateToEnglish = (mode == "en"),
                                        spokenLanguageHint = spokenHintSpec?.code,
                                        translateAfterTo = if (mode == "translate_own") ownTargetSpec else null,
                                    )
                                },
                                interactionSource = genSrc,
                                modifier = Modifier.iosLiquidPress(genSrc, scaleDown = 0.93f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Accent,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text("Generate", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            TextButton(onClick = onDismiss) {
                                Text("Hide", color = Accent)
                            }
                        }
                    }
                }
            }
        }
    }
    if (pickLangMode != null) {
        val currentPickMode = pickLangMode!!
        val pickerTitle = when (currentPickMode) {
            "spoken" -> "Spoken Video Language"
            "translate_now" -> "Translate Subtitles To"
            else -> "Target Subtitle Language"
        }
        val selectedCode = when (currentPickMode) {
            "spoken" -> spokenHintCode ?: "auto"
            else -> ownTargetCode
        }
        LanguagePickerDialog(
            title = pickerTitle,
            selectedCodes = setOf(selectedCode),
            multiSelect = false,
            includeAutoDetect = (currentPickMode == "spoken"),
            favoriteCodes = remember(favEpoch) { Store.favoriteSubtitleLanguages(context).toSet() },
            onToggleFavorite = { spec ->
                Store.toggleFavoriteSubtitleLanguage(context, spec.code)
                favEpoch++
            },
            onSelect = { spec ->
                when (currentPickMode) {
                    "spoken" -> spokenHintCode = spec.code
                    "translate_now" -> {
                        ownTargetCode = spec.code
                        service.translateSubtitleTrack(context, spec)
                    }
                    else -> {
                        ownTargetCode = spec.code
                        mode = "translate_own"
                    }
                }
                pickLangMode = null
            },
            onSelectSpecial = { special ->
                if (special == "auto" && currentPickMode == "spoken") {
                    spokenHintCode = null
                    pickLangMode = null
                }
            },
            onDismiss = { pickLangMode = null },
        )
    }
}

@Composable
fun SelectDecoderDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val glassEnabled by rememberGlassEnabled()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ImmersivePlayerDialog()
        Column(
            Modifier
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .widthIn(min = 320.dp, max = 440.dp)
                .frostedGlassChrome(
                    enabled = glassEnabled,
                    shape = RoundedCornerShape(if (glassEnabled) 18.dp else 12.dp),
                    tint = Color(0x9C0B121C),
                    fallbackColor = Color.Black.copy(alpha = 0.50f),
                )
                .padding(20.dp)
        ) {
            Text(
                "Select decoder",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            val options = listOf(
                "HW decoder" to "Hardware",
                "HW+ decoder" to "HW+",
                "SW decoder" to "Software"
            )
            val activeMode = if (current == "Auto") "HW+" else current
            options.forEach { (label, mode) ->
                val isSelected = activeMode == mode
                val pressSrc = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .iosLiquidPress(pressSrc, scaleDown = 0.97f)
                        .clickable(interactionSource = pressSrc, indication = ripple()) { onSelect(mode) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelect(mode) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFF2196F3),
                            unselectedColor = Color.White.copy(alpha = 0.7f)
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        label,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun TranslateSubtitlesDialog(
    service: PlaybackService,
    activity: MainActivity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val glassEnabled by rememberGlassEnabled()
    val playerState by service.state.collectAsState()
    val subStatus by service.autoSubStatus.collectAsState()
    val cueRevision by service.subtitleCuesRevision.collectAsState()
    val demuxing by service.subtitleDemuxing.collectAsState()
    val sourceTracks = remember(playerState.subtitles, cueRevision) { service.translatableSubtitleTracks() }
    val fallbackCues = remember(playerState.subtitles, cueRevision) { service.activeSubtitleCues() }
    var selectedSourceId by remember {
        mutableStateOf<Int?>(null)
    }
    LaunchedEffect(sourceTracks, playerState.selectedSubtitleTracks) {
        if (selectedSourceId == null || sourceTracks.none { it.first == selectedSourceId }) {
            selectedSourceId = playerState.selectedSubtitleTracks.firstOrNull { id -> sourceTracks.any { it.first == id } }
                ?: sourceTracks.firstOrNull()?.first
        }
    }
    var favEpoch by remember { mutableIntStateOf(0) }
    val defaultTarget = remember {
        val favCode = Store.favoriteSubtitleLanguages(context).firstOrNull { it != "en" }
            ?: Store.favoriteSubtitleLanguages(context).firstOrNull()
        SubtitleTranslator.findLanguage(favCode) ?: SubtitleTranslator.defaultOwnLanguage()
    }
    var targetCode by rememberSaveable { mutableStateOf(defaultTarget.code) }
    var dualMode by rememberSaveable { mutableStateOf(false) }
    var showLangPicker by remember { mutableStateOf(false) }
    val targetSpec = remember(targetCode) {
        SubtitleTranslator.findLanguage(targetCode) ?: SubtitleTranslator.defaultOwnLanguage()
    }
    val quickTargets = remember(favEpoch, targetSpec) {
        val favSpecs = Store.favoriteSubtitleLanguages(context).mapNotNull { SubtitleTranslator.findLanguage(it) }
        (listOf(targetSpec) + favSpecs + listOfNotNull(
            SubtitleTranslator.defaultOwnLanguage(),
            SubtitleTranslator.findLanguage("en"),
            SubtitleTranslator.findLanguage("ta"),
        )).distinctBy { it.code }.take(6)
    }
    val isProcessing = subStatus is AutoSubStatus.Processing
    val progressPercent = (subStatus as? AutoSubStatus.Processing)?.percent ?: 0
    val progressStage = (subStatus as? AutoSubStatus.Processing)?.stage ?: "Ready"
    val canTranslate = !isProcessing && (selectedSourceId != null || fallbackCues.isNotEmpty())

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ImmersivePlayerDialog()
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            val maxH = (maxHeight - 16.dp).coerceAtLeast(260.dp)
            Column(
                Modifier
                    .widthIn(min = 320.dp, max = 540.dp)
                    .heightIn(max = maxH)
                    .fillMaxWidth()
                    .frostedGlassChrome(
                        enabled = glassEnabled,
                        shape = RoundedCornerShape(if (glassEnabled) 22.dp else 12.dp),
                        tint = Color(0xCC0B121D),
                        fallbackColor = Color(0xFF1B1F26),
                    )
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Translate, null, tint = Cyan)
                    Column(Modifier.weight(1f)) {
                        Text("Translate Subtitles", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Full-length · ${SubtitleTranslator.supportedLanguages.size} languages", color = Muted, fontSize = 11.5.sp)
                    }
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!isProcessing) {
                        if (sourceTracks.isEmpty() && fallbackCues.isEmpty()) {
                            if (demuxing) {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
                                    Text("Scanning embedded subtitles…", color = Color.White, fontSize = 13.sp)
                                }
                            } else {
                                Text(
                                    "No text subtitle track loaded yet. Open an .srt/.vtt/.ass file or MKV/MP4 soft subtitle track first.",
                                    color = Color(0xFFFFD54F),
                                    fontSize = 13.sp,
                                )
                                OutlinedButton(
                                    onClick = {
                                        onDismiss()
                                        activity.subtitleFile.launch(arrayOf("*/*"))
                                    },
                                    border = BorderStroke(1.dp, Cyan.copy(alpha = 0.5f)),
                                ) {
                                    Icon(Icons.Default.FolderOpen, null, tint = Cyan, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Open (.srt / .vtt / .ass)", color = Cyan)
                                }
                            }
                        } else {
                            Text("Source Track", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (sourceTracks.isNotEmpty()) {
                                    sourceTracks.forEach { (id, name) ->
                                        val cueCount = remember(id, sourceTracks, cueRevision) { service.cuesForSubtitleTrack(id).size }
                                        FilterChip(
                                            selected = selectedSourceId == id,
                                            onClick = { selectedSourceId = id },
                                            label = { Text("$name ($cueCount)", fontSize = 12.sp, maxLines = 1) },
                                        )
                                    }
                                } else {
                                    FilterChip(
                                        selected = true,
                                        onClick = {},
                                        label = { Text("Active Subtitles (${fallbackCues.size})", fontSize = 12.sp) },
                                    )
                                }
                            }

                            Text("Target Language", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                quickTargets.forEach { spec ->
                                    FilterChip(
                                        selected = targetSpec.code == spec.code,
                                        onClick = { targetCode = spec.code },
                                        label = { Text(spec.label, fontSize = 12.sp) },
                                    )
                                }
                                FilterChip(
                                    selected = false,
                                    onClick = { showLangPicker = true },
                                    label = { Text("All ${SubtitleTranslator.supportedLanguages.size}...", fontSize = 12.sp, color = Cyan) },
                                )
                            }

                            Text("Display Mode", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !dualMode,
                                    onClick = { dualMode = false },
                                    label = { Text("Replace track", fontSize = 12.sp) },
                                )
                                FilterChip(
                                    selected = dualMode,
                                    onClick = { dualMode = true },
                                    label = { Text("Dual subtitles", fontSize = 12.sp) },
                                )
                            }
                        }

                        when (val currentStatus = subStatus) {
                            is AutoSubStatus.Success -> {
                                Text(
                                    "${currentStatus.count} lines · ${currentStatus.file?.name ?: "Ready"}",
                                    color = Color(0xFF81C784),
                                    fontSize = 13.sp,
                                )
                                currentStatus.file?.takeIf { it.exists() }?.let { srtFile ->
                                    val shareSrc = remember { MutableInteractionSource() }
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .frostedGlassChrome(
                                                enabled = glassEnabled,
                                                shape = RoundedCornerShape(10.dp),
                                                tint = Accent.copy(alpha = 0.28f),
                                            )
                                            .iosLiquidPress(shareSrc, scaleDown = 0.94f)
                                            .clickable(interactionSource = shareSrc, indication = ripple()) {
                                                val uri = service.exportSubtitleShareUri(context, srtFile)
                                                if (uri == null) {
                                                    activity.toast("Cannot share file")
                                                } else {
                                                    val send = Intent(Intent.ACTION_SEND).apply {
                                                        type = "*/*"
                                                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-subrip", "text/plain"))
                                                        putExtra(Intent.EXTRA_STREAM, uri)
                                                        putExtra(Intent.EXTRA_SUBJECT, srtFile.name)
                                                        clipData = ClipData.newUri(context.contentResolver, srtFile.name, uri)
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    runCatching {
                                                        context.startActivity(Intent.createChooser(send, "Share .srt"))
                                                    }.onFailure {
                                                        activity.toast("No share app found")
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Icon(Icons.Default.Share, null, tint = Color.White, modifier = Modifier.size(15.dp))
                                            Text("Share .srt", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                            is AutoSubStatus.Error -> {
                                Text(currentStatus.message, color = Color(0xFFE57373), fontSize = 13.sp)
                            }
                            else -> Unit
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    progressStage,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "$progressPercent%",
                                    color = Accent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { progressPercent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Accent,
                                trackColor = Color.White.copy(alpha = 0.15f),
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isProcessing) {
                        TextButton(onClick = onDismiss) { Text("Close", color = Color.LightGray) }
                        Spacer(Modifier.width(8.dp))
                        val runSrc = remember { MutableInteractionSource() }
                        Button(
                            enabled = canTranslate,
                            onClick = {
                                service.translateSubtitleTrack(
                                    context = context,
                                    target = targetSpec,
                                    sourceTrackId = selectedSourceId,
                                    dualMode = dualMode,
                                )
                            },
                            interactionSource = runSrc,
                            modifier = Modifier.iosLiquidPress(runSrc, enabled = canTranslate, scaleDown = 0.93f),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                        ) {
                            Text("Translate to ${targetSpec.label}", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        TextButton(onClick = onDismiss) { Text("Hide", color = Accent) }
                    }
                }
            }
        }
    }
    if (showLangPicker) {
        LanguagePickerDialog(
            title = "Translate Subtitles To",
            selectedCodes = setOf(targetSpec.code),
            multiSelect = false,
            favoriteCodes = remember(favEpoch) { Store.favoriteSubtitleLanguages(context).toSet() },
            onToggleFavorite = { spec ->
                Store.toggleFavoriteSubtitleLanguage(context, spec.code)
                favEpoch++
            },
            onSelect = { spec ->
                targetCode = spec.code
                showLangPicker = false
            },
            onDismiss = { showLangPicker = false },
        )
    }
}

@Composable
fun LanguagePickerDialog(
    title: String,
    selectedCodes: Set<String>,
    multiSelect: Boolean = false,
    includeAll: Boolean = false,
    includeAutoDetect: Boolean = false,
    inPlayer: Boolean = true,
    favoriteCodes: Set<String> = emptySet(),
    onToggleFavorite: ((SubtitleTranslator.LanguageSpec) -> Unit)? = null,
    onSelect: (SubtitleTranslator.LanguageSpec) -> Unit,
    onSelectSpecial: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val palette = LocalAppPalette.current
    val useLight = palette.isLight && !inPlayer
    val glassEnabled = palette.isGlass
    val dialogText = if (useLight) palette.textPrimary else Color.White
    val dialogSubText = if (useLight) palette.textSecondary else Muted
    val rowBg = if (useLight) palette.chipBackground else Color.White.copy(alpha = 0.05f)
    val accentColor = if (useLight) palette.accent else Accent
    var query by remember { mutableStateOf("") }
    val filteredLanguages = remember(query, favoriteCodes, selectedCodes) {
        val q = query.trim().lowercase(java.util.Locale.ROOT)
        val base = if (q.isEmpty()) {
            SubtitleTranslator.supportedLanguages
        } else {
            SubtitleTranslator.supportedLanguages.filter { spec ->
                spec.label.lowercase(java.util.Locale.ROOT).contains(q) ||
                    spec.nativeName.lowercase(java.util.Locale.ROOT).contains(q) ||
                    spec.code.lowercase(java.util.Locale.ROOT).contains(q) ||
                    spec.code3.lowercase(java.util.Locale.ROOT).contains(q) ||
                    spec.aliases.any { it.lowercase(java.util.Locale.ROOT).contains(q) }
            }
        }
        base.sortedWith(
            compareByDescending<SubtitleTranslator.LanguageSpec> {
                it.code in favoriteCodes || (multiSelect && it.code in selectedCodes)
            }.thenBy { it.label }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        if (inPlayer) ImmersivePlayerDialog()
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            val maxH = (maxHeight - 16.dp).coerceAtLeast(260.dp)
            Column(
                Modifier
                    .widthIn(min = 320.dp, max = 500.dp)
                    .heightIn(max = maxH)
                    .fillMaxWidth()
                    .frostedGlassChrome(
                        enabled = glassEnabled,
                        shape = RoundedCornerShape(if (glassEnabled) 22.dp else 16.dp),
                        tint = Color(0xCC0B121D),
                        fallbackColor = if (useLight) Color(0xFFFFFFFF) else Color(0xFF1B1F26),
                        fallbackBorderColor = if (useLight) palette.cardBorder else Color.White.copy(alpha = 0.18f),
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, color = dialogText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search language or ISO code (e.g. ta, es, ja)...") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, "Clear search")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (includeAll && (query.isBlank() || "all languages".contains(query.trim(), ignoreCase = true))) {
                        item(key = "__all") {
                            val selectedAll = "all" in selectedCodes
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selectedAll) accentColor.copy(alpha = if (useLight) 0.14f else 0.22f) else rowBg)
                                    .clickable { onSelectSpecial?.invoke("all") }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("All Languages", color = dialogText, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (selectedAll) Icon(Icons.Default.Check, null, tint = accentColor, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (includeAutoDetect && (query.isBlank() || "auto detect".contains(query.trim(), ignoreCase = true))) {
                        item(key = "__auto") {
                            val selectedAuto = "auto" in selectedCodes
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selectedAuto) accentColor.copy(alpha = if (useLight) 0.14f else 0.22f) else rowBg)
                                    .clickable { onSelectSpecial?.invoke("auto") }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Auto-detect Spoken Language", color = dialogText, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (selectedAuto) Icon(Icons.Default.Check, null, tint = accentColor, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    items(filteredLanguages, key = { it.code }) { spec ->
                        val isSelected = spec.code in selectedCodes
                        val isFav = spec.code in favoriteCodes || (multiSelect && isSelected)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) accentColor.copy(alpha = if (useLight) 0.14f else 0.22f) else rowBg)
                                .clickable { onSelect(spec) }
                                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    spec.label,
                                    color = if (isSelected) (if (useLight) accentColor else Color.White) else if (useLight) dialogText else Color(0xFFE6ECF4),
                                    fontSize = 14.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                )
                                Text(
                                    "${spec.nativeName} · ${spec.code.uppercase(java.util.Locale.ROOT)}",
                                    color = if (isSelected) (if (useLight) palette.accentSecondary else Cyan) else dialogSubText,
                                    fontSize = 11.5.sp,
                                )
                            }
                            if (isSelected) {
                                Icon(Icons.Default.Check, null, tint = accentColor, modifier = Modifier.padding(end = 4.dp).size(18.dp))
                            }
                            if (onToggleFavorite != null) {
                                IconButton(
                                    onClick = { onToggleFavorite(spec) },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = if (isFav) "Remove favorite" else "Favorite language",
                                        tint = if (isFav) Color(0xFFEAB308) else dialogSubText,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(if (multiSelect) "Done" else "Cancel", color = accentColor, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun ChaptersDialog(
    chapters: List<ContainerDemuxer.ChapterMarker>,
    currentPos: Long,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val glassEnabled by rememberGlassEnabled()
    val activeIdx = remember(chapters, currentPos) {
        chapters.indexOfLast { it.startMs <= currentPos }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = activeIdx)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ImmersivePlayerDialog()
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            val maxH = (maxHeight - 16.dp).coerceAtLeast(240.dp)
            Column(
                Modifier
                    .widthIn(min = 320.dp, max = 480.dp)
                    .heightIn(max = maxH)
                    .fillMaxWidth()
                    .frostedGlassChrome(
                        enabled = glassEnabled,
                        shape = RoundedCornerShape(if (glassEnabled) 22.dp else 12.dp),
                        tint = Color(0xCC0B121D),
                        fallbackColor = Color(0xFF1B1F26),
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Menu, null, tint = Cyan)
                    Text("Chapters (${chapters.size})", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(chapters.size, key = { it }) { idx ->
                        val ch = chapters[idx]
                        val isActive = idx == activeIdx
                        val chSrc = remember(idx) { MutableInteractionSource() }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isActive) Accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.05f))
                                .iosLiquidPress(chSrc, scaleDown = 0.98f)
                                .clickable(interactionSource = chSrc, indication = ripple()) { onSelect(idx) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "${idx + 1}.",
                                color = if (isActive) Accent else Muted,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                ch.title,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                durationText(ch.startMs),
                                color = if (isActive) Cyan else Muted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close", color = Accent) }
                }
            }
        }
    }
}
