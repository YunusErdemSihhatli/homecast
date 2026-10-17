package io.github.yunuserdemsihhatli.homecast.subtitles

import org.junit.Assert.assertEquals
import org.junit.Test

class SrtToVttTest {
    @Test
    fun convertsTimestampsAndAddsHeader() {
        val srt =
            "1\r\n00:00:01,500 --> 00:00:03,000\r\nHello <i>world</i>\r\n\r\n" +
                "2\r\n00:01:02,003 --> 00:01:04,000\r\nLine 1\r\nLine 2\r\n"
        assertEquals(
            "WEBVTT\n\n00:00:01.500 --> 00:00:03.000\nHello <i>world</i>\n\n" +
                "00:01:02.003 --> 00:01:04.000\nLine 1\nLine 2\n",
            SrtToVtt.convert(srt),
        )
    }

    @Test
    fun stripsUnsupportedFontTagsButKeepsBasicFormatting() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n<font color=\"#fff\">Hi</font> <b>a</b><i>b</i><u>c</u>\n"
        assertEquals(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi <b>a</b><i>b</i><u>c</u>\n",
            SrtToVtt.convert(srt),
        )
    }

    @Test
    fun toleratesMissingIndexAndExtraBlankLines() {
        val srt = "\n\n00:00:01,000 --> 00:00:02,000\nA\n\n\n\n5\n00:00:03,000 --> 00:00:04,000\nB\n\n\n"
        assertEquals(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nA\n\n00:00:03.000 --> 00:00:04.000\nB\n",
            SrtToVtt.convert(srt),
        )
    }

    @Test
    fun padsSingleDigitHours() {
        assertEquals(
            "WEBVTT\n\n01:02:03.004 --> 01:02:05.000\nx\n",
            SrtToVtt.convert("1\n1:02:03,004 --> 1:02:05,000\nx\n"),
        )
    }

    @Test
    fun keepsCommasInCueText() {
        assertEquals(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nYes, 00:00:09,123 sir\n",
            SrtToVtt.convert("1\n00:00:01,000 --> 00:00:02,000\nYes, 00:00:09,123 sir\n"),
        )
    }

    @Test
    fun keepsNumericCueTextLine() {
        assertEquals(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n42\nlines\n",
            SrtToVtt.convert("1\n00:00:01,000 --> 00:00:02,000\n42\nlines\n"),
        )
    }

    @Test
    fun handlesBareCrLineEndings() {
        assertEquals(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nA\n",
            SrtToVtt.convert("1\r00:00:01,000 --> 00:00:02,000\rA\r"),
        )
    }

    @Test
    fun ensureVttPassesThroughVtt() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nA\n"
        assertEquals(vtt, SrtToVtt.ensureVtt(vtt))
    }

    @Test
    fun ensureVttConvertsSrt() {
        assertEquals(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nA\n",
            SrtToVtt.ensureVtt("1\n00:00:01,000 --> 00:00:02,000\nA\n"),
        )
    }
}
