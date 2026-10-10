package com.ninepointnine.desktop.input

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the gap between claim acknowledgement and the shell worker's settings write. */
class RemoteInputRestoreClaimTest {
    private var now = 0L
    private val target = RemoteInputTarget("target.app", 7)
    private val focus = RemoteInputFocusMetadata(target, "field-a")

    private fun create(store: RemoteInputOwnerStore? = null): RemoteInputCoordinator = RemoteInputCoordinator(
        imeDeclared = true,
        nowMillis = { now },
        deadlines = RemoteInputDeadlineScheduler { _, _ -> RemoteInputCancellation { } },
        ownerStore = store,
    )

    private fun open(owner: RemoteInputCoordinator): RemoteInputCoordinator = owner.also {
        it.connected()
        it.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target), focus)
    }

    private fun begin(owner: RemoteInputCoordinator, id: String = A) =
        owner.begin(RemoteInputBegin(id, owner.state().epoch, "手机文字"))

    @Test fun claimFencesNewBeginAndCancelsPendingCommit() {
        val owner = open(create())
        val original = RemoteInputBegin(A, owner.state().epoch, "原文")
        assertTrue(owner.begin(original).accepted)
        assertTrue(owner.claimRestore(A))
        assertEquals("restore_claimed", owner.result(A).get().reason)
        assertFalse(owner.state().sending)
        assertFalse(owner.state().available)
        assertEquals(0, owner.retainedTextCount())
        var commits = 0
        assertNull(owner.tryCommit("target.app", true, { true }, { focus }) { commits++; true })
        assertEquals(0, commits)
        assertEquals("restore_in_progress", begin(owner, B).reason)
        assertEquals("restore_in_progress", owner.begin(original).reason)
        assertFalse(owner.claimRestore(A))
        assertFalse(owner.claimRestore(B))
        assertFalse(owner.releaseRestore(B))
        assertEquals("restore_in_progress", begin(owner, B).reason)
    }

    @Test fun onlyMatchingReleaseAllowsBeginAndNoGuardCanReclaim() {
        val owner = open(create())
        assertTrue(begin(owner).accepted)
        assertTrue(owner.claimRestore(A))
        assertFalse(owner.releaseRestore("invalid"))
        assertTrue(owner.releaseRestore(A.uppercase()))
        assertTrue(owner.state().available)
        assertFalse(owner.releaseRestore(A))
        assertFalse(owner.claimRestore(A))
        assertTrue(begin(owner, B).accepted)
        assertFalse(owner.claimRestore(A))
        assertFalse(owner.releaseRestore(A))
        assertTrue(owner.state().sending)
    }

    @Test fun diagnosticExpiryNeverUnlocksAWorkerThatMightStillWrite() {
        val store = MemoryStore()
        val owner = open(create(store))
        assertTrue(begin(owner).accepted)
        assertTrue(owner.claimRestore(A))
        assertEquals(RemoteInputCoordinator.RESTORE_DIAGNOSTIC_MILLIS, store.claim?.expiresAtMillis)
        now = RemoteInputCoordinator.RESTORE_DIAGNOSTIC_MILLIS + RemoteInputCoordinator.DEADLINE_MILLIS + 1
        owner.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, target), focus)
        assertEquals("restore_in_progress", begin(owner, B).reason)
        assertFalse(owner.state().available)
        owner.disconnected()
        open(owner)
        assertEquals("restore_in_progress", begin(owner, B).reason)
        assertTrue(owner.releaseRestore(A))
        assertTrue(begin(owner, B).accepted)
    }

    @Test fun processRecreationRetainsTheExclusiveClaimUntilRelease() {
        val store = MemoryStore()
        val owner = open(create(store))
        assertTrue(begin(owner).accepted)
        assertTrue(owner.claimRestore(A))
        now += RemoteInputCoordinator.RESTORE_DIAGNOSTIC_MILLIS * 2
        val recreated = open(create(store))
        assertFalse(recreated.state().available)
        assertEquals(0, recreated.retainedTextCount())
        assertEquals("unknown_request", recreated.result(A).get().reason)
        assertFalse(recreated.claimRestore(A))
        assertEquals("restore_in_progress", begin(recreated, B).reason)
        assertFalse(recreated.releaseRestore(B))
        assertTrue(recreated.releaseRestore(A))
        assertTrue(begin(recreated, B).accepted)
    }

    @Test fun releasedTombstoneSurvivesRecreationAndNewOwnerReplacesIt() {
        val store = MemoryStore()
        val owner = open(create(store))
        assertTrue(begin(owner).accepted)
        assertTrue(owner.claimRestore(A))
        assertTrue(owner.releaseRestore(A))
        val recreated = open(create(store))
        assertTrue(recreated.state().available)
        assertFalse(recreated.claimRestore(A))
        assertFalse(recreated.releaseRestore(A))
        assertTrue(begin(recreated, B).accepted)
        assertEquals(B, store.latest)
        assertNull(store.claim)
        val replacement = create(store)
        assertFalse(replacement.claimRestore(A))
        assertTrue(replacement.claimRestore(B))
    }

    @Test fun failedClaimCheckpointNeverGrantsAWorkerAndLocallyFencesFurtherInput() {
        val store = MemoryStore()
        val owner = open(create(store))
        assertTrue(begin(owner).accepted)
        store.failClaimSave = true
        assertFalse(owner.claimRestore(A))
        assertEquals("restore_checkpoint_unavailable", owner.result(A).get().reason)
        assertFalse(owner.state().available)
        assertEquals("restore_in_progress", begin(owner, B).reason)
        assertFalse(owner.claimRestore(A))
        assertEquals(0, owner.retainedTextCount())
    }

    @Test fun failedReleaseCheckpointRetainsTheFenceAcrossRecreation() {
        val store = MemoryStore()
        val owner = open(create(store))
        assertTrue(begin(owner).accepted)
        assertTrue(owner.claimRestore(A))
        store.failClaimSave = true
        assertFalse(owner.releaseRestore(A))
        assertEquals("restore_in_progress", begin(owner, B).reason)
        val recreated = open(create(store))
        assertEquals("restore_in_progress", begin(recreated, B).reason)
        store.failClaimSave = false
        assertTrue(recreated.releaseRestore(A))
        assertTrue(begin(recreated, B).accepted)
    }

    @Test fun malformedLatestOwnerAndReadFailureDoNotBecomeAnEmptyUsableStore() {
        val malformed = MemoryStore().apply { latest = "malformed" }
        val malformedOwner = open(create(malformed))
        assertFalse(malformedOwner.state().available)
        assertEquals("restore_checkpoint_unavailable", begin(malformedOwner, B).reason)
        assertEquals(0, malformed.ownerSaves)
        val failed = MemoryStore().apply { latest = A; throwOnLatestRead = true }
        val failedOwner = open(create(failed))
        assertEquals("restore_checkpoint_unavailable", begin(failedOwner, B).reason)
        assertFalse(failedOwner.claimRestore(A))
    }

    @Test fun claimReadFailureOrMismatchedOwnerFailsClosed() {
        val store = MemoryStore().apply { latest = A; throwOnClaimRead = true }
        val unreadable = open(create(store))
        assertFalse(unreadable.state().available)
        assertEquals("restore_checkpoint_unavailable", begin(unreadable, B).reason)
        assertFalse(unreadable.claimRestore(A))
        assertFalse(unreadable.releaseRestore(A))
        val mismatch = MemoryStore().apply { latest = B; claim = RemoteInputRestoreClaim(A, 16_000L) }
        val mismatched = open(create(mismatch))
        assertEquals("restore_checkpoint_unavailable", begin(mismatched, B).reason)
        assertFalse(mismatched.claimRestore(B))
    }

    @Test fun oldStoreWithoutClaimPersistenceCannotGrantAnUnsafeClaim() {
        val legacyStore = object : RemoteInputOwnerStore {
            private var latest: String? = null
            override fun loadLatestAcceptedId(): String? = latest
            override fun saveLatestAcceptedId(id: String): Boolean { latest = id; return true }
        }
        val owner = open(create(legacyStore))
        assertTrue(begin(owner).accepted)
        assertFalse(owner.claimRestore(A))
        assertFalse(owner.state().available)
        assertEquals("restore_in_progress", begin(owner, B).reason)
    }

    @Test fun normalCommitRequiresNoRestoreClaimRoundTrip() {
        val store = MemoryStore()
        val owner = open(create(store))
        assertTrue(begin(owner).accepted)
        var committedText: String? = null
        assertTrue(owner.tryCommit("target.app", true, { true }, { focus }) { committedText = it; true }!!.committed)
        assertEquals("手机文字", committedText)
        assertTrue(owner.state().available)
        assertEquals(1, store.ownerSaves)
        assertEquals(0, store.claimSaves)
        assertNull(store.claim)
    }

    @Test fun claimCannotAcknowledgeBeforePersistenceOrLeaveAGapForBegin() {
        val started = CountDownLatch(1)
        val allowSave = CountDownLatch(1)
        val store = object : MemoryStore() {
            override fun saveRestoreClaim(claim: RemoteInputRestoreClaim): Boolean {
                started.countDown()
                assertTrue(allowSave.await(2, TimeUnit.SECONDS))
                return super.saveRestoreClaim(claim)
            }
        }
        val owner = open(create(store))
        assertTrue(begin(owner).accepted)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val claiming = workers.submit<Boolean> { owner.claimRestore(A) }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertFalse(claiming.isDone)
            val beginStarted = CountDownLatch(1)
            val next = workers.submit<RemoteInputAcceptance> { beginStarted.countDown(); begin(owner, B) }
            assertTrue(beginStarted.await(1, TimeUnit.SECONDS))
            allowSave.countDown()
            assertTrue(claiming.get(2, TimeUnit.SECONDS))
            assertEquals("restore_in_progress", next.get(2, TimeUnit.SECONDS).reason)
            assertEquals(A, store.latest)
            assertEquals(A, store.claim?.id)
        } finally { allowSave.countDown(); workers.shutdownNow() }
    }

    @Test fun concurrentGuardsGrantExactlyOneOwner() {
        val owner = open(create())
        assertTrue(begin(owner).accepted)
        val workers = Executors.newFixedThreadPool(2)
        val release = CountDownLatch(1)
        try {
            val first = workers.submit<Boolean> { release.await(); owner.claimRestore(A) }
            val second = workers.submit<Boolean> { release.await(); owner.claimRestore(A) }
            release.countDown()
            assertEquals(1, listOf(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS)).count { it })
        } finally { release.countDown(); workers.shutdownNow() }
    }

    private open class MemoryStore : RemoteInputOwnerStore {
        var latest: String? = null
        var claim: RemoteInputRestoreClaim? = null
        var failClaimSave = false
        var throwOnLatestRead = false
        var throwOnClaimRead = false
        var ownerSaves = 0
        var claimSaves = 0
        override fun saveLatestAcceptedId(id: String): Boolean {
            ownerSaves++
            latest = id
            claim = null
            return true
        }
        override fun loadLatestAcceptedId(): String? {
            if (throwOnLatestRead) throw IllegalStateException("unreadable owner")
            return latest
        }
        override fun saveRestoreClaim(claim: RemoteInputRestoreClaim): Boolean {
            claimSaves++
            if (failClaimSave) return false
            latest = claim.id
            this.claim = claim
            return true
        }
        override fun loadRestoreClaim(): RemoteInputRestoreClaim? {
            if (throwOnClaimRead) throw IllegalStateException("unreadable claim")
            return claim
        }
    }

    companion object {
        private const val A = "00000000-0000-0000-0000-000000000001"
        private const val B = "00000000-0000-0000-0000-000000000002"
    }
}
