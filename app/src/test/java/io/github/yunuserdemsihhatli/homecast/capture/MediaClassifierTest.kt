package io.github.yunuserdemsihhatli.homecast.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaClassifierTest {
    @Test fun recognisesHlsFileDashAndSubtitles() {
        assertEquals(Classification.Video(MediaKind.HLS), MediaClassifier.classifyUrl("https://cdn.x/a.m3u8?x=1"))
        assertEquals(Classification.Video(MediaKind.FILE), MediaClassifier.classifyUrl("https://cdn.x/a.mp4"))
        assertEquals(Classification.Video(MediaKind.FILE), MediaClassifier.classifyUrl("https://cdn.x/a.WEBM#t=3"))
        assertEquals(Classification.Video(MediaKind.FILE), MediaClassifier.classifyUrl("https://cdn.x/a.mkv"))
        assertEquals(Classification.Video(MediaKind.DASH), MediaClassifier.classifyUrl("https://cdn.x/a.mpd"))
        assertEquals(Classification.Subtitle(SubtitleFormat.VTT), MediaClassifier.classifyUrl("https://cdn.x/a.vtt"))
        assertEquals(
            Classification.Subtitle(SubtitleFormat.SRT),
            MediaClassifier.classifyUrl("https://cdn.x/a.srt?l=tr"),
        )
    }

    @Test fun ignoresSegmentsAndImages() {
        listOf("a.ts", "a.m4s", "a.aac", "a.png", "a.js", "https://x/noextension").forEach {
            assertNull(it, MediaClassifier.classifyUrl(it))
        }
    }

    @Test fun contentTypeHls() {
        assertEquals(
            Classification.Video(MediaKind.HLS),
            MediaClassifier.classifyContentType("application/vnd.apple.mpegurl"),
        )
        assertEquals(
            Classification.Video(MediaKind.HLS),
            MediaClassifier.classifyContentType("application/x-mpegURL; charset=utf-8"),
        )
        assertEquals(Classification.Subtitle(SubtitleFormat.VTT), MediaClassifier.classifyContentType("text/vtt"))
        assertEquals(Classification.Video(MediaKind.FILE), MediaClassifier.classifyContentType("video/mp4"))
        assertEquals(Classification.Video(MediaKind.DASH), MediaClassifier.classifyContentType("application/dash+xml"))
        assertNull(MediaClassifier.classifyContentType("text/html"))
        assertNull(MediaClassifier.classifyContentType(null))
    }
}
