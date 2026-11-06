@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
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
import org.videolan.libvlc.MediaPlayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
                                val extension =
                                    name.substringAfterLast('.', "srt").lowercase().takeIf {
                                        it in
                                            listOf("srt", "ass", "ssa", "vtt", "sub", "idx", "sup")
                                    } ?: "srt"
                                val target =
                                    File(
                                        cacheDir,
                                        "subtitle-${System.currentTimeMillis()}.$extension",
                                    )
                                contentResolver.openInputStream(uri)?.use { input ->
                                    target.outputStream().use { out ->
                                        val buffer = ByteArray(8192)
                                        var total = 0L
                                        while (true) {
                                            val n = input.read(buffer)
                                            if (n < 0) break
                                            total += n
                                            if (total > 32 * 1024 * 1024)
                                                error("Subtitle exceeds 32 MB")
                                            out.write(buffer, 0, n)
                                        }
                                    }
                                } ?: error("Cannot read subtitle")
                                target
                            }
                        }
                    result.fold(
                        {
                            if (service?.addSubtitle(it.absolutePath) != true)
                                toast("The engine could not load this subtitle.")
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        showPlayer =
            savedInstanceState?.getBoolean("player") ?: intent.getBooleanExtra("show_player", false)
        pendingUri = intent.data
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data != null)
            lifecycleScope.launch {
                val item = Library.opened(this@MainActivity, intent.data!!)
                startMedia(listOf(item), item)
            }
        else if (intent.getBooleanExtra("show_player", false)) showPlayer = true
    }

    override fun onResume() {
        super.onResume()
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

@Composable
fun App(activity: MainActivity) {
    val service = activity.service
    val metadata = remember(service) { (service?.state ?: activity.emptyState).map { it.copy(position = 0) }.distinctUntilChanged() }
    val state by metadata.collectAsState(initial = service?.state?.value ?: PlayerState())
    LaunchedEffect(state.closed) { if (state.closed) activity.closeApp() }
    if (activity.showPlayer && service != null && state.item != null) {
        PlayerScreen(activity, service, state)
        return
    }
    var tab by rememberSaveable { mutableStateOf("Folders") }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("Name") }
    var sortMenu by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var detail by remember { mutableStateOf<MediaItem?>(null) }
    var playlistItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var playlistDialog by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("Favorites") }
    var activePlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    var folderAction by remember { mutableStateOf<String?>(null) }
    var streamDialog by remember { mutableStateOf(false) }
    var streamUrl by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<MediaItem?>(null) }
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<SavedMedia>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<String>>(emptyList()) }
    var playlistContent by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(tab, refresh, activePlaylist) {
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
                searching = false
                search = ""
            }
            folder != null -> folder = null
            else -> activePlaylist = null
        }
    }
    val hidden = Store.prefs(activity).getStringSet("hidden", emptySet()).orEmpty()
    val raw =
        when (tab) {
            "Audio" -> activity.audios
            "History" -> history.map { saved ->
                (activity.audios + activity.videos).firstOrNull { it.uri == saved.uri }
                    ?: MediaItem(saved.uri, saved.title, duration = saved.position,
                        audio = PlaybackRules.audioFile(null, saved.title) || PlaybackRules.audioFile(null, Uri.parse(saved.uri).lastPathSegment ?: ""))
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
                    refresh++
                }
            "Delete" -> deleteTarget = item
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (folder != null || activePlaylist != null)
                IconButton(
                    onClick = {
                        folder = null
                        activePlaylist = null
                    }
                ) {
                    Icon(Icons.Default.ArrowBack, "Back")
                }
            if (searching)
                OutlinedTextField(
                    search,
                    { search = it },
                    Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Search local media") },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                searching = false
                                search = ""
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
                    fontSize = 26.sp,
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
                    }
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
                    }
                ) {
                    Icon(Icons.Default.PlayArrow, "Play selected")
                }
                IconButton(onClick = { selected = emptySet() }) {
                    Icon(Icons.Default.Close, "Clear selection")
                }
            } else if (tab != "Settings") {
                IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Search") }
                IconButton(onClick = { grid = !grid }) {
                    Icon(
                        if (grid) Icons.Default.ViewList else Icons.Default.GridView,
                        "Change layout",
                    )
                }
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.Default.MoreVert, "Library options")
                    }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        listOf("Name", "Date", "Size", "Duration").forEach { label ->
                            DropdownMenuItem(
                                text = { Text("Sort by $label") },
                                onClick = {
                                    sort = label
                                    sortMenu = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Rescan") },
                            onClick = {
                                Library.retryThumbnails()
                                activity.reload()
                                refresh++
                                sortMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Open file") },
                            onClick = {
                                activity.openFile.launch(arrayOf("*/*"))
                                sortMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Choose folder") },
                            onClick = {
                                activity.openFolder.launch(null)
                                sortMenu = false
                            },
                        )
                        DropdownMenuItem(text = { Text("Exit") }, onClick = { activity.closeApp() })
                        DropdownMenuItem(
                            text = { Text("Media permissions") },
                            onClick = {
                                activity.requestAccess()
                                sortMenu = false
                            },
                        )
                    }
                }
            }
        }
        if (activity.scanning) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                tab == "Settings" -> SettingsScreen(activity)
                tab == "Playlists" && activePlaylist == null -> {
                    if (playlists.isEmpty())
                        EmptyLibrary(
                            "Your playlists",
                            "Add a video or audio file using its menu.",
                            Icons.Default.QueueMusic,
                        )
                    else
                        LazyColumn {
                            items(playlists) { name ->
                                ListItem(
                                    headlineContent = { Text(name) },
                                    leadingContent = {
                                        Icon(Icons.Default.QueueMusic, null, tint = Accent)
                                    },
                                    modifier = Modifier.clickable { activePlaylist = name },
                                    colors = ListItemDefaults.colors(containerColor = Color.Black),
                                )
                            }
                        }
                }
                tab == "Folders" && folder == null && !searching -> {
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
                        val recentVideos = remember(activity.videos) {
                            activity.videos.filter { !it.audio }.sortedByDescending { it.added.takeIf { a -> a > 0 } ?: it.modified }.take(8)
                        }
                        LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 18.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    QuickAction("Music", Icons.Default.Headphones) { tab = "Audio" }
                                    QuickAction("Playlists", Icons.Default.PlaylistPlay) {
                                        tab = "Playlists"
                                    }
                                    QuickAction("History", Icons.Default.History) {
                                        tab = "History"
                                    }
                                    QuickAction("Stream", Icons.Default.CloudQueue) {
                                        streamDialog = true
                                    }
                                    QuickAction("Open", Icons.Default.FileOpen) {
                                        activity.openFile.launch(arrayOf("*/*"))
                                    }
                                }
                            }
                            if (recentVideos.isNotEmpty()) {
                                item {
                                    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) {
                                        Text("Recently Added", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.padding(top = 4.dp)
                                        ) {
                                            items(recentVideos, key = { "recent_${it.uri}" }) { video ->
                                                Column(
                                                    Modifier.width(130.dp).clickable { play(video) }
                                                ) {
                                                    Box(Modifier.width(130.dp).height(74.dp)) {
                                                        Thumbnail(video, Modifier.fillMaxSize())
                                                        if (history.none { it.uri == video.uri }) {
                                                            Box(
                                                                Modifier.align(Alignment.TopStart).padding(4.dp)
                                                                    .background(Color(0xFFFF3B72), RoundedCornerShape(3.dp))
                                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                                            ) {
                                                                Text("NEW", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                            }
                                                        }
                                                    }
                                                    Text(
                                                        video.title,
                                                        fontSize = 12.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        color = Color.White,
                                                        modifier = Modifier.padding(top = 4.dp)
                                                    )
                                                }
                                            }
                                        }
                                        HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 6.dp), color = Color.White.copy(alpha = 0.08f))
                                    }
                                }
                            }
                            items(folders, key = { it.name }) { entry ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { folder = entry.name },
                                            onLongClick = { folderAction = entry.name },
                                        )
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.width(72.dp).height(54.dp)) {
                                        FolderThumbnail(entry, Modifier.fillMaxSize())
                                        val newCount =
                                            entry.items.count { row ->
                                                history.none { it.uri == row.uri }
                                            }
                                        if (newCount > 0)
                                            Box(
                                                Modifier.align(Alignment.TopEnd)
                                                    .size(24.dp)
                                                    .background(Color(0xFFFF3B72), CircleShape),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    if (newCount > 99) "99+" else "$newCount",
                                                    fontSize = 12.sp,
                                                    lineHeight = 14.sp,
                                                )
                                            }
                                    }
                                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                                        Text(
                                            entry.name.substringAfterLast('/'),
                                            fontSize = 18.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                "${entry.items.size} ${if(entry.items.size==1) "video" else "videos"}",
                                                fontSize = 14.sp,
                                                color = Color.LightGray,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Badge(sizeText(entry.size))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                items.isEmpty() ->
                    if (tab == "Audio") AccessEmpty(activity)
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
                        contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 90.dp),
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
                                activePlaylist != null,
                            )
                        }
                    }
                else ->
                    LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
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
                                activePlaylist != null,
                            )
                        }
                    }
            }
            if (
                state.item?.audio != true && tab != "Settings" &&
                    !(tab == "Playlists" && activePlaylist == null) &&
                    (activity.videos.isNotEmpty() || state.item != null)
            )
                FloatingActionButton(
                    onClick = {
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
                    },
                    containerColor = Accent,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).size(68.dp),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        "Resume playback",
                        modifier = Modifier.size(44.dp),
                    )
                }
            if (state.item?.audio == true && service != null) {
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Panel)
                    .clickable { activity.showPlayer = true; activity.immersive(true) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null, tint = Accent)
                    Text(state.item!!.title, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { service.toggle() }) { Icon(if(state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause") }
                    IconButton(onClick = { service.next() }) { Icon(Icons.Default.SkipNext, "Next track") }
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
                            tab = name
                            folder = null
                            activePlaylist = null
                            search = ""
                            searching = false
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
    folderAction?.let { name ->
        AlertDialog(
            onDismissRequest = { folderAction = null },
            title = { Text(name.substringAfterLast('/')) },
            text = {
                Text("Exclude this folder from the library? Its files will remain on your device.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        Store.prefs(activity).edit().putStringSet("hidden", hidden + name).apply()
                        folderAction = null
                        activity.reload()
                        refresh++
                    }
                ) {
                    Text("Exclude")
                }
            },
            dismissButton = { TextButton(onClick = { folderAction = null }) { Text("Cancel") } },
        )
    }
    if (detail != null) {
        val item = detail!!
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("File details") },
            text = {
                Column {
                    Text(item.title)
                    Spacer(Modifier.height(12.dp))
                    Text("${sizeText(item.size)} · ${durationText(item.duration)}")
                    if (item.height > 0) Text("${item.width} × ${item.height}")
                    Text(item.uri, fontSize = 12.sp, color = Muted)
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } },
        )
    }
    if (playlistDialog)
        AlertDialog(
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
                            refresh++
                            playlistDialog = false
                            selected = emptySet()
                            activity.toast("Added to ${playlistName.trim()}")
                        }
                    },
                ) {
                    Text("Add")
                }
            },
            dismissButton = { TextButton(onClick = { playlistDialog = false }) { Text("Cancel") } },
        )
    if (deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete media file") },
            text = { Text("Permanently delete \"${target.title}\" from storage?") },
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
                            refresh++
                            activity.toast("File deleted")
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5252))
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
    if (streamDialog) {
        AlertDialog(
            onDismissRequest = { streamDialog = false },
            title = { Text("Network Stream") },
            text = {
                Column {
                    Text("Enter a video or audio stream URL (HTTP, HTTPS, RTSP, RTMP):", fontSize = 13.sp, color = Muted)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = streamUrl,
                        onValueChange = { streamUrl = it },
                        label = { Text("Stream URL") },
                        placeholder = { Text("https://example.com/stream.m3u8") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = PlaybackRules.isStreamUrl(streamUrl),
                    onClick = {
                        val url = streamUrl.trim()
                        streamDialog = false
                        streamUrl = ""
                        activity.lifecycleScope.launch {
                            val item = Library.opened(activity, Uri.parse(url))
                            play(item)
                        }
                    }
                ) { Text("Play") }
            },
            dismissButton = { TextButton(onClick = { streamDialog = false }) { Text("Cancel") } }
        )
    }
}

fun Set<String>.toggle(value: String) = if (value in this) this - value else this + value

@Composable
fun QuickAction(label: String, icon: ImageVector, action: () -> Unit) {
    Column(
        Modifier.width(68.dp).clickable(onClick = action),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).background(Color(0xFF102331), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(28.dp))
        }
        Text(label, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp), maxLines = 1)
    }
}

@Composable
fun AccessEmpty(activity: MainActivity) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.FolderOpen, null, tint = Accent, modifier = Modifier.size(64.dp))
        Text(
            "Your media, on your device",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            "Allow access to browse your library, or select a local file.",
            color = Muted,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = { activity.requestAccess() }) { Text("Allow media access") }
        TextButton(onClick = { activity.openFile.launch(arrayOf("*/*")) }) { Text("Open a file") }
        TextButton(onClick = { activity.openFolder.launch(null) }) { Text("Choose a folder") }
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
        modifier =
            Modifier.clip(RoundedCornerShape(3.dp))
                .background(color)
                .padding(horizontal = 5.dp, vertical = 1.dp),
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
) {
    val activity = LocalActivity.current as? MainActivity
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
        Modifier.fillMaxWidth()
            .background(if (selected) Color(0xFF123044) else Color.Black)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(resolved, if(item.audio) Modifier.size(56.dp) else Modifier.width(96.dp).height(54.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            val isPlaying = activity?.service?.state?.value?.item?.uri == item.uri
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    color = if (isPlaying) Accent else if (seen) Muted else Color.White,
                    fontSize = 16.sp,
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
                        Text("NEW", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
) {
    Column(
        Modifier.background(if (selected) Color(0xFF123044) else Color.Black)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
    ) {
        Thumbnail(item, Modifier.fillMaxWidth().height(100.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.title,
                Modifier.weight(1f),
                fontSize = 14.sp,
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
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "File options") }
        DropdownMenu(expanded, { expanded = false }) {
            listOf("Playlist", "Share", "Details", "Delete").forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (action) {
                                "Playlist" -> "Add to playlist"
                                "Delete" -> "Delete file"
                                else -> action
                            },
                            color = if (action == "Delete") Color(0xFFFF5252) else Color.White
                        )
                    },
                    onClick = {
                        expanded = false
                        onAction(action)
                    },
                )
            }
            if (remove)
                DropdownMenuItem(
                    text = { Text("Remove from playlist") },
                    onClick = {
                        expanded = false
                        onAction("Remove")
                    },
                )
        }
    }
}

@Composable
fun SettingsScreen(activity: MainActivity) {
    val prefs = remember { Store.prefs(activity) }
    var epoch by remember { mutableIntStateOf(0) }
    var about by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    key(epoch) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Text(
                    "Playback",
                    fontSize = 18.sp,
                    color = Accent,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            listOf(
                    Triple("resume", "Resume playback", "Remember where you stopped"),
                    Triple(
                        "background",
                        "Background video audio",
                        "Continue video audio when leaving the app",
                    ),
                    Triple(
                        "auto_pip",
                        "Automatic picture-in-picture",
                        "Enter a small player when leaving playback",
                    ),
                )
                .forEach { (key, title, description) ->
                    item {
                        epoch
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(title)
                                Text(description, color = Muted, fontSize = 13.sp)
                            }
                            Switch(
                                prefs.getBoolean(key, key != "background"),
                                {
                                    prefs.edit().putBoolean(key, it).apply()
                                    epoch++
                                },
                            )
                        }
                    }
                }
            item {
                Text("Double-tap seek", modifier = Modifier.padding(top = 16.dp))
                Row {
                    listOf(5, 10, 15, 30).forEach { seconds ->
                        FilterChip(
                            selected = prefs.getInt("double_tap", 10) == seconds,
                            onClick = {
                                prefs.edit().putInt("double_tap", seconds).apply()
                                epoch++
                            },
                            label = { Text("${seconds}s") },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
            item {
                Text("Repeat", modifier = Modifier.padding(top = 12.dp))
                Row {
                    listOf("off", "one", "all").forEach { mode ->
                        FilterChip(
                            selected = prefs.getString("repeat", "off") == mode,
                            onClick = {
                                prefs.edit().putString("repeat", mode).apply()
                                epoch++
                            },
                            label = { Text(mode.replaceFirstChar { it.uppercase() }) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
            item {
                Text("Decoder preference", modifier = Modifier.padding(top = 12.dp))
                Row {
                    listOf("Auto", "Hardware", "Software").forEach { mode ->
                        FilterChip(
                            selected = prefs.getString("decoder", "Auto") == mode,
                            onClick = {
                                prefs.edit().putString("decoder", mode).apply()
                                epoch++
                            },
                            label = { Text(mode) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                Text(
                    "Hardware decoding depends on the device and file. Software decoding may use more battery.",
                    color = Muted,
                    fontSize = 13.sp,
                )
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 20.dp))
                Text("Library", color = Accent, fontSize = 18.sp)
                TextButton(onClick = { activity.reload() }) { Text("Rescan media") }
                TextButton(onClick = { activity.requestAccess() }) { Text("Manage media access") }
                TextButton(onClick = { activity.openFolder.launch(null) }) {
                    Text("Choose additional folder")
                }
                TextButton(
                    onClick = {
                        prefs.edit().remove("tree").apply()
                        activity.reload()
                        epoch++
                    }
                ) {
                    Text("Forget additional folder")
                }
                TextButton(
                    onClick = {
                        prefs.edit().remove("hidden").apply()
                        activity.reload()
                        epoch++
                    }
                ) {
                    Text("Restore excluded folders")
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 20.dp))
                Text("MS PLAYER ${BuildConfig.VERSION_NAME}", fontSize = 18.sp)
                Text("Offline multimedia player · LibVLC 3.7.7", color = Muted, fontSize = 13.sp)
                TextButton(onClick = { about = true }) { Text("Open-source notices") }
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
    if (about)
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("Open-source software") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row {
                        listOf("Notices", "LGPL", "Apache").forEach { choice ->
                            TextButton(onClick = { licenseChoice = choice }) { Text(choice) }
                        }
                    }
                    Text(licenseText, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { about = false }) { Text("Close") } },
        )
}

@Composable
fun PlayerScreen(activity: MainActivity, service: PlaybackService, state: PlayerState) {
    if (state.item?.audio == true) { MusicPlayerScreen(activity, service, state); return }
    var controls by activity::playerControlsVisible
    var locked by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    var subtitleStyle by remember { mutableStateOf(SubtitleStyle.load(activity)) }
    var feedback by remember { mutableStateOf<PlayerFeedback?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var aspect by remember { mutableStateOf("Fit") }
    var dragPosition by remember { mutableStateOf<Long?>(null) }
    var touched by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
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
    fun touch() {
        touched = SystemClock.uptimeMillis()
        controls = true
    }
    LaunchedEffect(touched, state.playing, menu, locked) {
        if (state.playing && menu == null && !locked) {
            delay(4000)
            controls = false
        }
    }
    BackHandler(!activity.inPip) {
        if (menu != null) { menu = null; touch() } else if (locked) {
            locked = false
            controls = true
        } else activity.exitPlayer()
    }
    DisposableEffect(service) {
        activity.immersive(true)
        onDispose {
            service.endTemporarySpeed()
            service.detachVideo()
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context -> VLCVideoLayout(context).also { service.attachVideo(it) } },
            modifier = Modifier.fillMaxSize(),
            update = { service.updateSubtitlePlacement(controls && !activity.inPip) },
            onRelease = { service.detachVideo() },
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
                aspect, zoom, { zoom = it }, {
                    controls = !controls; touched = SystemClock.uptimeMillis()
                }, { dragPosition = it }, { value ->
                    feedbackJob?.cancel()
                    feedback = value
                    if (value != null && value !is PlayerFeedback.Speed) feedbackJob = scope.launch { delay(700); feedback = null }
                }))
            PlayerFeedbackHost(feedback)
            if (locked)
                IconButton(
                    onClick = {
                        locked = false
                        touch()
                    },
                    modifier =
                        Modifier.align(Alignment.BottomStart)
                            .safeDrawingPadding()
                            .padding(20.dp)
                            .background(Color.Black.copy(alpha = .6f), CircleShape),
                ) {
                    Icon(Icons.Default.Lock, "Unlock controls")
                }
            AnimatedVisibility(
                visible = controls && !locked,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(200)),
            ) {
                Column(
                    Modifier.fillMaxSize()
                        .safeDrawingPadding()
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .85f), Color.Black.copy(alpha = .4f), Color.Transparent)))
                            .padding(bottom = 6.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { activity.exitPlayer() }) {
                                Icon(Icons.Default.ArrowBack, "Back")
                            }
                            Text(
                                state.item?.title ?: "",
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(
                                onClick = {
                                    service.refreshTracks()
                                    menu = "Audio"
                                    touch()
                                }
                            ) {
                                Icon(Icons.Default.MusicNote, "Audio tracks")
                            }
                            IconButton(
                                onClick = {
                                    service.refreshTracks()
                                    menu = "Subtitles"
                                    touch()
                                }
                            ) {
                                Icon(Icons.Default.Subtitles, "Subtitles")
                            }
                            val codecTag = state.videoCodec.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                            val decoderTag = if (state.decoder == "Software") "SW" else if (state.decoder == "Hardware") "HW" else "Auto"
                            TextButton(
                                onClick = {
                                    menu = "Decoder"
                                    touch()
                                }
                            ) {
                                Text(
                                    "$decoderTag$codecTag",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            IconButton(
                                onClick = {
                                    menu = "Options"
                                    touch()
                                }
                            ) {
                                Icon(Icons.Default.MoreVert, "Player options")
                            }
                        }
                        Row(
                            Modifier.padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    menu = "Speed"
                                    touch()
                                }
                            ) {
                                Text("${state.rate}×", color = Color.White)
                            }
                            IconButton(onClick = { menu = "Audio effects"; touch() }, modifier = Modifier.size(44.dp).background(Color(0xCC22579A), CircleShape)) {
                                Icon(Icons.Default.GraphicEq, "Audio effects", tint = Color.White)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .5f), Color.Black.copy(alpha = .9f))))
                            .padding(top = 8.dp)
                    ) {
                        PlayerTimeline(service, dragPosition, { dragPosition = it }, { touch() })
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = {
                                    locked = true
                                    controls = false
                                }
                            ) {
                                Icon(Icons.Default.LockOpen, "Lock controls")
                            }
                            Spacer(Modifier.weight(1f))
                            if (state.subtitleTrack >= 0) {
                                IconButton(onClick = { service.seekCue(false); touch() }) {
                                    Icon(Icons.Default.FastRewind, "Previous cue", tint = Cyan, modifier = Modifier.size(28.dp))
                                }
                            }
                            IconButton(
                                onClick = {
                                    service.previous()
                                    touch()
                                }
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
                                modifier = Modifier.padding(horizontal = 14.dp).size(60.dp),
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
                                }
                            ) {
                                Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(34.dp))
                            }
                            if (state.subtitleTrack >= 0) {
                                IconButton(onClick = { service.seekCue(true); touch() }) {
                                    Icon(Icons.Default.FastForward, "Next cue", tint = Cyan, modifier = Modifier.size(28.dp))
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    menu = "Aspect ratio"
                                    touch()
                                }
                            ) {
                                Icon(Icons.Default.AspectRatio, "Aspect ratio")
                            }
                            IconButton(onClick = { activity.enterPip() }) {
                                Icon(Icons.Default.PictureInPictureAlt, "Picture-in-picture")
                            }
                        }
                    }
                }
            }
        }
        if (state.buffering)
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
        state.error?.let { error ->
            Column(
                Modifier.align(Alignment.Center)
                    .padding(30.dp)
                    .background(Panel, RoundedCornerShape(12.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error)
                TextButton(onClick = { service.decoder("Software") }) {
                    Text("Retry with software decoding")
                }
                TextButton(onClick = { activity.exitPlayer() }) { Text("Back to library") }
            }
        }
    }
    if (menu in listOf("Audio", "Subtitles", "Audio effects") && !activity.inPip) {
        PlayerSidePanel(if (menu == "Audio") "Audio Track" else if (menu == "Subtitles") "Subtitle" else "Audio", { menu = null; touch() }) {
            var expanded by remember(menu) { mutableStateOf(false) }
            when (menu) {
                "Audio" -> {
                    state.audioTracks.filter { it.first >= 0 }.forEach { (id, name) ->
                        TrackChoice(name, id == state.audioTrack) { service.audioTrack(id, name) }
                    }
                    TrackChoice("Disable", state.audioTrack < 0) { service.audioTrack(-1, "Disable") }
                    TextButton(onClick = { expanded = !expanded }) { Text("Audio delay ${if (expanded) "▴" else "▾"}") }
                    if (expanded) DelayControls("Audio delay", state.audioDelay) { service.audioDelay(it) }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Muted.copy(alpha = .3f))
                    TextButton(onClick = { menu = "Audio effects" }) { Text("Audio Effect / Equalizer") }
                }
                "Subtitles" -> {
                    TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color.White), onClick = { activity.subtitleFile.launch(arrayOf("*/*")); menu = null }) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(12.dp)); Text("Open") }
                    state.subtitles.filter { it.first >= 0 }.forEach { (id, name) ->
                        TrackChoice(name, id == state.subtitleTrack, true) {
                            if (id == state.subtitleTrack) service.subtitleTrack(-1, "Disable") else service.subtitleTrack(id, name)
                        }
                    }
                    if (state.subtitles.none { it.first >= 0 }) Text("No subtitle tracks", color = Muted)
                    TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color.White), onClick = { expanded = !expanded }) { Icon(Icons.Default.Settings, null); Spacer(Modifier.width(12.dp)); Text("Settings ${if (expanded) "▴" else "▾"}") }
                    if (expanded) {
                        DelayControls("Subtitle delay", state.subtitleDelay) { service.subtitleDelay(it) }
                        TextButton(onClick = { menu = "Subtitle appearance" }) { Text("Subtitle appearance") }
                    }
                }
                "Audio effects" -> AudioEffectsControls(service)
            }
        }
    } else if (menu == "Subtitle appearance" && !activity.inPip) {
        SubtitleTextDialog(subtitleStyle, cancel={menu=null;touch()}, apply={ value ->
            service.subtitleStyle(value)
            subtitleStyle=value
            menu=null
            touch()
        })
    } else if (menu != null && !activity.inPip)
        PlayerMenuDialog(
            onDismissRequest = {
                menu = null
                touch()
            },
            title = { Text(menu!!) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when (menu) {
                        "Speed" ->
                            listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { rate ->
                                TextButton(
                                    onClick = {
                                        service.rate(rate)
                                        menu = null
                                        touch()
                                    }
                                ) {
                                    Text("${rate}×")
                                }
                            }
                        "Decoder" -> {
                            Text(
                                "Auto prefers hardware where supported. Software can recover some incompatible files.",
                                fontSize = 13.sp,
                            )
                            listOf("Auto", "Hardware", "Software").forEach { mode ->
                                TextButton(
                                    onClick = {
                                        service.decoder(mode)
                                        menu = null
                                    }
                                ) {
                                    Text(mode)
                                }
                            }
                        }
                        "Aspect ratio" ->
                            listOf("Fit", "Fill", "16:9", "4:3", "Original").forEach { mode ->
                                TextButton(
                                    onClick = {
                                        aspect = mode
                                        zoom = 1f
                                        service.player.setVideoScale(
                                            when (mode) {
                                                "Fill" -> MediaPlayer.ScaleType.SURFACE_FILL
                                                "16:9" -> MediaPlayer.ScaleType.SURFACE_16_9
                                                "4:3" -> MediaPlayer.ScaleType.SURFACE_4_3
                                                "Original" -> MediaPlayer.ScaleType.SURFACE_ORIGINAL
                                                else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
                                            }
                                        )
                                        menu = null
                                    }
                                ) {
                                    Text((if (aspect == mode) "✓ " else "") + mode)
                                }
                            }
                        "Options" -> {
                            TextButton(
                                onClick = {
                                    activity.rotateVideo()
                                    menu = null
                                }
                            ) {
                                Text("Rotate screen")
                            }
                            TextButton(
                                onClick = {
                                    service.seek(0)
                                    menu = null
                                }
                            ) {
                                Text("Restart")
                            }
                            TextButton(
                                onClick = {
                                    activity.closeApp()
                                    menu = null
                                }
                            ) {
                                Text("Exit")
                            }
                            var fastSeek by remember { mutableStateOf(Store.prefs(activity).getBoolean("fast_seek", true)) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(fastSeek, { fastSeek = it; Store.prefs(activity).edit().putBoolean("fast_seek", it).apply() })
                                Text("Fast seeking (nearest keyframe)")
                            }
                            var diagnostics by remember { mutableStateOf(Store.prefs(activity).getBoolean("diagnostics", false)) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(diagnostics, { diagnostics = it; service.diagnosticsEnabled(it) })
                                Text("Record playback diagnostics")
                            }
                            TextButton(onClick = { activity.diagnosticDocument.launch("MS-PLAYER-diagnostics.txt") }) { Text("Save local diagnostic report") }

                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        menu = null
                        touch()
                    }
                ) {
                    Text("Done")
                }
            },
        )
}

@Composable
fun DelayControls(label: String, value: Long, onChange: (Long) -> Unit) {
    Text("$label: ${value} ms", modifier = Modifier.padding(top = 12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange((value - 100).coerceAtLeast(-10000)) }) { Text("−100 ms") }
        TextButton(onClick = { onChange(0) }) { Text("Reset") }
        TextButton(onClick = { onChange((value + 100).coerceAtMost(10000)) }) { Text("+100 ms") }
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
    val revision by service.subtitleStyleRevision.collectAsState()
    val context = LocalContext.current
    val style = remember(service, revision) { SubtitleStyle.load(context) }

    if (!subtitleText.isNullOrBlank()) {
        AndroidView(
            factory = { ctx ->
                StrokeView(ctx).apply {
                    applyStyle(style, controlsVisible)
                    setSubtitleText(subtitleText)
                }
            },
            update = { view ->
                view.applyStyle(style, controlsVisible)
                view.setSubtitleText(subtitleText)
            },
            modifier = modifier,
        )
    }
}
