package app.msplayer.offline

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.room.Room
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia

data class MediaItem(
    val uri: String,
    val title: String,
    val folder: String = "Opened files",
    val size: Long = 0,
    val duration: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val added: Long = 0,
    val audio: Boolean = false,
    val modified: Long = 0,
)

data class MediaFolder(val name: String, val items: List<MediaItem>) {
    val size
        get() = items.sumOf { it.size }
}

data class MediaInfo(
    val duration: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val subtitles: List<String> = emptyList(),
    val audioCodecs: List<String> = emptyList(),
)

data class MediaStreamDetail(
    val index: Int,
    val type: String, // "Video", "Audio", "Subtitle"
    val title: String = "",
    val codec: String = "",
    val profile: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: String = "",
    val sampleRateHz: Int = 0,
    val channels: Int = 0,
    val language: String = "",
)

data class MediaDetailedInfo(
    val fileName: String,
    val location: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
    val subtitleSummary: String,
    val format: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val encodingSoftware: String,
    val streams: List<MediaStreamDetail>,
)

object Store {
    @Volatile private var instance: PlayerDatabase? = null

    fun db(context: Context): PlayerDatabase =
        instance
            ?: synchronized(this) {
                instance
                    ?: Room.databaseBuilder(
                            context.applicationContext,
                            PlayerDatabase::class.java,
                            "player.db",
                        )
                        .build()
                        .also { instance = it }
            }

    fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun recentStreams(context: Context): List<String> {
        val raw = prefs(context).getString("recent_streams_v1", "") ?: return emptyList()
        return raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
    }

    fun recordStream(context: Context, url: String) {
        val clean = url.trim()
        if (!PlaybackRules.isStreamUrl(clean)) return
        val updated = (listOf(clean) + recentStreams(context).filter { it != clean }).take(10)
        prefs(context).edit().putString("recent_streams_v1", updated.joinToString("\n")).apply()
    }

    fun removeStream(context: Context, url: String) {
        val updated = recentStreams(context).filter { it != url.trim() }
        prefs(context).edit().putString("recent_streams_v1", updated.joinToString("\n")).apply()
    }

    fun dismissedRecentAdded(context: Context): Set<String> =
        prefs(context).getStringSet("dismissed_recent_added_v1", emptySet()).orEmpty().toSet()

    fun dismissRecentAdded(context: Context, uri: String) {
        val updated = dismissedRecentAdded(context) + uri
        prefs(context).edit().putStringSet("dismissed_recent_added_v1", updated).apply()
    }

    fun dismissAllRecentAdded(context: Context, uris: Iterable<String>) {
        val updated = dismissedRecentAdded(context) + uris
        prefs(context).edit().putStringSet("dismissed_recent_added_v1", updated).apply()
    }

    fun clearDismissedRecentAdded(context: Context) {
        prefs(context).edit().remove("dismissed_recent_added_v1").apply()
    }

    fun favoriteSubtitleLanguages(context: Context): List<String> {
        val raw = prefs(context).getString("favorite_sub_langs_v1", null)
        if (raw != null) {
            return raw.split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct()
        }
        val sys = Locale.getDefault().language.trim().lowercase(Locale.ROOT)
        val supported = SubtitleTranslator.supportedLanguages.any { it.code == sys }
        return if (supported && sys != "en") listOf(sys, "en") else listOf("en")
    }

    fun saveFavoriteSubtitleLanguages(context: Context, codes: List<String>) {
        val clean = codes.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct()
        prefs(context).edit().putString("favorite_sub_langs_v1", clean.joinToString(",")).apply()
    }

    fun toggleFavoriteSubtitleLanguage(context: Context, code: String): List<String> {
        val norm = code.trim().lowercase(Locale.ROOT)
        if (norm.isBlank() || norm == "all") return favoriteSubtitleLanguages(context)
        val cur = favoriteSubtitleLanguages(context).toMutableList()
        if (norm in cur) cur.remove(norm) else cur.add(0, norm)
        saveFavoriteSubtitleLanguages(context, cur)
        return cur
    }

    fun favoriteAudioLanguages(context: Context): List<String> {
        val raw = prefs(context).getString("favorite_audio_langs_v1", null) ?: return emptyList()
        return raw.split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct()
    }

    fun saveFavoriteAudioLanguages(context: Context, codes: List<String>) {
        val clean = codes.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct()
        prefs(context).edit().putString("favorite_audio_langs_v1", clean.joinToString(",")).apply()
    }

    fun toggleFavoriteAudioLanguage(context: Context, code: String): List<String> {
        val norm = code.trim().lowercase(Locale.ROOT)
        if (norm.isBlank() || norm == "all") return favoriteAudioLanguages(context)
        val cur = favoriteAudioLanguages(context).toMutableList()
        if (norm in cur) cur.remove(norm) else cur.add(0, norm)
        saveFavoriteAudioLanguages(context, cur)
        return cur
    }

    fun continueWatchingEnabled(context: Context): Boolean =
        prefs(context).getBoolean("show_continue_watching", true)

    fun saveContinueWatchingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("show_continue_watching", enabled).apply()
    }

    fun subtitleStarKey(trackName: String, path: String? = null): String {
        val candidate = path?.substringAfterLast('/')?.substringAfterLast('\\')?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() } ?: trackName
        return candidate.trim().lowercase(Locale.ROOT)
    }

    fun starredSubtitles(context: Context): Set<String> =
        prefs(context).getStringSet("starred_subs_v1", emptySet()).orEmpty().map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.toSet()

    fun isSubtitleStarred(context: Context, trackName: String, path: String? = null): Boolean {
        val stars = starredSubtitles(context)
        val nameKey = subtitleStarKey(trackName, null)
        val pathKey = if (path != null) subtitleStarKey(trackName, path) else nameKey
        return nameKey in stars || pathKey in stars
    }

    fun toggleStarredSubtitle(context: Context, trackName: String, path: String? = null): Boolean {
        val key = subtitleStarKey(trackName, path)
        if (key.isBlank()) return false
        val nameKey = subtitleStarKey(trackName, null)
        val cur = starredSubtitles(context).toMutableSet()
        val nowStarred = if (key in cur || nameKey in cur) {
            cur.remove(key)
            cur.remove(nameKey)
            false
        } else {
            cur.add(key)
            true
        }
        prefs(context).edit().putStringSet("starred_subs_v1", cur).apply()
        return nowStarred
    }

    fun tempSpeedAlignment(context: Context): String = "Top"

    fun saveTempSpeedAlignment(context: Context, alignment: String) {
        prefs(context).edit().putString("temp_speed_alignment", "Top").apply()
    }

    data class FolderPlaybackPrefs(
        val audioLang: String? = null,
        val subLang: String? = null,
        val rate: Float? = null,
        val aspectName: String? = null,
    )

    fun folderMemoryEnabled(context: Context): Boolean =
        prefs(context).getBoolean("folder_memory_enabled", true)

    fun saveFolderMemoryEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("folder_memory_enabled", enabled).apply()
    }

    fun folderMemory(context: Context, folder: String?): FolderPlaybackPrefs? {
        if (!folderMemoryEnabled(context)) return null
        val clean = folder?.trim()?.takeIf { it.isNotBlank() && it != "Opened files" && it != "Network Streams" } ?: return null
        val raw = prefs(context).getString("folder_mem_${clean.lowercase(Locale.ROOT).hashCode()}", null) ?: return null
        val parts = raw.split('\t')
        if (parts.size < 4) return null
        return FolderPlaybackPrefs(
            audioLang = parts[0].takeIf { it.isNotBlank() },
            subLang = parts[1].takeIf { it.isNotBlank() },
            rate = parts[2].toFloatOrNull()?.takeIf { it in 0.25f..4f },
            aspectName = parts[3].takeIf { it.isNotBlank() },
        )
    }

    fun updateFolderMemory(
        context: Context,
        folder: String?,
        audioLang: String? = null,
        subLang: String? = null,
        rate: Float? = null,
        aspectName: String? = null,
    ) {
        if (!folderMemoryEnabled(context)) return
        val clean = folder?.trim()?.takeIf { it.isNotBlank() && it != "Opened files" && it != "Network Streams" } ?: return
        val key = "folder_mem_${clean.lowercase(Locale.ROOT).hashCode()}"
        val prev = folderMemory(context, clean) ?: FolderPlaybackPrefs()
        val next = FolderPlaybackPrefs(
            audioLang = audioLang ?: prev.audioLang,
            subLang = subLang ?: prev.subLang,
            rate = rate ?: prev.rate,
            aspectName = aspectName ?: prev.aspectName,
        )
        val encoded = listOf(
            next.audioLang.orEmpty().replace('\t', ' '),
            next.subLang.orEmpty().replace('\t', ' '),
            next.rate?.toString().orEmpty(),
            next.aspectName.orEmpty().replace('\t', ' '),
        ).joinToString("\t")
        prefs(context).edit().putString(key, encoded).apply()
    }
}

object Library {
    suspend fun videoDimensions(context: Context, uri: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        if (PlaybackRules.isStreamUrl(uri)) return@withContext null
        val retriever = MediaMetadataRetriever()
        try {
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { descriptor ->
                retriever.setDataSource(descriptor.fileDescriptor)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                // Use native sample-aspect geometry unless an explicit display rotation needs preserving.
                if (width <= 0 || height <= 0 || rotation == 0) null else if (rotation % 180 != 0) height to width else width to height
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (_: Exception) { null
        } finally { retriever.release() }
    }
    private val probes = Semaphore(2)
    private val thumbSlots = Semaphore(8)
    val videoPlaybackActive = MutableStateFlow(false)
    val isScanning = MutableStateFlow(false)
    private val thumbLocks = Array(32) { Mutex() }
    val thumbnailRevision = MutableStateFlow(0)
    private val failedThumbs = LruCache<String, Boolean>(512)
    fun retryThumbnails() { thumbs.evictAll(); failedThumbs.evictAll(); thumbnailRevision.value++ }

    suspend fun folderThumbnail(context: Context, folder: MediaFolder): Bitmap? {
        val rep = folder.items.filter { !it.audio }.maxByOrNull { it.added.takeIf { a -> a > 0 } ?: it.modified }
            ?: folder.items.firstOrNull { !it.audio } ?: return null
        return thumbnail(context, rep)
    }
    private suspend fun awaitIdle() { videoPlaybackActive.first { !it } }
    private val metadata = LruCache<String, MediaInfo>(1024)

    suspend fun inspect(context: Context, vlc: LibVLC, item: MediaItem): MediaInfo? {
        if (PlaybackRules.isStreamUrl(item.uri)) return null
        return probes.withPermit {
            awaitIdle()
            val key = "v4|${item.uri}|${item.size}|${item.modified}|${item.added}|${thumbnailRevision.value}"
            metadata.get(key)?.let {
                return@withPermit it
            }
            withContext(Dispatchers.IO) {
                var descriptor: android.os.ParcelFileDescriptor? = null
                var media: Media? = null
                var probePlayer: MediaPlayer? = null
                try {
                    val uri = Uri.parse(item.uri)
                    media =
                        if (uri.scheme == "content") {
                            descriptor =
                                context.contentResolver.openFileDescriptor(uri, "r")
                                    ?: return@withContext null
                            Media(vlc, descriptor.fileDescriptor)
                        } else Media(vlc, uri)
                    val done = CompletableDeferred<Boolean>()
                    if (uri.scheme == "content") {
                        // VLC's preparser skips fd:// item types. Open a bounded,
                        // demux-only player using the authorized descriptor instead.
                        // Audio, video and subtitle output are disabled for this probe.
                        media.addOption(":no-audio")
                        media.addOption(":no-video")
                        media.addOption(":no-spu")
                        media.addOption(":stop-time=0.1")
                        probePlayer = MediaPlayer(vlc)
                        probePlayer.media = media
                        probePlayer.setEventListener { event ->
                            when (event.type) {
                                MediaPlayer.Event.Playing,
                                MediaPlayer.Event.EndReached -> done.complete(true)
                                MediaPlayer.Event.EncounteredError -> done.complete(false)
                            }
                        }
                        probePlayer.play()
                    } else {
                        media.setEventListener { event ->
                            if (event.type == IMedia.Event.ParsedChanged)
                                done.complete(event.parsedStatus == IMedia.ParsedStatus.Done)
                        }
                        if (!media.parseAsync(IMedia.Parse.ParseLocal, 2500))
                            return@withContext null
                    }
                    if (withTimeoutOrNull(3500) { done.await() } != true) return@withContext null
                    val tracks = (0 until media.trackCount).mapNotNull { media.getTrack(it) }
                    val video = tracks.filterIsInstance<IMedia.VideoTrack>().firstOrNull()
                    MediaInfo(
                            (probePlayer?.length ?: media.duration).coerceAtLeast(0),
                            video?.width ?: 0,
                            video?.height ?: 0,
                            tracks
                                .filter { it.type == IMedia.Track.Type.Text }
                                .map {
                                    PlaybackRules.subtitleBadge(PlaybackRules.fourcc(it.fourcc))
                                }
                                .distinct(),
                            tracks
                                .filter { it.type == IMedia.Track.Type.Audio }
                                .map { it.codec.trim().uppercase() }
                                .distinct(),
                        )
                        .also { metadata.put(key, it) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("MSPlayerProbe", "Metadata unavailable", e)
                    null
                } finally {
                    probePlayer?.setEventListener(null)
                    probePlayer?.stop()
                    probePlayer?.release()
                    media?.setEventListener(null)
                    media?.release()
                    descriptor?.close()
                }
            }
        }
    }

    @Volatile private var lastVideoScanEpoch = 0L
    @Volatile private var lastAudioScanEpoch = 0L
    private var cachedVideos: List<MediaItem> = emptyList()
    private var cachedAudio: List<MediaItem> = emptyList()

    fun invalidateScanCache() {
        lastVideoScanEpoch = 0L
        lastAudioScanEpoch = 0L
        cachedVideos = emptyList()
        cachedAudio = emptyList()
        metadata.evictAll()
    }

    fun sniffFile(file: java.io.File): String? = runCatching {
        if (!file.isFile || !file.canRead() || file.length() < 4) return@runCatching null
        java.io.FileInputStream(file).use { input ->
            val bytes = ByteArray(64)
            val count = input.read(bytes)
            PlaybackRules.sniffMedia(if (count > 0) bytes.copyOf(count) else byteArrayOf())
        }
    }.getOrNull()

    /** Asks Android MediaStore to index recently added files in standard media folders. */
    suspend fun triggerSystemMediaScan(context: Context) = withContext(Dispatchers.IO) {
        val dirs = listOf(
            android.os.Environment.DIRECTORY_DOWNLOADS,
            android.os.Environment.DIRECTORY_MOVIES,
            android.os.Environment.DIRECTORY_DCIM,
            android.os.Environment.DIRECTORY_MUSIC,
        ).mapNotNull { dir ->
            runCatching { android.os.Environment.getExternalStoragePublicDirectory(dir) }.getOrNull()
        }.filter { it.exists() && it.isDirectory }

        val paths = ArrayList<String>(48)
        for (root in dirs) {
            root.listFiles()?.forEach { f ->
                val isMedia = f.isFile && (PlaybackRules.mediaFile(null, f.name) ||
                    (PlaybackRules.temporaryFile(f.name) && sniffFile(f) != null))
                if (isMedia && paths.size < 120) {
                    paths.add(f.absolutePath)
                }
            }
        }
        if (paths.isNotEmpty()) {
            runCatching {
                android.media.MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null, null)
            }
        }
    }

    fun queryCursorWithPending(
        context: Context,
        base: Uri,
        projection: Array<String>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        sortOrder: String? = "date_added DESC",
    ): android.database.Cursor? {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val bundle = android.os.Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                if (!selection.isNullOrBlank()) putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                if (selectionArgs != null) putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                if (!sortOrder.isNullOrBlank()) putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            }
            val res = runCatching { context.contentResolver.query(base, projection, bundle, null) }.getOrNull()
            if (res != null) return res
        }
        return context.contentResolver.query(base, projection, selection, selectionArgs, sortOrder)
    }

    private fun scanDirectDiskMedia(audio: Boolean, knownKeys: Set<String>): List<MediaItem> {
        val extRoot = runCatching { android.os.Environment.getExternalStorageDirectory() }.getOrNull()
            ?.takeIf { it.exists() && it.isDirectory } ?: return emptyList()
        val hasAllFiles = android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
        val standardDirs = listOf(
            android.os.Environment.DIRECTORY_DOWNLOADS,
            android.os.Environment.DIRECTORY_MOVIES,
            android.os.Environment.DIRECTORY_DCIM,
            android.os.Environment.DIRECTORY_MUSIC,
        ).mapNotNull { runCatching { android.os.Environment.getExternalStoragePublicDirectory(it) }.getOrNull() }
            .filter { it.exists() && it.isDirectory }
        val roots = if (hasAllFiles) {
            val userTop = extRoot.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" }.orEmpty()
            (standardDirs + userTop).distinctBy { it.absolutePath }
        } else standardDirs
        val extra = ArrayList<MediaItem>()
        val queue = ArrayDeque<Pair<java.io.File, Int>>()
        roots.forEach { queue.add(it to 0) }
        var inspectedDirs = 0
        while (queue.isNotEmpty() && inspectedDirs++ < 60 && extra.size < 400) {
            val (dir, depth) = queue.removeFirst()
            val files = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (f in files) {
                if (f.isDirectory) {
                    if (depth < 2 && !f.name.startsWith(".") && f.name != "Android") {
                        queue.add(f to (depth + 1))
                    }
                    continue
                }
                if (!f.isFile || !f.canRead() || f.length() <= 0L) continue
                val name = f.name
                val temp = PlaybackRules.temporaryFile(name)
                if (!temp && !PlaybackRules.mediaFile(null, name)) continue
                val rel = f.parentFile?.absolutePath
                    ?.removePrefix(extRoot.absolutePath)
                    ?.trimStart('/')
                    ?.ifBlank { "Storage" } ?: "Storage"
                val key = "${rel.lowercase(Locale.ROOT)}|${name.lowercase(Locale.ROOT)}|${f.length()}"
                if (key in knownKeys || f.absolutePath in knownKeys) continue
                val mime = if (!PlaybackRules.mediaFile(null, name) && temp) sniffFile(f) else null
                if (!PlaybackRules.mediaFile(mime, name)) continue
                if (PlaybackRules.audioFile(mime, name) != audio) continue
                val modSec = (f.lastModified() / 1000L).coerceAtLeast(0L)
                extra += MediaItem(
                    uri = Uri.fromFile(f).toString(),
                    title = name,
                    folder = rel,
                    size = f.length(),
                    duration = 0L,
                    width = 0,
                    height = 0,
                    added = modSec,
                    audio = audio,
                    modified = modSec,
                )
            }
        }
        return extra
    }

    suspend fun scan(context: Context, audio: Boolean, forceFull: Boolean = false): List<MediaItem> =
        withContext(Dispatchers.IO) {
            isScanning.value = true
            try {
                val lastEpoch = if (audio) lastAudioScanEpoch else lastVideoScanEpoch
                val cached = if (audio) cachedAudio else cachedVideos
                val base =
                    if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val projection =
                    mutableListOf("_id", "_display_name", "_size", "duration", "date_added", "date_modified", "_data")
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    projection += "relative_path"
                    projection += "is_pending"
                }
                if (!audio) projection += listOf("width", "height")

                if (!forceFull && lastEpoch > 0L && cached.isNotEmpty()) {
                    val updated = ArrayList<MediaItem>()
                    try {
                        queryCursorWithPending(
                            context,
                            base,
                            projection.toTypedArray(),
                            "date_modified > ?",
                            arrayOf(lastEpoch.toString()),
                            "date_added DESC",
                        )?.use { cursor ->
                            fun str(name: String) =
                                cursor.getColumnIndex(name).let {
                                    if (it >= 0) cursor.getString(it) ?: "" else ""
                                }
                            fun num(name: String) =
                                cursor.getColumnIndex(name).let {
                                    if (it >= 0) cursor.getLong(it) else 0L
                                }
                            while (cursor.moveToNext()) {
                                val dataPath = str("_data")
                                val isPending = if (android.os.Build.VERSION.SDK_INT >= 29) num("is_pending") == 1L else false
                                val directFile = if (dataPath.isNotBlank()) java.io.File(dataPath) else null
                                val itemUri = if (isPending && directFile?.canRead() == true) {
                                    Uri.fromFile(directFile).toString()
                                } else {
                                    ContentUris.withAppendedId(base, num("_id")).toString()
                                }
                                val folder =
                                    if (android.os.Build.VERSION.SDK_INT >= 29)
                                        str("relative_path").trimEnd('/').ifBlank { "Storage" }
                                    else
                                        str("_data")
                                            .substringBeforeLast('/')
                                            .substringAfterLast('/')
                                            .ifBlank { "Storage" }
                                updated +=
                                    MediaItem(
                                        itemUri,
                                        str("_display_name"),
                                        folder,
                                        num("_size"),
                                        num("duration"),
                                        num("width").toInt(),
                                        num("height").toInt(),
                                        num("date_added"),
                                        audio,
                                        num("date_modified"),
                                    )
                            }
                        }
                    } catch (_: SecurityException) {
                    }

                    val currentIds = HashSet<String>()
                    try {
                        queryCursorWithPending(context, base, arrayOf("_id"), null, null, null)?.use { cursor ->
                            while (cursor.moveToNext()) {
                                currentIds += ContentUris.withAppendedId(base, cursor.getLong(0)).toString()
                            }
                        }
                    } catch (_: SecurityException) {
                    }

                    val knownKeys = HashSet<String>(cached.size * 2)
                    cached.forEach {
                        knownKeys += "${it.folder.lowercase(Locale.ROOT)}|${it.title.lowercase(Locale.ROOT)}|${it.size}"
                    }
                    updated.forEach {
                        knownKeys += "${it.folder.lowercase(Locale.ROOT)}|${it.title.lowercase(Locale.ROOT)}|${it.size}"
                    }
                    val directExtra = if (android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()) {
                        scanDirectDiskMedia(audio, knownKeys)
                    } else emptyList()
                    val cachedDiskUris = cached.filter { it.uri.startsWith("file://") }.count {
                        runCatching { java.io.File(Uri.parse(it.uri).path ?: "").exists() }.getOrDefault(false)
                    }
                    val cachedDiskCount = cached.count { it.uri.startsWith("file://") }

                    if (updated.isNotEmpty() || directExtra.isNotEmpty() ||
                        cachedDiskUris != cachedDiskCount ||
                        (currentIds.isNotEmpty() && currentIds.size != cached.count { !it.uri.startsWith("file://") })
                    ) {
                        val updatedUris = updated.map { it.uri }.toSet()
                        val keptCached = cached.filter { item ->
                            if (item.uri.startsWith("file://")) {
                                runCatching { java.io.File(Uri.parse(item.uri).path ?: "").exists() }.getOrDefault(false) &&
                                    item.uri !in updatedUris
                            } else {
                                item.uri !in updatedUris && item.uri in currentIds
                            }
                        }
                        val merged = keptCached + updated + directExtra
                        val result = merged.sortedByDescending { it.added.takeIf { a -> a > 0 } ?: it.modified }
                        val now = System.currentTimeMillis() / 1000L
                        if (audio) { cachedAudio = result; lastAudioScanEpoch = now }
                        else { cachedVideos = result; lastVideoScanEpoch = now }
                        return@withContext result
                    } else if (currentIds.isNotEmpty()) {
                        return@withContext cached
                    }
                }

                val items = ArrayList<MediaItem>()
                val knownKeys = HashSet<String>()
                try {
                    queryCursorWithPending(context, base, projection.toTypedArray(), null, null, "date_added DESC")
                        ?.use { cursor ->
                            fun str(name: String) =
                                cursor.getColumnIndex(name).let {
                                    if (it >= 0) cursor.getString(it) ?: "" else ""
                                }
                            fun num(name: String) =
                                cursor.getColumnIndex(name).let {
                                    if (it >= 0) cursor.getLong(it) else 0L
                                }
                            while (cursor.moveToNext()) {
                                val dataPath = str("_data")
                                val isPending = if (android.os.Build.VERSION.SDK_INT >= 29) num("is_pending") == 1L else false
                                val directFile = if (dataPath.isNotBlank()) java.io.File(dataPath) else null
                                val itemUri = if (isPending && directFile?.canRead() == true) {
                                    Uri.fromFile(directFile).toString()
                                } else {
                                    ContentUris.withAppendedId(base, num("_id")).toString()
                                }
                                val title = str("_display_name")
                                val size = num("_size")
                                val folder =
                                    if (android.os.Build.VERSION.SDK_INT >= 29)
                                        str("relative_path").trimEnd('/').ifBlank { "Storage" }
                                    else
                                        dataPath
                                            .substringBeforeLast('/')
                                            .substringAfterLast('/')
                                            .ifBlank { "Storage" }
                                if (dataPath.isNotBlank()) knownKeys += dataPath
                                knownKeys += "${folder.lowercase(Locale.ROOT)}|${title.lowercase(Locale.ROOT)}|${size}"
                                items +=
                                    MediaItem(
                                        itemUri,
                                        title,
                                        folder,
                                        size,
                                        num("duration"),
                                        num("width").toInt(),
                                        num("height").toInt(),
                                        num("date_added"),
                                        audio,
                                        num("date_modified"),
                                    )
                            }
                        }
                } catch (_: SecurityException) {
                    /* Restricted access can expose only the granted subset. */
                }
                if (android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()) {
                    items += scanDirectDiskMedia(audio, knownKeys)
                }
                val sorted = items.sortedByDescending { it.added.takeIf { a -> a > 0 } ?: it.modified }
                val now = System.currentTimeMillis() / 1000L
                if (audio) { cachedAudio = sorted; lastAudioScanEpoch = now }
                else { cachedVideos = sorted; lastVideoScanEpoch = now }
                sorted
            } finally {
                isScanning.value = false
            }
        }

    fun sniff(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArray(64)
            val count = input.read(bytes)
            PlaybackRules.sniffMedia(if (count > 0) bytes.copyOf(count) else byteArrayOf())
        }
    }.getOrNull()

    suspend fun opened(context: Context, uri: Uri): MediaItem =
        withContext(Dispatchers.IO) {
            if (uri.scheme in listOf("http", "https", "rtsp", "rtmp", "mms", "udp")) {
                val title = uri.lastPathSegment?.substringBefore('?')?.takeIf { it.isNotBlank() } ?: uri.host ?: uri.toString()
                return@withContext MediaItem(uri.toString(), title, folder = "Network Streams", size = 0L, audio = false)
            }
            var name = uri.lastPathSegment ?: "Media"
            var size = 0L
            runCatching {
                context.contentResolver
                    .query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )
                    ?.use {
                        if (it.moveToFirst()) {
                            name = it.getString(0) ?: name
                            size = if (it.isNull(1)) 0 else it.getLong(1)
                        }
                    }
            }
            val declared = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val mime = if (PlaybackRules.mediaFile(declared, name)) declared else sniff(context, uri) ?: declared
            val audio = PlaybackRules.audioFile(mime, name)
            MediaItem(uri.toString(), name, size = size, audio = audio)
        }

    private fun adaptiveCacheSize(): Int {
        val maxHeap = Runtime.getRuntime().maxMemory()
        return (maxHeap / 8).toInt().coerceIn(8 * 1024 * 1024, 64 * 1024 * 1024)
    }

    private val thumbs =
        object : LruCache<String, Bitmap>(adaptiveCacheSize()) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }

    fun isDark(bitmap: Bitmap): Boolean {
        var total = 0L
        var bright = 0
        for (x in 0..7) for (y in 0..7) {
            val pixel = bitmap.getPixel(x * (bitmap.width - 1) / 7, y * (bitmap.height - 1) / 7)
            val level = maxOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
            total += level
            if (level > 30) bright++
        }
        return total < 64 * 12 || bright < 3
    }

    suspend fun thumbnail(context: Context, item: MediaItem): Bitmap? {
        if (item.audio || PlaybackRules.isStreamUrl(item.uri)) return null
        val key = "v4|${item.uri}|${item.size}|${item.modified}|${item.added}|${thumbnailRevision.value}"
        if (failedThumbs.get(key) == true) return null
        return thumbLocks[(key.hashCode() and Int.MAX_VALUE) % thumbLocks.size].withLock {
            thumbs.get(key)?.let { return@withLock it }
            thumbSlots.withPermit {
                var bitmap = withContext(Dispatchers.IO) {
                    val directory = java.io.File(context.cacheDir, "video-thumbnails-v4").apply { mkdirs() }
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
                    val disk = java.io.File(directory, "$digest.jpg")
                    val foundDisk = if (disk.isFile && disk.length() > 0) android.graphics.BitmapFactory.decodeFile(disk.path) else null
                    if (foundDisk != null) return@withContext foundDisk

                    var found: Bitmap? = null
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val signal = android.os.CancellationSignal()
                        val cancellation = currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { signal.cancel() }
                        try {
                            val parsed = Uri.parse(item.uri)
                            found = runCatching {
                                context.contentResolver.loadThumbnail(parsed, android.util.Size(320, 180), signal)
                            }.getOrNull()
                        } finally {
                            cancellation?.dispose()
                        }
                    }

                    if (found == null) {
                        found = kotlinx.coroutines.withTimeoutOrNull(1500L) {
                            val retriever = MediaMetadataRetriever()
                            try {
                                val parsed = Uri.parse(item.uri)
                                if (parsed.scheme == "file") {
                                    retriever.setDataSource(parsed.path)
                                } else {
                                    try {
                                        context.contentResolver.openFileDescriptor(parsed, "r")?.use { descriptor ->
                                            retriever.setDataSource(descriptor.fileDescriptor)
                                        }
                                    } catch (_: Exception) {
                                        retriever.setDataSource(context, parsed)
                                    }
                                }
                                val duration = item.duration.takeIf { it > 0 } ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                val sourceW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.coerceAtLeast(1) ?: 240
                                val sourceH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.coerceAtLeast(1) ?: 135
                                val outW = if (sourceW >= sourceH) 320 else (320f * sourceW / sourceH).toInt().coerceAtLeast(1)
                                val outH = if (sourceH >= sourceW) 320 else (320f * sourceH / sourceW).toInt().coerceAtLeast(1)
                                val times = listOf((duration / 10).coerceIn(1000, 30000), 1000L, 5000L).distinct()
                                var extracted: Bitmap? = null
                                for (time in times) {
                                    currentCoroutineContext().ensureActive()
                                    val frame = if (android.os.Build.VERSION.SDK_INT >= 27)
                                        retriever.getScaledFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, outW, outH)
                                    else retriever.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { original ->
                                        Bitmap.createScaledBitmap(original, outW, outH, true).also { if (it !== original) original.recycle() }
                                    }
                                    if (frame != null) { extracted = frame; break }
                                }
                                extracted
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            } finally {
                                runCatching { retriever.release() }
                            }
                        }
                    }
                    found
                }
                currentCoroutineContext().ensureActive()
                if (bitmap != null) {
                    thumbs.put(key, bitmap)
                    withContext(Dispatchers.IO) {
                        val directory = java.io.File(context.cacheDir, "video-thumbnails-v4").apply { mkdirs() }
                        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
                        val target = java.io.File(directory, "$digest.jpg")
                        runCatching {
                            val temp = java.io.File(directory, "$digest.tmp")
                            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                            if (!temp.renameTo(target)) temp.delete()
                            val files = directory.listFiles()?.filter { it.extension == "jpg" }?.sortedByDescending { it.lastModified() }.orEmpty()
                            var total = 0L
                            files.forEach { total += it.length(); if (total > 48 * 1024 * 1024) it.delete() }
                        }
                    }
                } else {
                    failedThumbs.put(key, true)
                }
                bitmap
            }
        }
    }

    suspend fun inspectDetailed(context: Context, vlc: LibVLC?, item: MediaItem): MediaDetailedInfo =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(item.uri)
            val resolvedFile = resolvePathFile(context, uri)
            val location = resolvedFile?.parentFile?.absolutePath
                ?: item.folder.ifBlank { "Storage" }
            val ext = PlaybackRules.containerExtensionHint(
                item.title,
                if (resolvedFile != null) sniffFile(resolvedFile) else sniff(context, uri),
            )
            val containerInfo = if (ext in listOf("mkv", "webm", "mp4", "m4v", "mov")) {
                ContainerDemuxer.probeContainerInfo(context, uri, resolvedFile, ext)
            } else ContainerDemuxer.ContainerInfo()

            var durationMs = item.duration.takeIf { it > 0 } ?: containerInfo.durationMs
            var width = item.width
            var height = item.height
            var vlcTracks: List<IMedia.Track> = emptyList()
            var vlcEncodedBy = ""

            if (vlc != null && !PlaybackRules.isStreamUrl(item.uri)) {
                var descriptor: android.os.ParcelFileDescriptor? = null
                var media: Media? = null
                var probePlayer: MediaPlayer? = null
                try {
                    media = if (uri.scheme == "content") {
                        descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                        descriptor?.let { Media(vlc, it.fileDescriptor) }
                    } else Media(vlc, uri)
                    if (media != null) {
                        val done = CompletableDeferred<Boolean>()
                        if (uri.scheme == "content") {
                            media.addOption(":no-audio")
                            media.addOption(":no-video")
                            media.addOption(":no-spu")
                            media.addOption(":stop-time=0.1")
                            probePlayer = MediaPlayer(vlc)
                            probePlayer.media = media
                            probePlayer.setEventListener { event ->
                                when (event.type) {
                                    MediaPlayer.Event.Playing,
                                    MediaPlayer.Event.EndReached -> done.complete(true)
                                    MediaPlayer.Event.EncounteredError -> done.complete(false)
                                }
                            }
                            probePlayer.play()
                        } else {
                            media.setEventListener { event ->
                                if (event.type == IMedia.Event.ParsedChanged)
                                    done.complete(event.parsedStatus == IMedia.ParsedStatus.Done)
                            }
                            media.parseAsync(IMedia.Parse.ParseLocal, 2500)
                        }
                        if (withTimeoutOrNull(3000) { done.await() } == true) {
                            durationMs = (probePlayer?.length ?: media.duration).coerceAtLeast(durationMs)
                            vlcTracks = (0 until media.trackCount).mapNotNull { media.getTrack(it) }
                            vlcEncodedBy = media.getMeta(IMedia.Meta.EncodedBy).orEmpty()
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    probePlayer?.setEventListener(null)
                    probePlayer?.stop()
                    probePlayer?.release()
                    media?.setEventListener(null)
                    media?.release()
                    descriptor?.close()
                }
            }

            if (durationMs <= 0L || (width <= 0 && !item.audio)) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        if (resolvedFile != null) retriever.setDataSource(resolvedFile.absolutePath)
                        else context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            retriever.setDataSource(pfd.fileDescriptor)
                        }
                        if (durationMs <= 0L) {
                            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        }
                        if (width <= 0) {
                            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        }
                    } finally {
                        retriever.release()
                    }
                }
            }

            buildDetailedInfo(
                item = item,
                location = location,
                resolvedFile = resolvedFile,
                containerInfo = containerInfo,
                vlcTracks = vlcTracks,
                vlcEncodedBy = vlcEncodedBy,
                durationMs = durationMs,
                width = width,
                height = height,
                extHint = ext,
                subtitleSummary = null,
            )
        }

    fun findPhysicalMediaFile(item: MediaItem): java.io.File? {
        val cleanStem = item.title.substringBeforeLast('.').trim()
        val rawTitle = item.title.trim()
        val extRoot = runCatching { android.os.Environment.getExternalStorageDirectory() }.getOrNull() ?: return null

        val candidateDirs = buildList {
            if (item.folder.isNotBlank() && item.folder != "Storage") {
                if (item.folder.startsWith("/")) {
                    add(java.io.File(item.folder))
                } else {
                    add(java.io.File(extRoot, item.folder))
                }
            }
            listOf(
                android.os.Environment.DIRECTORY_DOWNLOADS,
                android.os.Environment.DIRECTORY_MOVIES,
                android.os.Environment.DIRECTORY_DCIM,
            ).forEach { dirName ->
                runCatching { android.os.Environment.getExternalStoragePublicDirectory(dirName) }.getOrNull()?.let { add(it) }
            }
            add(java.io.File(extRoot, "Download/Telegram"))
            add(java.io.File(extRoot, "Telegram"))
            add(java.io.File(extRoot, "Telegram/Telegram Video"))
            add(java.io.File(extRoot, "Movies/Telegram"))
            add(java.io.File(extRoot, "DCIM/Camera"))
        }.filter { it.exists() && it.isDirectory }.distinctBy { it.absolutePath }

        for (dir in candidateDirs) {
            val direct = java.io.File(dir, rawTitle)
            if (direct.exists() && direct.canRead()) return direct

            val files = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (f in files) {
                if (!f.isFile || !f.canRead()) continue
                val fn = f.name
                if (fn == rawTitle) return f
                if (PlaybackRules.temporaryFile(fn)) {
                    if (fn.contains(rawTitle) || (cleanStem.length >= 4 && fn.contains(cleanStem))) {
                        return f
                    }
                }
            }
        }
        return null
    }

    fun resolvePathFile(context: Context, uri: Uri, item: MediaItem? = null): java.io.File? {
        if (uri.scheme == "file") {
            return uri.path?.let { java.io.File(it) }?.takeIf { it.exists() && it.canRead() }
        }
        if (uri.scheme == "content" && uri.authority == "media") {
            runCatching {
                val id = ContentUris.parseId(uri)
                val isAudio = uri.toString().contains("audio")
                val tableUri = if (isAudio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                queryCursorWithPending(
                    context,
                    tableUri,
                    arrayOf(MediaStore.MediaColumns.DATA),
                    "${MediaStore.MediaColumns._ID} = ?",
                    arrayOf(id.toString()),
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val p = c.getString(0)
                        if (!p.isNullOrBlank()) {
                            val f = java.io.File(p)
                            if (f.exists() && f.canRead()) return f
                        }
                    }
                }
            }
            runCatching {
                queryCursorWithPending(
                    context,
                    uri,
                    arrayOf(MediaStore.MediaColumns.DATA),
                    null,
                    null,
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val p = c.getString(0)
                        if (!p.isNullOrBlank()) {
                            val f = java.io.File(p)
                            if (f.exists() && f.canRead()) return f
                        }
                    }
                }
            }
        }
        if (item != null) {
            val candidate = findPhysicalMediaFile(item)
            if (candidate != null && candidate.exists() && candidate.canRead()) return candidate
        }
        return uri.path?.let { java.io.File(it) }?.takeIf { it.exists() && it.canRead() }
    }

    fun buildDetailedInfo(
        item: MediaItem,
        location: String,
        resolvedFile: java.io.File?,
        containerInfo: ContainerDemuxer.ContainerInfo,
        vlcTracks: List<IMedia.Track>,
        vlcEncodedBy: String = "",
        durationMs: Long = item.duration,
        width: Int = item.width,
        height: Int = item.height,
        extHint: String = PlaybackRules.mediaExtension(item.title),
        subtitleSummary: String? = null,
    ): MediaDetailedInfo {
        val streamList = ArrayList<MediaStreamDetail>()
        val vlcVideo = vlcTracks.filterIsInstance<IMedia.VideoTrack>()
        val vlcAudio = vlcTracks.filterIsInstance<IMedia.AudioTrack>()
        val vlcText = vlcTracks.filter { it.type == IMedia.Track.Type.Text }

        val containerVideoDetails = containerInfo.trackDetails.values.filter { it.width > 0 && it.height > 0 }
        val containerAudioDetails = containerInfo.trackDetails.values.filter { it.sampleRateHz > 0 || it.channels > 0 }

        var streamIndex = 1
        vlcVideo.forEachIndexed { idx, t ->
            val detail = containerVideoDetails.getOrNull(idx)
            val fpsVal = when {
                t.frameRateNum > 0 && t.frameRateDen > 0 -> t.frameRateNum.toDouble() / t.frameRateDen.toDouble()
                detail != null && detail.fps > 0.0 -> detail.fps
                else -> 0.0
            }
            val fpsStr = if (fpsVal > 0.0) String.format(Locale.ROOT, "%.3f", fpsVal).trimEnd('0').trimEnd('.') else ""
            streamList += MediaStreamDetail(
                index = streamIndex++,
                type = "Video",
                title = t.description.orEmpty(),
                codec = readableCodecName(t.codec.ifBlank { PlaybackRules.fourcc(t.fourcc) }, "Video"),
                profile = t.profile.takeIf { it > 0 }?.let { "Profile $it" }.orEmpty(),
                width = if (t.width > 0) t.width else (detail?.width ?: width),
                height = if (t.height > 0) t.height else (detail?.height ?: height),
                frameRate = fpsStr,
                language = readableLanguageName(t.language.orEmpty()),
            )
        }
        vlcAudio.forEachIndexed { idx, t ->
            val detail = containerAudioDetails.getOrNull(idx)
            streamList += MediaStreamDetail(
                index = streamIndex++,
                type = "Audio",
                title = t.description.orEmpty(),
                codec = readableCodecName(t.codec.ifBlank { PlaybackRules.fourcc(t.fourcc) }, "Audio"),
                profile = "",
                sampleRateHz = t.rate.takeIf { it > 0 } ?: (detail?.sampleRateHz ?: 0),
                channels = t.channels.takeIf { it > 0 } ?: (detail?.channels ?: 0),
                language = readableLanguageName(t.language.orEmpty()),
            )
        }
        vlcText.forEach { t ->
            streamList += MediaStreamDetail(
                index = streamIndex++,
                type = "Subtitle",
                title = t.description.orEmpty(),
                codec = readableCodecName(t.codec.ifBlank { PlaybackRules.fourcc(t.fourcc) }, "Subtitle"),
                language = readableLanguageName(t.language.orEmpty()),
            )
        }

        val firstVideo = vlcVideo.firstOrNull()
        val finalW = firstVideo?.width?.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?: containerVideoDetails.firstOrNull()?.width ?: 0
        val finalH = firstVideo?.height?.takeIf { it > 0 } ?: height.takeIf { it > 0 } ?: containerVideoDetails.firstOrNull()?.height ?: 0
        val formatLabel = containerInfo.format.ifBlank {
            when (extHint.lowercase(Locale.ROOT)) {
                "mkv" -> "Matroska / WebM"
                "webm" -> "WebM / Matroska"
                "mp4", "m4v", "mov" -> "MPEG-4 / QuickTime"
                "avi" -> "AVI"
                "ts", "m2ts", "mts" -> "MPEG Transport Stream"
                "mp3" -> "MP3 Audio"
                "flac" -> "FLAC Audio"
                "m4a", "aac" -> "AAC / MPEG-4 Audio"
                "wav" -> "WAV Audio"
                "ogg", "opus" -> "Ogg / Opus Audio"
                else -> extHint.uppercase(Locale.ROOT).ifBlank { "Media File" }
            }
        }
        val sizeBytes = item.size.takeIf { it > 0 } ?: resolvedFile?.length() ?: 0L
        val modMs = when {
            resolvedFile != null && resolvedFile.lastModified() > 0L -> resolvedFile.lastModified()
            item.modified > 1_000_000_000_000L -> item.modified
            item.modified > 0L -> item.modified * 1000L
            item.added > 0L -> item.added * 1000L
            else -> 0L
        }
        val subStatus = subtitleSummary ?: when {
            vlcText.isNotEmpty() -> "Embedded subtitle"
            else -> "None"
        }
        return MediaDetailedInfo(
            fileName = item.title,
            location = location,
            sizeBytes = sizeBytes,
            modifiedAtMs = modMs,
            subtitleSummary = subStatus,
            format = formatLabel,
            width = finalW,
            height = finalH,
            durationMs = durationMs.takeIf { it > 0 } ?: containerInfo.durationMs,
            encodingSoftware = containerInfo.encodingSoftware.ifBlank { vlcEncodedBy },
            streams = streamList,
        )
    }

}

fun durationText(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000)
    return if (seconds >= 3600)
        String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

fun sizeText(bytes: Long): String =
    when {
        bytes >= 1_000_000_000 -> String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.0f MB", bytes / 1_000_000.0)
        bytes > 0 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1000.0)
        else -> "—"
    }

fun formatDetailedBytes(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val exact = java.text.NumberFormat.getIntegerInstance(Locale.US).format(bytes)
    return "${sizeText(bytes)} ($exact bytes)"
}

fun formatDetailedDate(millis: Long): String {
    if (millis <= 0L) return "—"
    val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.LONG, java.text.DateFormat.SHORT, Locale.getDefault())
    return fmt.format(java.util.Date(millis))
}

fun readableCodecName(raw: String, type: String): String {
    val c = raw.trim()
    if (c.isBlank()) return "Unknown"
    return when (c.lowercase(Locale.ROOT)) {
        "hevc", "h265", "hev1", "hvc1", "v_mpegh/iso/hevc" -> "HEVC / H.265 ($c)"
        "h264", "avc1", "v_mpeg4/iso/avc" -> "AVC / H.264 ($c)"
        "vp9", "vp90" -> "VP9 ($c)"
        "av01", "av1" -> "AV1 ($c)"
        "eac3", "ec-3", "a_eac3" -> "Dolby Digital Plus / E-AC-3 ($c)"
        "ac3", "a52", "a_ac3" -> "Dolby Digital / AC-3 ($c)"
        "mp4a", "aac", "a_aac" -> "AAC ($c)"
        "opus", "a_opus" -> "Opus ($c)"
        "flac", "a_flac" -> "FLAC ($c)"
        "subt", "srt", "s_text/utf8" -> "SubRip / SRT ($c)"
        "ssa", "ass", "s_text/ass", "s_text/ssa" -> "SubStation Alpha ($c)"
        "pgss", "bdpg", "pgs", "s_hdmv/pgs" -> "Blu-ray PGS ($c)"
        else -> if (type == "Subtitle") PlaybackRules.subtitleBadge(c) else c.uppercase(Locale.ROOT)
    }
}

fun readableLanguageName(raw: String): String {
    val clean = raw.trim()
    if (clean.isBlank() || clean.equals("und", ignoreCase = true)) return "Undetermined"
    SubtitleTranslator.findLanguage(clean)?.let { return it.label }
    val loc = Locale.forLanguageTag(clean.replace('_', '-'))
    return loc.getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && !it.equals(clean, ignoreCase = true) }
        ?: clean.replaceFirstChar { it.uppercase(Locale.ROOT) }
}
