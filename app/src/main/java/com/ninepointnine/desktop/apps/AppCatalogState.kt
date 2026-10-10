package com.ninepointnine.desktop.apps

import com.ninepointnine.desktop.model.AppEntry
import com.ninepointnine.desktop.model.AppIdentity

/** Immutable catalog snapshot: filtering never changes the full order or hidden slots. */
data class AppCatalogState(
    val sortedCatalog: List<AppEntry> = emptyList(),
    val hiddenPackages: Set<String> = emptySet(),
) {
    val visibleEntries: List<AppEntry>
        get() = sortedCatalog.filterNot { it.packageName in hiddenPackages }

    val hiddenEntries: List<AppEntry>
        get() = sortedCatalog.filter { it.packageName in hiddenPackages }

    fun hide(packageName: String): AppCatalogState =
        if (packageName.isBlank()) this else copy(hiddenPackages = hiddenPackages + packageName)

    fun restore(packageNames: Collection<String>): AppCatalogState =
        copy(hiddenPackages = hiddenPackages - packageNames.toSet())

    /** Merge a visible drag order into visible slots, retaining every hidden entry in place. */
    fun reorderVisible(entries: List<AppEntry>): AppCatalogState {
        val currentVisible = visibleEntries
        val byIdentity = currentVisible.associateBy(AppEntry::identity)
        val requested = entries.mapNotNull { byIdentity[it.identity] }.distinctBy(AppEntry::identity)
        val requestedIdentities = requested.map(AppEntry::identity).toSet()
        // A stale/partial UI snapshot must not discard catalog entries or insert unknown apps.
        val reordered = (requested + currentVisible.filterNot { it.identity in requestedIdentities }).iterator()
        return copy(sortedCatalog = sortedCatalog.map { entry ->
            if (entry.packageName in hiddenPackages) entry else reordered.next()
        })
    }

    companion object {
        fun reconcile(
            catalog: List<AppEntry>,
            storedOrder: List<AppIdentity>,
            hiddenPackages: Set<String>,
        ): AppCatalogState = AppCatalogState(
            sortedCatalog = AppOrderReconciler.reconcile(storedOrder, catalog),
            hiddenPackages = hiddenPackages.toSet(),
        )
    }
}
