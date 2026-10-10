package com.ninepointnine.desktop.input

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

/** Checks the actual Android store adapter with a durable/in-memory preferences split. */
class AndroidRemoteInputOwnerStoreTest {
    @Test fun claimAndOwnerReachOneSynchronousCommitWithoutText() {
        val preferences = Preferences()
        val store = AndroidRemoteInputOwnerStore(preferences)
        assertTrue(store.saveLatestAcceptedId(A))
        val claim = RemoteInputRestoreClaim(A, 16_000L)
        assertTrue(store.saveRestoreClaim(claim))
        assertEquals(2, preferences.commits)
        assertEquals(claim, store.loadRestoreClaim())
        assertEquals(setOf("latest_accepted_id", "restore_claim_id", "restore_claim_expires_at", "restore_claim_released"), preferences.getAll().keys)
        val reopened = AndroidRemoteInputOwnerStore(preferences.reopen())
        assertEquals(A, reopened.loadLatestAcceptedId())
        assertEquals(claim, reopened.loadRestoreClaim())
    }

    @Test fun newOwnerCannotOverwriteActiveClaimAndReleasedTombstoneIsAtomicallyReplaced() {
        val preferences = Preferences()
        val store = AndroidRemoteInputOwnerStore(preferences)
        assertTrue(store.saveLatestAcceptedId(A))
        val claim = RemoteInputRestoreClaim(A, 16_000L)
        assertTrue(store.saveRestoreClaim(claim))
        assertFalse(store.saveLatestAcceptedId(B))
        assertFalse(store.saveRestoreClaim(claim))
        assertFalse(store.saveRestoreClaim(claim.copy(id = B, released = true)))
        assertEquals(2, preferences.commits)
        assertTrue(store.saveRestoreClaim(claim.copy(released = true)))
        assertFalse(store.saveRestoreClaim(claim))
        assertTrue(store.saveRestoreClaim(claim.copy(released = true)))
        assertTrue(store.saveLatestAcceptedId(B))
        val recreated = AndroidRemoteInputOwnerStore(preferences.reopen())
        assertEquals(B, recreated.loadLatestAcceptedId())
        assertNull(recreated.loadRestoreClaim())
        assertEquals(setOf("latest_accepted_id"), preferences.getAll().keys)
    }

    @Test fun aPartialOrMismatchedClaimRecordIsAnErrorRatherThanNoLock() {
        val records = listOf(
            mapOf("latest_accepted_id" to A, "restore_claim_id" to A),
            mapOf("latest_accepted_id" to B, "restore_claim_id" to A, "restore_claim_expires_at" to 16_000L, "restore_claim_released" to false),
            mapOf("latest_accepted_id" to A, "restore_claim_expires_at" to 16_000L),
            mapOf("latest_accepted_id" to A, "restore_claim_id" to A, "restore_claim_expires_at" to -1L, "restore_claim_released" to false),
        )
        records.forEach { record ->
            val store = AndroidRemoteInputOwnerStore(Preferences(record))
            assertThrows(IllegalStateException::class.java) { store.loadRestoreClaim() }
            val owner = coordinator(store)
            assertFalse(owner.state().available)
            assertEquals("restore_checkpoint_unavailable", begin(owner, B).reason)
        }
    }

    @Test fun incorrectlyTypedOwnerCannotBeReadAsEmptyAndUnblockNewInput() {
        val store = AndroidRemoteInputOwnerStore(Preferences(mapOf("latest_accepted_id" to 42)))
        assertThrows(ClassCastException::class.java) { store.loadLatestAcceptedId() }
        val owner = coordinator(store)
        assertEquals("restore_checkpoint_unavailable", begin(owner, B).reason)
        assertFalse(owner.claimRestore(A))
    }

    @Test fun failedClaimSaveNeverAcknowledgesAndFailedReleaseCannotDropDurableFence() {
        val preferences = Preferences()
        val owner = coordinator(AndroidRemoteInputOwnerStore(preferences))
        assertTrue(begin(owner, A).accepted)
        assertTrue(owner.claimRestore(A))
        preferences.failCommit = true
        assertFalse(owner.releaseRestore(A))
        // Android commit may change process memory even when persistence fails.
        // Coordinator must keep its own fence until a successful durable release.
        assertEquals("restore_in_progress", begin(owner, B).reason)
        val recreated = coordinator(AndroidRemoteInputOwnerStore(preferences.reopen()))
        assertEquals("restore_in_progress", begin(recreated, B).reason)
        assertTrue(recreated.releaseRestore(A))
        assertTrue(begin(recreated, B).accepted)

        val otherPreferences = Preferences()
        val other = coordinator(AndroidRemoteInputOwnerStore(otherPreferences))
        assertTrue(begin(other, A).accepted)
        otherPreferences.failCommit = true
        assertFalse(other.claimRestore(A))
        assertEquals("restore_in_progress", begin(other, B).reason)
    }

    @Test fun releaseRetryCanReachDiskAfterTheFailedCommitChangedTheProcessCache() {
        val preferences = Preferences()
        val owner = coordinator(AndroidRemoteInputOwnerStore(preferences))
        assertTrue(begin(owner, A).accepted)
        assertTrue(owner.claimRestore(A))
        preferences.failCommit = true
        assertFalse(owner.releaseRestore(A))
        assertEquals("restore_in_progress", begin(owner, B).reason)
        preferences.failCommit = false
        assertTrue(owner.releaseRestore(A))
        assertFalse(owner.releaseRestore(A))
        assertFalse(owner.claimRestore(A))
        assertTrue(begin(owner, B).accepted)
        assertEquals(B, AndroidRemoteInputOwnerStore(preferences.reopen()).loadLatestAcceptedId())
    }

    private fun coordinator(store: RemoteInputOwnerStore) = RemoteInputCoordinator(
        true, { 0L }, RemoteInputDeadlineScheduler { _, _ -> RemoteInputCancellation { } }, ownerStore = store,
    ).also {
        it.connected()
        it.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, RemoteInputTarget("target.app", 7)))
    }
    private fun begin(owner: RemoteInputCoordinator, id: String) = owner.begin(RemoteInputBegin(id, owner.state().epoch, "正文"))

    private class Preferences(initial: Map<String, Any?> = emptyMap()) : SharedPreferences {
        private val values = initial.toMutableMap()
        private val durable = initial.toMutableMap()
        var failCommit = false
        var commits = 0
        fun reopen() = Preferences(durable)
        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun getString(key: String, defValue: String?): String? = (values[key] as String?) ?: defValue
        override fun getInt(key: String, defValue: Int): Int = (values[key] as Int?) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (values[key] as Long?) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (values[key] as Float?) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (values[key] as Boolean?) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = (values[key] as MutableSet<String>?) ?: defValues
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun edit(): SharedPreferences.Editor = Editor()

        private inner class Editor : SharedPreferences.Editor {
            private val writes = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clear = false
            override fun putString(key: String, value: String?): SharedPreferences.Editor = set(key, value)
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = set(key, value)
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = set(key, value)
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = set(key, value)
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = set(key, value)
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = set(key, values?.toMutableSet())
            private fun set(key: String, value: Any?): SharedPreferences.Editor {
                if (value == null) return remove(key)
                writes[key] = value
                removes.remove(key)
                return this
            }
            override fun remove(key: String): SharedPreferences.Editor {
                removes.add(key)
                writes.remove(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor { clear = true; return this }
            override fun commit(): Boolean {
                commits++
                if (clear) values.clear()
                removes.forEach(values::remove)
                values.putAll(writes)
                if (failCommit) return false
                durable.clear()
                durable.putAll(values)
                return true
            }
            override fun apply() { throw AssertionError("Recovery checkpoints require synchronous commit") }
        }
    }

    companion object {
        private const val A = "00000000-0000-0000-0000-000000000001"
        private const val B = "00000000-0000-0000-0000-000000000002"
    }
}
