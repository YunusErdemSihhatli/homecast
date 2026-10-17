package io.github.yunuserdemsihhatli.homecast.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateStoreTest {
    private var now = 0L
    private val store = CandidateStore { ++now }
    private val h = UpstreamHeaders(null, null, "UA", null)
    private val page = "https://site.example/watch"

    @Test fun hlsMasterRanksAboveFilesAndDuplicatesCollapse() {
        repeat(20) { store.addVideo("https://ads.x/pre$it.mp4", MediaKind.FILE, h, page, "t") }
        store.addVideo("https://cdn.y/master.m3u8", MediaKind.HLS, h, page, "t", isMaster = true)
        store.addVideo("https://cdn.y/master.m3u8#again", MediaKind.HLS, h, page, "t", isMaster = true)
        val list = store.candidates.value
        assertEquals("https://cdn.y/master.m3u8", list.first().url)
        assertEquals(21, list.size)
    }

    @Test fun newestFirstWithinRank() {
        store.addVideo("https://a/1.mp4", MediaKind.FILE, h, page, "t")
        store.addVideo("https://a/2.mp4", MediaKind.FILE, h, page, "t")
        assertEquals(listOf("https://a/2.mp4", "https://a/1.mp4"), store.candidates.value.map { it.url })
    }

    @Test fun hlsMediaPlaylistRanksAboveFile() {
        store.addVideo("https://a/1.mp4", MediaKind.FILE, h, page, "t")
        store.addVideo("https://b/v.m3u8", MediaKind.HLS, h, page, "t")
        assertEquals(
            "https://b/v.m3u8",
            store.candidates.value
                .first()
                .url,
        )
    }

    @Test fun variantPlaylistsHiddenWhenMasterPresent() {
        store.addVideo("https://cdn.y/v/720/index.m3u8", MediaKind.HLS, h, page, "t")
        store.addVideo("https://cdn.y/v/master.m3u8", MediaKind.HLS, h, page, "t", isMaster = true)
        store.addVideo("https://cdn.y/v/480/index.m3u8", MediaKind.HLS, h, page, "t")
        store.addVideo("https://other.z/v/index.m3u8", MediaKind.HLS, h, page, "t")
        assertEquals(
            listOf("https://cdn.y/v/master.m3u8", "https://other.z/v/index.m3u8"),
            store.candidates.value.map { it.url },
        )
    }

    @Test fun markMasterPromotesExistingCandidate() {
        store.addVideo("https://cdn.y/v/master.m3u8", MediaKind.HLS, h, page, "t")
        store.addVideo("https://cdn.y/v/a/index.m3u8", MediaKind.HLS, h, page, "t")
        store.markMaster("https://cdn.y/v/master.m3u8")
        val list = store.candidates.value
        assertEquals(1, list.size)
        assertTrue(list.single().isMaster)
    }

    @Test fun dashCandidatesListedLast() {
        store.addVideo("https://a/m.mpd", MediaKind.DASH, h, page, "t")
        store.addVideo("https://a/1.mp4", MediaKind.FILE, h, page, "t")
        assertEquals(
            MediaKind.DASH,
            store.candidates.value
                .last()
                .kind,
        )
    }

    @Test fun subtitlesDeduplicateById() {
        val s = SubtitleSource("1", "TR", "tr", SubtitleFormat.VTT, url = "https://a/s.vtt")
        store.addSubtitle(s)
        store.addSubtitle(s)
        assertEquals(listOf(s), store.subtitles.value)
    }

    @Test fun pageChangeClearsCandidatesAndSubtitles() {
        store.addVideo("https://a/1.mp4", MediaKind.FILE, h, page, "t")
        store.addSubtitle(SubtitleSource("1", "TR", "tr", SubtitleFormat.VTT, url = "https://a/s.vtt"))
        store.onPageChanged("https://site.example/other")
        assertTrue(store.candidates.value.isEmpty())
        assertTrue(store.subtitles.value.isEmpty())
    }

    @Test fun fallbackHeadersAreUpgradedByConfirmedOnes() {
        val fallback = UpstreamHeaders("https://site.example/watch", null, "UA", null)
        val iframe = UpstreamHeaders("https://player.example/embed/1", null, "UA", null)
        store.addVideo("https://cdn.y/v.m3u8", MediaKind.HLS, fallback, page, "t", fallbackHeaders = true)

        store.addVideo("https://cdn.y/v.m3u8", MediaKind.HLS, iframe, page, "t")

        val upgraded = store.candidates.value.single()
        assertEquals(iframe, upgraded.headers)
        assertFalse(upgraded.fallbackHeaders)
    }

    @Test fun confirmedHeadersAreNeverReplaced() {
        val iframe = UpstreamHeaders("https://player.example/embed/1", null, "UA", null)
        val other = UpstreamHeaders("https://elsewhere.example/", null, "UA", null)
        store.addVideo("https://cdn.y/v.m3u8", MediaKind.HLS, iframe, page, "t")

        store.addVideo("https://cdn.y/v.m3u8", MediaKind.HLS, other, page, "t")
        store.addVideo("https://cdn.y/v.m3u8", MediaKind.HLS, other, page, "t", fallbackHeaders = true)

        assertEquals(
            iframe,
            store.candidates.value
                .single()
                .headers,
        )
    }

    @Test fun networkConfirmedMasterRanksAboveScriptOnlyMaster() {
        store.addVideo(
            "https://cdn.y/real/master",
            MediaKind.HLS,
            h,
            page,
            "t",
            isMaster = true,
            networkConfirmed = true,
        )
        store.addVideo("https://evil.z/fake/master", MediaKind.HLS, h, page, "t", isMaster = true)

        assertEquals(
            listOf("https://cdn.y/real/master", "https://evil.z/fake/master"),
            store.candidates.value.map { it.url },
        )
    }

    @Test fun networkConfirmationIsKeptWhenMerging() {
        store.addVideo("https://cdn.y/v/master", MediaKind.HLS, h, page, "t", networkConfirmed = true)
        store.addVideo("https://cdn.y/v/master", MediaKind.HLS, h, page, "t", isMaster = true)

        val merged = store.candidates.value.single()
        assertTrue(merged.isMaster)
        assertTrue(merged.networkConfirmed)
    }

    @Test fun subtitleShowingStateIsUpdatedInPlace() {
        val a = SubtitleSource("a", "EN", "en", SubtitleFormat.VTT, url = "https://a/en.vtt")
        val b = SubtitleSource("b", "TR", "tr", SubtitleFormat.VTT, url = "https://a/tr.vtt")
        store.addSubtitle(a)
        store.addSubtitle(b)

        store.setSubtitleShowing("a", true)

        assertEquals(listOf(a.copy(showing = true), b), store.subtitles.value)
    }

    @Test fun addingAnAlreadyCapturedSubtitleNeverChangesItsShowingState() {
        val track = SubtitleSource("u", "TR", "tr", SubtitleFormat.VTT, url = "https://a/tr.vtt", showing = true)
        store.addSubtitle(track) // reported by page script: showing

        store.addSubtitle(track.copy(label = "tr.vtt", showing = false)) // the same file seen by interception

        assertEquals(listOf(track), store.subtitles.value)
        store.setSubtitleShowing("u", false)
        assertEquals(
            false,
            store.subtitles.value
                .single()
                .showing,
        )
    }

    private fun intercepted(url: String) =
        SubtitleSource(url, url.substringAfterLast('/'), null, SubtitleFormat.VTT, url = url)

    private fun fromTrack(
        url: String,
        label: String = "Türkçe",
    ) = SubtitleSource(url, label, "tr", SubtitleFormat.VTT, url = url, reportedByPage = true)

    @Test fun interceptedHlsSubtitleSegmentsAreNotCaptured() {
        for (url in listOf(
            "https://cdn.y/subs/en/0001.vtt",
            "https://cdn.y/subs/en/12.vtt?token=a",
            "https://cdn.y/subs/en/seg-3.vtt",
            "https://cdn.y/subs/en/segment_12.vtt",
            "https://cdn.y/subs/en/chunk4.vtt",
            "https://cdn.y/subs/en/frag-7.vtt",
            "https://cdn.y/subs/en/en_seg00004.vtt",
        )) {
            store.addSubtitle(intercepted(url))
        }
        assertTrue(store.subtitles.value.toString(), store.subtitles.value.isEmpty())
    }

    @Test fun interceptedVttInTheDirectoryOfASubtitlePlaylistIsASegment() {
        val fromPage = h.copy(referer = page)
        store.addVideo("https://cdn.y/subs/en/index.m3u8", MediaKind.HLS, fromPage, page, "t") // seen by interception
        store.addSubtitle(intercepted("https://cdn.y/subs/en/english-a.vtt")) // arrived before the report

        store.addSubtitlePlaylist("https://cdn.y/subs/en/index.m3u8", "https://site.example")
        store.addSubtitle(intercepted("https://cdn.y/subs/en/english-b.vtt"))
        store.addSubtitle(intercepted("https://cdn.y/other/english.vtt"))

        assertEquals(listOf("https://cdn.y/other/english.vtt"), store.subtitles.value.map { it.url })
        assertTrue("a subtitle rendition is not a video", store.candidates.value.isEmpty())
    }

    @Test fun subtitlePlaylistReportNeverRemovesAVideoCapturedFromAnotherOrigin() {
        val fromPlayer = h.copy(referer = "https://player.other/embed/1")
        store.addVideo("https://cdn.y/v/index.m3u8", MediaKind.HLS, fromPlayer, page, "t")

        store.addSubtitlePlaylist("https://cdn.y/v/index.m3u8", reporterOrigin = "https://site.example")
        store.addSubtitlePlaylist("https://cdn.y/v/index.m3u8", reporterOrigin = null)

        assertEquals(listOf("https://cdn.y/v/index.m3u8"), store.candidates.value.map { it.url })
    }

    @Test fun subtitlePlaylistReportFromTheCapturingOriginRemovesIt() {
        val fromPlayer = h.copy(referer = "https://player.other/embed/1")
        store.addVideo("https://cdn.y/subs/index.m3u8", MediaKind.HLS, fromPlayer, page, "t")

        store.addSubtitlePlaylist("https://cdn.y/subs/index.m3u8", reporterOrigin = "https://player.other:443")

        assertTrue(store.candidates.value.isEmpty())
    }

    @Test fun untrustedTrackReportCannotReplaceOrOutrankIntercepted() {
        // The browser passes reportedByPage = false for a track report it does not trust (ReportTrust).
        store.addSubtitle(intercepted("https://cdn.y/s/a.vtt"))
        store.addSubtitle(intercepted("https://cdn.y/s/a.vtt").copy(label = "Spoofed"))
        repeat(25) { store.addSubtitle(intercepted("https://cdn.y/s/x$it.vtt").copy(label = "untrusted")) }

        val list = store.subtitles.value
        assertEquals(CandidateStore.MAX_SUBTITLES, list.size)
        assertFalse(list.any { it.label == "Spoofed" })
        assertFalse(list.any { it.reportedByPage })
    }

    @Test fun sidecarSubtitleNextToAVideoPlaylistIsKept() {
        store.addVideo("https://cdn.y/film/index.m3u8", MediaKind.HLS, h, page, "t")
        store.addSubtitle(intercepted("https://cdn.y/film/tr.vtt"))
        assertEquals(listOf("https://cdn.y/film/tr.vtt"), store.subtitles.value.map { it.url })
    }

    @Test fun pageChangeForgetsSubtitlePlaylists() {
        store.addSubtitlePlaylist("https://cdn.y/subs/en/index.m3u8", "https://site.example")
        store.onPageChanged("https://site.example/next")
        store.addSubtitle(intercepted("https://cdn.y/subs/en/english.vtt"))
        assertEquals(1, store.subtitles.value.size)
    }

    @Test fun ordinarySubtitleFilesAreStillCaptured() {
        for (url in listOf(
            "https://cdn.y/films/film-2019.tr.vtt",
            "https://cdn.y/films/segunda-parte.vtt",
            "https://cdn.y/s/English.vtt",
        )) {
            store.addSubtitle(intercepted(url))
        }
        assertEquals(3, store.subtitles.value.size)
    }

    @Test fun thumbnailTracksAreNeverCaptured() {
        store.addSubtitle(intercepted("https://cdn.y/thumbs/sprite.vtt"))
        store.addSubtitle(intercepted("https://cdn.y/v/storyboard.vtt"))
        store.addSubtitle(fromTrack("https://cdn.y/v/preview-thumbnails.vtt"))
        store.addSubtitle(intercepted("https://cdn.y/v/thumbnails/en.vtt"))
        assertTrue(store.subtitles.value.toString(), store.subtitles.value.isEmpty())
    }

    @Test fun trackReportedSubtitlesComeFirstAndReplaceTheirInterceptedCopy() {
        store.addSubtitle(intercepted("https://cdn.y/s/a.vtt"))
        store.addSubtitle(intercepted("https://cdn.y/s/b.vtt"))
        store.setSubtitleShowing("https://cdn.y/s/b.vtt", true)

        store.addSubtitle(fromTrack("https://cdn.y/s/b.vtt", label = "English"))

        val list = store.subtitles.value
        assertEquals(listOf("https://cdn.y/s/b.vtt", "https://cdn.y/s/a.vtt"), list.map { it.url })
        assertEquals("English", list[0].label)
        assertTrue(list[0].reportedByPage)
        assertTrue("showing state kept", list[0].showing)
    }

    @Test fun atMostTwentySubtitlesDroppingTheOldestInterceptedFirst() {
        store.addSubtitle(fromTrack("https://cdn.y/s/page.vtt"))
        repeat(25) { store.addSubtitle(intercepted("https://cdn.y/s/lang$it.vtt")) }

        val urls = store.subtitles.value.map { it.url }
        assertEquals(CandidateStore.MAX_SUBTITLES, urls.size)
        assertEquals("https://cdn.y/s/page.vtt", urls.first())
        assertFalse("oldest intercepted dropped", "https://cdn.y/s/lang0.vtt" in urls)
        assertTrue("newest kept", "https://cdn.y/s/lang24.vtt" in urls)
    }

    @Test fun inlineSubtitlesWithoutUrlAreKept() {
        val inline = SubtitleSource("file-1", "Mine", null, SubtitleFormat.VTT, inlineVtt = "WEBVTT\n")
        store.addSubtitle(inline)
        assertEquals(listOf(inline), store.subtitles.value)
    }
}
