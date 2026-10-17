package io.github.yunuserdemsihhatli.homecast.cast.protocol

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Hand-written protobuf (proto2) codec for the CASTV2 `CastMessage` body. No length prefix; framing
 * is [CastChannel]'s job. Fields: 1 protocol_version, 2 source_id, 3 destination_id, 4 namespace,
 * 5 payload_type, 6 payload_utf8, 7 payload_binary (ignored on decode).
 */
object CastMessageCodec {
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_FIXED32 = 5

    fun encode(m: CastMessage): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeVarintField(1, 0) // CASTV2_1_0
        out.writeStringField(2, m.sourceId)
        out.writeStringField(3, m.destinationId)
        out.writeStringField(4, m.namespace)
        out.writeVarintField(5, 0) // STRING
        out.writeStringField(6, m.payloadUtf8)
        return out.toByteArray()
    }

    /** @throws IOException if [bytes] is not a well-formed message. */
    fun decode(bytes: ByteArray): CastMessage {
        val r = Reader(bytes)
        var source = ""
        var dest = ""
        var ns = ""
        var payload = ""
        while (r.hasMore()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            when (val wire = (tag and 7).toInt()) {
                WIRE_VARINT -> {
                    r.varint()
                }

                WIRE_FIXED64 -> {
                    r.skip(8)
                }

                WIRE_FIXED32 -> {
                    r.skip(4)
                }

                WIRE_LEN -> {
                    val data = r.lengthDelimited()
                    when (field) {
                        2 -> source = data.toString(Charsets.UTF_8)
                        3 -> dest = data.toString(Charsets.UTF_8)
                        4 -> ns = data.toString(Charsets.UTF_8)
                        6 -> payload = data.toString(Charsets.UTF_8)
                        // 7 (payload_binary) and unknown fields are ignored.
                    }
                }

                else -> {
                    throw IOException("Unsupported protobuf wire type $wire")
                }
            }
        }
        return CastMessage(source, dest, ns, payload)
    }

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        write(v.toInt())
    }

    private fun ByteArrayOutputStream.writeVarintField(
        field: Int,
        value: Long,
    ) {
        writeVarint((field shl 3 or WIRE_VARINT).toLong())
        writeVarint(value)
    }

    private fun ByteArrayOutputStream.writeStringField(
        field: Int,
        value: String,
    ) {
        val data = value.toByteArray(Charsets.UTF_8)
        writeVarint((field shl 3 or WIRE_LEN).toLong())
        writeVarint(data.size.toLong())
        write(data)
    }

    private class Reader(
        private val buf: ByteArray,
    ) {
        private var pos = 0

        fun hasMore() = pos < buf.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= buf.size) throw IOException("Truncated varint")
                if (shift > 63) throw IOException("Varint too long")
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun skip(n: Int) {
            if (n > buf.size - pos) throw IOException("Truncated field")
            pos += n
        }

        fun lengthDelimited(): ByteArray {
            val len = varint()
            if (len < 0 || len > buf.size - pos) throw IOException("Truncated length-delimited field")
            val out = buf.copyOfRange(pos, pos + len.toInt())
            pos += len.toInt()
            return out
        }
    }
}
