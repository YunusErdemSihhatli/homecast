package io.github.yunuserdemsihhatli.homecast.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds Cast receivers via mDNS (`_googlecast._tcp`) without Play Services.
 * Discovery runs only while [devices] is collected.
 */
class CastDiscovery(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun devices(): Flow<List<CastDevice>> =
        callbackFlow {
            val lock = wifi.createMulticastLock("homecast-mdns").apply { setReferenceCounted(false) }
            lock.acquire()

            val closed = AtomicBoolean(false)
            val mainHandler = Handler(Looper.getMainLooper())
            val mainExecutor = Executor { mainHandler.post(it) }
            val monitor = Any()

            // Service names currently advertised, and the resolved devices among them. Guarded by [monitor].
            val tracked = HashSet<String>()
            val state = MutableStateFlow<Map<String, CastDevice>>(emptyMap())
            val infoCallbacks = HashMap<String, Any>() // API 34+: NsdManager.ServiceInfoCallback per service

            fun addDevice(
                name: String,
                info: NsdServiceInfo,
                host: InetAddress?,
                attrs: Map<String, ByteArray?>,
            ) {
                val ip = (host as? Inet4Address)?.hostAddress ?: return
                val device = TxtRecordParser.toDevice(info.serviceName, ip, info.port, attrs)
                synchronized(monitor) {
                    if (closed.get() || name !in tracked) return // closed, or lost while resolving
                    state.value = state.value + (name to device)
                }
            }

            fun removeDevice(name: String) {
                synchronized(monitor) {
                    tracked -= name
                    if (name in state.value) state.value = state.value - name
                }
            }

            val scheduler =
                Scheduler { delayMs, task ->
                    val runnable = Runnable(task)
                    mainHandler.postDelayed(runnable, delayMs)
                    ({ mainHandler.removeCallbacks(runnable) })
                }

            @Suppress("DEPRECATION")
            val resolveQueue =
                ResolveQueue<NsdServiceInfo>(scheduler, { info, done ->
                    if (closed.get()) {
                        done(true)
                    } else {
                        val name = info.serviceName
                        nsd.resolveService(
                            info,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(
                                    serviceInfo: NsdServiceInfo,
                                    errorCode: Int,
                                ) {
                                    Log.w(TAG, "Resolve failed for $name: $errorCode")
                                    done(false)
                                }

                                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                    addDevice(name, serviceInfo, serviceInfo.host, serviceInfo.attributes)
                                    done(true)
                                }
                            },
                        )
                    }
                })

            fun registerInfoCallback(serviceInfo: NsdServiceInfo) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
                val name = serviceInfo.serviceName
                val callback =
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                            Log.w(TAG, "Info callback failed for $name: $errorCode")
                            synchronized(monitor) { infoCallbacks.remove(name) }
                        }

                        override fun onServiceUpdated(updated: NsdServiceInfo) {
                            val host = updated.hostAddresses.firstOrNull { it is Inet4Address }
                            addDevice(name, updated, host, updated.attributes)
                        }

                        override fun onServiceLost() {
                            val cb = synchronized(monitor) { infoCallbacks.remove(name) }
                            removeDevice(name)
                            if (cb is NsdManager.ServiceInfoCallback) {
                                runCatching { nsd.unregisterServiceInfoCallback(cb) }
                            }
                        }

                        override fun onServiceInfoCallbackUnregistered() = Unit
                    }
                synchronized(monitor) {
                    if (closed.get() || infoCallbacks.containsKey(name)) return
                    infoCallbacks[name] = callback
                }
                try {
                    nsd.registerServiceInfoCallback(serviceInfo, mainExecutor, callback)
                } catch (e: Exception) {
                    Log.w(TAG, "Info callback rejected for $name", e)
                    synchronized(monitor) { infoCallbacks.remove(name) }
                }
            }

            val discoveryListener =
                object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit

                    override fun onDiscoveryStopped(serviceType: String) = Unit

                    override fun onStartDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
                        Log.w(TAG, "Start discovery failed: $errorCode")
                    }

                    override fun onStopDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
                        Log.w(TAG, "Stop discovery failed: $errorCode")
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (closed.get()) return
                        synchronized(monitor) { tracked += serviceInfo.serviceName }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            registerInfoCallback(serviceInfo)
                        } else {
                            resolveQueue.enqueue(serviceInfo)
                        }
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                        val name = serviceInfo.serviceName
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            val cb = synchronized(monitor) { infoCallbacks.remove(name) }
                            if (cb is NsdManager.ServiceInfoCallback) {
                                runCatching { nsd.unregisterServiceInfoCallback(cb) }
                            }
                        }
                        resolveQueue.remove { it.serviceName == name }
                        removeDevice(name)
                    }
                }

            try {
                launch { state.collect { trySend(it.values.toList()) } }
                try {
                    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                } catch (e: Exception) {
                    Log.w(TAG, "discoverServices failed", e)
                }
                awaitClose()
            } finally {
                closed.set(true)
                resolveQueue.close()
                runCatching { nsd.stopServiceDiscovery(discoveryListener) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val callbacks =
                        synchronized(monitor) { infoCallbacks.values.toList().also { infoCallbacks.clear() } }
                    callbacks
                        .filterIsInstance<NsdManager.ServiceInfoCallback>()
                        .forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
                }
                if (lock.isHeld) lock.release()
            }
        }

    private companion object {
        const val TAG = "CastDiscovery"
        const val SERVICE_TYPE = "_googlecast._tcp"
    }
}
