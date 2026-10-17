package io.github.yunuserdemsihhatli.homecast.cast

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

interface DeviceStore {
    val saved: StateFlow<List<CastDevice>>

    /** Upserts by id when known, else by host:port; the saved device becomes the most recent. */
    fun save(device: CastDevice)

    fun remove(device: CastDevice)

    fun lastUsed(): CastDevice?
}

internal fun CastDevice.sameAs(other: CastDevice): Boolean =
    if (id != null && other.id != null) id == other.id else host == other.host && port == other.port

/** Discovered devices win for the same id (the IP may have changed); saved-only entries are kept. */
fun mergeDevices(
    discovered: List<CastDevice>,
    saved: List<CastDevice>,
): List<CastDevice> = discovered + saved.filter { s -> discovered.none { it.sameAs(s) } }

class PrefsDeviceStore(
    private val prefs: SharedPreferences,
) : DeviceStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CastDevice.serializer())
    private val state = MutableStateFlow(load())

    override val saved: StateFlow<List<CastDevice>> = state

    @Synchronized
    override fun save(device: CastDevice) {
        update { list -> listOf(device) + list.filterNot { it.sameAs(device) } }
    }

    @Synchronized
    override fun remove(device: CastDevice) {
        update { list -> list.filterNot { it.sameAs(device) } }
    }

    override fun lastUsed(): CastDevice? = state.value.firstOrNull()

    private fun update(transform: (List<CastDevice>) -> List<CastDevice>) {
        val next = transform(state.value)
        prefs.edit().putString(KEY, json.encodeToString(serializer, next)).apply()
        state.value = next
    }

    private fun load(): List<CastDevice> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString(serializer, raw)
        } catch (e: Exception) {
            Log.w("PrefsDeviceStore", "Ignoring corrupt saved devices", e)
            emptyList()
        }
    }

    private companion object {
        const val KEY = "devices"
    }
}
