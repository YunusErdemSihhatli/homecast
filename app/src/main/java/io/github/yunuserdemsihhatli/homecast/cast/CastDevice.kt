package io.github.yunuserdemsihhatli.homecast.cast

import kotlinx.serialization.Serializable

/** A Cast receiver on the LAN. [id] is the mDNS `id` TXT record when known. */
@Serializable
data class CastDevice(
    val id: String?,
    val name: String,
    val host: String,
    val port: Int = 8009,
    val model: String? = null,
)
