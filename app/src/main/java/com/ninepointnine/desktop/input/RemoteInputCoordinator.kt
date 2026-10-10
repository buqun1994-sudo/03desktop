package com.ninepointnine.desktop.input

import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture

enum class RemoteKeyboard(val wireValue: String) { OPEN("open"), CLOSED("closed"), UNKNOWN("unknown") }

data class RemoteInputTarget(val packageName: String = "", val windowId: Int = -1) {
    val isKnown: Boolean get() = packageName.isNotBlank() && windowId >= 0
}

data class RemoteWindowMetadata(val keyboard: RemoteKeyboard, val target: RemoteInputTarget)
data class RemoteInputFocusMetadata(val target: RemoteInputTarget, val token: String?)

data class RemoteInputState(
    val available: Boolean,
    val keyboard: RemoteKeyboard,
    val epoch: Long,
    val target: RemoteInputTarget,
    val sequence: Long,
    val sending: Boolean,
)

data class RemoteInputBegin(val id: String, val epoch: Long, val text: String)
data class RemoteInputAcceptance(val id: String, val accepted: Boolean, val reason: String)
data class RemoteInputResult(val id: String, val committed: Boolean, val reason: String)

/** Deadline is diagnostic only: a live worker may still write after that time. */
data class RemoteInputRestoreClaim(
    val id: String,
    val expiresAtMillis: Long,
    val released: Boolean = false,
)

fun interface RemoteInputCancellation { fun cancel() }
fun interface RemoteInputDeadlineScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): RemoteInputCancellation
}
interface RemoteInputOwnerStore {
    /** Saves the new owner and removes a released claim in the same durable commit. */
    fun saveLatestAcceptedId(id: String): Boolean
    fun loadLatestAcceptedId(): String?

    /** Saves owner + claim atomically. Old stores cannot grant an unsafe restore lock. */
    fun saveRestoreClaim(claim: RemoteInputRestoreClaim): Boolean = false
    fun loadRestoreClaim(): RemoteInputRestoreClaim? = null
}

/** The only request/state owner. It never reads an editor and retains text only until a terminal result. */
class RemoteInputCoordinator(
    private val imeDeclared: Boolean,
    private val nowMillis: () -> Long,
    private val deadlines: RemoteInputDeadlineScheduler,
    initialEpoch: Long = 0,
    /** A null store is only for ephemeral test instances; production always installs a durable store. */
    private val ownerStore: RemoteInputOwnerStore? = null,
) {
    private class Receipt(
        val id: String,
        val fingerprint: String,
        val requestedEpoch: Long,
        var acceptance: RemoteInputAcceptance,
        var text: String?,
        val frozenTarget: RemoteInputTarget,
        val focusGeneration: Long,
        val frozenFocus: RemoteInputFocusMetadata?,
        val deadlineAt: Long,
    ) {
        val result = CompletableFuture<RemoteInputResult>()
        var timer: RemoteInputCancellation? = null
        var attempted = false
    }

    private val lock = Any()
    private var connected = false
    private var keyboard = RemoteKeyboard.UNKNOWN
    private var target = RemoteInputTarget()
    private var epoch = initialEpoch
    private var sequence = 0L
    private var focusGeneration = 0L
    private var focusToken: RemoteInputFocusMetadata? = null
    private var active: Receipt? = null
    private var ownerCheckpointReadable = true
    private var latestAcceptedId: String? = loadLatestOwner()
    private var restoreClaim: RemoteInputRestoreClaim? = loadRestoreCheckpoint()
    private val receipts = LinkedHashMap<String, Receipt>(MAX_RECEIPTS, 0.75f, true)
    private val listeners = LinkedHashMap<Any, (RemoteInputState) -> Unit>()

    fun state(): RemoteInputState = synchronized(lock) { snapshot() }

    fun listen(key: Any, listener: (RemoteInputState) -> Unit) = synchronized(lock) {
        listeners[key] = listener
        listener(snapshot())
    }

    fun unlisten(key: Any) = synchronized(lock) { listeners.remove(key); Unit }

    fun connected() = synchronized(lock) {
        if (!connected) {
            connected = true
            epoch += 1
        }
        publish()
    }

    fun disconnected() = synchronized(lock) {
        if (connected || keyboard != RemoteKeyboard.UNKNOWN || target.isKnown) epoch += 1
        connected = false
        keyboard = RemoteKeyboard.UNKNOWN
        target = RemoteInputTarget()
        focusToken = null
        active?.let { finish(it, false, "accessibility_lost") }
        publish()
    }

    /** Counts every delivered metadata event exactly once. */
    fun event(metadata: RemoteWindowMetadata, focused: RemoteInputFocusMetadata? = null) = synchronized(lock) {
        if (!connected) return@synchronized
        val previousTarget = target
        val targetChanged = previousTarget != metadata.target
        val keyboardChanged = keyboard != metadata.keyboard
        keyboard = metadata.keyboard
        target = metadata.target
        if (targetChanged || keyboardChanged) epoch += 1
        if (previousTarget.isKnown && metadata.target.isKnown && previousTarget != metadata.target) {
            focusToken = null
        }
        // A switch-induced missing/closed IME window cannot retarget an accepted request.
        // A known different application window is an actual target change and is terminal.
        active?.let { request ->
            if (target.isKnown && target != request.frozenTarget) finish(request, false, "target_changed")
        }
        // The service filters input-method windows before creating this metadata. A stable
        // token identifies a synthetic duplicate during IME restart; an absent token is
        // deliberately conservative and rejects the pending transaction.
        focused?.let { value ->
            val sameToken = value.token != null && focusToken?.token == value.token &&
                focusToken?.target == value.target
            if (!sameToken) {
                focusGeneration += 1
                epoch += 1
                active?.let { finish(it, false, "focus_changed") }
            }
            focusToken = value
        }
        publish()
    }

    fun metadataEvent(focusedTarget: RemoteInputTarget? = null) = synchronized(lock) {
        if (focusedTarget?.isKnown == true) {
            focusGeneration += 1
            epoch += 1
            active?.let { finish(it, false, "focus_changed") }
        }
        publish()
    }

    fun updateWindows(metadata: RemoteWindowMetadata) = synchronized(lock) {
        if (!connected) return@synchronized
        val targetChanged = target != metadata.target
        val keyboardChanged = keyboard != metadata.keyboard
        keyboard = metadata.keyboard
        target = metadata.target
        if (targetChanged || keyboardChanged) epoch += 1
        active?.let { request ->
            if (target.isKnown && target != request.frozenTarget) finish(request, false, "target_changed")
        }
        if (targetChanged || keyboardChanged) publish()
    }

    fun begin(request: RemoteInputBegin): RemoteInputAcceptance = synchronized(lock) {
        expireIfDue()
        val id = canonicalId(request.id)
            ?: return@synchronized RemoteInputAcceptance(request.id, false, "invalid_id")
        if (!ownerCheckpointReadable) return@synchronized RemoteInputAcceptance(id, false, "restore_checkpoint_unavailable")
        if (restoreClaim?.released == false) return@synchronized RemoteInputAcceptance(id, false, "restore_in_progress")
        if (request.text.isEmpty()) return@synchronized RemoteInputAcceptance(id, false, "empty_text")
        val bytes = strictUtf8(request.text)
            ?: return@synchronized RemoteInputAcceptance(id, false, "invalid_text")
        if (bytes.size > MAX_TEXT_BYTES) return@synchronized RemoteInputAcceptance(id, false, "text_too_large")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        receipts[id]?.let { previous ->
            return@synchronized if (previous.fingerprint == fingerprint && previous.requestedEpoch == request.epoch) {
                previous.acceptance
            } else {
                RemoteInputAcceptance(id, false, "id_reused")
            }
        }
        val reason = when {
            !connected || !imeDeclared -> "unavailable"
            keyboard != RemoteKeyboard.OPEN -> "keyboard_not_open"
            epoch != request.epoch -> "stale_epoch"
            !target.isKnown -> "target_unknown"
            active != null -> "busy"
            else -> "accepted"
        }
        val acceptance = RemoteInputAcceptance(id, reason == "accepted", reason)
        val receipt = Receipt(
            id, fingerprint, request.epoch, acceptance,
            if (acceptance.accepted) request.text else null,
            target, focusGeneration, focusToken, nowMillis() + DEADLINE_MILLIS,
        )
        receipts[id] = receipt
        trimReceipts()
        if (!acceptance.accepted) {
            receipt.result.complete(RemoteInputResult(id, false, reason))
            return@synchronized acceptance
        }
        try {
            receipt.timer = deadlines.schedule(DEADLINE_MILLIS) {
                synchronized(lock) {
                    if (active === receipt) {
                        finish(receipt, false, "deadline")
                        publish()
                    }
                }
            }
        } catch (_: RuntimeException) {
            receipt.acceptance = RemoteInputAcceptance(id, false, "deadline_unavailable")
            finish(receipt, false, "deadline_unavailable")
            publish()
            return@synchronized receipt.acceptance
        }
        active = receipt
        val checkpointed = runCatching {
            ownerStore?.saveLatestAcceptedId(id) ?: true
        }.getOrDefault(false)
        if (!checkpointed) {
            val failure = RemoteInputAcceptance(id, false, "owner_checkpoint_unavailable")
            receipt.acceptance = failure
            finish(receipt, false, failure.reason)
            publish()
            return@synchronized failure
        }
        latestAcceptedId = id
        restoreClaim = null
        publish()
        receipt.acceptance
    }

    fun cancel(id: String): RemoteInputAcceptance = synchronized(lock) {
        val canonical = canonicalId(id) ?: return@synchronized RemoteInputAcceptance(id, false, "invalid_id")
        val receipt = receipts[canonical]
            ?: return@synchronized RemoteInputAcceptance(canonical, false, "unknown_request")
        if (receipt.result.isDone) {
            return@synchronized RemoteInputAcceptance(canonical, false, "already_terminal")
        }
        finish(receipt, false, "cancelled")
        publish()
        RemoteInputAcceptance(canonical, true, "cancelled")
    }

    /** One worker owns the entire external restore until its matching release. */
    fun claimRestore(id: String): Boolean = synchronized(lock) {
        val canonical = canonicalId(id) ?: return@synchronized false
        if (!ownerCheckpointReadable || latestAcceptedId != canonical || restoreClaim != null) {
            return@synchronized false
        }
        val now = nowMillis().coerceAtLeast(0)
        val claim = RemoteInputRestoreClaim(
            canonical,
            if (now > Long.MAX_VALUE - RESTORE_DIAGNOSTIC_MILLIS) Long.MAX_VALUE else now + RESTORE_DIAGNOSTIC_MILLIS,
        )
        // Keep a local fence even on ambiguous storage failure. Never grant a worker
        // permission when its fence has not reached the durable checkpoint.
        restoreClaim = claim
        val checkpointed = runCatching { ownerStore?.saveRestoreClaim(claim) ?: true }.getOrDefault(false)
        active?.let { finish(it, false, if (checkpointed) "restore_claimed" else "restore_checkpoint_unavailable") }
        publish()
        checkpointed
    }

    fun releaseRestore(id: String): Boolean = synchronized(lock) {
        val canonical = canonicalId(id) ?: return@synchronized false
        val claim = restoreClaim ?: return@synchronized false
        if (claim.id != canonical || claim.released || !ownerCheckpointReadable) return@synchronized false
        val released = claim.copy(released = true)
        if (!runCatching { ownerStore?.saveRestoreClaim(released) ?: true }.getOrDefault(false)) {
            return@synchronized false
        }
        // Retain a UUID-only tombstone: a delayed duplicate guard cannot reclaim A.
        // A newer accepted B removes it atomically with B's owner checkpoint.
        restoreClaim = released
        publish()
        true
    }

    fun result(id: String): CompletableFuture<RemoteInputResult> = synchronized(lock) {
        expireIfDue()
        receipts[canonicalId(id)]?.result
            ?: CompletableFuture.completedFuture(RemoteInputResult(id, false, "unknown_request"))
    }

    /** Runs on the IME main thread. The lock spans validation and the single commit attempt. */
    fun tryCommit(
        editorPackage: String,
        validEditor: Boolean,
        sessionIsCurrent: () -> Boolean,
        currentFocus: () -> RemoteInputFocusMetadata?,
        commit: (String) -> Boolean,
    ): RemoteInputResult? = synchronized(lock) {
        expireIfDue()
        val request = active ?: return@synchronized null
        if (!connected || !imeDeclared) return@synchronized fail(request, "unavailable")
        if (editorPackage.isNotBlank() && editorPackage != request.frozenTarget.packageName) {
            return@synchronized fail(request, "target_changed")
        }
        if (focusGeneration != request.focusGeneration) return@synchronized fail(request, "focus_changed")
        if (target.isKnown && target != request.frozenTarget) return@synchronized fail(request, "target_changed")
        if (!validEditor) {
            if (editorPackage.isBlank()) return@synchronized null
            return@synchronized fail(request, "invalid_editor")
        }
        if (!target.isKnown || !sessionIsCurrent() || request.attempted) return@synchronized null
        val liveFocus = runCatching(currentFocus).getOrNull()
        expireIfDue()
        if (active !== request) return@synchronized request.result.getNow(null)
        val frozenFocus = request.frozenFocus
        if (liveFocus == null || frozenFocus?.token.isNullOrBlank()) return@synchronized fail(request, "focus_unverified")
        if (liveFocus != frozenFocus || liveFocus.target != request.frozenTarget) return@synchronized fail(request, "focus_changed")
        if (!sessionIsCurrent()) return@synchronized fail(request, "focus_changed")
        request.attempted = true
        val text = request.text ?: return@synchronized fail(request, "text_unavailable")
        val committed = try { commit(text) } catch (_: RuntimeException) { false }
        finish(request, committed, if (committed) "committed" else "commit_failed")
        publish()
        request.result.getNow(null)
    }

    fun imeLost() = synchronized(lock) {
        active?.let {
            finish(it, false, "ime_disconnected")
            publish()
        }
    }

    internal fun retainedReceiptCount(): Int = synchronized(lock) { receipts.size }
    internal fun retainedTextCount(): Int = synchronized(lock) { receipts.values.count { it.text != null } }

    private fun fail(request: Receipt, reason: String): RemoteInputResult {
        finish(request, false, reason)
        publish()
        return request.result.getNow(null)
    }

    private fun expireIfDue() {
        active?.let {
            if (nowMillis() >= it.deadlineAt) {
                finish(it, false, "deadline")
                publish()
            }
        }
    }

    private fun finish(request: Receipt, committed: Boolean, reason: String) {
        if (request.result.isDone) return
        request.text = null
        request.timer?.cancel()
        request.timer = null
        if (active === request) active = null
        request.result.complete(RemoteInputResult(request.id, committed, reason))
    }

    private fun trimReceipts() {
        while (receipts.size > MAX_RECEIPTS) {
            val evict = receipts.entries.firstOrNull { it.value !== active } ?: break
            evict.value.text = null
            receipts.remove(evict.key)
        }
    }

    private fun loadLatestOwner(): String? = try {
        ownerStore?.loadLatestAcceptedId()?.let { value ->
            requireNotNull(canonicalId(value)) { "Invalid restore owner checkpoint" }
        }
    } catch (_: Exception) {
        ownerCheckpointReadable = false
        null
    }

    private fun loadRestoreCheckpoint(): RemoteInputRestoreClaim? = try {
        ownerStore?.loadRestoreClaim()?.also { claim ->
            require(canonicalId(claim.id) == claim.id && claim.id == latestAcceptedId && claim.expiresAtMillis > 0) {
                "Invalid restore claim checkpoint"
            }
        }
    } catch (_: Exception) {
        ownerCheckpointReadable = false
        null
    }

    private fun snapshot() = RemoteInputState(
        connected && imeDeclared && ownerCheckpointReadable && restoreClaim?.released != false,
        keyboard, epoch, target, sequence, active != null,
    )

    private fun publish() {
        sequence += 1
        val snapshot = snapshot()
        listeners.values.toList().forEach { listener -> runCatching { listener(snapshot) } }
    }

    companion object {
        const val MAX_TEXT_BYTES = 32_768
        const val MAX_RECEIPTS = 32
        const val DEADLINE_MILLIS = 10_000L
        const val RESTORE_DIAGNOSTIC_MILLIS = 16_000L
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        fun canonicalId(value: String): String? =
            value.takeIf { UUID_PATTERN.matches(it) }?.let { UUID.fromString(it).toString() }

        fun strictUtf8(value: String): ByteArray? = try {
            val encoded = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(value))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }
}
