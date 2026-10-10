package com.ninepointnine.desktop.input

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RemoteInputSocketServerTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val coordinator = RemoteInputCoordinator(
        imeDeclared = true,
        nowMillis = { 0L },
        deadlines = RemoteInputDeadlineScheduler { _, _ -> RemoteInputCancellation { } },
    ).apply {
        connected()
        event(RemoteWindowMetadata(RemoteKeyboard.OPEN, RemoteInputTarget("target.app", 7)))
    }

    @Test
    fun `uid gate accepts root and shell only`() {
        assertTrue(RemoteInputSocketServer.isAllowedUid(0))
        assertTrue(RemoteInputSocketServer.isAllowedUid(2000))
        listOf(-1, 1000, 2001, 10_001).forEach {
            assertFalse(RemoteInputSocketServer.isAllowedUid(it))
        }
        val listener = FakeListener()
        RemoteInputSocketServer(coordinator) { listener }.use { server ->
            assertTrue(server.start())
            val unauthorized = FakeConnection(1000, begin())
            listener.offer(unauthorized)
            unauthorized.awaitClosed()
            assertEquals("", unauthorized.outputText())
            assertFalse(coordinator.state().sending)
        }
    }

    @Test
    fun `subscriber stays idle while second client returns a single accepted frame`() {
        val listener = FakeListener()
        RemoteInputSocketServer(coordinator) { listener }.use { server ->
            assertTrue(server.start())
            val subscription = FakeConnection(2000, "{\"v\":1,\"op\":\"subscribe\"}\n")
            listener.offer(subscription)
            subscription.awaitFrame()
            assertEquals("state", JSONObject(subscription.firstFrame()).getString("type"))
            assertEquals(listOf(5_000, 0), subscription.timeouts.toList())

            val request = FakeConnection(2000, begin())
            listener.offer(request)
            request.awaitClosed()
            val frames = request.outputText().lineSequence().filter(String::isNotEmpty).toList()
            assertEquals(1, frames.size)
            assertTrue(JSONObject(frames.single()).getBoolean("accepted"))
            assertEquals(listOf(5_000), request.timeouts.toList())
            assertFalse(subscription.closed.get())

            subscription.disconnect()
            subscription.awaitClosed()
            val extra = FakeConnection(0, "{\"v\":1,\"op\":\"invalid\"}\n")
            listener.offer(extra)
            extra.awaitClosed()
            assertEquals("invalid_request", JSONObject(extra.firstFrame()).getString("reason"))
        }
    }

    @Test
    fun `two client slots reject a third without blocking accept`() {
        val listener = FakeListener()
        RemoteInputSocketServer(coordinator) { listener }.use { server ->
            server.start()
            val first = FakeConnection(2000, "{\"v\":1,\"op\":\"subscribe\"}\n")
            val second = FakeConnection(0, "{\"v\":1,\"op\":\"subscribe\"}\n")
            listener.offer(first)
            first.awaitFrame()
            listener.offer(second)
            second.awaitFrame()
            val third = FakeConnection(2000, begin())
            listener.offer(third)
            third.awaitClosed()
            assertEquals("", third.outputText())
            assertFalse(coordinator.state().sending)
        }
    }

    @Test
    fun `handshake timeout truncated frame and invalid op never submit`() {
        val listener = FakeListener()
        RemoteInputSocketServer(coordinator) { listener }.use { server ->
            server.start()
            val timeout = FakeConnection(2000, "", throwTimeout = true)
            listener.offer(timeout)
            timeout.awaitClosed()
            assertEquals(listOf(5_000), timeout.timeouts.toList())

            val truncated = FakeConnection(2000, begin().removeSuffix("\n"), eof = true)
            listener.offer(truncated)
            truncated.awaitClosed()
            assertEquals("", truncated.outputText())

            val invalid = FakeConnection(2000, "{\"v\":1,\"op\":\"commit\"}\n")
            listener.offer(invalid)
            invalid.awaitClosed()
            assertFalse(JSONObject(invalid.firstFrame()).getBoolean("accepted"))
            assertFalse(coordinator.state().sending)
        }
    }

    @Test
    fun `line framing requires newline enforces byte bound and preserves CR`() {
        val max = RemoteInputWire.MAX_REQUEST_FRAME_BYTES
        assertNull(RemoteInputSocketServer.readLine(ByteArrayInputStream("{}".toByteArray()), max))
        assertArrayEquals("a\rb".toByteArray(), RemoteInputSocketServer.readLine(
            ByteArrayInputStream("a\rb\n".toByteArray()), max,
        ))
        assertEquals(max, RemoteInputSocketServer.readLine(
            ByteArrayInputStream(ByteArray(max + 1) { if (it == max) 10 else 120 }), max,
        )?.size)
        assertNull(RemoteInputSocketServer.readLine(
            ByteArrayInputStream(ByteArray(max + 2) { if (it == max + 1) 10 else 120 }), max,
        ))
    }

    @Test
    fun `oversized state closes reader and failed accept stops instead of spinning`() {
        coordinator.event(RemoteWindowMetadata(
            RemoteKeyboard.OPEN, RemoteInputTarget("x".repeat(RemoteInputWire.MAX_STATE_FRAME_BYTES), 7),
        ))
        val listener = FakeListener()
        RemoteInputSocketServer(coordinator) { listener }.use { server ->
            server.start()
            val subscription = FakeConnection(2000, "{\"v\":1,\"op\":\"subscribe\"}\n")
            listener.offer(subscription)
            subscription.awaitClosed()
            assertEquals("", subscription.outputText())
            listener.failAccept()
            listener.awaitClosed()
            assertFalse(server.start())
        }
        assertEquals(2, listener.accepts.get())
    }

    private fun begin(): String = JSONObject()
        .put("v", 1).put("op", "begin").put("id", id)
        .put("epoch", coordinator.state().epoch).put("text", "text").toString() + "\n"

    private class FakeListener : RemoteInputSocketListener {
        private val incoming = LinkedBlockingQueue<Any>()
        private val stopped = AtomicBoolean(false)
        private val closed = CountDownLatch(1)
        private val end = Any()
        val accepts = AtomicInteger()

        fun offer(connection: FakeConnection) { incoming.put(connection) }
        fun failAccept() { incoming.put(end) }
        fun awaitClosed() { assertTrue(closed.await(2, TimeUnit.SECONDS)) }

        override fun accept(): RemoteInputSocketConnection {
            accepts.incrementAndGet()
            val next = try { incoming.take() } catch (_: InterruptedException) {
                throw IOException("interrupted")
            }
            if (next === end) throw IOException("closed listener")
            return next as FakeConnection
        }

        override fun close() {
            if (stopped.compareAndSet(false, true)) {
                incoming.offer(end)
                closed.countDown()
            }
        }
    }

    private class FakeConnection(
        override val peerUid: Int,
        first: String,
        eof: Boolean = false,
        throwTimeout: Boolean = false,
    ) : RemoteInputSocketConnection {
        private val monitor = Object()
        private val bytes = first.toByteArray(Charsets.UTF_8)
        private var position = 0
        private var inputEnded = eof
        private val ended = CountDownLatch(1)
        private val frameReady = CountDownLatch(1)
        private val recorded = ByteArrayOutputStream()
        val closed = AtomicBoolean(false)
        val timeouts = CopyOnWriteArrayList<Int>()

        override val input = object : InputStream() {
            override fun read(): Int = synchronized(monitor) {
                if (throwTimeout) throw SocketTimeoutException("fixture timeout")
                if (position < bytes.size) return@synchronized bytes[position++].toInt() and 0xff
                while (!inputEnded && !closed.get()) {
                    try { monitor.wait() } catch (_: InterruptedException) { throw IOException("interrupted") }
                }
                -1
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = synchronized(monitor) {
                if (length == 0) return@synchronized 0
                if (position < bytes.size) {
                    val count = minOf(length, bytes.size - position)
                    bytes.copyInto(buffer, offset, position, position + count)
                    position += count
                    return@synchronized count
                }
                val next = read()
                if (next < 0) -1 else { buffer[offset] = next.toByte(); 1 }
            }
        }

        override val output = object : OutputStream() {
            override fun write(value: Int) = synchronized(recorded) {
                if (closed.get()) throw IOException("closed")
                recorded.write(value)
                if (value == 10) frameReady.countDown()
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) = synchronized(recorded) {
                if (closed.get()) throw IOException("closed")
                recorded.write(buffer, offset, length)
                if ((offset until offset + length).any { buffer[it] == 10.toByte() }) frameReady.countDown()
            }
        }

        override fun setReadTimeout(millis: Int) { timeouts += millis }
        fun awaitFrame() { assertTrue(frameReady.await(2, TimeUnit.SECONDS)) }
        fun awaitClosed() { assertTrue(ended.await(2, TimeUnit.SECONDS)) }
        fun outputText(): String = synchronized(recorded) { recorded.toString("UTF-8") }
        fun firstFrame(): String = outputText().substringBefore('\n')
        fun disconnect() = synchronized(monitor) { inputEnded = true; monitor.notifyAll() }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                synchronized(monitor) { monitor.notifyAll() }
                ended.countDown()
            }
        }
    }
}
