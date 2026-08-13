package com.tcrrry.desktop.apps

class PackageEventReducer(
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    private var dirty = false
    private var dragging = false
    private var lastEventAtMs = Long.MIN_VALUE

    fun markDirty(nowMs: Long) {
        dirty = true
        lastEventAtMs = nowMs
    }

    fun setDragging(isDragging: Boolean) {
        dragging = isDragging
    }

    fun consumeRefreshDue(nowMs: Long): Boolean {
        if (!dirty || dragging || nowMs - lastEventAtMs < debounceMs) return false
        dirty = false
        return true
    }

    companion object {
        const val DEBOUNCE_MS = 500L
    }
}
