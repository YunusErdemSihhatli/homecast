package io.github.yunuserdemsihhatli.homecast.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/** The phone's address on the Wi-Fi network the Cast device is on. */
interface WifiAddressProvider {
    /** Wi-Fi IPv4 address, or null when the phone is not on Wi-Fi. */
    val address: StateFlow<String?>
}

/** Tracks Wi-Fi IPv4 addresses with a [ConnectivityManager] callback for the lifetime of the app. */
class NetworkAddresses(
    context: Context,
) : WifiAddressProvider {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val addresses = LinkedHashMap<Network, String>() // guarded by itself
    private val _address = MutableStateFlow<String?>(null)

    override val address: StateFlow<String?> = _address.asStateFlow()

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                val ipv4 = ipv4(linkProperties)
                update { if (ipv4 == null) it.remove(network) else it[network] = ipv4 }
            }

            override fun onLost(network: Network) {
                update { it.remove(network) }
            }
        }

    init {
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // A LAN with only the TV on it has no internet access but is exactly what we need.
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        connectivity.registerNetworkCallback(request, callback)
    }

    private fun update(change: (MutableMap<Network, String>) -> Unit) {
        synchronized(addresses) {
            change(addresses)
            _address.value = addresses.values.lastOrNull()
        }
    }

    private fun ipv4(linkProperties: LinkProperties): String? =
        linkProperties.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
}
