package io.github.yunuserdemsihhatli.homecast.cast

/** Turns the mDNS service data of a `_googlecast._tcp` record into a [CastDevice]. */
object TxtRecordParser {
    fun toDevice(
        serviceName: String,
        host: String,
        port: Int,
        attributes: Map<String, ByteArray?>,
    ): CastDevice {
        fun text(key: String) = attributes[key]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        return CastDevice(
            id = text("id"),
            name = text("fn") ?: serviceName,
            host = host,
            port = port,
            model = text("md"),
        )
    }
}
