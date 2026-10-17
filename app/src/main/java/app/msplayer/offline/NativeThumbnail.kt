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
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/** Bounded, offscreen software fallback; called only through the serial thumbnail queue. */
object NativeThumbnail {
    suspend fun read(context: Context, item: MediaItem): Bitmap? = withContext(Dispatchers.Main) {
        if (Library.videoPlaybackActive.value) return@withContext null
        val reader = ImageReader.newInstance(240, 136, PixelFormat.RGBX_8888, 2)
        val thread = HandlerThread("thumbnail-frame").apply { start() }
        val result = CompletableDeferred<Bitmap?>()
        var descriptor: android.os.ParcelFileDescriptor? = null
        var vlc: LibVLC? = null
        var player: MediaPlayer? = null
        val suspendForPlayback = launch {
            Library.videoPlaybackActive.first { it }
            result.complete(null)
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
                        raw.copyPixelsFromBuffer(plane.buffer)
                        val frame = Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
                        if (frame !== raw) raw.recycle()
                        if (!Library.isDark(frame)) {
                            if (!result.complete(frame)) frame.recycle()
                        } else frame.recycle()
                    }
                }
                }.onFailure { result.complete(null) }
            }, Handler(thread.looper))
            vlc = LibVLC(context, arrayListOf("--no-audio", "--no-spu", "--avcodec-threads=1", "--no-video-title-show"))
            player = MediaPlayer(vlc)
            val uri = Uri.parse(item.uri)
            val media = if (uri.scheme == "content") {
                descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                Media(vlc, descriptor!!.fileDescriptor)
            } else Media(vlc, uri)
            media.setHWDecoderEnabled(false, false)
            media.addOption(":start-time=${if (item.duration > 0) (item.duration / 10000.0).coerceAtMost(30.0) else 2.0}")
            player.vlcVout.setVideoSurface(reader.surface, null)
            player.vlcVout.setWindowSize(240, 136)
            player.vlcVout.attachViews()
            player.media = media
            media.release()
            player.setEventListener { if (it.type == MediaPlayer.Event.EncounteredError) result.complete(null) }
            player.play()
            withTimeoutOrNull(4000) { result.await() }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { null
        } finally {
            suspendForPlayback.cancel()
            player?.setEventListener(null)
            player?.stop()
            player?.vlcVout?.detachViews()
            player?.release()
            vlc?.release()
            descriptor?.close()
            reader.setOnImageAvailableListener(null, null)
            thread.quitSafely()
            reader.close()
        }
    }
}
