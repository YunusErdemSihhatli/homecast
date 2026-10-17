package io.github.yunuserdemsihhatli.homecast.cast.protocol

import io.github.yunuserdemsihhatli.homecast.cast.LoadRequest
import io.github.yunuserdemsihhatli.homecast.cast.MediaStatus
import io.github.yunuserdemsihhatli.homecast.cast.PlayerState
import io.github.yunuserdemsihhatli.homecast.cast.StreamType
import io.github.yunuserdemsihhatli.homecast.cast.VolumeState
import io.github.yunuserdemsihhatli.homecast.proxy.ProxiedSubtitle
import io.github.yunuserdemsihhatli.homecast.proxy.SegmentFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The running receiver application we talk to (its `transportId` addresses media messages). */
data class ReceiverApp(
    val sessionId: String,
    val transportId: String,
)

/** Parsed `RECEIVER_STATUS`: volume and the Default Media Receiver if it is running. */
data class ReceiverStatus(
    val volume: VolumeState?,
    val app: ReceiverApp?,
)

/** CASTV2 JSON vocabulary: namespaces, payload builders and status parsers. */
object CastJson {
    const val SENDER_ID = "sender-0"
    const val RECEIVER_ID = "receiver-0"
    const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"

    const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

    /** Replies that reject a request; they carry the request's `requestId`. */
    val ERROR_TYPES =
        setOf("LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST", "INVALID_PLAYER_STATE", "LAUNCH_ERROR")

    fun parse(payload: String): JsonObject? =
        runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()

    fun type(json: JsonObject): String? = json.string("type")

    fun requestId(json: JsonObject): Int? = (json["requestId"] as? JsonPrimitive)?.intOrNull

    fun simple(type: String): JsonObject = buildJsonObject { put("type", type) }

    fun launch(): JsonObject =
        buildJsonObject {
            put("type", "LAUNCH")
            put("appId", DEFAULT_MEDIA_RECEIVER_APP_ID)
        }

    fun setVolumeLevel(level: Double): JsonObject =
        buildJsonObject {
            put("type", "SET_VOLUME")
            putJsonObject("volume") { put("level", level.coerceIn(0.0, 1.0)) }
        }

    fun setMuted(muted: Boolean): JsonObject =
        buildJsonObject {
            put("type", "SET_VOLUME")
            putJsonObject("volume") { put("muted", muted) }
        }

    /** A media command (PLAY, PAUSE, STOP, ...) addressed to [mediaSessionId]. */
    fun mediaCommand(
        type: String,
        mediaSessionId: Int,
        extra: Map<String, JsonElement> = emptyMap(),
    ): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive(type), "mediaSessionId" to JsonPrimitive(mediaSessionId)) + extra)

    fun seek(
        mediaSessionId: Int,
        seconds: Double,
    ) = mediaCommand("SEEK", mediaSessionId, mapOf("currentTime" to JsonPrimitive(seconds)))

    fun editTracks(
        mediaSessionId: Int,
        trackIds: List<Int>,
    ) = mediaCommand(
        "EDIT_TRACKS_INFO",
        mediaSessionId,
        mapOf("activeTrackIds" to JsonArray(trackIds.map(::JsonPrimitive))),
    )

    /** Most text tracks a LOAD carries; receivers reject or choke on huge track lists. */
    const val MAX_TRACKS = 20

    /**
     * The tracks a LOAD carries: at most [MAX_TRACKS], always including the active ones, then the newest (highest
     * position in the list, e.g. a file the user just added); the original order is kept.
     */
    private fun tracksToSend(request: LoadRequest): List<ProxiedSubtitle> {
        val all = request.subtitles
        if (all.size <= MAX_TRACKS) return all
        val active = all.filter { it.trackId in request.activeTrackIds }.take(MAX_TRACKS)
        val rest = all.asReversed().filter { it !in active }.take(MAX_TRACKS - active.size)
        val keep = (active + rest).toSet()
        return all.filter { it in keep }
    }

    fun load(
        sessionId: String,
        request: LoadRequest,
    ): JsonObject {
        val tracks = tracksToSend(request)
        val sentIds = tracks.mapTo(HashSet()) { it.trackId }
        return buildJsonObject {
            put("type", "LOAD")
            put("sessionId", sessionId)
            putJsonObject("media") {
                put("contentId", request.url)
                put("contentUrl", request.url)
                put("contentType", request.contentType)
                put("streamType", request.streamType.name)
                putJsonObject("metadata") {
                    put("metadataType", 0)
                    put("title", request.title)
                }
                if (tracks.isNotEmpty()) {
                    putJsonArray("tracks") {
                        tracks.forEach { subtitle ->
                            add(
                                buildJsonObject {
                                    put("trackId", subtitle.trackId)
                                    put("type", "TEXT")
                                    put("subtype", "SUBTITLES")
                                    put("trackContentId", subtitle.url)
                                    put("trackContentType", "text/vtt")
                                    put("name", subtitle.label)
                                    subtitle.language?.let { put("language", it) }
                                },
                            )
                        }
                    }
                }
                request.hlsSegmentFormat?.let { format ->
                    put("hlsSegmentFormat", format.castValue)
                    // fMP4 segments carry the video too; the receiver needs to be told for that track as well.
                    if (format == SegmentFormat.FMP4) put("hlsVideoSegmentFormat", format.castValue)
                }
            }
            put("autoplay", true)
            if (request.streamType != StreamType.LIVE) put("currentTime", request.startTimeSeconds)
            putJsonArray("activeTrackIds") {
                request.activeTrackIds.filter { it in sentIds }.forEach { add(JsonPrimitive(it)) }
            }
        }
    }

    fun parseReceiverStatus(json: JsonObject): ReceiverStatus {
        val status = json["status"] as? JsonObject ?: return ReceiverStatus(null, null)
        val volume =
            (status["volume"] as? JsonObject)?.let { v ->
                val level = (v["level"] as? JsonPrimitive)?.doubleOrNull
                val muted = (v["muted"] as? JsonPrimitive)?.booleanOrNull
                if (level == null && muted == null) null else VolumeState(level ?: 0.0, muted ?: false)
            }
        val app =
            (status["applications"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.string("appId") == DEFAULT_MEDIA_RECEIVER_APP_ID }
                ?.let { a ->
                    val sessionId = a.string("sessionId") ?: return@let null
                    val transportId = a.string("transportId") ?: return@let null
                    ReceiverApp(sessionId, transportId)
                }
        return ReceiverStatus(volume, app)
    }

    /**
     * Parses `MEDIA_STATUS`. Returns null when `status` is empty (nothing loaded). Receivers omit
     * `media` and `activeTrackIds` when unchanged, so those fall back to [previous] for the same
     * media session.
     */
    fun parseMediaStatus(
        json: JsonObject,
        previous: MediaStatus?,
    ): MediaStatus? {
        val entry = (json["status"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val sessionId = (entry["mediaSessionId"] as? JsonPrimitive)?.intOrNull ?: return null
        val same = previous?.takeIf { it.mediaSessionId == sessionId }
        val playerState =
            when (entry.string("playerState")) {
                "IDLE" -> PlayerState.IDLE
                "BUFFERING", "LOADING" -> PlayerState.BUFFERING
                "PLAYING" -> PlayerState.PLAYING
                "PAUSED" -> PlayerState.PAUSED
                else -> PlayerState.UNKNOWN
            }
        val duration =
            (entry["media"] as? JsonObject)?.let { media ->
                (media["duration"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it >= 0 }
            } ?: same?.durationSeconds
        val tracks =
            (entry["activeTrackIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                ?: same?.activeTrackIds
                ?: emptyList()
        return MediaStatus(
            mediaSessionId = sessionId,
            playerState = playerState,
            currentTimeSeconds =
                (entry["currentTime"] as? JsonPrimitive)?.doubleOrNull ?: same?.currentTimeSeconds ?: 0.0,
            durationSeconds = duration,
            idleReason = entry.string("idleReason"),
            activeTrackIds = tracks,
        )
    }

    /** `reason` / `detailedErrorCode` of an error reply, for messages. */
    fun errorReason(json: JsonObject): String? = json.string("reason") ?: json["detailedErrorCode"]?.toString()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
