package io.github.yunuserdemsihhatli.homecast.cast.protocol

import app.cash.turbine.test
import io.github.yunuserdemsihhatli.homecast.cast.FakeCastReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

class CastChannelTest {
    private val receiver = FakeCastReceiver()
    private var channel: CastChannel? = null

    private val ns = "urn:x-cast:com.google.cast.tp.heartbeat"

    private fun msg(payload: String) = CastMessage("sender-0", "receiver-0", ns, payload)

    @After fun tearDown() {
        channel?.close()
        receiver.close()
    }

    private fun newChannel(): CastChannel {
        val port = receiver.start()
        return CastChannel("127.0.0.1", port).also { channel = it }
    }

    @Test fun channelExchangesFramesWithFakeReceiver() =
        runBlocking {
            withTimeout(10_000) {
                receiver.autoRespond =
                    { listOf(CastMessage(it.destinationId, it.sourceId, it.namespace, "{\"type\":\"PONG\"}")) }
                val ch = newChannel()
                ch.incoming.test(timeout = 5.seconds) {
                    ch.connect(2000)
                    ch.send(msg("{\"type\":\"PING\"}"))
                    assertEquals(msg("{\"type\":\"PING\"}"), receiver.received.receive())
                    assertEquals(CastMessage("receiver-0", "sender-0", ns, "{\"type\":\"PONG\"}"), awaitItem())
                    ch.close()
                    awaitComplete()
                }
            }
        }

    @Test fun concurrentSendsDoNotInterleaveFrames() =
        runBlocking {
            withTimeout(10_000) {
                val ch = newChannel()
                ch.connect(2000)
                val payloads = (0 until 20).map { "{\"n\":$it,\"pad\":\"${"p".repeat(2000)}\"}" }
                withContext(Dispatchers.Default) { payloads.map { async { ch.send(msg(it)) } }.awaitAll() }
                val got = (0 until 20).map { receiver.received.receive().payloadUtf8 }
                assertEquals(payloads.toSet(), got.toSet())
            }
        }

    @Test fun incomingCompletesWhenReceiverDropsConnection() =
        runBlocking {
            withTimeout(10_000) {
                val ch = newChannel()
                ch.incoming.test(timeout = 5.seconds) {
                    ch.connect(2000)
                    ch.send(msg("hello"))
                    receiver.received.receive()
                    receiver.dropConnection()
                    awaitComplete()
                }
            }
        }

    @Test fun rejectsOversizedFrame() =
        runBlocking {
            withTimeout(10_000) {
                val ch = newChannel()
                ch.incoming.test(timeout = 5.seconds) {
                    ch.connect(2000)
                    ch.send(msg("hello"))
                    receiver.received.receive()
                    val size = MAX_CAST_FRAME_BYTES + 1
                    receiver.sendRaw(byteArrayOf(0, (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
                    val error = awaitError()
                    assertTrue(error is IOException)
                    assertTrue(error.message!!.contains("exceeds"))
                }
                // The channel is closed: sending now fails.
                var failed = false
                try {
                    ch.send(msg("again"))
                } catch (_: IOException) {
                    failed = true
                }
                assertTrue(failed)
            }
        }

    @Test fun lateCollectorOfEndedChannelCompletes() =
        runBlocking {
            withTimeout(10_000) {
                val ch = newChannel()
                ch.connect(2000)
                ch.close()
                ch.incoming.test(timeout = 5.seconds) { awaitComplete() }
            }
        }

    @Test fun twoCollectorsBothSeeEveryMessage() =
        runBlocking {
            withTimeout(10_000) {
                val ch = newChannel()
                ch.incoming.test(timeout = 5.seconds) {
                    ch.incoming.test(timeout = 5.seconds) {
                        ch.connect(2000)
                        ch.send(msg("x"))
                        receiver.received.receive()
                        receiver.send(msg("one"))
                        assertEquals("one", awaitItem().payloadUtf8)
                    }
                    assertEquals("one", awaitItem().payloadUtf8)
                }
            }
        }

    /** Accepts TCP connections but never speaks TLS, so a handshake hangs. */
    private class StallingServer : java.io.Closeable {
        val server = java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val accepted = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        private val t =
            Thread {
                while (!server.isClosed) {
                    runCatching { accepted += server.accept() }
                }
            }.apply {
                isDaemon = true
                start()
            }

        override fun close() {
            server.close()
            accepted.forEach { runCatching { it.close() } }
        }
    }

    @Test fun closeDuringConnectAbortsHandshakeAndChannelIsDead() =
        runBlocking {
            withTimeout(10_000) {
                StallingServer().use { srv ->
                    val ch = CastChannel("127.0.0.1", srv.server.localPort).also { channel = it }
                    val connecting = async(Dispatchers.Default) { runCatching { ch.connect(8000) } }
                    while (srv.accepted.isEmpty()) delay(10)
                    ch.close()
                    assertTrue(connecting.await().exceptionOrNull() is IOException)
                    var failed = false
                    try {
                        ch.send(msg("x"))
                    } catch (_: IOException) {
                        failed = true
                    }
                    assertTrue(failed)
                    ch.incoming.test(timeout = 5.seconds) { awaitComplete() }
                }
            }
        }

    @Test fun cancelledConnectClosesTheSocket() =
        runBlocking {
            withTimeout(10_000) {
                StallingServer().use { srv ->
                    val ch = CastChannel("127.0.0.1", srv.server.localPort).also { channel = it }
                    val job = launch(Dispatchers.Default) { ch.connect(8000) }
                    while (srv.accepted.isEmpty()) delay(10)
                    job.cancelAndJoin()
                    // Our side closed the socket, so the peer sees EOF/reset rather than a hang.
                    val peer = srv.accepted.first()
                    peer.soTimeout = 3000
                    // Drain the ClientHello; the stream must then end instead of timing out.
                    val r = runCatching { while (peer.getInputStream().read() != -1) Unit }
                    assertTrue(r.isSuccess || r.exceptionOrNull() !is java.net.SocketTimeoutException)
                }
            }
        }

    @Test fun closeEndsStalledCollectorEvenWithFullBuffer() =
        runBlocking {
            withTimeout(15_000) {
                val ch = newChannel()
                val done = CompletableDeferred<Unit>()
                val collector =
                    launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        ch.incoming.collect { delay(10) } // slow collector
                        done.complete(Unit)
                    }
                ch.connect(2000)
                ch.send(msg("x"))
                receiver.received.receive()
                repeat(200) { receiver.send(msg("m$it")) }
                delay(300)
                ch.close()
                withTimeout(5000) { done.await() }
                collector.cancelAndJoin()
            }
        }

    @Test fun autoRespondFailureSurfacesInTest() =
        runBlocking {
            withTimeout(10_000) {
                receiver.autoRespond = { error("boom") }
                val ch = newChannel()
                ch.connect(2000)
                ch.send(msg("x"))
                var thrown: Throwable? = null
                try {
                    receiver.received.receive()
                    receiver.received.receive()
                } catch (e: Throwable) {
                    thrown = e
                }
                assertTrue(thrown is AssertionError)
            }
        }

    @Test fun frameReceivedBeforeAnyoneCollectsIsDelivered() =
        runBlocking {
            withTimeout(10_000) {
                val ch = newChannel()
                ch.connect(2000)
                ch.send(msg("x"))
                receiver.received.receive()
                receiver.send(msg("early"))
                delay(300) // reader has the frame and is holding it
                val got = CompletableDeferred<CastMessage>()
                val job = launch(Dispatchers.Default) { ch.incoming.collect { got.complete(it) } }
                assertEquals("early", withTimeout(5000) { got.await() }.payloadUtf8)
                job.cancelAndJoin()
            }
        }

    @Test fun readFailureIsNotOverwrittenByClose() =
        runBlocking {
            withTimeout(10_000) {
                val ch = newChannel()
                ch.incoming.test(timeout = 5.seconds) {
                    ch.connect(2000)
                    ch.send(msg("x"))
                    receiver.received.receive()
                    receiver.sendRaw(byteArrayOf(0x7F, 0, 0, 0))
                    // Wait until the failure is the recorded terminal state, then close.
                    val error = awaitError()
                    assertTrue(error.message!!.contains("exceeds"))
                    ch.close()
                }
                ch.incoming.test(timeout = 5.seconds) {
                    assertTrue(awaitError().message!!.contains("exceeds"))
                }
            }
        }
}
