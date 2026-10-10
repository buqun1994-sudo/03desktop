package com.ninepointnine.desktop.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputCoordinatorTest {
    private var now = 0L
    private val scheduler = FakeScheduler()
    private val coordinator = RemoteInputCoordinator(
        imeDeclared = true,
        nowMillis = { now },
        deadlines = scheduler,
    )

    @Test
    fun `connection and metadata make an open target available`() {
        coordinator.connected()
        coordinator.event(
            RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)),
        )

        val state = coordinator.state()
        assertTrue(state.available)
        assertEquals(RemoteKeyboard.OPEN, state.keyboard)
        assertEquals("target.app", state.target.packageName)
        assertEquals(7, state.target.windowId)
        assertTrue(state.sequence >= 2)
    }

    @Test
    fun `unknown keyboard is never rewritten as closed after accessibility loss`() {
        coordinator.connected()
        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)))
        coordinator.disconnected()

        val state = coordinator.state()
        assertFalse(state.available)
        assertEquals(RemoteKeyboard.UNKNOWN, state.keyboard)
        assertEquals(RemoteInputTarget(), state.target)
    }

    @Test
    fun `begin requires open current epoch target and only one active request`() {
        coordinator.connected()
        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.CLOSED, target("target.app", 7)))
        val closed = coordinator.begin(begin("00000000-0000-0000-0000-000000000009", "closed"))
        assertFalse(closed.accepted)
        assertEquals("keyboard_not_open", closed.reason)

        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)))
        val epoch = coordinator.state().epoch
        val first = coordinator.begin(begin("00000000-0000-0000-0000-000000000001", "one", epoch))
        assertTrue(first.accepted)
        val busy = coordinator.begin(begin("00000000-0000-0000-0000-000000000002", "two", epoch))
        assertFalse(busy.accepted)
        assertEquals("busy", busy.reason)
    }

    @Test
    fun `same id is deduped but changed payload is rejected`() {
        coordinator.connected()
        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)))
        val epoch = coordinator.state().epoch
        val request = begin("00000000-0000-0000-0000-000000000001", "one", epoch)
        val accepted = coordinator.begin(request)
        val duplicate = coordinator.begin(request)
        assertEquals(accepted, duplicate)
        val reused = coordinator.begin(request.copy(text = "different"))
        assertFalse(reused.accepted)
        assertEquals("id_reused", reused.reason)
    }

    @Test
    fun `transient ime close and reopen preserves frozen request`() {
        coordinator.connected()
        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)), focus())
        val epoch = coordinator.state().epoch
        val id = "00000000-0000-0000-0000-000000000001"
        assertTrue(coordinator.begin(begin(id, "全文", epoch)).accepted)

        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.CLOSED, RemoteInputTarget()))
        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)))
        assertTrue(coordinator.state().sending)

        val result = coordinator.tryCommit(
            editorPackage = "target.app",
            validEditor = true,
            sessionIsCurrent = { true },
            currentFocus = { focus() },
            commit = { it == "全文" },
        )
        assertNotNull(result)
        assertTrue(result!!.committed)
        assertFalse(coordinator.state().sending)
    }

    @Test
    fun `real target change and focus event reject request`() {
        coordinator.connected()
        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)))
        val epoch = coordinator.state().epoch
        val firstId = "00000000-0000-0000-0000-000000000001"
        assertTrue(coordinator.begin(begin(firstId, "one", epoch)).accepted)
        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("other.app", 8)))
        assertEquals("target_changed", coordinator.result(firstId).get().reason)

        val nextEpoch = coordinator.state().epoch
        val secondId = "00000000-0000-0000-0000-000000000002"
        assertTrue(coordinator.begin(begin(secondId, "two", nextEpoch)).accepted)
        coordinator.event(
            RemoteWindowMetadata(RemoteKeyboard.OPEN, target("other.app", 8)),
            focused = RemoteInputFocusMetadata(target("other.app", 8), "field-2"),
        )
        assertEquals("focus_changed", coordinator.result(secondId).get().reason)
    }

    @Test
    fun `same focus token is ignored but changed token rejects while keyboard is closed`() {
        coordinator.connected()
        val appTarget = target("target.app", 7)
        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, appTarget))
        coordinator.event(
            RemoteWindowMetadata(RemoteKeyboard.OPEN, appTarget),
            focused = RemoteInputFocusMetadata(appTarget, "field-1"),
        )
        val epoch = coordinator.state().epoch
        val id = "00000000-0000-0000-0000-000000000001"
        assertTrue(coordinator.begin(begin(id, "one", epoch)).accepted)

        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.CLOSED, RemoteInputTarget()))
        coordinator.event(
            RemoteWindowMetadata(RemoteKeyboard.CLOSED, RemoteInputTarget()),
            focused = RemoteInputFocusMetadata(appTarget, "field-1"),
        )
        assertTrue(coordinator.state().sending)
        coordinator.event(
            RemoteWindowMetadata(RemoteKeyboard.CLOSED, RemoteInputTarget()),
            focused = RemoteInputFocusMetadata(appTarget, "field-2"),
        )
        assertEquals("focus_changed", coordinator.result(id).get().reason)
    }

    @Test
    fun `focus event without token is conservatively terminal`() {
        coordinator.connected()
        val appTarget = target("target.app", 7)
        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, appTarget))
        val epoch = coordinator.state().epoch
        val id = "00000000-0000-0000-0000-000000000001"
        assertTrue(coordinator.begin(begin(id, "one", epoch)).accepted)
        coordinator.event(
            RemoteWindowMetadata(RemoteKeyboard.CLOSED, RemoteInputTarget()),
            focused = RemoteInputFocusMetadata(appTarget, null),
        )
        assertEquals("focus_changed", coordinator.result(id).get().reason)
    }

    @Test
    fun `commit result false and invalid editor are terminal`() {
        coordinator.connected()
        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)), focus())
        val epoch = coordinator.state().epoch
        val id = "00000000-0000-0000-0000-000000000001"
        assertTrue(coordinator.begin(begin(id, "one", epoch)).accepted)
        val invalid = coordinator.tryCommit("target.app", false, { true }, { focus() }) { true }
        assertEquals("invalid_editor", invalid?.reason)

        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)), focus())
        val next = "00000000-0000-0000-0000-000000000002"
        val nextEpoch = coordinator.state().epoch
        assertTrue(coordinator.begin(begin(next, "two", nextEpoch)).accepted)
        val failed = coordinator.tryCommit("target.app", true, { true }, { focus() }) { false }
        assertEquals("commit_failed", failed?.reason)
    }

    @Test
    fun `deadline clears retained text and result remains readable`() {
        coordinator.connected()
        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)))
        val id = "00000000-0000-0000-0000-000000000001"
        assertTrue(coordinator.begin(begin(id, "one", coordinator.state().epoch)).accepted)
        now = RemoteInputCoordinator.DEADLINE_MILLIS
        assertEquals("deadline", coordinator.result(id).get().reason)
        assertEquals(0, coordinator.retainedTextCount())
    }

    @Test
    fun `receipt lru is bounded and restore owner follows latest accepted id`() {
        coordinator.connected()
        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)))
        repeat(33) { index ->
            val id = "%08d-0000-0000-0000-%012d".format(index + 1, index + 1)
            assertTrue(coordinator.begin(begin(id, "text", coordinator.state().epoch)).accepted)
            assertTrue(coordinator.cancel(id).accepted)
        }
        assertEquals(RemoteInputCoordinator.MAX_RECEIPTS, coordinator.retainedReceiptCount())
        val newest = "00000033-0000-0000-0000-000000000033"
        assertTrue(coordinator.claimRestore(newest))
        val replacement = "00000099-0000-0000-0000-000000000099"
        assertEquals("restore_in_progress", coordinator.begin(begin(replacement, "new", coordinator.state().epoch)).reason)
        assertTrue(coordinator.releaseRestore(newest))
        assertTrue(coordinator.begin(begin(replacement, "new", coordinator.state().epoch)).accepted)
        assertFalse(coordinator.claimRestore(newest))
        assertTrue(coordinator.claimRestore(replacement))
    }

    @Test
    fun `utf8 byte limit is enforced and text is released after cancellation`() {
        coordinator.connected()
        coordinator.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)))
        val epoch = coordinator.state().epoch
        val tooLarge = "😀".repeat(8_193)
        val rejected = coordinator.begin(begin("00000000-0000-0000-0000-000000000001", tooLarge, epoch))
        assertFalse(rejected.accepted)
        assertEquals("text_too_large", rejected.reason)

        val id = "00000000-0000-0000-0000-000000000002"
        assertTrue(coordinator.begin(begin(id, "short", epoch)).accepted)
        assertEquals(1, coordinator.retainedTextCount())
        assertTrue(coordinator.cancel(id).accepted)
        assertEquals(0, coordinator.retainedTextCount())
        assertFalse(coordinator.cancel(id).accepted)
    }

    @Test
    fun `owner checkpoint survives coordinator recreation and newer owner replaces it`() {
        val store = FakeOwnerStore()
        fun create() = RemoteInputCoordinator(
            imeDeclared = true,
            nowMillis = { 0L },
            deadlines = FakeScheduler(),
            ownerStore = store,
        )
        val first = create()
        first.connected()
        first.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)))
        val firstId = "00000000-0000-0000-0000-000000000001"
        assertTrue(first.begin(begin(firstId, "one", first.state().epoch)).accepted)
        assertTrue(first.cancel(firstId).accepted)

        val recreated = create()
        assertTrue(recreated.claimRestore(firstId))
        recreated.connected()
        recreated.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)))
        val secondId = "00000000-0000-0000-0000-000000000002"
        assertEquals("restore_in_progress", recreated.begin(begin(secondId, "two", recreated.state().epoch)).reason)
        assertTrue(recreated.releaseRestore(firstId))
        assertTrue(recreated.begin(begin(secondId, "two", recreated.state().epoch)).accepted)
        val afterReplacement = create()
        assertFalse(afterReplacement.claimRestore(firstId))
        assertTrue(afterReplacement.claimRestore(secondId))
    }

    @Test
    fun `failed owner checkpoint rejects begin before active state`() {
        val store = FakeOwnerStore(failSave = true)
        val guarded = RemoteInputCoordinator(
            imeDeclared = true,
            nowMillis = { 0L },
            deadlines = FakeScheduler(),
            ownerStore = store,
        )
        guarded.connected()
        guarded.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)))
        val id = "00000000-0000-0000-0000-000000000001"
        val rejected = guarded.begin(begin(id, "one", guarded.state().epoch))
        assertFalse(rejected.accepted)
        assertEquals("owner_checkpoint_unavailable", rejected.reason)
        assertFalse(guarded.state().sending)
        assertFalse(guarded.claimRestore(id))
    }
    @Test
    fun `same view id with different source identity rejects frozen request`() {
        coordinator.connected()
        val appTarget = target("target.app", 7)
        coordinator.event(
            RemoteWindowMetadata(RemoteKeyboard.OPEN, appTarget),
            RemoteInputFocusMetadata(appTarget, "101:target.app:id/editor"),
        )
        val id = "00000000-0000-0000-0000-000000000001"
        assertTrue(coordinator.begin(begin(id, "text")).accepted)
        coordinator.event(
            RemoteWindowMetadata(RemoteKeyboard.CLOSED, appTarget),
            RemoteInputFocusMetadata(appTarget, "102:target.app:id/editor"),
        )
        assertEquals("focus_changed", coordinator.result(id).get().reason)
    }

    @Test
    fun `empty text is rejected but whitespace is preserved for desktop own editor`() {
        coordinator.connected()
        val desktopFocus = focus("com.ninepointnine.desktop", 7)
        coordinator.event(RemoteWindowMetadata(
            RemoteKeyboard.OPEN, target("com.ninepointnine.desktop", 7),
        ), desktopFocus)
        val emptyId = "00000000-0000-0000-0000-000000000001"
        assertEquals("empty_text", coordinator.begin(begin(emptyId, "")).reason)
        assertFalse(coordinator.state().sending)
        val id = "00000000-0000-0000-0000-000000000002"
        assertTrue(coordinator.begin(begin(id, " \n")).accepted)
        val result = coordinator.tryCommit("com.ninepointnine.desktop", true, { true }, { desktopFocus }) {
            it == " \n"
        }
        assertTrue(result!!.committed)
    }

    @Test
    fun `deadline setup failure keeps previous durable restore owner`() {
        val previous = "00000000-0000-0000-0000-000000000001"
        val store = FakeOwnerStore().apply { latest = previous }
        val guarded = RemoteInputCoordinator(
            imeDeclared = true,
            nowMillis = { 0L },
            deadlines = RemoteInputDeadlineScheduler { _, _ -> throw IllegalStateException("unavailable") },
            ownerStore = store,
        )
        guarded.connected()
        guarded.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)))
        val rejectedId = "00000000-0000-0000-0000-000000000002"
        assertEquals("deadline_unavailable", guarded.begin(begin(rejectedId, "text", guarded.state().epoch)).reason)
        assertTrue(guarded.claimRestore(previous))
        assertFalse(guarded.claimRestore(rejectedId))
        assertEquals(previous, store.latest)
        assertEquals(0, store.saves)
        assertEquals(0, guarded.retainedTextCount())
    }

    @Test
    fun `recreation restores owner only and never replays text or receipt`() {
        val store = FakeOwnerStore()
        fun create() = RemoteInputCoordinator(true, { 0L }, FakeScheduler(), ownerStore = store)
        val original = create()
        original.connected()
        original.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)))
        val id = "00000000-0000-0000-0000-000000000001"
        val request = begin(id, "text", original.state().epoch)
        assertTrue(original.begin(request).accepted)
        assertTrue(original.begin(request).accepted)
        original.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target("target.app", 7)))
        assertEquals(1, store.saves)
        val recreated = create()
        assertTrue(recreated.claimRestore(id))
        assertFalse(recreated.state().sending)
        assertEquals(0, recreated.retainedTextCount())
        assertEquals("unknown_request", recreated.result(id).get().reason)
    }

    private fun begin(id: String, text: String, epoch: Long = coordinator.state().epoch) =
        RemoteInputBegin(id, epoch, text)

    private fun focus(packageName: String = "target.app", windowId: Int = 7) =
        RemoteInputFocusMetadata(target(packageName, windowId), "field-a")

    private fun target(packageName: String, windowId: Int) = RemoteInputTarget(packageName, windowId)

    private class FakeOwnerStore(private val failSave: Boolean = false) : RemoteInputOwnerStore {
        var latest: String? = null
        var claim: RemoteInputRestoreClaim? = null
        var saves = 0

        override fun saveLatestAcceptedId(id: String): Boolean {
            saves += 1
            if (failSave) return false
            latest = id
            claim = null
            return true
        }

        override fun loadLatestAcceptedId(): String? = latest
        override fun saveRestoreClaim(claim: RemoteInputRestoreClaim): Boolean {
            if (failSave) return false
            latest = claim.id
            this.claim = claim
            return true
        }
        override fun loadRestoreClaim(): RemoteInputRestoreClaim? = claim
    }

    private class FakeScheduler : RemoteInputDeadlineScheduler {
        override fun schedule(delayMillis: Long, action: () -> Unit): RemoteInputCancellation =
            RemoteInputCancellation { }
    }
}
