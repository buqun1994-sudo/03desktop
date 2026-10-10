package com.ninepointnine.desktop.input

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class RemoteInputWireTest {
    private val id = "00000000-0000-0000-0000-000000000001"

    @Test
    fun `begin accepts strict v1 UTF8 JSON and cancel canonicalizes uuid`() {
        val beginJson = JSONObject()
            .put("v", 1).put("id", id).put("epoch", 9).put("text", "手机输入")
            .toString().toByteArray(StandardCharsets.UTF_8)
        val parsed = RemoteInputWire.parseBegin(beginJson)
        assertEquals(RemoteInputBegin(id, 9, "手机输入"), parsed)

        val cancelJson = JSONObject()
            .put("v", 1).put("id", id.uppercase()).toString().toByteArray(StandardCharsets.UTF_8)
        assertEquals(id, RemoteInputWire.parseCancel(cancelJson))
    }

    @Test
    fun `invalid version trailing JSON and malformed UTF8 are rejected`() {
        val valid = "{\"v\":1,\"id\":\"$id\",\"epoch\":1,\"text\":\"x\"}"
        assertNull(RemoteInputWire.parseBegin((valid + "{}" ).toByteArray()))
        assertNull(RemoteInputWire.parseBegin(valid.replace("\"v\":1", "\"v\":2").toByteArray()))
        assertNull(RemoteInputWire.parseBegin(byteArrayOf(0x7b, 0x22, 0x76, 0x22, 0x3a, 0x31, 0x2c, 0x22, 0x74, 0x22, 0x3a, 0x22, 0xc3.toByte(), 0x28, 0x22, 0x7d)))
    }

    @Test
    fun `socket begin carries escaped and maximum sized text without shell encoding`() {
        val text = "line\n\\quote\u0000" + "\u0000".repeat(32_768 - 12)
        val json = JSONObject()
            .put("v", 1).put("op", "begin").put("id", id).put("epoch", 3).put("text", text)
            .toString().toByteArray(StandardCharsets.UTF_8)
        assertTrue(json.size <= RemoteInputWire.MAX_REQUEST_FRAME_BYTES)
        val parsed = RemoteInputWire.parseSocketBegin(json)
        assertEquals(text, parsed?.text)
        assertEquals(32_768, parsed?.text?.length)
        assertNull(RemoteInputWire.parseSocketBegin(json.copyOf(json.size - 1)))
    }

    @Test
    fun `socket begin rejects invalid operation and oversized frame`() {
        val subscribe = "{\"v\":1,\"op\":\"subscribe\"}".toByteArray()
        assertNull(RemoteInputWire.parseSocketBegin(subscribe))
        val providerShape = "{\"v\":1,\"id\":\"$id\",\"epoch\":1,\"text\":\"x\"}".toByteArray()
        assertNull(RemoteInputWire.parseSocketBegin(providerShape))
        assertNull(RemoteInputWire.parseSocketBegin(ByteArray(RemoteInputWire.MAX_REQUEST_FRAME_BYTES + 1)))
    }
    @Test
    fun `subscribe accepts only a bounded v1 command`() {
        assertTrue(RemoteInputWire.isSubscribe("{\"v\":1,\"op\":\"subscribe\"}".toByteArray()))
        assertFalse(RemoteInputWire.isSubscribe("{\"v\":2,\"op\":\"subscribe\"}".toByteArray()))
        assertFalse(RemoteInputWire.isSubscribe("{\"v\":1,\"op\":\"subscribe\"}x".toByteArray()))
    }

    @Test
    fun `frames contain required v1 state and terminal fields`() {
        val state = RemoteInputState(
            available = true,
            keyboard = RemoteKeyboard.OPEN,
            epoch = 4,
            target = RemoteInputTarget("target.app", 7),
            sequence = 8,
            sending = true,
        )
        val stateJson = JSONObject(RemoteInputWire.state(state))
        assertEquals(1, stateJson.getInt("v"))
        assertEquals("state", stateJson.getString("type"))
        assertEquals("open", stateJson.getString("keyboard"))
        assertEquals("target.app", stateJson.getString("targetPackage"))
        assertTrue(stateJson.getBoolean("sending"))

        val accepted = JSONObject(RemoteInputWire.accepted(RemoteInputAcceptance(id, true, "accepted")))
        assertEquals("accepted", accepted.getString("type"))
        assertTrue(accepted.getBoolean("accepted"))
        val result = JSONObject(RemoteInputWire.result(RemoteInputResult(id, false, "deadline")))
        assertFalse(result.getBoolean("committed"))
        val restore = JSONObject(RemoteInputWire.restoreClaim(id, true))
        assertEquals("restore_claim", restore.getString("type"))
        assertTrue(restore.getBoolean("allowed"))
        val release = JSONObject(RemoteInputWire.restoreRelease(id, false))
        assertEquals("restore_release", release.getString("type"))
        assertEquals(id, release.getString("id"))
        assertFalse(release.getBoolean("released"))
    }

    @Test
    fun `capability preserves active sending and rejects raw control bytes`() {
        val state = RemoteInputState(true, RemoteKeyboard.OPEN, 4, RemoteInputTarget("target.app", 7), 8, true)
        assertTrue(JSONObject(RemoteInputWire.capability("com.ninepointnine.desktop", state)).getBoolean("sending"))
        val prefix = "{\"v\":1,\"op\":\"begin\",\"id\":\"$id\",\"epoch\":4,\"text\":\""
        assertNull(RemoteInputWire.parseSocketBegin((prefix + "a\rb\"}").toByteArray()))
        assertNull(RemoteInputWire.parseSocketBegin((prefix + "ab\"}\u0000").toByteArray()))
    }

    @Test
    fun `capability includes package service and local socket`() {
        val capability = JSONObject(
            RemoteInputWire.capability(
                "com.ninepointnine.desktop",
                RemoteInputState(false, RemoteKeyboard.UNKNOWN, 1, RemoteInputTarget(), 2, false),
            ),
        )
        assertEquals("capability", capability.getString("type"))
        assertEquals(
            "com.ninepointnine.desktop/com.ninepointnine.desktop.input.RemoteInputMethodService",
            capability.getString("ime"),
        )
        assertEquals("com.ninepointnine.desktop.remoteinput", capability.getString("socket"))
        assertEquals("socket", capability.getString("requestTransport"))
        assertEquals("exclusive", capability.getString("restoreProtocol"))
        assertEquals(listOf("claim_restore", "release_restore"), capability.getJSONArray("restoreMethods").let { methods ->
            (0 until methods.length()).map(methods::getString)
        })
        assertFalse(capability.getBoolean("sending"))
    }
}
