package io.github.yunuserdemsihhatli.homecast.subtitles

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Decodes subtitle file bytes, falling back to Turkish Windows-1254 when the input is not valid UTF-8. */
object SubtitleDecoder {
    private val windows1254: Charset = Charset.forName("windows-1254")

    fun decode(bytes: ByteArray): String {
        bom(bytes)?.let { (charset, length) ->
            return String(bytes, length, bytes.size - length, charset)
        }
        return try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, windows1254)
        }
    }

    private fun bom(bytes: ByteArray): Pair<Charset, Int>? {
        fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF)
        return when {
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> Charsets.UTF_8 to 3
            at(0) == 0xFF && at(1) == 0xFE -> Charsets.UTF_16LE to 2
            at(0) == 0xFE && at(1) == 0xFF -> Charsets.UTF_16BE to 2
            else -> null
        }
    }
}
