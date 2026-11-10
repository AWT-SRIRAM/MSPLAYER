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
import android.provider.DocumentsContract
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.videolan.libvlc.util.VLCVideoLayout

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
    private var scanJob: Job? = null
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

    private var closing = false
    private var manualOrientationFor: String? = null
    val diagnosticDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) lifecycleScope.launch {
            val source = service?.exportDiagnostics() ?: return@launch
            withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } } }
            toast("Diagnostics saved")
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

    private fun enableHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.post {
                val display = window.decorView.display
                val supportedModes = display?.supportedModes.orEmpty()
                val maxRefreshMode = supportedModes.maxByOrNull { it.refreshRate }
                if (maxRefreshMode != null && maxRefreshMode.refreshRate > 60f) {
                    val params = window.attributes
                    params.preferredDisplayModeId = maxRefreshMode.modeId
                    window.attributes = params
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val display = windowManager.defaultDisplay
            val supportedModes = display?.supportedModes.orEmpty()
            val maxRefreshMode = supportedModes.maxByOrNull { it.refreshRate }
            if (maxRefreshMode != null && maxRefreshMode.refreshRate > 60f) {
                val params = window.attributes
                params.preferredDisplayModeId = maxRefreshMode.modeId
                window.attributes = params
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GlassTheme.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableHighRefreshRate()
        showPlayer =
            savedInstanceState?.getBoolean("player") ?: intent.getBooleanExtra("show_player", false)
        extractIncomingMedia(intent)
        startService(Intent(this, PlaybackService::class.java))
        bindService(Intent(this, PlaybackService::class.java), connection, BIND_AUTO_CREATE)
        reload()
        setContent {
            MaterialTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Accent,
                        secondary = Cyan,
                        background = Color.Black,
                        surface = Panel,
                        onBackground = Color.White,
                        onSurface = Color.White,
                    )
            ) {
                Surface(color = Color.Black) { App(this) }
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
        if (incoming.action == Intent.ACTION_SEND && incoming.type == "text/plain") {
            val shared = incoming.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val normalized = PlaybackRules.normalizeStreamUrl(shared)
            if (PlaybackRules.isStreamUrl(normalized)) {
                pendingUri = Uri.parse(normalized)
            } else {
                pendingStreamPrompt = shared.trim()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uriData = intent.data ?: run {
            if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
                val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                val normalized = PlaybackRules.normalizeStreamUrl(shared)
                if (PlaybackRules.isStreamUrl(normalized)) Uri.parse(normalized)
                else {
                    pendingStreamPrompt = shared.trim()
                    null
                }
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

    override fun onResume() {
        super.onResume()
        enableHighRefreshRate()
        if (wasRestricted) reload()
        wasRestricted = !hasLibraryPermission()
        immersive(showPlayer)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        service?.endTemporarySpeed()
        super.onConfigurationChanged(newConfig)
        service?.updateSubtitlePlacement(playerControlsVisible && !inPip)
    }

    override fun onPause() { service?.endTemporarySpeed(); super.onPause() }

    override fun onStop() {
        super.onStop()
        if (
            !isChangingConfigurations &&
                !inPip &&
                service?.state?.value?.item?.audio != true &&
                !Store.prefs(this).getBoolean("background", false)
        )
            service?.pause()
    }

    override fun onDestroy() {
        if (isFinishing) service?.stopPlayback()
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
    }

    fun enterPip() {
        if (
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                service?.state?.value?.item != null
        ) {
            val video = service?.state?.value
            val ratio =
                if (video != null && video.videoWidth > 0 && video.videoHeight > 0)
                    video.videoWidth.toFloat() / video.videoHeight
                else 16f / 9
            runCatching {
                    enterPictureInPictureMode(
                        PictureInPictureParams.Builder()
                            .setAspectRatio(
                                Rational((ratio.coerceIn(0.5f, 2.39f) * 1000).toInt(), 1000)
                            )
                            .build()
                    )
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
        ) == PackageManager.PERMISSION_GRANTED

    fun reload() {
        scanJob?.cancel()
        scanJob =
            lifecycleScope.launch {
                scanning = true
                libraryError = null
                try {
                    val v = Library.scan(this@MainActivity, false)
                    val a = Library.scan(this@MainActivity, true)
                    val tree = Store.prefs(this@MainActivity).getString("tree", null)
                    val extra = if (tree != null) scanTree(Uri.parse(tree)) else emptyList()
                    videos = (v + extra.filter { !it.audio }).distinctBy { it.uri }
                    audios = (a + extra.filter { it.audio }).distinctBy { it.uri }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    libraryError = "Some files could not be scanned. ${e.message}"
                } finally {
                    scanning = false
                }
            }
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

    val screenMode = when {
        activity.showPlayer && service != null && state.item?.audio == true -> AppScreenMode.MUSIC
        activity.showPlayer && service != null && state.item != null -> AppScreenMode.VIDEO
        else -> AppScreenMode.LIBRARY
    }

    SharedTransitionLayout {
        AnimatedContent(
            targetState = screenMode,
            transitionSpec = {
                when {
                    targetState == AppScreenMode.MUSIC || initialState == AppScreenMode.MUSIC ->
                        fadeIn(tween(180)) togetherWith fadeOut(tween(170))
                    else ->
                        fadeIn(tween(160)) togetherWith fadeOut(tween(140))
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
    val glassEnabled by rememberGlassEnabled()
    val appBackdrop = rememberLayerBackdrop()
    val listBottomPad = if (glassEnabled) 124.dp else 88.dp
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(appBackdrop)) {
            LiquidGlassBackdropBackground(glassEnabled)
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
                            Icon(Icons.Default.ArrowBack, "Back")
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
                            Icon(Icons.Default.PlaylistAdd, "Add selected to playlist")
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
                            Icon(Icons.Default.PlayArrow, "Play selected")
                        }
                        IconButton(onClick = { selected = emptySet() }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Close, "Clear selection")
                        }
                    } else if (tab != "Settings") {
                        IconButton(onClick = { onSearchingChange(true) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Search, "Search", modifier = Modifier.size(21.dp))
                        }
                        IconButton(onClick = { onGridChange(!grid) }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                if (grid) Icons.Default.ViewList else Icons.Default.GridView,
                                "Change layout",
                                modifier = Modifier.size(21.dp),
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.MoreVert, "Library options", modifier = Modifier.size(21.dp))
                            }
                            LiquidGlassPopupMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                listOf("Name", "Date", "Size", "Duration").forEach { label ->
                                    LiquidGlassPopupMenuItem(
                                        text = {
                                            Text(
                                                "Sort by $label",
                                                color = if (sort == label) Accent else Color.White,
                                                fontWeight = if (sort == label) FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                        },
                                        trailingIcon = if (sort == label) {
                                            { Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(16.dp)) }
                                        } else null,
                                        onClick = {
                                            onSortChange(label)
                                            sortMenu = false
                                        },
                                    )
                                }
                                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Color.White.copy(alpha = 0.12f))
                                LiquidGlassPopupMenuItem(
                                    text = { Text("Stream URL") },
                                    leadingIcon = { Icon(Icons.Default.CloudQueue, null, tint = Cyan, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        onStreamDialogChange(true)
                                        sortMenu = false
                                    },
                                )
                                LiquidGlassPopupMenuItem(
                                    text = { Text("Rescan") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        Library.retryThumbnails()
                                        activity.reload()
                                        onBumpRefresh()
                                        sortMenu = false
                                    },
                                )
                                LiquidGlassPopupMenuItem(
                                    text = { Text("Open file") },
                                    leadingIcon = { Icon(Icons.Default.FileOpen, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        activity.openFile.launch(arrayOf("*/*"))
                                        sortMenu = false
                                    },
                                )
                                LiquidGlassPopupMenuItem(
                                    text = { Text("Choose folder") },
                                    leadingIcon = { Icon(Icons.Default.FolderOpen, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
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
                                    text = { Text("Exit", color = Color(0xFFFF7A7A)) },
                                    onClick = { activity.closeApp() },
                                )
                            }
                        }
                    }
                }
                if (activity.scanning) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
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
                                    (slideInHorizontally(MotionSpec.PanelOffsetSpring) { it / 3 } + fadeIn(tween(160))) togetherWith
                                        (slideOutHorizontally(MotionSpec.PanelOffsetSpring) { -it / 6 } + fadeOut(tween(120)))
                                drillingOut ->
                                    (slideInHorizontally(MotionSpec.PanelOffsetSpring) { -it / 4 } + fadeIn(tween(160))) togetherWith
                                        (slideOutHorizontally(MotionSpec.PanelOffsetSpring) { it / 3 } + fadeOut(tween(120)))
                                else -> fadeIn(tween(160)) togetherWith fadeOut(tween(130))
                            }
                        },
                        label = "libraryContentTransition",
                    ) { (curTab, curFolder, curPlaylist) ->
                        when {
                            curTab == "Settings" -> SettingsScreen(activity)
                            curTab == "Playlists" && curPlaylist == null -> {
                                if (playlists.isEmpty())
                                    EmptyLibrary(
                                        "Your playlists",
                                        "Add a video or audio file using its menu.",
                                        Icons.Default.QueueMusic,
                                    )
                                else
                                    LazyColumn(contentPadding = PaddingValues(bottom = listBottomPad)) {
                                        items(playlists, key = { it }) { name ->
                                            val plSrc = remember { MutableInteractionSource() }
                                            ListItem(
                                                headlineContent = { Text(name) },
                                                leadingContent = {
                                                    Icon(Icons.Default.QueueMusic, null, tint = Accent)
                                                },
                                                modifier = Modifier
                                                    .animateItem()
                                                    .iosLiquidPress(plSrc, scaleDown = 0.98f, sheenAlpha = 0.10f)
                                                    .clickable(interactionSource = plSrc, indication = ripple()) {
                                                        onActivePlaylistChange(name)
                                                    },
                                                colors = ListItemDefaults.colors(
                                                    containerColor = if (glassEnabled) Color.Transparent else Color.Black
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
                                if (folders.isEmpty()) AccessEmpty(activity)
                                else {
                                    val recentVideos = remember(activity.videos, hidden, dismissedRecent) {
                                        activity.videos
                                            .filter { !it.audio && it.folder !in hidden && it.uri !in dismissedRecent }
                                            .sortedByDescending { it.added.takeIf { a -> a > 0 } ?: it.modified }
                                            .take(8)
                                    }
                                    LazyColumn(contentPadding = PaddingValues(bottom = listBottomPad)) {
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
                                                            color = Color.White,
                                                        )
                                                        Text(
                                                            "Clear",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = Accent,
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
                                                                    .animateItem()
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
                                                                    color = Color.White,
                                                                    modifier = Modifier.padding(top = 3.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 2.dp), color = Color.White.copy(alpha = 0.08f))
                                                }
                                            }
                                        }
                                        items(folders, key = { it.name }) { entry ->
                                            val folderSrc = remember { MutableInteractionSource() }
                                            val isCurrentFolder = entry.items.any { it.uri == state.item?.uri }
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .animateItem()
                                                    .iosLiquidPress(folderSrc, scaleDown = 0.98f, sheenAlpha = 0.10f)
                                                    .combinedClickable(
                                                        interactionSource = folderSrc,
                                                        indication = ripple(),
                                                        onClick = { onFolderChange(entry.name) },
                                                        onLongClick = { folderAction = entry.name },
                                                    )
                                                    .padding(horizontal = 14.dp, vertical = 6.dp),
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
                                                        color = if (isCurrentFolder) Accent else Color.White,
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
                                                            color = Color(0xFFB5BDCB),
                                                        )
                                                        Text(
                                                            sizeText(entry.size),
                                                            color = Color(0xFFE0E5EF),
                                                            fontSize = 10.5.sp,
                                                            lineHeight = 12.sp,
                                                            maxLines = 1,
                                                            softWrap = false,
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(4.dp))
                                                                .background(Color(0xFF343A43))
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
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                }
                            else ->
                                LazyColumn(contentPadding = PaddingValues(bottom = listBottomPad)) {
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
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                }
                        }
                    }
                    activity.libraryError?.let {
                        Text(
                            it,
                            color = Color(0xFFFFB4AB),
                            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                        )
                    }
                }
                if (!glassEnabled) {
                    NavigationBar(containerColor = Color(0xFF101216), tonalElevation = 0.dp) {
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
                                            selectedIconColor = Accent,
                                            selectedTextColor = Accent,
                                            indicatorColor = Color.Transparent,
                                            unselectedIconColor = Muted,
                                            unselectedTextColor = Muted,
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
                        .padding(end = 16.dp, bottom = 78.dp)
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
                    containerColor = Accent,
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
                    .padding(horizontal = if (glassEnabled) 16.dp else 0.dp)
                    .padding(bottom = if (glassEnabled) 72.dp else 72.dp)
                    .fillMaxWidth()
                    .then(morphModifier)
                    .iosLiquidPress(miniSrc, scaleDown = 0.96f, sheenAlpha = 0.14f)
                    .let {
                        if (glassEnabled) {
                            it.liquidGlassSurface(
                                backdrop = appBackdrop,
                                enabled = true,
                                shape = RoundedCornerShape(20.dp),
                                tint = Color(0x6E0E1726),
                            )
                        } else {
                            it.background(Panel)
                        }
                    }
                    .clickable(interactionSource = miniSrc, indication = ripple()) {
                        activity.showPlayer = true
                        activity.immersive(true)
                    }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.MusicNote, null, tint = Accent)
                Text(
                    state.item!!.title,
                    Modifier.weight(1f).padding(horizontal = 12.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { service.toggle() }) {
                    Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause")
                }
                IconButton(onClick = { service.next() }) {
                    Icon(Icons.Default.SkipNext, "Next track")
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
            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .height(54.dp)
                    .liquidGlassSurface(
                        backdrop = appBackdrop,
                        enabled = true,
                        shape = RoundedCornerShape(27.dp),
                        tint = Color(0x640D1522),
                        refractionHeight = 12.dp,
                        refractionAmount = 22.dp,
                    )
                    .padding(horizontal = 5.dp, vertical = 4.dp),
            ) {
                val tabWidth = maxWidth / dockTabs.size
                val pillOffset by animateDpAsState(
                    targetValue = tabWidth * activeIdx,
                    animationSpec = MotionSpec.DpSpring,
                    label = "dockActivePill",
                )
                Box(
                    Modifier
                        .offset(x = pillOffset)
                        .width(tabWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Accent.copy(alpha = 0.28f),
                                    Accent.copy(alpha = 0.13f),
                                )
                            )
                        )
                        .border(0.75.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(22.dp))
                )
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dockTabs.forEach { (name, icon, label) ->
                        val active = tab == name
                        val tabSrc = remember { MutableInteractionSource() }
                        val itemTint by animateColorAsState(
                            targetValue = if (active) Accent else Color.White.copy(alpha = 0.78f),
                            animationSpec = MotionSpec.ColorSpring,
                            label = "dockTabTint",
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(22.dp))
                                .iosLiquidPress(tabSrc, scaleDown = 0.90f, sheenAlpha = 0.16f)
                                .clickable(interactionSource = tabSrc, indication = null) {
                                    onTabChange(name)
                                    onFolderChange(null)
                                    onActivePlaylistChange(null)
                                    onSearchChange("")
                                    onSearchingChange(false)
                                    selected = emptySet()
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
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                            )
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
                    Text("Exclude", color = Accent)
                }
            },
            dismissButton = { TextButton(onClick = { folderAction = null }) { Text("Cancel", color = Color.LightGray) } },
        )
    }
    if (detail != null) {
        val item = detail!!
        LiquidGlassAlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("Properties") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, fontWeight = FontWeight.Medium)
                    Text("${sizeText(item.size)} · ${durationText(item.duration)}")
                    if (item.height > 0) Text("${item.width} × ${item.height}")
                    Text(item.uri, fontSize = 11.5.sp, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Close", color = Accent) } },
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
                    Text("Add", color = Accent)
                }
            },
            dismissButton = { TextButton(onClick = { playlistDialog = false }) { Text("Cancel", color = Color.LightGray) } },
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
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val uri = Uri.parse(target.uri)
                                if (uri.scheme == "content") {
                                    runCatching { activity.contentResolver.delete(uri, null, null) }
                                } else if (uri.scheme == "file") {
                                    runCatching { File(uri.path ?: "").delete() }
                                }
                            }
                            deleteTarget = null
                            Library.invalidateScanCache()
                            activity.reload()
                            onBumpRefresh()
                            activity.toast("File deleted")
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5252))
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = Color.LightGray) } }
        )
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
    val glassEnabled by rememberGlassEnabled()
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
                        shape = RoundedCornerShape(if (glassEnabled) 22.dp else 12.dp),
                        tint = Color(0xBC0B121D),
                        fallbackColor = Color(0xFF1B1F26),
                    )
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.CloudQueue, null, tint = Accent)
                    Text("Network Stream", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "HTTP / HTTPS / HLS (.m3u8) / DASH / RTSP",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    clipboardSuggestion?.let { clipUrl ->
                        val clipSrc = remember { MutableInteractionSource() }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Accent.copy(alpha = 0.14f))
                                .border(0.8.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .iosLiquidPress(clipSrc, scaleDown = 0.97f)
                                .clickable(interactionSource = clipSrc, indication = ripple()) {
                                    onStreamUrlChange(clipUrl)
                                    clipboardSuggestion = null
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.ContentPaste, null, tint = Accent, modifier = Modifier.size(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Paste clipboard", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(clipUrl, color = Color.White, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                            color = Color(0xFFFFD54F),
                            fontSize = 12.sp,
                        )
                    }
                    if (recentStreams.isNotEmpty()) {
                        Text("Recent", color = Color(0xFF9FB0C4), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        recentStreams.take(5).forEach { recent ->
                            val recSrc = remember { MutableInteractionSource() }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .iosLiquidPress(recSrc, scaleDown = 0.98f)
                                    .clickable(interactionSource = recSrc, indication = ripple()) {
                                        onPlayUrl(recent)
                                    }
                                    .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.History, null, tint = Accent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    recent,
                                    color = Color.White,
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { onRemoveRecent(recent) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(Icons.Default.Close, "Remove recent stream", tint = Muted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Color.LightGray) }
                    Spacer(Modifier.width(8.dp))
                    val playSrc = remember { MutableInteractionSource() }
                    Button(
                        enabled = isValid,
                        onClick = { onPlayUrl(normalized) },
                        interactionSource = playSrc,
                        modifier = Modifier.iosLiquidPress(playSrc, enabled = isValid, scaleDown = 0.93f),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
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
    val glassEnabled by rememberGlassEnabled()
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
                    fallbackColor = Color(0xFF102331),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(21.dp))
        }
        Text(
            label,
            fontSize = 11.sp,
            color = Color(0xFFD5DCE8),
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AccessEmpty(activity: MainActivity) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.FolderOpen, null, tint = Accent, modifier = Modifier.size(56.dp))
        Text(
            "No media found",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Grant access or open a file.",
            color = Muted,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        Button(onClick = { activity.requestAccess() }) { Text("Allow access") }
        TextButton(onClick = { activity.openFile.launch(arrayOf("*/*")) }) { Text("Open file") }
        TextButton(onClick = { activity.openFolder.launch(null) }) { Text("Choose folder") }
    }
}

@Composable
fun EmptyLibrary(title: String, description: String, icon: ImageVector) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(56.dp))
        Text(title, fontSize = 22.sp, modifier = Modifier.padding(top = 16.dp))
        Text(description, color = Muted, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun Badge(text: String, color: Color = Color(0xFF42464D)) {
    Text(
        text,
        color = Color.White,
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
fun Thumbnail(item: MediaItem, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap by remember(item.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val revision by Library.thumbnailRevision.collectAsState()
    var loading by remember(item.uri, revision) { mutableStateOf(true) }
    LaunchedEffect(item.uri, item.size, item.modified, item.added, revision) {
        loading = true
        bitmap = Library.thumbnail(context, item)
        loading = false
    }
    Box(
        modifier.clip(RoundedCornerShape(5.dp)).background(Panel),
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
                if (item.audio) Icons.Default.Headphones else if (loading) Icons.Default.HourglassEmpty else Icons.Default.Movie,
                null,
                tint = Muted,
                modifier = Modifier.size(32.dp),
            )
        if (item.duration > 0)
            Text(
                durationText(item.duration),
                fontSize = 12.sp,
                lineHeight = 14.sp,
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .background(Color.Black.copy(alpha = .6f))
                        .padding(horizontal = 3.dp),
            )
    }
}

@Composable
fun FolderThumbnail(entry: MediaFolder, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(entry.name, entry.items.size) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(entry.name, entry.items.size) {
        bitmap = Library.folderThumbnail(context, entry)
    }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF1E232B)), contentAlignment = Alignment.Center) {
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
            drawPath(path, Color(0xFF45505D))
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
    val glassEnabled by rememberGlassEnabled()
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
            .background(
                when {
                    selected -> Color(0x55123044)
                    glassEnabled -> Color.Transparent
                    else -> Color.Black
                }
            )
            .iosLiquidPress(pressSrc, scaleDown = 0.98f, sheenAlpha = 0.09f)
            .combinedClickable(
                interactionSource = pressSrc,
                indication = ripple(),
                onClick = onClick,
                onLongClick = onLong,
            )
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(resolved, if (item.audio) Modifier.size(48.dp) else Modifier.width(84.dp).height(48.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    color = if (isPlaying) Accent else if (seen) Muted else Color.White,
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
                if (resolved.height > 0) Badge("${resolved.height}p")
                if (item.size > 0) Badge(sizeText(item.size))
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
    val glassEnabled by rememberGlassEnabled()
    val pressSrc = remember { MutableInteractionSource() }
    Column(
        modifier
            .background(
                when {
                    selected -> Color(0x55123044)
                    glassEnabled -> Color.Transparent
                    else -> Color.Black
                },
                RoundedCornerShape(6.dp),
            )
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
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.MoreVert, "File options", modifier = Modifier.size(20.dp))
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
                            color = if (action == "Delete") Color(0xFFFF6E6E) else Color.White,
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
                            tint = if (action == "Delete") Color(0xFFFF6E6E) else Color.White,
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
                    text = { Text("Remove") },
                    leadingIcon = { Icon(Icons.Default.PlaylistRemove, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
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
    glassEnabled: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .frostedGlassChrome(
                enabled = glassEnabled,
                shape = RoundedCornerShape(if (glassEnabled) 18.dp else 10.dp),
                tint = Color(0x4C0B1320),
                fallbackColor = Color(0xFF101318),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
fun SettingsScreen(activity: MainActivity) {
    val prefs = remember { Store.prefs(activity) }
    val glassEnabled by rememberGlassEnabled()
    var epoch by remember { mutableIntStateOf(0) }
    var about by remember { mutableStateOf(false) }
    var showApiKeys by remember { mutableStateOf(false) }
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
            item {
                SettingsGlassCard(glassEnabled) {
                    Text("Interface", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Accent)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Liquid Glass", modifier = Modifier.weight(1f), fontSize = 15.sp)
                        Switch(
                            checked = glassEnabled,
                            onCheckedChange = {
                                GlassTheme.setEnabled(activity, it)
                                epoch++
                            },
                        )
                    }
                }
            }
            item {
                SettingsGlassCard(glassEnabled) {
                    Text("Player", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Accent, modifier = Modifier.padding(bottom = 4.dp))
                    listOf(
                        "resume" to "Resume playback",
                        "background" to "Background audio",
                        "auto_pip" to "Auto PiP",
                    ).forEach { (key, title) ->
                        epoch
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(title, modifier = Modifier.weight(1f), fontSize = 14.5.sp)
                            Switch(
                                prefs.getBoolean(key, key != "background"),
                                {
                                    prefs.edit().putBoolean(key, it).apply()
                                    epoch++
                                },
                            )
                        }
                    }
                    Text("Seek step", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
                    Row {
                        listOf(5, 10, 15, 30).forEach { seconds ->
                            FilterChip(
                                selected = prefs.getInt("double_tap", 10) == seconds,
                                onClick = {
                                    prefs.edit().putInt("double_tap", seconds).apply()
                                    epoch++
                                },
                                label = { Text("${seconds}s") },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                    Text("Repeat", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
                    Row {
                        listOf("off", "one", "all").forEach { mode ->
                            FilterChip(
                                selected = prefs.getString("repeat", "off") == mode,
                                onClick = {
                                    prefs.edit().putString("repeat", mode).apply()
                                    epoch++
                                },
                                label = { Text(mode.replaceFirstChar { it.uppercase() }) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                    Text("Decoder", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
                    Row {
                        listOf("Auto", "Hardware", "Software").forEach { mode ->
                            FilterChip(
                                selected = prefs.getString("decoder", "Auto") == mode,
                                onClick = {
                                    prefs.edit().putString("decoder", mode).apply()
                                    epoch++
                                },
                                label = { Text(mode) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                }
            }
            item {
                SettingsGlassCard(glassEnabled) {
                    Text("Subtitles & Cloud", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Accent)
                    val aiReady = SpeechToTextClient.activeGroqKey(activity).isNotBlank() ||
                        SpeechToTextClient.activeDeepgramKey(activity).isNotBlank()
                    val subReady = OpenSubtitlesClient.activeApiKey(activity).isNotBlank() ||
                        OnlineSubtitlesClient.activeSubDlKey(activity).isNotBlank() ||
                        OnlineSubtitlesClient.activeSubSourceKey(activity).isNotBlank() ||
                        OnlineSubtitlesClient.activeTmdbKey(activity).isNotBlank()
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (aiReady && subReady) "Cloud keys: Active"
                            else if (aiReady || subReady) "Partial keys configured"
                            else "Keys needed",
                            color = Muted,
                            fontSize = 13.sp,
                        )
                        TextButton(onClick = { showApiKeys = true }) { Text("API Keys", color = Accent) }
                    }
                }
            }
            item {
                SettingsGlassCard(glassEnabled) {
                    Text("Library", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Accent)
                    TextButton(onClick = { activity.reload() }) { Text("Rescan", color = Color.White) }
                    TextButton(onClick = { activity.requestAccess() }) { Text("Permissions", color = Color.White) }
                    TextButton(onClick = { activity.openFolder.launch(null) }) { Text("Add folder", color = Color.White) }
                    TextButton(
                        onClick = {
                            prefs.edit().remove("tree").apply()
                            activity.reload()
                            epoch++
                        }
                    ) { Text("Clear added folder", color = Color.White) }
                    TextButton(
                        onClick = {
                            prefs.edit().remove("hidden").apply()
                            activity.reload()
                            epoch++
                        }
                    ) { Text("Restore hidden folders", color = Color.White) }
                    TextButton(
                        onClick = {
                            Store.clearDismissedRecentAdded(activity)
                            activity.toast("Restored")
                            epoch++
                        }
                    ) { Text("Restore recent strip", color = Color.White) }
                }
            }
            item {
                SettingsGlassCard(glassEnabled) {
                    Text("MS PLAYER ${BuildConfig.VERSION_NAME}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "By SRIRAM S & ANTIGRAVITY",
                        color = Cyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text("LibVLC 3.7.7", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    TextButton(
                        onClick = { about = true },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    ) { Text("Open-source licenses", color = Accent) }
                }
            }
        }
    }
    var licenseChoice by remember { mutableStateOf("Notices") }
    val licenseText by
        produceState("", about, licenseChoice) {
            if (about)
                value =
                    withContext(Dispatchers.IO) {
                        val file =
                            when (licenseChoice) {
                                "LGPL" -> "LGPL-2.1.txt"
                                "Apache" -> "Apache-2.0.txt"
                                else -> "Third-party-notices.txt"
                            }
                        activity.assets.open("licenses/$file").bufferedReader().use {
                            it.readText()
                        }
                    }
        }
    if (showApiKeys) {
        ApiKeysDialog(
            activity = activity,
            inPlayer = false,
            onDismiss = {
                showApiKeys = false
                epoch++
            },
        )
    }
    if (about)
        LiquidGlassAlertDialog(
            onDismissRequest = { about = false },
            title = { Text("Licenses") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row {
                        listOf("Notices", "LGPL", "Apache").forEach { choice ->
                            TextButton(onClick = { licenseChoice = choice }) {
                                Text(choice, color = if (licenseChoice == choice) Accent else Color.White)
                            }
                        }
                    }
                    Text(licenseText, fontSize = 11.5.sp)
                }
            },
            confirmButton = { TextButton(onClick = { about = false }) { Text("Close", color = Accent) } },
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
    var aspectOption by remember { mutableStateOf(PlaybackRules.AspectOption.BEST_FIT) }
    var dragPosition by remember { mutableStateOf<Long?>(null) }
    var attachedLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    val latest by rememberUpdatedState(state)
    LaunchedEffect(state.item?.uri, state.videoWidth, state.videoHeight, activity.inPip) { activity.videoOrientation(state) }
    LaunchedEffect(controls, state.subtitleTrack, state.plainSubtitle, subtitleStyle) { service.updateSubtitlePlacement(controls && !activity.inPip) }
    val audioManager = remember { activity.getSystemService(AudioManager::class.java) }
    val scope = rememberCoroutineScope()
    var feedbackJob by remember { mutableStateOf<Job?>(null) }
    fun hint(value: PlayerFeedback) {
        feedback = value
        feedbackJob?.cancel()
        feedbackJob =
            scope.launch {
                delay(1200)
                feedback = null
            }
    }
    LaunchedEffect(touched, state.playing, menu, locked) {
        if (state.playing && menu == null && !locked) {
            delay(4000)
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
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context -> VLCVideoLayout(context).also { attachedLayout = it; service.attachVideo(it) } },
            modifier = Modifier.fillMaxSize(),
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
            Box(Modifier.fillMaxSize().videoGestures(
                activity, service, locked, menu == null && !activity.inPip,
                aspectOption.label, zoom, { zoom = it }, {
                    controls = !controls; touched = SystemClock.uptimeMillis()
                }, { dragPosition = it }, { value ->
                    feedbackJob?.cancel()
                    feedback = value
                    if (value != null && value !is PlayerFeedback.Speed) feedbackJob = scope.launch { delay(700); feedback = null }
                }))
            if (locked) {
                val unlockSrc = remember { MutableInteractionSource() }
                IconButton(
                    onClick = {
                        locked = false
                        touch()
                    },
                    interactionSource = unlockSrc,
                    modifier =
                        Modifier.align(Alignment.BottomStart)
                            .safeDrawingPadding()
                            .padding(20.dp)
                            .iosLiquidPress(unlockSrc, scaleDown = 0.88f)
                            .background(Color.Black.copy(alpha = .6f), CircleShape),
                ) {
                    Icon(Icons.Default.Lock, "Unlock controls")
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
                                Icon(Icons.Default.ArrowBack, "Back")
                            }
                            val displayTitle = state.item?.title?.substringBeforeLast('.') ?: ""
                            Text(
                                displayTitle,
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
                                Icon(Icons.Default.PictureInPictureAlt, "Picture-in-picture")
                            }
                            IconButton(
                                onClick = {
                                    service.refreshTracks()
                                    openPlayerMenu("Audio")
                                }
                            ) {
                                Icon(Icons.Default.MusicNote, "Audio tracks")
                            }
                            IconButton(
                                onClick = {
                                    service.refreshTracks()
                                    openPlayerMenu("Subtitles")
                                }
                            ) {
                                Icon(Icons.Default.Subtitles, "Subtitles")
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
                                Icon(Icons.Default.MoreVert, "Player options")
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
                                Text("${state.rate}×", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                        PlayerTimeline(service, dragPosition, { dragPosition = it }, { touch() })
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
                                Icon(Icons.Default.LockOpen, "Lock controls")
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
                                Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(34.dp))
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    aspectOption = aspectOption.next()
                                    zoom = 1f
                                    service.setAspectMode(aspectOption)
                                    touch()
                                    hint(PlayerFeedback.AspectRatio(aspectOption.label))
                                },
                                interactionSource = aspectSrc,
                                modifier = Modifier.iosLiquidPress(aspectSrc, scaleDown = 0.88f),
                            ) {
                                Icon(Icons.Default.AspectRatio, "Aspect ratio")
                            }
                            IconButton(
                                onClick = { activity.rotateVideo() },
                                interactionSource = rotSrc,
                                modifier = Modifier.iosLiquidPress(rotSrc, scaleDown = 0.88f),
                            ) {
                                Icon(Icons.Default.ScreenRotation, "Rotate screen")
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
            Column(
                Modifier.align(Alignment.Center)
                    .padding(30.dp)
                    .frostedGlassChrome(glassEnabled, RoundedCornerShape(18.dp), fallbackColor = Panel)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error)
                TextButton(onClick = { service.decoder("Software") }) {
                    Text("Use SW decoder", color = Accent)
                }
                TextButton(onClick = { activity.exitPlayer() }) { Text("Back", color = Color.LightGray) }
            }
        }
    }
    if (menu in listOf("Audio", "Subtitles", "Audio effects") && !activity.inPip) {
        PlayerSidePanel(if (menu == "Audio") "Audio Track" else if (menu == "Subtitles") "Subtitle" else "Audio", { closePlayerMenu() }) {
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
                        if (state.selectedSubtitleTracks.size > 1) {
                            Badge("${state.selectedSubtitleTracks.size} active", color = Color(0xFF1976D2))
                        }
                    }
                    TextButton(
                        colors = ButtonDefaults.textButtonColors(contentColor = Cyan),
                        onClick = { openPlayerMenu("OnlineSubtitles") }
                    ) {
                        Icon(Icons.Default.CloudDownload, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Online Subtitles")
                    }
                    Spacer(Modifier.height(6.dp))
                    val validSubs = state.subtitles.filter { it.first >= 0 }
                    if (validSubs.isNotEmpty()) {
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
                                validSubs.forEach { (id, name) ->
                                    val isSelected = id in state.selectedSubtitleTracks
                                    TrackChoice(name, isSelected, subtitle = true) {
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
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF64B5F6)),
                        onClick = { openPlayerMenu("AutoSub") }
                    ) {
                        Icon(Icons.Default.AutoAwesome, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Auto-Generate (AI)")
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Translate, null, tint = Cyan, modifier = Modifier.size(18.dp))
                        Text("Translate", color = Color.White.copy(alpha = 0.88f), fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                        listOf(
                            SubtitleTranslator.TargetLanguage.ENGLISH,
                            SubtitleTranslator.TargetLanguage.TAMIL,
                        ).forEach { targetLang ->
                            val trSrc = remember(targetLang) { MutableInteractionSource() }
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .frostedGlassChrome(
                                        enabled = glassEnabled,
                                        shape = RoundedCornerShape(10.dp),
                                        tint = Color(0x5212243A),
                                    )
                                    .iosLiquidPress(trSrc, scaleDown = 0.93f)
                                    .clickable(interactionSource = trSrc, indication = ripple()) {
                                        if (service.activeSubtitleCues().isEmpty()) {
                                            activity.toast("Load .srt or Auto-Generate first")
                                        } else {
                                            service.translateActiveSubtitles(context, targetLang)
                                            openPlayerMenu("AutoSub")
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Text(targetLang.label, color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
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
            onDismiss = { closePlayerMenu() }
        )
    } else if (menu == "OnlineSubtitles" && !activity.inPip) {
        OnlineSubtitlesDialog(
            service = service,
            activity = activity,
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
    } else if (menu != null && !activity.inPip)
        PlayerMenuDialog(
            onDismissRequest = {
                closePlayerMenu()
            },
            title = { Text(menu!!, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    when (menu) {
                        "Speed" ->
                            listOf(.25f, .5f, 1f, 1.5f, 2f, 2.5f, 3f, 4f).forEach { rate ->
                                val selectedRate = kotlin.math.abs(state.rate - rate) < 0.02f
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selectedRate) Accent.copy(alpha = 0.22f) else Color.Transparent)
                                        .clickable {
                                            service.rate(rate)
                                            closePlayerMenu()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "${rate}×",
                                        color = if (selectedRate) Accent else Color.White,
                                        fontWeight = if (selectedRate) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 15.sp,
                                    )
                                    if (selectedRate) {
                                        Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        "Options" -> {
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
fun PlayerTimeline(service: PlaybackService, dragPosition: Long?, onDrag: (Long?) -> Unit, onTouch: () -> Unit) {
    val state by service.timeline.collectAsState()
    // Own the commit value: a tap can finish before the caller recomposes.
    var sliderTarget by remember(service) { mutableStateOf<Long?>(null) }
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
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).onGloballyPositioned { service.subtitleControlsTop(it.positionInWindow().y) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(durationText(sliderTarget ?: dragPosition ?: state.position), fontSize = 14.sp)
            Slider(
                thumb = {
                    Box(
                        Modifier.size(18.dp)
                            .background(
                                if (state.seekable) Accent else Muted,
                                CircleShape,
                            )
                    )
                },
                track = { slider ->
                    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                        val y = size.height / 2
                        drawLine(
                            Color.Gray,
                            androidx.compose.ui.geometry.Offset(0f, y),
                            androidx.compose.ui.geometry.Offset(size.width, y),
                            strokeWidth = 3.dp.toPx(),
                        )
                        val fraction =
                            ((slider.value - slider.valueRange.start) /
                                    (slider.valueRange.endInclusive -
                                        slider.valueRange.start))
                                .coerceIn(0f, 1f)
                        drawLine(
                            Accent,
                            androidx.compose.ui.geometry.Offset(0f, y),
                            androidx.compose.ui.geometry.Offset(
                                size.width * fraction,
                                y,
                            ),
                            strokeWidth = 3.dp.toPx(),
                        )
                    }
                },
                value =
                    (sliderTarget ?: dragPosition ?: state.position)
                        .toFloat()
                        .coerceIn(0f, state.duration.coerceAtLeast(1).toFloat()),
                onValueChange = {
                    sliderTarget = it.toLong()
                    onDrag(it.toLong())
                    onTouch()
                },
                onValueChangeFinished = {
                    sliderTarget?.let { service.seek(it) }
                    sliderTarget = null
                    onDrag(null)
                },
                valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(),
                enabled = state.seekable,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { contentDescription = "Playback timeline" },
                colors =
                    SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = Color.Gray,
                    ),
            )
            Text(durationText(state.duration), fontSize = 14.sp)
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
    val playerState by service.state.collectAsState()
    val controlsTopPx by service.subtitleControlsTopPx.collectAsState()
    val revision by service.subtitleStyleRevision.collectAsState()
    val context = LocalContext.current
    val style = remember(service, revision) { SubtitleStyle.load(context) }
    var totalDragX by remember { mutableFloatStateOf(0f) }
    var totalDragY by remember { mutableFloatStateOf(0f) }
    val hasActiveSub = !subtitleText.isNullOrBlank()

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
                                totalDragY += pan.y
                                if (kotlin.math.abs(totalDragX) > 80f) {
                                    service.seekCue(totalDragX > 0)
                                    totalDragX = 0f
                                }
                                if (kotlin.math.abs(totalDragY) > 50f) {
                                    val deltaPercent = (-totalDragY / 25f).toInt()
                                    val newMargin = (style.bottomMargin + deltaPercent).coerceIn(0, 40)
                                    if (newMargin != style.bottomMargin) {
                                        val updated = style.copy(bottomMargin = newMargin)
                                        updated.save(context)
                                        service.subtitleStyle(updated)
                                    }
                                    totalDragY = 0f
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
                        dockTop = style.secondaryTop,
                        liftAbovePrimarySpu = playerState.hasPrimaryHwSubtitle,
                        controlsBottomReservedPx = controlsTopPx,
                    )
                    setSubtitleText(subtitleText)
                }
            },
            update = { view ->
                view.applyStyle(
                    style = style,
                    isControlsVisible = controlsVisible,
                    dockTop = style.secondaryTop,
                    liftAbovePrimarySpu = playerState.hasPrimaryHwSubtitle,
                    controlsBottomReservedPx = controlsTopPx,
                )
                view.setSubtitleText(subtitleText)
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
    var selectedLang by remember { mutableStateOf(OpenSubtitlesClient.languageOptions.first()) }
    var selectedProvider by remember { mutableStateOf<OnlineSubtitlesClient.OnlineProvider?>(null) }
    var movieHash by remember(currentItem?.uri) { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<OnlineSubtitlesClient.SearchSummary?>(null) }
    var searching by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showKeys by remember { mutableStateOf(false) }
    var keyEpoch by remember { mutableIntStateOf(0) }
    val hasAnySubKey = remember(keyEpoch) {
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
                    Row(
                        Modifier.weight(1f),
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
                    if (!movieHash.isNullOrBlank()) {
                        Badge("Hash", color = Color(0xFF1B5E20))
                    }
                    TextButton(
                        onClick = { showKeys = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("API Keys", color = Accent, fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }

                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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

                    summary?.tmdbMatch?.let { match ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Badge(
                                "TMDB · ${match.title}${match.year?.let { " ($it)" } ?: ""}",
                                color = Color(0xFF0E4D7A),
                            )
                            if (initialHints.season != null && initialHints.episode != null) {
                                Badge(
                                    String.format(java.util.Locale.ROOT, "S%02dE%02d", initialHints.season, initialHints.episode),
                                    color = Color(0xFF283546),
                                )
                            }
                        }
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OpenSubtitlesClient.languageOptions.forEach { lang ->
                            FilterChip(
                                selected = selectedLang == lang,
                                onClick = {
                                    selectedLang = lang
                                    runSearch(lang = lang)
                                },
                                label = { Text(lang.label, fontSize = 12.sp) },
                            )
                        }
                    }

                    if (summary != null) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = selectedProvider == null,
                                onClick = { selectedProvider = null },
                                label = { Text("All (${allCandidates.size})", fontSize = 11.5.sp) },
                            )
                            OnlineSubtitlesClient.OnlineProvider.entries.forEach { prov ->
                                val count = allCandidates.count { it.provider == prov }
                                if (count > 0 || prov in summary!!.activeProviders) {
                                    FilterChip(
                                        selected = selectedProvider == prov,
                                        onClick = { selectedProvider = if (selectedProvider == prov) null else prov },
                                        label = { Text("${prov.badge} ($count)", fontSize = 11.5.sp) },
                                    )
                                }
                            }
                        }
                    }

                    errorMsg?.let { err ->
                        Text(err, color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }

                    if (searching) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
                            Text("Searching...", color = Color.White, fontSize = 13.sp)
                        }
                    } else if (summary != null && visibleCandidates.isEmpty() && errorMsg == null) {
                        Text("No matches found", color = Muted, fontSize = 12.5.sp)
                    }

                    visibleCandidates.take(35).forEach { cand ->
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
                                    if (cand.hashMatch) Badge("Hash", color = Color(0xFF2E7D32))
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
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                            Text("Browser", color = Cyan, fontSize = 12.5.sp, maxLines = 1, softWrap = false)
                        }
                        TextButton(
                            onClick = {
                                onDismiss()
                                activity.subtitleFile.launch(arrayOf("*/*"))
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(Icons.Default.FolderZip, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Import", color = Color.White, fontSize = 12.5.sp, maxLines = 1, softWrap = false)
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = Color.LightGray, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
    if (showKeys) {
        ApiKeysDialog(
            activity = activity,
            inPlayer = true,
            onDismiss = {
                showKeys = false
                keyEpoch++
            },
        )
    }
}

@Composable
fun AutoSubDialog(
    service: PlaybackService,
    activity: MainActivity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val glassEnabled by rememberGlassEnabled()
    val subStatus by service.autoSubStatus.collectAsState()
    var showKeys by remember { mutableStateOf(false) }
    var keyEpoch by remember { mutableIntStateOf(0) }
    var directEnglish by rememberSaveable { mutableStateOf(false) }
    val primaryActive = remember(keyEpoch) { SpeechToTextClient.activeGroqKey(context).isNotBlank() }
    val backupActive = remember(keyEpoch) { SpeechToTextClient.activeDeepgramKey(context).isNotBlank() }
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
                    Text("AI Subtitles", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
                                selected = !directEnglish,
                                onClick = { directEnglish = false },
                                label = { Text("Original speech", fontSize = 12.sp) },
                            )
                            FilterChip(
                                selected = directEnglish,
                                onClick = { directEnglish = true },
                                label = { Text("Speech → English", fontSize = 12.sp) },
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
                                    Modifier.fillMaxWidth(),
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
                                    listOf(
                                        SubtitleTranslator.TargetLanguage.ENGLISH,
                                        SubtitleTranslator.TargetLanguage.TAMIL,
                                    ).forEach { targetLang ->
                                        val trSrc = remember(targetLang) { MutableInteractionSource() }
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
                                                    service.translateActiveSubtitles(context, targetLang)
                                                }
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                        ) {
                                            Text("→ ${targetLang.label}", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (!isProcessing) {
                        TextButton(onClick = { showKeys = true }) {
                            Text("API Keys", color = Accent)
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
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
                                    service.startAutoSubtitles(context, translateToEnglish = directEnglish)
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
    if (showKeys) {
        ApiKeysDialog(
            activity = activity,
            inPlayer = true,
            onDismiss = {
                showKeys = false
                keyEpoch++
            },
        )
    }
}

@Composable
fun ApiKeysDialog(
    activity: MainActivity,
    inPlayer: Boolean,
    onDismiss: () -> Unit,
) {
    val prefs = remember { Store.prefs(activity) }
    val glassEnabled by rememberGlassEnabled()
    var primaryInput by remember { mutableStateOf(prefs.getString("groq_api_key", "") ?: "") }
    var backupInput by remember { mutableStateOf(prefs.getString("deepgram_api_key", "") ?: "") }
    var openSubInput by remember { mutableStateOf(prefs.getString("opensubtitles_api_key", "") ?: "") }
    var subDlInput by remember { mutableStateOf(prefs.getString("subdl_api_key", "") ?: "") }
    var subSourceInput by remember { mutableStateOf(prefs.getString("subsource_api_key", "") ?: "") }
    var wyzieInput by remember { mutableStateOf(prefs.getString("wyzie_api_key", "") ?: "") }
    var tmdbInput by remember { mutableStateOf(prefs.getString("tmdb_api_key", "") ?: "") }

    val hasBundledPrimary = SpeechToTextClient.DEFAULT_GROQ_KEY.isNotBlank()
    val hasBundledBackup = SpeechToTextClient.DEFAULT_DEEPGRAM_KEY.isNotBlank()
    val hasBundledOpenSub = OpenSubtitlesClient.DEFAULT_API_KEY.isNotBlank()
    val hasBundledSubDl = OnlineSubtitlesClient.DEFAULT_SUBDL_KEY.isNotBlank()
    val hasBundledSubSource = OnlineSubtitlesClient.DEFAULT_SUBSOURCE_KEY.isNotBlank()
    val hasBundledWyzie = OnlineSubtitlesClient.DEFAULT_WYZIE_KEY.isNotBlank()
    val hasBundledTmdb = OnlineSubtitlesClient.DEFAULT_TMDB_KEY.isNotBlank()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        if (inPlayer) ImmersivePlayerDialog()
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            val maxH = (maxHeight - 16.dp).coerceAtLeast(250.dp)
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("API Keys", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Saved locally", color = Muted, fontSize = 11.5.sp)
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = primaryInput,
                        onValueChange = { primaryInput = it },
                        label = { Text("Primary AI (Groq)") },
                        placeholder = { Text(if (hasBundledPrimary) "Built-in active" else "Enter key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = backupInput,
                        onValueChange = { backupInput = it },
                        label = { Text("Backup AI (Deepgram)") },
                        placeholder = { Text(if (hasBundledBackup) "Built-in active" else "Enter key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = openSubInput,
                        onValueChange = { openSubInput = it },
                        label = { Text("OpenSubtitles") },
                        placeholder = { Text(if (hasBundledOpenSub) "Built-in active" else "Enter key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = subDlInput,
                        onValueChange = { subDlInput = it },
                        label = { Text("SubDL") },
                        placeholder = { Text(if (hasBundledSubDl) "Built-in active" else "Enter key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = subSourceInput,
                        onValueChange = { subSourceInput = it },
                        label = { Text("SubSource") },
                        placeholder = { Text(if (hasBundledSubSource) "Built-in active" else "Enter key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = wyzieInput,
                        onValueChange = { wyzieInput = it },
                        label = { Text("Wyzie") },
                        placeholder = { Text(if (hasBundledWyzie) "Built-in active" else "Enter key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = tmdbInput,
                        onValueChange = { tmdbInput = it },
                        label = { Text("TMDB") },
                        placeholder = { Text(if (hasBundledTmdb) "Built-in active" else "Enter key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Color.LightGray) }
                    Spacer(Modifier.width(8.dp))
                    val saveSrc = remember { MutableInteractionSource() }
                    Button(
                        onClick = {
                            prefs.edit().apply {
                                val p = primaryInput.trim()
                                val b = backupInput.trim()
                                if (p.isEmpty()) remove("groq_api_key") else putString("groq_api_key", p)
                                if (b.isEmpty()) remove("deepgram_api_key") else putString("deepgram_api_key", b)
                            }.apply()
                            OpenSubtitlesClient.saveApiKey(activity, openSubInput)
                            OnlineSubtitlesClient.saveSubDlKey(activity, subDlInput)
                            OnlineSubtitlesClient.saveSubSourceKey(activity, subSourceInput)
                            OnlineSubtitlesClient.saveWyzieKey(activity, wyzieInput)
                            OnlineSubtitlesClient.saveTmdbKey(activity, tmdbInput)
                            activity.toast("Saved")
                            onDismiss()
                        },
                        interactionSource = saveSrc,
                        modifier = Modifier.iosLiquidPress(saveSrc, scaleDown = 0.93f),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                    ) {
                        Text("Save", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
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
