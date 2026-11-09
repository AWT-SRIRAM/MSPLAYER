package app.msplayer.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OpenSubtitlesClientTest {

    @Test
    fun cleanMediaQueryStripsReleaseTagsAndHandles() {
        assertEquals(
            "Komi S2 E06",
            OpenSubtitlesClient.cleanMediaQuery("Komi - S2 E06 [Dual] 480p @Anime_Fair.mkv")
        )
        assertEquals(
            "Inception 2010",
            OpenSubtitlesClient.cleanMediaQuery("Inception.2010.1080p.BluRay.x264.AAC.mp4")
        )
        assertEquals(
            "Spider-Man Across the Spider-Verse 2023",
            OpenSubtitlesClient.cleanMediaQuery("Spider-Man_Across_the_Spider-Verse_(2023)_WEB-DL_10bit_HEVC.mkv")
        )
        assertEquals(
            "Big Buck Bunny",
            OpenSubtitlesClient.cleanMediaQuery("https://cdn.example.com/media/Big.Buck.Bunny.1080p.mp4?token=123")
        )
    }

    @Test
    fun browserSearchUrlEncodesQueryAndLanguage() {
        val url = OpenSubtitlesClient.browserSearchUrl("Komi S2 E06", "eng")
        assertTrue(url.startsWith("https://www.opensubtitles.org/en/search2/sublanguageid-eng/"))
        assertTrue(url.contains("moviename-Komi%20S2%20E06"))

        val emptyUrl = OpenSubtitlesClient.browserSearchUrl("  ", "all")
        assertEquals("https://www.opensubtitles.org/en/search/sublanguageid-all", emptyUrl)
    }

    @Test
    fun computeMovieHashMatchesOpenSubtitles64BitSpec() {
        val dir = Files.createTempDirectory("os_hash_test").toFile()
        try {
            val tiny = java.io.File(dir, "tiny.bin").apply { writeBytes(ByteArray(1024)) }
            assertNull(OpenSubtitlesClient.computeMovieHash(tiny))

            // 131072 zero bytes -> hash equals file size = 0x20000
            val zeros = java.io.File(dir, "zeros.bin").apply { writeBytes(ByteArray(131072)) }
            assertEquals("0000000000020000", OpenSubtitlesClient.computeMovieHash(zeros))

            // Known non-zero first 8 bytes (LE 1) + last 8 bytes (LE 2) + size 131072 -> 0x20003
            val sampleBytes = ByteArray(131072)
            sampleBytes[0] = 1
            sampleBytes[sampleBytes.size - 8] = 2
            val patterned = java.io.File(dir, "pat.bin").apply { writeBytes(sampleBytes) }
            assertEquals("0000000000020003", OpenSubtitlesClient.computeMovieHash(patterned))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun parseSearchResponsePrioritizesHashMatchAndPopularity() {
        val sample = """
            {
              "total_count": 2,
              "data": [
                {
                  "id": "1",
                  "attributes": {
                    "language": "en",
                    "download_count": 4200,
                    "release": "Komi.S02E06.WEB-DL",
                    "fps": 23.976,
                    "moviehash_match": false,
                    "hearing_impaired": false,
                    "uploader": {"name": "subuser"},
                    "files": [{"file_id": 101, "file_name": "Komi.S02E06.srt"}]
                  }
                },
                {
                  "id": "2",
                  "attributes": {
                    "language": "en",
                    "download_count": 300,
                    "release": "Komi.S02E06.Dual.480p",
                    "fps": 23.976,
                    "moviehash_match": true,
                    "hearing_impaired": true,
                    "uploader": {"name": "matcher"},
                    "files": [{"file_id": 202, "file_name": "Komi.Dual.srt"}]
                  }
                }
              ]
            }
        """.trimIndent()

        val list = OpenSubtitlesClient.parseSearchResponse(sample)
        assertEquals(2, list.size)
        // Hash match comes first even with lower download count
        assertEquals(202, list[0].fileId)
        assertTrue(list[0].hashMatch)
        assertTrue(list[0].hearingImpaired)
        assertEquals(101, list[1].fileId)
        assertEquals("EN", list[1].language)
    }

    @Test
    fun extractSubtitleFromZipArchiveFiltersNonSubtitlesAndKeepsSrt() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("readme.nfo"))
            zos.write("info text".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("__MACOSX/._hidden.srt"))
            zos.write("ignored".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("subs/episode.en.srt"))
            zos.write("1\n00:00:01,000 --> 00:00:03,000\nHello OpenSubtitles\n".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("subs/signs.ass"))
            zos.write("[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Sign\n".toByteArray())
            zos.closeEntry()
        }

        val destDir = Files.createTempDirectory("os_zip_test").toFile()
        try {
            val files = OpenSubtitlesClient.extractSubtitleFromStream(
                input = ByteArrayInputStream(baos.toByteArray()),
                originalName = "komi_subs.zip",
                destDir = destDir,
                preferredPrefix = "Komi S2 E06",
            )
            assertEquals(2, files.size)
            assertEquals("srt", files[0].extension)
            assertNotNull(files[0].readText().takeIf { it.contains("Hello OpenSubtitles") })
            assertEquals("ass", files[1].extension)
        } finally {
            destDir.deleteRecursively()
        }
    }
}

