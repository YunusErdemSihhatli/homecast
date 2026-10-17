package io.github.yunuserdemsihhatli.homecast.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureMessageTest {
    @Test fun parsesHlsMessage() {
        val msg =
            CaptureMessage.parse(
                """{"type":"hls","url":"https://cdn.example/master","frame":"https://player.example/e/1","master":true}""",
                sourceOrigin = null,
            )

        assertEquals(CaptureMessage.Hls("https://cdn.example/master", "https://player.example/e/1", master = true), msg)
    }

    @Test fun parsesVideoAndTrackMessages() {
        assertEquals(
            CaptureMessage.Video("http://cdn.example/a.mp4", frame = null),
            CaptureMessage.parse("""{"type":"video","url":"http://cdn.example/a.mp4"}""", sourceOrigin = null),
        )
        assertEquals(
            CaptureMessage.Track(
                "https://cdn.example/tr.vtt",
                "https://page.example/",
                label = "Türkçe",
                language = "tr",
            ),
            CaptureMessage.parse(
                """{"type":"track","url":"https://cdn.example/tr.vtt","frame":"https://page.example/","label":"Türkçe","lang":"tr"}""",
                sourceOrigin = null,
            ),
        )
    }

    @Test fun rejectsNonHttpUrls() {
        assertNull(CaptureMessage.parse("""{"type":"video","url":"javascript:alert(1)"}""", sourceOrigin = null))
        assertNull(
            CaptureMessage.parse("""{"type":"video","url":"blob:https://page.example/123"}""", sourceOrigin = null),
        )
        assertNull(CaptureMessage.parse("""{"type":"hls","url":"file:///sdcard/x.m3u8"}""", sourceOrigin = null))
    }

    @Test fun dropsNonHttpFrame() {
        val msg =
            CaptureMessage.parse(
                """{"type":"video","url":"https://cdn.example/a.mp4","frame":"about:srcdoc"}""",
                sourceOrigin = null,
            )

        assertEquals(CaptureMessage.Video("https://cdn.example/a.mp4", frame = null), msg)
    }

    @Test fun rejectsMalformedUnknownAndOversizedMessages() {
        assertNull(CaptureMessage.parse("not json", sourceOrigin = null))
        assertNull(CaptureMessage.parse("""{"type":"eval","url":"https://cdn.example/a.mp4"}""", sourceOrigin = null))
        assertNull(CaptureMessage.parse("""{"type":"video","url":42}""", sourceOrigin = null))
        assertNull(CaptureMessage.parse("""{"type":"video"}""", sourceOrigin = null))
        val longUrl = "https://cdn.example/" + "a".repeat(5000) + ".mp4"
        assertNull(CaptureMessage.parse("""{"type":"video","url":"$longUrl"}""", sourceOrigin = null))
    }

    @Test fun capsTrackLabelAndLanguage() {
        val msg =
            CaptureMessage.parse(
                """{"type":"track","url":"https://cdn.example/x.vtt","label":"${"L".repeat(
                    300,
                )}","lang":"${"x".repeat(100)}"}""",
                sourceOrigin = null,
            ) as CaptureMessage.Track

        assertEquals(100, msg.label.length)
        assertEquals(35, msg.language!!.length)
    }

    @Test fun acceptsFrameFromTheSendingOrigin() {
        val msg =
            CaptureMessage.parse(
                """{"type":"hls","url":"https://cdn.example/m","frame":"https://player.example:8443/e/1"}""",
                sourceOrigin = "https://player.example:8443",
            )

        assertEquals(
            CaptureMessage.Hls("https://cdn.example/m", "https://player.example:8443/e/1", master = false),
            msg,
        )
    }

    @Test fun rejectsFrameClaimingAnotherOrigin() {
        val msg =
            CaptureMessage.parse(
                """{"type":"hls","url":"https://cdn.example/m","frame":"https://victim.example/watch","master":true}""",
                sourceOrigin = "https://ads.example",
            )

        assertNull(msg)
    }

    @Test fun usesSendingOriginWhenFrameIsMissingOrUnverifiable() {
        assertEquals(
            CaptureMessage.Video("https://cdn.example/a.mp4", frame = "https://player.example/"),
            CaptureMessage.parse(
                """{"type":"video","url":"https://cdn.example/a.mp4"}""",
                sourceOrigin = "https://player.example",
            ),
        )
        assertEquals(
            CaptureMessage.Video("https://cdn.example/a.mp4", frame = null),
            CaptureMessage.parse(
                """{"type":"video","url":"https://cdn.example/a.mp4","frame":"https://player.example/"}""",
                sourceOrigin = "null",
            ),
        )
    }

    @Test fun parsesShowingTrack() {
        val msg =
            CaptureMessage.parse(
                """{"type":"track","url":"https://cdn.example/tr.vtt","label":"TR","lang":"tr","showing":true}""",
                sourceOrigin = null,
            ) as CaptureMessage.Track

        assertTrue(msg.showing)
    }

    @Test fun parsesSubtitlePlaylist() {
        val msg =
            CaptureMessage.parse(
                """{"type":"subplaylist","url":"https://cdn.y/subs/en/index.m3u8","frame":"https://p.example/x"}""",
                "https://p.example",
            )
        assertEquals(CaptureMessage.SubtitlePlaylist("https://cdn.y/subs/en/index.m3u8", "https://p.example/x"), msg)
    }
}
