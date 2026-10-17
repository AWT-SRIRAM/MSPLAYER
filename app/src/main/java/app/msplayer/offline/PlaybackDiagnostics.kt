package app.msplayer.offline

import android.content.Context
import android.os.Build
import org.videolan.libvlc.MediaPlayer
import java.io.File

/** Opt-in bounded in-memory samples. Export only on an explicit user action. */
class PlaybackDiagnostics {
    var enabled = false
    private val lines = ArrayDeque<String>()
    fun event(line: String) {
        if (!enabled) return
        if (lines.size >= 600) lines.removeFirst()
        lines.addLast("${android.os.SystemClock.elapsedRealtime()} $line")
    }
    fun sample(player: MediaPlayer, state: PlayerState) {
        if (!enabled) return
        val media = player.media ?: return
        try {
            val stats = media.stats ?: return
            event("position=${state.position} preference=${state.decoder} buffering=${state.buffering} " +
                "decoded=${stats.decodedVideo} displayed=${stats.displayedPictures} lost=${stats.lostPictures} " +
                "audioPlayed=${stats.playedAbuffers} audioLost=${stats.lostAbuffers} bitrate=${stats.demuxBitrate}")
        } finally { media.release() }
    }
    fun export(context: Context, state: PlayerState, effects: AudioEffectSettings): File {
        val text = buildString {
            appendLine("MS PLAYER ${BuildConfig.VERSION_NAME} local playback diagnostics")
            appendLine("Android ${Build.VERSION.RELEASE}; ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("file=${state.item?.title}; video=${state.videoWidth}x${state.videoHeight}; decoderPreference=${state.decoder}; effect=${effects.preset}")
            appendLine("Decoder preference is not proof of the active native codec. No media contents are included.")
            lines.forEach { appendLine(it) }
        }
        return File(context.cacheDir, "msplayer-diagnostics.txt").apply { writeText(text) }
    }
}
