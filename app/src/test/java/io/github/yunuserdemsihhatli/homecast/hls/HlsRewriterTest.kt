package io.github.yunuserdemsihhatli.homecast.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsRewriterTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { "missing $name" }
            .bufferedReader()
            .use { it.readText() }

    private val marker: (String, HlsUriKind) -> String = { u, k -> "P<$k>[$u]" }

    @Test
    fun rewritesVariantUrisInMaster() {
        val out = HlsRewriter.rewrite(fixture("master.m3u8"), "https://host.example/v/master.m3u8", marker)
        assertTrue(out.contains("\nP<PLAYLIST>[https://host.example/v/360p/index.m3u8]\n"))
        assertTrue(out.contains("\nP<PLAYLIST>[https://cdn.example/720p/index.m3u8?sig=2]\n"))
        assertTrue(out.contains("URI=\"P<PLAYLIST>[https://host.example/v/iframe/index.m3u8]\""))
    }

    @Test
    fun rewritesSegmentsRelativeAndAbsolute() {
        val body = "#EXTM3U\n#EXTINF:4,\nseg1.ts\n#EXTINF:4,\n/abs/seg2.ts\n#EXTINF:4,\nhttps://cdn.x/seg3.ts\n"
        val out = HlsRewriter.rewrite(body, "https://host.example/a/b/index.m3u8", marker)
        assertTrue(out.contains("\nP<SEGMENT>[https://host.example/a/b/seg1.ts]\n"))
        assertTrue(out.contains("\nP<SEGMENT>[https://host.example/abs/seg2.ts]\n"))
        assertTrue(out.contains("\nP<SEGMENT>[https://cdn.x/seg3.ts]\n"))
    }

    @Test
    fun rewritesKeyMapAndMediaUriAttributes() {
        val keys = HlsRewriter.rewrite(fixture("media_keys_map.m3u8"), "https://h.example/v/i.m3u8", marker)
        assertTrue(keys.contains("#EXT-X-MAP:URI=\"P<INIT>[https://h.example/v/init.mp4]\",BYTERANGE=\"720@0\""))
        assertTrue(
            keys.contains(
                "#EXT-X-KEY:METHOD=AES-128,URI=\"P<KEY>[https://h.example/v/k.key]\",IV=0x00000000000000000000000000000001",
            ),
        )
        assertTrue(keys.contains("\n#EXT-X-KEY:METHOD=NONE\n"))

        val master = HlsRewriter.rewrite(fixture("master.m3u8"), "https://h.example/v/m.m3u8", marker)
        assertTrue(
            master.contains(
                "NAME=\"English\",LANGUAGE=\"en\",URI=\"P<PLAYLIST>[https://h.example/v/audio/en.m3u8]\"",
            ),
        )
        assertTrue(
            master.contains(
                "TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"English\",LANGUAGE=\"en\"," +
                    "URI=\"P<SUBTITLE_PLAYLIST>[https://h.example/v/subs/en.m3u8]\"",
            ),
        )
        assertTrue(master.contains("BANDWIDTH=800000,RESOLUTION=640x360,AUDIO=\"aud\",SUBTITLES=\"subs\""))
    }

    @Test
    fun resolvesAgainstFinalUrlAndKeepsQuery() {
        val out =
            HlsRewriter.rewrite(
                "#EXTM3U\n#EXTINF:4,\nseg1.ts?token=abc\n",
                "https://cdn.example/v/1/index.m3u8?sig=1",
            ) { u, _ -> "P[$u]" }
        assertTrue(out.contains("P[https://cdn.example/v/1/seg1.ts?token=abc]"))
    }

    @Test
    fun skipsDataAndSkdUris() {
        val body =
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\"\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"data:text/plain;base64,AAAA\"\n"
        assertEquals(body, HlsRewriter.rewrite(body, "https://h.example/i.m3u8", marker))
    }

    @Test
    fun detectsLiveAndVod() {
        assertTrue(HlsRewriter.isLive(fixture("media_live.m3u8")))
        assertFalse(HlsRewriter.isLive(fixture("media_vod.m3u8")))
        assertFalse(HlsRewriter.isLive(fixture("master.m3u8")))
        assertTrue(HlsRewriter.isMaster(fixture("master.m3u8")))
        assertFalse(HlsRewriter.isMaster(fixture("media_vod.m3u8")))
        assertTrue(HlsRewriter.isPlaylist("\n  #EXTM3U\n#EXT-X-VERSION:3"))
        assertFalse(HlsRewriter.isPlaylist("<html>"))
    }

    @Test
    fun preservesCommentsTagsAndBlankLines() {
        val body = "#EXTM3U\n# a comment\n\n#EXT-X-VERSION:3\r\n#EXTINF:4,\r\nseg1.ts\r\n\n#EXT-X-ENDLIST\n"
        val out = HlsRewriter.rewrite(body, "https://h.example/i.m3u8") { u, _ -> u }
        assertEquals(body.replace("seg1.ts", "https://h.example/seg1.ts"), out)
    }

    @Test
    fun firstSegmentUrlResolves() {
        assertEquals(
            "https://h.example/v/seg1.ts",
            HlsRewriter.firstSegmentUrl(fixture("media_vod.m3u8"), "https://h.example/v/i.m3u8"),
        )
        assertNull(HlsRewriter.firstSegmentUrl("#EXTM3U\n", "https://h.example/v/i.m3u8"))
    }
}
