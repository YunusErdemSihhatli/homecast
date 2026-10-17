package io.github.yunuserdemsihhatli.homecast.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory [DeviceStore] with the same matching rules as [PrefsDeviceStore]. */
class FakeDeviceStore(
    vararg initial: CastDevice,
) : DeviceStore {
    private val state = MutableStateFlow(initial.toList())
    override val saved: StateFlow<List<CastDevice>> = state

    override fun save(device: CastDevice) {
        state.value = listOf(device) + state.value.filterNot { it.sameAs(device) }
    }

    override fun remove(device: CastDevice) {
        state.value = state.value.filterNot { it.sameAs(device) }
    }

    override fun lastUsed(): CastDevice? = state.value.firstOrNull()
}
