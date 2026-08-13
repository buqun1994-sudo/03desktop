package com.tcrrry.desktop.apps

import android.os.SystemClock
import com.tcrrry.desktop.model.AppEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AppCatalogCoordinator(
    private val repository: AppCatalogRepository,
    private val orderStore: AppOrderStore,
    private val onEntriesChanged: (List<AppEntry>) -> Unit,
    private val onLoadingChanged: (Boolean) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val eventReducer = PackageEventReducer()
    private var refreshJob: Job? = null
    private var debounceJob: Job? = null
    private var active = false

    fun start() {
        if (active) return
        active = true
        refreshNow()
    }

    fun stop() {
        active = false
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
        eventReducer.setDragging(isDragging)
        if (!isDragging) scheduleDebouncedRefresh()
    }

    fun persistOrder(entries: List<AppEntry>) {
        orderStore.write(entries.map(AppEntry::identity))
    }

    fun refreshNow() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            onLoadingChanged(true)
            try {
                val catalog = repository.load()
                val storedOrder = orderStore.read()
                val reconciled = AppOrderReconciler.reconcile(storedOrder.entries, catalog)
                val reconciledOrder = reconciled.map(AppEntry::identity)
                if (storedOrder.hadInvalidEntries || reconciledOrder != storedOrder.entries) {
                    orderStore.write(reconciledOrder)
                }
                onEntriesChanged(reconciled)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                android.util.Log.w(TAG, "App catalog refresh failed")
            } finally {
                onLoadingChanged(false)
            }
        }
    }

    private fun scheduleDebouncedRefresh() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(PackageEventReducer.DEBOUNCE_MS)
            if (eventReducer.consumeRefreshDue(SystemClock.elapsedRealtime())) refreshNow()
        }
    }

    private companion object {
        const val TAG = "AppCatalog"
    }
}
