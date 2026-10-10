package com.ninepointnine.desktop.input

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** JSON v1. Android transport alone owns Base64; sockets and receipts carry raw UTF-8 JSON lines. */
object RemoteInputWire {
    const val VERSION = 1
    const val MAX_REQUEST_FRAME_BYTES = 262_144
    const val MAX_STATE_FRAME_BYTES = 65_536
    const val MAX_ENCODED_REQUEST_LENGTH = (MAX_REQUEST_FRAME_BYTES * 4 + 2) / 3 + 4
    const val MAX_SUBSCRIBE_BYTES = 256

    fun capability(packageName: String, state: RemoteInputState): String = stateJson("capability", state)
        .put("ime", "$packageName/com.ninepointnine.desktop.input.RemoteInputMethodService")
        .put("socket", "$packageName.remoteinput")
        .put("requestTransport", "socket")
        .put("restoreProtocol", "exclusive")
        .put("restoreMethods", org.json.JSONArray(listOf("claim_restore", "release_restore")))
        .toString()

    fun state(state: RemoteInputState): String = stateJson("state", state).toString()

    fun accepted(value: RemoteInputAcceptance): String = JSONObject()
        .put("v", VERSION).put("type", "accepted").put("id", value.id)
        .put("accepted", value.accepted).put("reason", value.reason).toString()

    fun result(value: RemoteInputResult): String = JSONObject()
        .put("v", VERSION).put("type", "result").put("id", value.id)
        .put("committed", value.committed).put("reason", value.reason).toString()

    fun restoreClaim(id: String, allowed: Boolean): String = JSONObject()
        .put("v", VERSION).put("type", "restore_claim").put("id", id)
        .put("allowed", allowed).toString()

    fun restoreRelease(id: String, released: Boolean): String = JSONObject()
        .put("v", VERSION).put("type", "restore_release").put("id", id)
        .put("released", released).toString()

    fun parseBegin(bytes: ByteArray): RemoteInputBegin? = parse(bytes)?.let { json ->
        val id = (json.opt("id") as? String) ?: return@let null
        val text = (json.opt("text") as? String) ?: return@let null
        val epoch = integer(json.opt("epoch")) ?: return@let null
        if (epoch < 0 || RemoteInputCoordinator.canonicalId(id) == null) return@let null
        if (text.length > RemoteInputCoordinator.MAX_TEXT_BYTES) return@let null
        RemoteInputBegin(id, epoch, text)
    }

    fun parseSocketBegin(bytes: ByteArray): RemoteInputBegin? {
        if (bytes.size > MAX_REQUEST_FRAME_BYTES) return null
        val json = parse(bytes) ?: return null
        if (json.opt("op") != "begin") return null
        return parseBegin(bytes)
    }

    fun parseCancel(bytes: ByteArray): String? = parse(bytes)?.let { json ->
        (json.opt("id") as? String)?.let(RemoteInputCoordinator::canonicalId)
    }

    fun isSubscribe(bytes: ByteArray): Boolean = bytes.size <= MAX_SUBSCRIBE_BYTES &&
        parse(bytes)?.opt("op") == "subscribe"

    private fun parse(bytes: ByteArray): JSONObject? {
        return try {
            if (bytes.size > MAX_REQUEST_FRAME_BYTES) return null
            val decoded = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            if (invalidControls(decoded)) return null
            // Reject trailing payloads accepted by JSONObject's permissive tokenizer.
            val tokenizer = org.json.JSONTokener(decoded)
            val json = tokenizer.nextValue() as? JSONObject ?: return null
            if (tokenizer.nextClean() != '\u0000' || integer(json.opt("v")) != VERSION.toLong()) return null
            json
        } catch (_: Exception) {
            null
        }
    }

    private fun invalidControls(value: String): Boolean {
        var quoted = false
        var escaped = false
        for (char in value) {
            if (char.code < 0x20 && (quoted || char !in "\t\r\n")) return true
            when {
                escaped -> escaped = false
                quoted && char == '\\' -> escaped = true
                char == '"' -> quoted = !quoted
            }
        }
        return false
    }

    private fun integer(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun stateJson(type: String, value: RemoteInputState) = JSONObject()
        .put("v", VERSION).put("type", type).put("available", value.available)
        .put("keyboard", value.keyboard.wireValue).put("epoch", value.epoch)
        .put("targetPackage", value.target.packageName).put("targetWindow", value.target.windowId)
        .put("sequence", value.sequence).put("sending", value.sending)
}
