package app.msplayer.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/** Bounded, offscreen software fallback; called only through the serial thumbnail queue. */
object NativeThumbnail {
    private val slot = Semaphore(2)
    @Volatile private var sharedVlc: LibVLC? = null

    private fun getVlc(context: Context): LibVLC {
        return sharedVlc ?: synchronized(this) {
            sharedVlc ?: LibVLC(context.applicationContext, arrayListOf(
                "--no-audio",
                "--no-spu",
                "--no-sub-autodetect",
                "--avcodec-threads=2",
                "--avcodec-fast",
                "--no-video-title-show"
            )).also { sharedVlc = it }
        }
    }

    suspend fun read(context: Context, item: MediaItem): Bitmap? = slot.withPermit {
        withContext(Dispatchers.Main) {
            if (Library.videoPlaybackActive.value) {
                android.util.Log.i("MSPLAYER_NATIVE", "Skipping thumbnail for ${item.title} because video playback is active")
                return@withContext null
            }
            android.util.Log.i("MSPLAYER_NATIVE", "NativeThumbnail.read starting for ${item.title}")
            val reader = ImageReader.newInstance(320, 180, PixelFormat.RGBX_8888, 2)
            val thread = HandlerThread("thumbnail-frame").apply { start() }
            val result = CompletableDeferred<Bitmap?>()
            var descriptor: android.os.ParcelFileDescriptor? = null
            var player: MediaPlayer? = null
            var lastFrame: Bitmap? = null
            val suspendForPlayback = launch {
                Library.videoPlaybackActive.first { it }
                if (!result.isCompleted) result.complete(null)
            }
            try {
                reader.setOnImageAvailableListener({ source ->
                    runCatching {
                        source.acquireLatestImage()?.use { image ->
                            if (!result.isCompleted) {
                                val plane = image.planes[0]
                                val paddedWidth = plane.rowStride / plane.pixelStride
                                val raw = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                                raw.setHasAlpha(false)
                                plane.buffer.rewind()
                                raw.copyPixelsFromBuffer(plane.buffer)
                                val frame = Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
                                if (frame !== raw) raw.recycle()
                                val isDark = Library.isDark(frame)
                                android.util.Log.i("MSPLAYER_NATIVE", "Received frame for ${item.title}: ${frame.width}x${frame.height}, dark=$isDark")
                                if (!isDark) {
                                    if (!result.complete(frame)) frame.recycle()
                                } else {
                                    lastFrame?.recycle()
                                    lastFrame = frame
                                }
                            }
                        }
                    }.onFailure { err ->
                        android.util.Log.w("MSPLAYER_NATIVE", "Error in onImageAvailable", err)
                        if (!result.isCompleted) result.complete(null)
                    }
                }, Handler(thread.looper))

                val vlc = getVlc(context)
                player = MediaPlayer(vlc)
                val uri = Uri.parse(item.uri)
                val directFile = if (uri.scheme == "file") java.io.File(uri.path ?: "") else Library.resolvePathFile(context, uri, item)
                val media = if (directFile != null && directFile.canRead()) {
                    android.util.Log.i("MSPLAYER_NATIVE", "Using direct file: ${directFile.absolutePath}")
                    Media(vlc, directFile.absolutePath)
                } else if (uri.scheme == "content") {
                    val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
                        ?: return@withContext null
                    descriptor = pfd
                    android.util.Log.i("MSPLAYER_NATIVE", "Using PFD for uri: $uri")
                    Media(vlc, pfd.fileDescriptor)
                } else Media(vlc, uri)

                media.setHWDecoderEnabled(false, false)
                media.addOption(":no-audio")
                media.addOption(":no-spu")
                player.vlcVout.setVideoSurface(reader.surface, null)
                player.vlcVout.setWindowSize(320, 180)
                player.vlcVout.attachViews()
                player.media = media
                media.release()
                player.setRate(2.0f)
                player.setEventListener { ev ->
                    android.util.Log.i("MSPLAYER_NATIVE", "Player event for ${item.title}: ${ev.type}")
                    when (ev.type) {
                        MediaPlayer.Event.EncounteredError,
                        MediaPlayer.Event.EndReached -> {
                            if (!result.isCompleted) result.complete(null)
                        }
                    }
                }
                player.play()
                val res = withTimeoutOrNull(4500L) { result.await() }
                if (res != null && lastFrame !== res) {
                    lastFrame?.recycle()
                }
                val finalBitmap = res ?: lastFrame
                android.util.Log.i("MSPLAYER_NATIVE", "Finished thumbnail for ${item.title}: success=${finalBitmap != null}")
                finalBitmap
            } catch (e: CancellationException) {
                throw e
            } catch (ex: Exception) {
                android.util.Log.w("MSPLAYER_NATIVE", "Exception in NativeThumbnail.read", ex)
                null
            } finally {
                suspendForPlayback.cancel()
                player?.setEventListener(null)
                player?.stop()
                player?.vlcVout?.detachViews()
                player?.release()
                descriptor?.close()
                reader.setOnImageAvailableListener(null, null)
                thread.quitSafely()
                reader.close()
            }
        }
    }
}
