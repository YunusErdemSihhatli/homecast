package io.github.yunuserdemsihhatli.homecast.cast

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Connects to a remembered TV, following it to a new IP address.
 *
 * When the connection to [CastDevice.host] fails, the network is searched for the same TV for
 * [discoveryTimeoutMillis]. "The same TV" means the same mDNS `id` only: a name is not proof of identity (anything on
 * the LAN can advertise any name), so entries typed in by IP, which have no id, are never followed and simply fail.
 * If the TV turns up at another address, that address replaces the saved entry and the connection is tried once more.
 */
class DeviceConnector(
    private val cast: CastRemote,
    private val devices: DeviceStore,
    private val discover: () -> Flow<List<CastDevice>>,
    private val discoveryTimeoutMillis: Long = DEFAULT_DISCOVERY_TIMEOUT_MILLIS,
) {
    /** Throws the first [CastConnectException] when the TV is not found elsewhere, or the retry's failure. */
    suspend fun connect(device: CastDevice) {
        try {
            cast.connect(device)
        } catch (e: CastConnectException) {
            if (device.id == null) throw e
            val moved = findElsewhere(device) ?: throw e
            devices.remove(device)
            devices.save(moved)
            cast.connect(moved)
        }
    }

    private suspend fun findElsewhere(device: CastDevice): CastDevice? =
        withTimeoutOrNull(discoveryTimeoutMillis) {
            discover()
                .catch { emit(emptyList()) }
                .map { found -> found.firstOrNull { it.isSameTvAs(device) && !it.isAtSameAddressAs(device) } }
                .filterNotNull()
                .firstOrNull()
        }

    companion object {
        const val DEFAULT_DISCOVERY_TIMEOUT_MILLIS = 5_000L

        /** Identity by mDNS id only, never by name (spoofable); an id-less entry matches nothing. */
        internal fun CastDevice.isSameTvAs(saved: CastDevice): Boolean = saved.id != null && id == saved.id

        private fun CastDevice.isAtSameAddressAs(other: CastDevice) = host == other.host && port == other.port
    }
}
