package io.github.yunuserdemsihhatli.homecast.cast

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceConnectorTest {
    private val cast = FakeCastRemote()
    private val oldTv = CastDevice(id = "tv-1", name = "Living room", host = "192.168.1.20")
    private val movedTv = oldTv.copy(host = "192.168.1.77")
    private var discoveries = 0

    private fun unreachable(vararg hosts: String) {
        cast.connectFailureFor = { d ->
            if (d.host in hosts) CastConnectException(d, ConnectException("failed to connect to /${d.host}")) else null
        }
    }

    /** Emits nothing, then [found] after 1 s of (virtual) searching, then keeps searching. */
    private fun discovery(vararg found: CastDevice): () -> Flow<List<CastDevice>> =
        {
            discoveries++
            flow {
                emit(emptyList())
                kotlinx.coroutines.delay(1_000)
                emit(found.toList())
                awaitCancellation()
            }
        }

    private suspend fun expectConnectFailure(block: suspend () -> Unit): CastConnectException {
        try {
            block()
        } catch (e: CastConnectException) {
            return e
        }
        fail("expected a CastConnectException")
        error("unreachable")
    }

    @Test fun reachableDeviceConnectsWithoutDiscovery() =
        runTest {
            val store = FakeDeviceStore(oldTv)
            DeviceConnector(cast, store, discovery(movedTv)).connect(oldTv)

            assertEquals(listOf(FakeCastRemote.Call.Connect(oldTv)), cast.calls)
            assertEquals(0, discoveries)
            assertEquals(listOf(oldTv), store.saved.value)
        }

    @Test fun movedDeviceIsFoundByIdSavedAndRetriedOnce() =
        runTest {
            unreachable(oldTv.host)
            val other = CastDevice(id = "tv-2", name = "Bedroom", host = "192.168.1.30")
            val store = FakeDeviceStore(oldTv, other)

            DeviceConnector(cast, store, discovery(other, movedTv)).connect(oldTv)

            assertEquals(
                listOf(FakeCastRemote.Call.Connect(oldTv), FakeCastRemote.Call.Connect(movedTv)),
                cast.calls,
            )
            assertEquals(ConnectionState.Connected(movedTv), cast.connection.value)
            assertEquals(listOf(movedTv, other), store.saved.value)
        }

    @Test fun manualEntryWithoutIdIsNeverFollowedEvenWhenTheNameMatches() =
        runTest {
            val manual = CastDevice(id = null, name = "Living room", host = "192.168.1.20")
            unreachable(manual.host)
            val store = FakeDeviceStore(manual)

            expectConnectFailure { DeviceConnector(cast, store, discovery(movedTv)).connect(manual) }

            assertEquals(listOf(FakeCastRemote.Call.Connect(manual)), cast.calls)
            assertEquals(0, discoveries)
            assertEquals(listOf(manual), store.saved.value)
        }

    @Test fun deviceWithTheSameNameButNoIdIsNotFollowed() =
        runTest {
            unreachable(oldTv.host)
            val spoof = CastDevice(id = null, name = oldTv.name, host = "192.168.1.66")
            val store = FakeDeviceStore(oldTv)

            expectConnectFailure { DeviceConnector(cast, store, discovery(spoof)).connect(oldTv) }

            assertEquals(1, cast.calls.size)
            assertEquals(listOf(oldTv), store.saved.value)
        }

    @Test fun otherTvWithTheSameNameButAnotherIdIsNotUsed() =
        runTest {
            unreachable(oldTv.host)
            val namesake = CastDevice(id = "tv-9", name = oldTv.name, host = "192.168.1.88")
            val store = FakeDeviceStore(oldTv)

            expectConnectFailure { DeviceConnector(cast, store, discovery(namesake)).connect(oldTv) }

            assertEquals(1, cast.calls.size)
            assertEquals(listOf(oldTv), store.saved.value)
        }

    @Test fun notFoundWithinTheTimeoutRethrowsTheOriginalFailure() =
        runTest {
            unreachable(oldTv.host)
            val store = FakeDeviceStore(oldTv)
            val original = CastConnectException(oldTv, ConnectException("refused"))
            cast.connectFailureFor = { original }

            val thrown = expectConnectFailure { DeviceConnector(cast, store, discovery()).connect(oldTv) }

            assertSame(original, thrown)
            assertEquals(DeviceConnector.DEFAULT_DISCOVERY_TIMEOUT_MILLIS, currentTime)
            assertEquals(1, cast.calls.size)
            assertEquals(listOf(oldTv), store.saved.value)
        }

    @Test fun foundAtTheSameAddressIsNotRetried() =
        runTest {
            unreachable(oldTv.host)
            val store = FakeDeviceStore(oldTv)

            expectConnectFailure { DeviceConnector(cast, store, discovery(oldTv)).connect(oldTv) }

            assertEquals(1, cast.calls.size)
        }

    @Test fun failedRetryAtTheNewAddressIsThrownAndOnlyTriedOnce() =
        runTest {
            unreachable(oldTv.host, movedTv.host)
            val store = FakeDeviceStore(oldTv)

            val thrown = expectConnectFailure { DeviceConnector(cast, store, discovery(movedTv)).connect(oldTv) }

            assertEquals(movedTv, thrown.device)
            assertEquals(2, cast.calls.size)
        }

    @Test fun failingDiscoveryCountsAsNotFound() =
        runTest {
            unreachable(oldTv.host)
            val broken: () -> Flow<List<CastDevice>> = { flow { throw SecurityException("no multicast") } }

            expectConnectFailure { DeviceConnector(cast, FakeDeviceStore(oldTv), broken).connect(oldTv) }

            assertEquals(1, cast.calls.size)
        }

    @Test fun nonConnectFailuresAreNotRetried() =
        runTest {
            cast.connectFailure = CastRequestException("LAUNCH_ERROR", null)
            try {
                DeviceConnector(cast, FakeDeviceStore(oldTv), { flowOf(listOf(movedTv)) }).connect(oldTv)
                fail("expected the launch error")
            } catch (_: CastRequestException) {
            }
            assertEquals(1, cast.calls.size)
        }
}
