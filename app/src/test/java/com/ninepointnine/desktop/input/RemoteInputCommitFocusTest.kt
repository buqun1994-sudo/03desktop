package com.ninepointnine.desktop.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputCommitFocusTest {
    private val target = RemoteInputTarget("target.app", 7)
    private val fieldA = RemoteInputFocusMetadata(target, "field-a")
    private val fieldB = RemoteInputFocusMetadata(target, "field-b")
    private val id = "00000000-0000-0000-0000-000000000001"
    private var now = 0L

    @Test
    fun `new input connection before its focus event cannot receive frozen text`() {
        val owner = ready(fieldA)
        var commits = 0
        val result = owner.tryCommit(target.packageName, true, { true }, { fieldB }) { commits++; true }
        assertFalse(result!!.committed)
        assertEquals("focus_changed", result.reason)
        assertEquals(0, commits)
        owner.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target), fieldB)
        assertEquals(result, owner.result(id).getNow(null))
    }

    @Test
    fun `same field synthetic event plus current proof permits only one commit`() {
        val owner = ready(fieldA)
        owner.event(RemoteWindowMetadata(RemoteKeyboard.CLOSED, RemoteInputTarget()), fieldA)
        owner.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target), fieldA)
        var commits = 0
        var queries = 0
        val result = owner.tryCommit(target.packageName, true, { true }, { queries++; fieldA }) { commits++; true }
        assertTrue(result!!.committed)
        assertNull(owner.tryCommit(target.packageName, true, { true }, { queries++; fieldA }) { commits++; true })
        assertEquals(1, commits)
        assertEquals(1, queries)
    }

    @Test
    fun `missing current proof cannot commit to a previously known field`() {
        val owner = ready(fieldA)
        val result = owner.tryCommit(target.packageName, true, { true }, { null }) { error("Must not commit") }
        assertEquals("focus_unverified", result!!.reason)
    }

    @Test
    fun `unknown frozen field is never rebound to a current field`() {
        val owner = ready(null)
        val result = owner.tryCommit(target.packageName, true, { true }, { fieldA }) { error("Must not commit") }
        assertEquals("focus_unverified", result!!.reason)
    }

    @Test
    fun `deadline is rechecked after a slow current focus query`() {
        val owner = ready(fieldA)
        val result = owner.tryCommit(target.packageName, true, { true }, {
            now = RemoteInputCoordinator.DEADLINE_MILLIS
            fieldA
        }) { error("Must not commit beyond deadline") }
        assertEquals("deadline", result!!.reason)
    }

    @Test
    fun `input connection changed during query is rejected`() {
        val owner = ready(fieldA)
        var current = true
        val result = owner.tryCommit(target.packageName, true, { current }, {
            current = false
            fieldA
        }) { error("Must not commit to a changed connection") }
        assertEquals("focus_changed", result!!.reason)
    }

    @Test
    fun `actual same window event is not replaced with stale snapshot field`() {
        val signal = RemoteInputTargetResolver.viewFocus(target, fieldB.token, fieldA)
        assertNull(signal.token)
        val owner = ready(fieldA)
        owner.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target), signal)
        assertEquals("focus_changed", owner.result(id).getNow(null)!!.reason)
    }

    @Test
    fun `actual event matching current field can be deduped`() {
        val signal = RemoteInputTargetResolver.viewFocus(target, fieldA.token, fieldA)
        assertEquals(fieldA, signal)
        val owner = ready(fieldA)
        owner.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target), signal)
        assertTrue(owner.state().sending)
    }

    @Test
    fun `missing source identity is not inferred from current snapshot`() {
        val signal = RemoteInputTargetResolver.viewFocus(target, null, fieldA)
        assertNull(signal.token)
    }

    private fun ready(focus: RemoteInputFocusMetadata?) = RemoteInputCoordinator(
        imeDeclared = true,
        nowMillis = { now },
        deadlines = RemoteInputDeadlineScheduler { _, _ -> RemoteInputCancellation { } },
    ).also {
        it.connected()
        it.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target), focus)
        assertTrue(it.begin(RemoteInputBegin(id, it.state().epoch, "text")).accepted)
    }
}
