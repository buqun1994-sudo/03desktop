package com.ninepointnine.desktop.input

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

object RemoteInputAvailability {
    fun isDeclared(context: Context): Boolean = runCatching {
        val component = ComponentName(context, RemoteInputMethodService::class.java)
        val info = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        info.enabled && info.exported &&
            info.permission == "android.permission.BIND_INPUT_METHOD" &&
            info.metaData?.getInt("android.view.im", 0) != 0
    }.getOrDefault(false)
}

/** Process-local bridge shared by the accessibility service, provider and bundled IME. */
object RemoteInputRuntime {
    private val lock = Any()
    private var owner: RemoteInputCoordinator? = null
    private var focusVerifier: Pair<Any, () -> RemoteInputFocusMetadata?>? = null

    fun attachFocusVerifier(key: Any, verifier: () -> RemoteInputFocusMetadata?) = synchronized(lock) {
        focusVerifier = key to verifier
    }

    fun detachFocusVerifier(key: Any) = synchronized(lock) {
        if (focusVerifier?.first === key) focusVerifier = null
    }

    /** One current field proof, requested only for a pending IME commit. */
    fun currentFocus(): RemoteInputFocusMetadata? {
        val verify = synchronized(lock) { focusVerifier?.second } ?: return null
        return runCatching(verify).getOrNull()
    }

    fun coordinator(context: Context): RemoteInputCoordinator = synchronized(lock) {
        val application = context.applicationContext
        owner ?: RemoteInputCoordinator(
            imeDeclared = RemoteInputAvailability.isDeclared(application),
            nowMillis = { android.os.SystemClock.elapsedRealtime() },
            deadlines = HandlerDeadlineScheduler(Handler(Looper.getMainLooper())),
            ownerStore = AndroidRemoteInputOwnerStore(application),
        ).also { owner = it }
    }

    fun connectAccessibility(context: Context) = coordinator(context).connected()

    fun disconnectAccessibility(context: Context) = coordinator(context).disconnected()

    fun refreshFromIme(context: Context, listenerKey: Any, listener: (RemoteInputState) -> Unit) {
        coordinator(context).listen(listenerKey, listener)
    }

    fun removeListener(context: Context, listenerKey: Any) {
        coordinator(context).unlisten(listenerKey)
    }

    fun socketName(context: Context): String = "${context.applicationContext.packageName}.remoteinput"
}

private class HandlerDeadlineScheduler(private val handler: Handler) : RemoteInputDeadlineScheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit): RemoteInputCancellation {
        val runnable = Runnable(action)
        check(handler.postDelayed(runnable, delayMillis)) { "Deadline scheduler unavailable" }
        return RemoteInputCancellation { handler.removeCallbacks(runnable) }
    }
}

internal class AndroidRemoteInputOwnerStore(
    private val preferences: android.content.SharedPreferences,
) : RemoteInputOwnerStore {
    constructor(context: Context) : this(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE))

    override fun saveLatestAcceptedId(id: String): Boolean {
        require(RemoteInputCoordinator.canonicalId(id) == id)
        if (loadRestoreClaim()?.released == false) return false
        return preferences.edit()
            .putString(KEY_LATEST_ACCEPTED_ID, id)
            .remove(KEY_CLAIM_ID).remove(KEY_CLAIM_EXPIRES_AT).remove(KEY_CLAIM_RELEASED)
            .commit()
    }

    override fun loadLatestAcceptedId(): String? =
        preferences.getString(KEY_LATEST_ACCEPTED_ID, null)

    override fun saveRestoreClaim(claim: RemoteInputRestoreClaim): Boolean {
        require(RemoteInputCoordinator.canonicalId(claim.id) == claim.id && claim.expiresAtMillis > 0)
        if (loadLatestAcceptedId() != claim.id) return false
        val previous = loadRestoreClaim()
        if (claim.released) {
            // commit(false) may already change the in-process preference cache.
            // Re-persisting the same released tombstone is safe; the coordinator
            // still rejects a second successfully acknowledged release.
            if (previous == null || previous.id != claim.id || previous.expiresAtMillis != claim.expiresAtMillis) return false
        } else if (previous != null) return false
        // One small synchronous commit; no text, editor identity or request payload.
        return preferences.edit()
            .putString(KEY_LATEST_ACCEPTED_ID, claim.id)
            .putString(KEY_CLAIM_ID, claim.id)
            .putLong(KEY_CLAIM_EXPIRES_AT, claim.expiresAtMillis)
            .putBoolean(KEY_CLAIM_RELEASED, claim.released)
            .commit()
    }

    override fun loadRestoreClaim(): RemoteInputRestoreClaim? {
        if (!preferences.contains(KEY_CLAIM_ID) && !preferences.contains(KEY_CLAIM_EXPIRES_AT) &&
            !preferences.contains(KEY_CLAIM_RELEASED)) return null
        val id = preferences.getString(KEY_CLAIM_ID, null)
        val deadline = preferences.getLong(KEY_CLAIM_EXPIRES_AT, -1)
        check(id != null && RemoteInputCoordinator.canonicalId(id) == id &&
            loadLatestAcceptedId() == id && deadline > 0 && preferences.contains(KEY_CLAIM_RELEASED)) {
            "Invalid restore claim checkpoint"
        }
        return RemoteInputRestoreClaim(id, deadline, preferences.getBoolean(KEY_CLAIM_RELEASED, false))
    }

    private companion object {
        const val PREFERENCES_NAME = "remote_input_owner_v1"
        const val KEY_LATEST_ACCEPTED_ID = "latest_accepted_id"
        const val KEY_CLAIM_ID = "restore_claim_id"
        const val KEY_CLAIM_EXPIRES_AT = "restore_claim_expires_at"
        const val KEY_CLAIM_RELEASED = "restore_claim_released"
    }
}
