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
}

object Library {
    suspend fun videoDimensions(context: Context, uri: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
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
    private val thumbSlots = Semaphore(1)
    val videoPlaybackActive = MutableStateFlow(false)
    private val thumbLocks = Array(32) { Mutex() }
    val thumbnailRevision = MutableStateFlow(0)
    fun retryThumbnails() { thumbs.evictAll(); thumbnailRevision.value++ }
    private suspend fun awaitIdle() { videoPlaybackActive.first { !it } }
    private val metadata = LruCache<String, MediaInfo>(1024)

    suspend fun inspect(context: Context, vlc: LibVLC, item: MediaItem): MediaInfo? =
        probes.withPermit {
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

    suspend fun scan(context: Context, audio: Boolean): List<MediaItem> =
        withContext(Dispatchers.IO) {
            awaitIdle()
            val base =
                if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection =
                mutableListOf("_id", "_display_name", "_size", "duration", "date_added", "date_modified")
            if (android.os.Build.VERSION.SDK_INT >= 29) projection += "relative_path"
            else projection += "_data"
            if (!audio) projection += listOf("width", "height")
            val items = ArrayList<MediaItem>()
            try {
                context.contentResolver
                    .query(base, projection.toTypedArray(), null, null, "date_added DESC")
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
                            val folder =
                                if (android.os.Build.VERSION.SDK_INT >= 29)
                                    str("relative_path").trimEnd('/').ifBlank { "Storage" }
                                else
                                    str("_data")
                                        .substringBeforeLast('/')
                                        .substringAfterLast('/')
                                        .ifBlank { "Storage" }
                            items +=
                                MediaItem(
                                    ContentUris.withAppendedId(base, num("_id")).toString(),
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
                /* Restricted access can expose only the granted subset. */
            }
            items
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

    private val thumbs =
        object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
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
        if (item.audio) return null
        val key = "v4|${item.uri}|${item.size}|${item.modified}|${item.added}|${thumbnailRevision.value}"
        return thumbLocks[(key.hashCode() and Int.MAX_VALUE) % thumbLocks.size].withLock {
            thumbs.get(key)?.let { return@withLock it }
            awaitIdle()
            thumbSlots.withPermit {
                awaitIdle()
                var bitmap = withContext(Dispatchers.IO) {
                    val directory=java.io.File(context.cacheDir,"video-thumbnails-v4").apply { mkdirs() }
                    val digest=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
                    val disk=java.io.File(directory,"$digest.jpg")
                    var found: Bitmap? = if(disk.isFile) android.graphics.BitmapFactory.decodeFile(disk.path) else null
                    if(found!=null) return@withContext found
                    val retriever = MediaMetadataRetriever()
                    try {
                        if (found == null) {
                            context.contentResolver.openFileDescriptor(Uri.parse(item.uri), "r")?.use { descriptor ->
                                retriever.setDataSource(descriptor.fileDescriptor)
                                val duration = item.duration.takeIf { it > 0 } ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                val sourceW=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.coerceAtLeast(1) ?: 240
                                val sourceH=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.coerceAtLeast(1) ?: 135
                                val outW=if(sourceW>=sourceH) 320 else (320f*sourceW/sourceH).toInt().coerceAtLeast(1)
                                val outH=if(sourceH>=sourceW) 320 else (320f*sourceH/sourceW).toInt().coerceAtLeast(1)
                                val times = listOf((duration / 10).coerceIn(1000, 30000), 1000L, 5000L).distinct()
                                for (time in times) {
                                    currentCoroutineContext().ensureActive()
                                    if (videoPlaybackActive.value) break
                                    val frame = if (android.os.Build.VERSION.SDK_INT >= 27)
                                        retriever.getScaledFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, outW, outH)
                                    else retriever.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { original ->
                                        Bitmap.createScaledBitmap(original, outW, outH, true).also { if (it !== original) original.recycle() }
                                    }
                                    if (frame != null && !isDark(frame)) { found = frame; break }
                                    frame?.recycle()
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (_: Exception) { /* Native fallback below. */
                    } finally { retriever.release() }
                    if(found==null && !videoPlaybackActive.value) {
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            val signal = android.os.CancellationSignal()
                            val cancellation = currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { signal.cancel() }
                            try {
                                found = runCatching { context.contentResolver.loadThumbnail(Uri.parse(item.uri), android.util.Size(240, 135), signal) }.getOrNull()
                            } finally { cancellation?.dispose() }
                        }
                        if (found != null && isDark(found!!)) { found!!.recycle(); found = null }
                    }
                    found
                }
                currentCoroutineContext().ensureActive()
                if (bitmap == null && !videoPlaybackActive.value) bitmap = NativeThumbnail.read(context, item)
                bitmap?.let { image ->
                    thumbs.put(key,image)
                    withContext(Dispatchers.IO) {
                        val directory=java.io.File(context.cacheDir,"video-thumbnails-v4").apply { mkdirs() }
                        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
                        val target=java.io.File(directory,"$digest.jpg")
                        runCatching {
                            val temp=java.io.File(directory,"$digest.tmp")
                            temp.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG,85,it) }
                            if(!temp.renameTo(target)) temp.delete()
                            val files=directory.listFiles()?.filter { it.extension=="jpg" }?.sortedByDescending { it.lastModified() }.orEmpty()
                            var total=0L
                            files.forEach { total+=it.length(); if(total>48*1024*1024) it.delete() }
                        }
                    }
                }
                bitmap
            }
        }
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
