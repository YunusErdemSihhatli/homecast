package io.github.yunuserdemsihhatli.homecast.cast

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceStoreTest {
    private class FakePrefs(
        initial: Map<String, String> = emptyMap(),
    ) : SharedPreferences {
        val data = initial.toMutableMap()

        override fun getString(
            key: String?,
            defValue: String?,
        ): String? = data[key] ?: defValue

        override fun edit(): SharedPreferences.Editor =
            object : SharedPreferences.Editor {
                private val pending = mutableMapOf<String, String>()

                override fun putString(
                    key: String?,
                    value: String?,
                ) = apply { pending[key!!] = value!! }

                override fun apply() {
                    data.putAll(pending)
                }

                override fun commit(): Boolean {
                    apply()
                    return true
                }

                override fun putStringSet(
                    k: String?,
                    v: MutableSet<String>?,
                ) = unsupported()

                override fun putInt(
                    k: String?,
                    v: Int,
                ) = unsupported()

                override fun putLong(
                    k: String?,
                    v: Long,
                ) = unsupported()

                override fun putFloat(
                    k: String?,
                    v: Float,
                ) = unsupported()

                override fun putBoolean(
                    k: String?,
                    v: Boolean,
                ) = unsupported()

                override fun remove(k: String?) = unsupported()

                override fun clear() = unsupported()
            }

        override fun getAll(): MutableMap<String, *> = unsupported()

        override fun getStringSet(
            k: String?,
            d: MutableSet<String>?,
        ) = unsupported()

        override fun getInt(
            k: String?,
            d: Int,
        ) = unsupported()

        override fun getLong(
            k: String?,
            d: Long,
        ) = unsupported()

        override fun getFloat(
            k: String?,
            d: Float,
        ) = unsupported()

        override fun getBoolean(
            k: String?,
            d: Boolean,
        ) = unsupported()

        override fun contains(k: String?) = data.containsKey(k)

        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) =
            unsupported()

        override fun unregisterOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }

    private val a = CastDevice("a", "Living room", "192.168.1.20")
    private val b = CastDevice("b", "Bedroom", "192.168.1.21")

    @Test
    fun saveUpsertsByIdAndKeepsMostRecentFirst() {
        val prefs = FakePrefs()
        val store = PrefsDeviceStore(prefs)
        store.save(a)
        store.save(b)
        store.save(a.copy(host = "192.168.1.99"))
        assertEquals(listOf(a.copy(host = "192.168.1.99"), b), store.saved.value)
        assertEquals(a.copy(host = "192.168.1.99"), store.lastUsed())
        // persisted
        assertEquals(store.saved.value, PrefsDeviceStore(prefs).saved.value)
    }

    @Test
    fun manualIpDeviceWithoutIdUpsertsByHostPort() {
        val store = PrefsDeviceStore(FakePrefs())
        store.save(CastDevice(null, "192.168.1.50", "192.168.1.50"))
        store.save(a)
        store.save(CastDevice(null, "Kitchen", "192.168.1.50"))
        assertEquals(listOf("Kitchen", "Living room"), store.saved.value.map { it.name })
        store.remove(a)
        assertEquals(listOf("Kitchen"), store.saved.value.map { it.name })
    }

    @Test
    fun corruptJsonStartsEmpty() {
        val store = PrefsDeviceStore(FakePrefs(mapOf("devices" to "{not json")))
        assertEquals(emptyList<CastDevice>(), store.saved.value)
        assertNull(store.lastUsed())
    }

    @Test
    fun mergePrefersDiscoveredAddressForSameId() {
        val saved = listOf(CastDevice("X", "TV", "192.168.1.20"), CastDevice(null, "Manual", "10.0.0.9"))
        val discovered = listOf(CastDevice("X", "TV", "192.168.1.31"))
        val merged = mergeDevices(discovered, saved)
        assertEquals(listOf(CastDevice("X", "TV", "192.168.1.31"), CastDevice(null, "Manual", "10.0.0.9")), merged)
    }
}
