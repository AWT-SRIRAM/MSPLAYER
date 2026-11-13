package app.msplayer.offline

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ContainerDemuxerTest {

    @Test
    fun testSanitizeSubtitleAss() {
        val rawAss = "0,0,Default,,0,0,0,,{\\pos(192,200)\\c&H00FFFF&}Hello {\\i1}World{\\i0}!"
        val clean = ContainerDemuxer.sanitizeSubtitle(rawAss, "S_TEXT/ASS")
        assertEquals("Hello World!", clean)
    }

    @Test
    fun testSanitizeSubtitleAssWithNewlinesAndHardSpaces() {
        val rawAss = "1,0,Default,,0,0,0,,First line\\NSecond\\hline"
        val clean = ContainerDemuxer.sanitizeSubtitle(rawAss, "S_TEXT/ASS")
        assertEquals("First line\nSecond line", clean)
    }

    @Test
    fun testParseSyntheticUnknownSizeMkvFiltersAudioAndBitmapSubs() {
        fun u16(v: Int) = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
        fun vint(len: Int): ByteArray {
            require(len in 0..126)
            return byteArrayOf((0x80 or len).toByte())
        }
        fun elem(id: ByteArray, body: ByteArray): ByteArray = id + vint(body.size) + body
        fun str(id: ByteArray, text: String) = elem(id, text.toByteArray(Charsets.UTF_8))
        fun uint1(id: ByteArray, v: Int) = elem(id, byteArrayOf((v and 0xFF).toByte()))

        val trackAudio = elem(
            byteArrayOf(0xAE.toByte()),
            uint1(byteArrayOf(0xD7.toByte()), 1) +
                uint1(byteArrayOf(0x83.toByte()), 2) + // Audio
                str(byteArrayOf(0x86.toByte()), "A_AAC") +
                str(byteArrayOf(0x53, 0x6E), "Telegram - Anime Fair")
        )
        val trackAss = elem(
            byteArrayOf(0xAE.toByte()),
            uint1(byteArrayOf(0xD7.toByte()), 2) +
                uint1(byteArrayOf(0x83.toByte()), 17) + // Subtitle
                str(byteArrayOf(0x86.toByte()), "S_TEXT/ASS") +
                str(byteArrayOf(0x53, 0x6E), "Dialogue [English]")
        )
        val trackPgs = elem(
            byteArrayOf(0xAE.toByte()),
            uint1(byteArrayOf(0xD7.toByte()), 3) +
                uint1(byteArrayOf(0x83.toByte()), 17) + // Subtitle (Bitmap PGS)
                str(byteArrayOf(0x86.toByte()), "S_HDMV/PGS") +
                str(byteArrayOf(0x53, 0x6E), "PGS Sub")
        )
        val tracksEl = elem(byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B), trackAudio + trackAss + trackPgs)

        // BlockGroup inside unknown-size Cluster: Timecode=1000ms, Track 2 ASS event (rel +500ms, dur 2000ms)
        val assPayload = "0,0,Default,,0,0,0,,Hello Komi!".toByteArray(Charsets.UTF_8)
        val blockBody = byteArrayOf(0x82.toByte()) + u16(500) + byteArrayOf(0x00) + assPayload
        val blockGroup = elem(
            byteArrayOf(0xA0.toByte()),
            elem(byteArrayOf(0xA1.toByte()), blockBody) +
                elem(byteArrayOf(0x9B.toByte()), u16(2000))
        )
        // PGS SimpleBlock that must NOT fail or add garbage text
        val pgsBlock = elem(
            byteArrayOf(0xA3.toByte()),
            byteArrayOf(0x83.toByte()) + u16(600) + byteArrayOf(0x00, 0x16, 0x00)
        )
        val unknownSizeMarker = byteArrayOf(0xFF.toByte())
        val clusterUnknown =
            byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75) + unknownSizeMarker +
                elem(byteArrayOf(0xE7.toByte()), u16(1000)) +
                blockGroup +
                pgsBlock

        val ebmlHeader = elem(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), str(byteArrayOf(0x42, 0x82.toByte()), "matroska"))
        val segmentUnknownHeader =
            byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, 0x01) +
                byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        val tmp = File.createTempFile("demux_unknown_test_", ".mkv")
        try {
            tmp.writeBytes(ebmlHeader + segmentUnknownHeader + tracksEl + clusterUnknown)
            val result = ContainerDemuxer.parseMkv(tmp)

            assertEquals(3, result.tracks.size)
            assertEquals(2, result.tracks[1]?.type)
            assertEquals("Dialogue [English]", result.tracks[2]?.name)
            assertEquals("S_HDMV/PGS", result.tracks[3]?.codecId)
            assertNull(result.cuesByTrack[3]) // bitmap PGS excluded from text cues

            val cues = result.cuesByTrack[2].orEmpty()
            assertEquals(1, cues.size)
            assertEquals("Hello Komi!", cues[0].text)
            assertEquals(1500L, cues[0].startMs)
            assertEquals(3500L, cues[0].endMs)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun testParseMkvMultiTrack() {
        val mkvFile = File("src/androidTest/assets/multi-track.mkv")
        if (!mkvFile.exists()) return // skip if not found in working dir

        val result = ContainerDemuxer.parseMkv(mkvFile)
        assertTrue(result.tracks.isNotEmpty())
        assertEquals("S_TEXT/UTF8", result.tracks[4]?.codecId)
        assertEquals("S_TEXT/ASS", result.tracks[5]?.codecId)

        val engCues = result.cuesByTrack[4].orEmpty()
        assertTrue(engCues.isNotEmpty())
        assertEquals("MS PLAYER subtitle timing test", engCues[0].text)
        assertEquals(0L, engCues[0].startMs)
        assertEquals(10000L, engCues[0].endMs)

        val tamCues = result.cuesByTrack[5].orEmpty()
        assertTrue(tamCues.isNotEmpty())
        assertEquals("MS PLAYER ASS style test", tamCues[0].text)
    }

    @Test
    fun testDemuxExtractsContainerMetadataAndRespectsCancellation() {
        fun vint(len: Int) = byteArrayOf((0x80 or len).toByte())
        fun elem(id: ByteArray, body: ByteArray) = id + vint(body.size) + body
        fun str(id: ByteArray, text: String) = elem(id, text.toByteArray(Charsets.UTF_8))

        val ebmlHeader = elem(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), str(byteArrayOf(0x42, 0x82.toByte()), "matroska"))
        val infoEl = elem(
            byteArrayOf(0x15, 0x49, 0xA9.toByte(), 0x66),
            str(byteArrayOf(0x4D, 0x80.toByte()), "libebml v1.4.5") +
                str(byteArrayOf(0x57, 0x41), "libmatroska v1.7.1")
        )
        val tmp = File.createTempFile("demux_info_test_", ".mkv")
        try {
            tmp.writeBytes(ebmlHeader + infoEl)
            val result = ContainerDemuxer.parseMkv(tmp)
            assertEquals("Matroska / WebM", result.containerInfo.format)
            assertEquals("libebml v1.4.5 + libmatroska v1.7.1", result.containerInfo.encodingSoftware)

            val cancelled = ContainerDemuxer.parseMkv(tmp, isCancelled = { true })
            assertTrue(cancelled.tracks.isEmpty())
            assertTrue(cancelled.cuesByTrack.isEmpty())
        } finally {
            tmp.delete()
        }
    }
}

