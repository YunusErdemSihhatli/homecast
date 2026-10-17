package io.github.yunuserdemsihhatli.homecast.cast.protocol

import io.github.yunuserdemsihhatli.homecast.cast.LoadRequest
import io.github.yunuserdemsihhatli.homecast.cast.StreamType
import io.github.yunuserdemsihhatli.homecast.proxy.ProxiedSubtitle
import io.github.yunuserdemsihhatli.homecast.proxy.SegmentFormat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastJsonTest {
    private fun request(
        format: SegmentFormat? = null,
        subtitles: List<ProxiedSubtitle> = emptyList(),
        active: List<Int> = emptyList(),
    ) = LoadRequest(
        url = "http://192.168.1.50:8080/p/t/master.m3u8",
        contentType = "application/x-mpegURL",
        streamType = StreamType.BUFFERED,
        title = "Film",
        subtitles = subtitles,
        activeTrackIds = active,
        startTimeSeconds = 0.0,
        hlsSegmentFormat = format,
    )

    private fun JsonObject.media() = this["media"]!!.jsonObject

    @Test fun fmp4LoadAlsoNamesTheVideoSegmentFormat() {
        val media = CastJson.load("s1", request(SegmentFormat.FMP4)).media()
        assertEquals("fmp4", media["hlsSegmentFormat"]!!.jsonPrimitive.content)
        assertEquals("fmp4", media["hlsVideoSegmentFormat"]!!.jsonPrimitive.content)
    }

    @Test fun tsLoadHasNoVideoSegmentFormat() {
        val media = CastJson.load("s1", request(SegmentFormat.TS)).media()
        assertEquals("ts", media["hlsSegmentFormat"]!!.jsonPrimitive.content)
        assertFalse(media.containsKey("hlsVideoSegmentFormat"))
    }

    @Test fun loadNeverCarriesMoreThanTwentyTracksAndKeepsTheActiveOnes() {
        val subtitles = (1..30).map { ProxiedSubtitle(it, "http://192.168.1.50:8080/s/$it.vtt", "Track $it", null) }
        val json = CastJson.load("s1", request(subtitles = subtitles, active = listOf(2)))

        val ids = json.media()["tracks"]!!.jsonArray.map { it.jsonObject["trackId"]!!.jsonPrimitive.int }
        assertEquals(CastJson.MAX_TRACKS, ids.size)
        assertTrue("active track kept", 2 in ids)
        assertTrue("newest track kept", 30 in ids)
        assertEquals("original order kept", ids.sorted(), ids)
        assertEquals(listOf(2), json["activeTrackIds"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    @Test fun activeIdsOfDroppedTracksAreNotSent() {
        val subtitles = (1..25).map { ProxiedSubtitle(it, "http://192.168.1.50:8080/s/$it.vtt", "Track $it", null) }
        val json = CastJson.load("s1", request(subtitles = subtitles, active = listOf(99)))
        assertTrue(json["activeTrackIds"]!!.jsonArray.isEmpty())
    }
}
