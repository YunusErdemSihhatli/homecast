package io.github.yunuserdemsihhatli.homecast.cast

import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Scripted Chromecast behaviour for [FakeCastReceiver.autoRespond]: answers PING, receiver
 * GET_STATUS / LAUNCH / SET_VOLUME and media GET_STATUS / LOAD / PLAY / PAUSE / SEEK / STOP /
 * EDIT_TRACKS_INFO roughly like the Default Media Receiver does.
 */
class FakeDefaultMediaReceiver(
    @Volatile var appRunning: Boolean = false,
) {
    /** Message types (`type` field) that get no reply at all, e.g. to provoke timeouts. */
    @Volatile var ignoredTypes: Set<String> = emptySet()

    /** Replaces the reply to LOAD, e.g. with LOAD_FAILED. */
    @Volatile var loadReply: ((requestId: Int) -> JsonObject)? = null

    @Volatile var playerState = "IDLE"

    @Volatile var currentTime = 0.0

    @Volatile var mediaSessionId: Int? = null

    @Volatile var volumeLevel = 0.5

    @Volatile var muted = false

    /** transportId reported for the running app; change it to simulate the app being replaced. */
    @Volatile var transportId = TRANSPORT_ID

    /**
     * Called first for every message (on the fake's read thread, so it may block to hold a reply);
     * a non-null result replaces the scripted reply.
     */
    @Volatile var intercept: ((CastMessage, JsonObject) -> List<CastMessage>?)? = null

    fun respond(m: CastMessage): List<CastMessage> {
        val json = Json.parseToJsonElement(m.payloadUtf8).jsonObject
        val type = json["type"]?.jsonPrimitive?.content ?: return emptyList()
        if (type in ignoredTypes) return emptyList()
        intercept?.invoke(m, json)?.let { return it }
        val requestId = json["requestId"]?.jsonPrimitive?.int ?: 0

        fun reply(
            ns: String,
            payload: JsonObject,
        ) = listOf(CastMessage(m.destinationId, m.sourceId, ns, payload.toString()))
        return when (m.namespace) {
            HEARTBEAT -> {
                if (type == "PING") reply(HEARTBEAT, buildJsonObject { put("type", "PONG") }) else emptyList()
            }

            RECEIVER -> {
                when (type) {
                    "GET_STATUS" -> {
                        reply(RECEIVER, receiverStatus(requestId))
                    }

                    "LAUNCH" -> {
                        appRunning = true
                        reply(RECEIVER, receiverStatus(requestId))
                    }

                    "SET_VOLUME" -> {
                        val volume = json["volume"]!!.jsonObject
                        volume["level"]?.jsonPrimitive?.content?.let { volumeLevel = it.toDouble() }
                        volume["muted"]?.jsonPrimitive?.content?.let { muted = it.toBoolean() }
                        reply(RECEIVER, receiverStatus(requestId))
                    }

                    else -> {
                        emptyList()
                    }
                }
            }

            MEDIA -> {
                when (type) {
                    "GET_STATUS" -> {
                        reply(MEDIA, mediaStatus(requestId))
                    }

                    "LOAD" -> {
                        loadReply?.let { return reply(MEDIA, it(requestId)) }
                        mediaSessionId = (mediaSessionId ?: 0) + 1
                        playerState = "PLAYING"
                        currentTime = json["currentTime"]?.jsonPrimitive?.content?.toDouble() ?: 0.0
                        reply(MEDIA, mediaStatus(requestId))
                    }

                    "PLAY", "PAUSE", "SEEK", "STOP", "EDIT_TRACKS_INFO" -> {
                        when (type) {
                            "PLAY" -> playerState = "PLAYING"
                            "PAUSE" -> playerState = "PAUSED"
                            "STOP" -> playerState = "IDLE"
                            "SEEK" -> currentTime = json["currentTime"]!!.jsonPrimitive.content.toDouble()
                        }
                        reply(MEDIA, mediaStatus(requestId))
                    }

                    else -> {
                        emptyList()
                    }
                }
            }

            else -> {
                emptyList()
            }
        }
    }

    fun receiverStatus(requestId: Int = 0): JsonObject =
        buildJsonObject {
            put("type", "RECEIVER_STATUS")
            put("requestId", requestId)
            putJsonObject("status") {
                putJsonObject("volume") {
                    put("level", volumeLevel)
                    put("muted", muted)
                }
                putJsonArray("applications") {
                    if (appRunning) {
                        add(
                            buildJsonObject {
                                put("appId", "CC1AD845")
                                put("sessionId", SESSION_ID)
                                put("transportId", transportId)
                            },
                        )
                    }
                }
            }
        }

    fun mediaStatus(requestId: Int = 0): JsonObject =
        buildJsonObject {
            put("type", "MEDIA_STATUS")
            put("requestId", requestId)
            putJsonArray("status") {
                val id = mediaSessionId ?: return@putJsonArray
                add(
                    buildJsonObject {
                        put("mediaSessionId", id)
                        put("playerState", playerState)
                        put("currentTime", currentTime)
                        putJsonObject("media") { put("duration", 120.0) }
                        put("activeTrackIds", buildJsonArray { })
                    },
                )
            }
        }

    companion object {
        const val TRANSPORT_ID = "t1"
        const val SESSION_ID = "s1"
        const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val MEDIA = "urn:x-cast:com.google.cast.media"
    }
}
