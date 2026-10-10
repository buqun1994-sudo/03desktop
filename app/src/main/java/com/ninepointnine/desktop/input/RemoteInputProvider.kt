package com.ninepointnine.desktop.input

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class RemoteInputProvider : ContentProvider() {
    private lateinit var coordinator: RemoteInputCoordinator
    private lateinit var socketServer: RemoteInputSocketServer
    private lateinit var writers: ThreadPoolExecutor

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        coordinator = RemoteInputRuntime.coordinator(appContext)
        socketServer = RemoteInputSocketServer(
            RemoteInputRuntime.socketName(appContext), coordinator,
        )
        writers = ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32),
        )
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (!isAllowedCaller()) return frame(error("unauthorized"))
        return when (method) {
            METHOD_GET_CAPABILITY -> {
                socketServer.start()
                frame(RemoteInputWire.capability(providerContext().packageName, coordinator.state()))
            }
            METHOD_GET_STATE -> frame(RemoteInputWire.state(coordinator.state()))
            METHOD_BEGIN -> {
                val request = decode(arg)?.let(RemoteInputWire::parseBegin)
                frame(
                    RemoteInputWire.accepted(
                        request?.let(coordinator::begin)
                            ?: RemoteInputAcceptance("", false, "invalid_request"),
                    ),
                )
            }
            METHOD_CANCEL -> {
                val id = decode(arg)?.let(RemoteInputWire::parseCancel)
                frame(
                    RemoteInputWire.accepted(
                        id?.let(coordinator::cancel)
                            ?: RemoteInputAcceptance("", false, "invalid_request"),
                    ),
                )
            }
            METHOD_CLAIM_RESTORE -> {
                val id = decode(arg)?.let(RemoteInputWire::parseCancel)
                val canonical = id ?: ""
                val allowed = canonical.isNotEmpty() && coordinator.claimRestore(canonical)
                restoreFrame(RemoteInputWire.restoreClaim(canonical, allowed), allowed)
            }
            METHOD_RELEASE_RESTORE -> {
                val id = decode(arg)?.let(RemoteInputWire::parseCancel)
                val canonical = id ?: ""
                val released = canonical.isNotEmpty() && coordinator.releaseRestore(canonical)
                frame(RemoteInputWire.restoreRelease(canonical, released)).apply {
                    putBoolean(KEY_RESTORE_RELEASED, released)
                }
            }
            else -> frame(error("invalid_method"))
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!isAllowedCaller()) throw FileNotFoundException("unauthorized")
        if (mode != "r") throw FileNotFoundException("read-only result pipe")
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != RESULT_PATH) {
            throw FileNotFoundException("invalid result URI")
        }
        val id = RemoteInputCoordinator.canonicalId(segments[1])
            ?: throw FileNotFoundException("invalid result id")
        val pipes = try {
            ParcelFileDescriptor.createReliablePipe()
        } catch (error: IOException) {
            throw FileNotFoundException(error.message ?: "pipe unavailable")
        }
        try {
            writers.execute { writeResult(pipes[1], id) }
        } catch (_: RuntimeException) {
            closeQuietly(pipes[0])
            closeQuietly(pipes[1])
            throw FileNotFoundException("result writer unavailable")
        }
        return pipes[0]
    }

    private fun writeResult(writer: ParcelFileDescriptor, id: String) {
        ParcelFileDescriptor.AutoCloseOutputStream(writer).use { stream ->
            try {
                val result = coordinator.result(id).get()
                stream.write((RemoteInputWire.result(result) + "\n").toByteArray(Charsets.UTF_8))
                stream.flush()
            } catch (_: Exception) {
                // Reader disconnect and process shutdown both close the reliable pipe.
            }
        }
    }

    private fun decode(value: String?): ByteArray? {
        if (value.isNullOrBlank() || value.length > RemoteInputWire.MAX_ENCODED_REQUEST_LENGTH) return null
        return runCatching { Base64.decode(value, Base64.DEFAULT) }
            .getOrNull()
            ?.takeIf { it.size <= RemoteInputWire.MAX_REQUEST_FRAME_BYTES }
    }

    private fun frame(json: String) = Bundle().apply {
        putString(KEY_FRAME, Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
    }

    private fun restoreFrame(json: String, allowed: Boolean) = Bundle().apply {
        putString(KEY_FRAME, Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        putBoolean(KEY_RESTORE_ALLOWED, allowed)
    }

    private fun error(reason: String) = RemoteInputWire.accepted(
        RemoteInputAcceptance("", false, reason),
    )

    private fun isAllowedCaller(): Boolean = Binder.getCallingUid() == UID_ROOT || Binder.getCallingUid() == UID_SHELL

    private fun providerContext() = requireNotNull(context)

    override fun shutdown() {
        if (::socketServer.isInitialized) socketServer.close()
        if (::writers.isInitialized) writers.shutdownNow()
        super.shutdown()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val METHOD_GET_CAPABILITY = "get_capability"
        const val METHOD_GET_STATE = "get_state"
        const val METHOD_BEGIN = "begin"
        const val METHOD_CANCEL = "cancel"
        const val METHOD_CLAIM_RESTORE = "claim_restore"
        const val METHOD_RELEASE_RESTORE = "release_restore"
        const val RESULT_PATH = "results"
        const val KEY_FRAME = "frame"
        const val KEY_RESTORE_ALLOWED = "restoreAllowed"
        const val KEY_RESTORE_RELEASED = "restoreReleased"
        private const val UID_ROOT = 0
        private const val UID_SHELL = 2000
    }
}

private fun closeQuietly(value: ParcelFileDescriptor) {
    runCatching { value.close() }
}
