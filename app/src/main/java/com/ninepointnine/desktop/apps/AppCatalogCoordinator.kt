package com.ninepointnine.desktop.apps

import android.os.SystemClock
import com.ninepointnine.desktop.model.AppEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AppCatalogCoordinator(
    private val repository: AppCatalogRepository,
    private val orderStore: AppOrderStore,
    private val visibilityStore: AppVisibilityStore,
    private val onEntriesChanged: (List<AppEntry>) -> Unit,
    private val onLoadingChanged: (Boolean) -> Unit,
    private val onHiddenEntriesChanged: (List<AppEntry>) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val onCatalogChanged: (List<AppEntry>) -> Unit = {},
) {
    private val eventReducer = PackageEventReducer()
    private var state = AppCatalogState()
    private var refreshJob: Job? = null
    private var debounceJob: Job? = null
    private var active = false
    private var dragging = false
    private var pendingRefresh = false
    private var refreshGeneration = 0L
    private var loading = false

    /** Full current catalog, including hidden entries in their persisted slots. */
    val sortedCatalog: List<AppEntry>
        get() = state.sortedCatalog

    fun start() {
        if (active) return
        active = true
        refreshNow()
    }

    fun stop() {
        active = false
        refreshGeneration += 1
        refreshJob?.cancel()
        debounceJob?.cancel()
        scope.cancel()
    }

    fun onPackageDirty() {
        if (!active) return
        eventReducer.markDirty(SystemClock.elapsedRealtime())
        scheduleDebouncedRefresh()
    }

    fun setDragging(isDragging: Boolean) {
        dragging = isDragging
        eventReducer.setDragging(isDragging)
        if (isDragging) {
            // An enumeration already in flight must not replace the drag session's snapshot.
            if (refreshJob?.isActive == true) {
                pendingRefresh = true
                refreshGeneration += 1
                refreshJob?.cancel()
                setLoading(false)
            }
        } else {
            // The drag controller commits its order after this call; debounce until it has done so.
            scheduleDebouncedRefresh()
        }
    }

    fun persistOrder(entries: List<AppEntry>) {
        state = state.reorderVisible(entries)
        orderStore.write(state.sortedCatalog.map(AppEntry::identity))
        // The adapter already owns the committed drag order. Do not submit it back to the grid.
        publishCatalogDetails()
    }

    fun hideEntry(entry: AppEntry) {
        visibilityStore.hidePackage(entry.packageName)
        state = state.copy(hiddenPackages = visibilityStore.readHidden())
        publishSnapshot()
    }

    fun restoreHiddenEntries(entries: List<AppEntry>) {
        visibilityStore.restorePackages(entries.map(AppEntry::packageName))
        state = state.copy(hiddenPackages = visibilityStore.readHidden())
        publishSnapshot()
    }

    fun refreshNow() {
        if (dragging) {
            pendingRefresh = true
            return
        }
        pendingRefresh = false
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        refreshJob = scope.launch {
            setLoading(true)
            try {
                // Exactly one enumeration supplies visible, hidden and full catalog snapshots.
                val catalog = repository.load()
                if (generation != refreshGeneration || dragging) return@launch
                // Read after enumeration: a hide/restore/order commit during IO must win.
                val storedOrder = orderStore.read()
                state = AppCatalogState.reconcile(catalog, storedOrder.entries, visibilityStore.readHidden())
                val reconciledOrder = state.sortedCatalog.map(AppEntry::identity)
                if (storedOrder.hadInvalidEntries || reconciledOrder != storedOrder.entries) {
                    orderStore.write(reconciledOrder)
                }
                publishSnapshot()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                android.util.Log.w(TAG, "App catalog refresh failed")
            } finally {
                // A cancelled older job must not clear a newer job's loading state.
                if (generation == refreshGeneration) setLoading(false)
            }
        }
    }

    private fun publishSnapshot() {
        publishCatalogDetails()
        onEntriesChanged(state.visibleEntries)
    }

    private fun publishCatalogDetails() {
        onCatalogChanged(state.sortedCatalog)
        onHiddenEntriesChanged(state.hiddenEntries)
    }

    private fun setLoading(value: Boolean) {
        if (loading == value) return
        loading = value
        onLoadingChanged(value)
    }

    private fun scheduleDebouncedRefresh() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(PackageEventReducer.DEBOUNCE_MS)
            if (!active || dragging) return@launch
            val eventRefreshDue = eventReducer.consumeRefreshDue(SystemClock.elapsedRealtime())
            if (pendingRefresh || eventRefreshDue) refreshNow()
        }
    }

    private companion object {
        const val TAG = "AppCatalog"
    }
}
