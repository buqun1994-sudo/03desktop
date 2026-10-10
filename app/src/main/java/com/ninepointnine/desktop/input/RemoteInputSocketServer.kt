package com.ninepointnine.desktop.input

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface RemoteInputSocketConnection : Closeable {
    val peerUid: Int
    val input: InputStream
    val output: OutputStream
    fun setReadTimeout(millis: Int)
}

internal interface RemoteInputSocketListener : Closeable {
    fun accept(): RemoteInputSocketConnection
}

/** One localabstract endpoint; requests and subscriptions share two client slots and bounded workers. */
class RemoteInputSocketServer private constructor(
    private val createListener: () -> RemoteInputSocketListener,
    private val coordinator: RemoteInputCoordinator,
) : Closeable {
    constructor(socketName: String, coordinator: RemoteInputCoordinator) : this(
        { AndroidSocketListener(socketName) }, coordinator,
    )

    internal constructor(
        coordinator: RemoteInputCoordinator,
        createListener: () -> RemoteInputSocketListener,
    ) : this(createListener, coordinator)

    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val clients = LinkedHashSet<Client>()
    private val workers = ThreadPoolExecutor(
        MAX_CLIENTS * 2, MAX_CLIENTS * 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_CLIENTS * 2),
        { action -> Thread(action, "remote-input-io").apply { isDaemon = true } },
    )
    private var server: RemoteInputSocketListener? = null
    private var acceptThread: Thread? = null

    fun start(): Boolean = synchronized(lock) {
        if (stopped.get()) return false
        if (running.get()) return true
        val listener = runCatching { createListener() }.getOrNull() ?: return false
        server = listener
        running.set(true)
        acceptThread = Thread({ acceptLoop(listener) }, "remote-input-accept").apply {
            isDaemon = true
            start()
        }
        true
    }

    private fun acceptLoop(listener: RemoteInputSocketListener) {
        try {
            while (running.get()) {
                val connection = try {
                    listener.accept()
                } catch (_: IOException) {
                    // A failed listener is terminal; do not spin on a broken accept socket.
                    break
                }
                if (!runCatching { isAllowedUid(connection.peerUid) }.getOrDefault(false)) {
                    closeQuietly(connection)
                    continue
                }
                val client = Client(connection)
                val admitted = synchronized(lock) {
                    if (!running.get() || clients.size >= MAX_CLIENTS) false
                    else clients.add(client)
                }
                if (!admitted) {
                    client.close()
                    continue
                }
                try {
                    workers.execute(client::run)
                } catch (_: RuntimeException) {
                    client.close()
                }
            }
        } finally {
            close()
        }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        val listener: RemoteInputSocketListener?
        val currentClients: List<Client>
        synchronized(lock) {
            running.set(false)
            listener = server
            server = null
            currentClients = clients.toList()
            acceptThread?.interrupt()
            acceptThread = null
        }
        closeQuietly(listener)
        currentClients.forEach(Client::close)
        workers.shutdownNow()
    }

    private inner class Client(private val connection: RemoteInputSocketConnection) : Closeable {
        private val closed = AtomicBoolean(false)
        private val subscriptionLock = Any()
        private val queue = ArrayBlockingQueue<RemoteInputState>(1)
        private val listenerKey = Any()
        private var writer: Future<*>? = null

        fun run() {
            try {
                connection.setReadTimeout(HANDSHAKE_TIMEOUT_MILLIS)
                val input = connection.input.buffered()
                val first = readLine(input, RemoteInputWire.MAX_REQUEST_FRAME_BYTES) ?: return
                if (RemoteInputWire.isSubscribe(first)) runSubscription(input)
                else runRequest(first)
            } catch (_: IOException) {
                // Timeout, EOF and disconnect all terminate this client.
            } finally {
                close()
            }
        }

        private fun runRequest(first: ByteArray) {
            val request = RemoteInputWire.parseSocketBegin(first)
            val acceptance = request?.let(coordinator::begin)
                ?: RemoteInputAcceptance("", false, "invalid_request")
            connection.output.write((RemoteInputWire.accepted(acceptance) + "\n").toByteArray(Charsets.UTF_8))
            connection.output.flush()
        }

        private fun runSubscription(input: InputStream) {
            connection.setReadTimeout(0)
            synchronized(subscriptionLock) {
                if (closed.get()) return
                coordinator.listen(listenerKey) { state ->
                    queue.poll()
                    queue.offer(state)
                }
                writer = workers.submit(::writeLoop)
            }
            // EOF is the lifetime; a subscription accepts no second command.
            readLine(input, RemoteInputWire.MAX_SUBSCRIBE_BYTES)
        }

        private fun writeLoop() {
            try {
                val output = connection.output
                while (!closed.get()) {
                    val frame = (RemoteInputWire.state(queue.take()) + "\n").toByteArray(Charsets.UTF_8)
                    if (frame.size > RemoteInputWire.MAX_STATE_FRAME_BYTES) return
                    output.write(frame)
                    output.flush()
                }
            } catch (_: IOException) {
                // Closing the connection also unblocks the EOF reader.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                close()
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            synchronized(subscriptionLock) {
                coordinator.unlisten(listenerKey)
                writer?.cancel(true)
            }
            synchronized(lock) { clients.remove(this) }
            closeQuietly(connection)
        }
    }

    companion object {
        internal const val MAX_CLIENTS = 2
        internal const val HANDSHAKE_TIMEOUT_MILLIS = 5_000

        internal fun isAllowedUid(uid: Int): Boolean = uid == 0 || uid == 2000

        internal fun readLine(input: InputStream, maxBytes: Int): ByteArray? {
            val output = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) return null
                if (value == '\n'.code) return output.toByteArray()
                if (output.size() >= maxBytes) return null
                // Preserve CR and all other bytes; decoding, not framing, validates JSON.
                output.write(value)
            }
        }

        private fun closeQuietly(value: Closeable?) {
            runCatching { value?.close() }
        }
    }
}

private class AndroidSocketListener(name: String) : RemoteInputSocketListener {
    private val socket = LocalServerSocket(name)

    override fun accept(): RemoteInputSocketConnection = AndroidSocketConnection(socket.accept())
    override fun close() = socket.close()
}

private class AndroidSocketConnection(private val socket: LocalSocket) : RemoteInputSocketConnection {
    override val peerUid: Int get() = socket.peerCredentials.uid
    override val input: InputStream get() = socket.inputStream
    override val output: OutputStream get() = socket.outputStream
    override fun setReadTimeout(millis: Int) { socket.soTimeout = millis }
    override fun close() = socket.close()
}
