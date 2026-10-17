package io.github.yunuserdemsihhatli.homecast.cast.protocol

/** A CASTV2 string-payload message (`protocol_version` 0, `payload_type` STRING). */
data class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadUtf8: String,
)
