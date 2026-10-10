package com.ninepointnine.desktop.apps

import com.ninepointnine.desktop.model.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class AppCatalogStateTest {
    @Test
    fun `hide and restore retain original full catalog positions`() {
        val catalog = listOf(entry("a"), entry("b"), entry("c"), entry("d"))
        val hidden = AppCatalogState(catalog).hide("b").hide("d")

        assertEquals(catalog, hidden.sortedCatalog)
        assertEquals(listOf("a", "c"), hidden.visibleEntries.packages())
        assertEquals(listOf("b", "d"), hidden.hiddenEntries.packages())
        assertEquals(catalog, hidden.restore(listOf("b", "d")).visibleEntries)
    }

    @Test
    fun `reinstalled package remains hidden despite changed installation identity`() {
        val original = entry("hidden", firstInstallTime = 1L)
        val hidden = AppCatalogState(listOf(original, entry("visible"))).hide(original.packageName)
        val reinstalled = entry("hidden", firstInstallTime = 9L)

        val refreshed = AppCatalogState.reconcile(
            catalog = listOf(reinstalled, entry("visible")),
            storedOrder = hidden.sortedCatalog.map(AppEntry::identity),
            hiddenPackages = hidden.hiddenPackages,
        )

        assertEquals(listOf("visible"), refreshed.visibleEntries.packages())
        assertEquals(listOf(reinstalled), refreshed.hiddenEntries)
    }

    @Test
    fun `updated hidden package uses current metadata without becoming visible`() {
        val original = entry("hidden")
        val updated = original.copy(lastUpdateTime = 200L, label = "updated")
        val refreshed = AppCatalogState.reconcile(
            catalog = listOf(updated, entry("visible")),
            storedOrder = listOf(original.identity),
            hiddenPackages = setOf("hidden"),
        )

        assertEquals(listOf(updated), refreshed.hiddenEntries)
        assertEquals(listOf("visible"), refreshed.visibleEntries.packages())
    }

    @Test
    fun `temporary absence preserves blacklist through later catalog refresh`() {
        val missing = AppCatalogState.reconcile(
            catalog = listOf(entry("visible")),
            storedOrder = emptyList(),
            hiddenPackages = setOf("hidden", "still-missing"),
        )
        val returned = AppCatalogState.reconcile(
            catalog = listOf(entry("visible"), entry("hidden", firstInstallTime = 8L)),
            storedOrder = missing.sortedCatalog.map(AppEntry::identity),
            hiddenPackages = missing.hiddenPackages,
        )

        assertEquals(setOf("hidden", "still-missing"), missing.hiddenPackages)
        assertEquals(emptyList<AppEntry>(), missing.hiddenEntries)
        assertEquals(listOf("visible"), returned.visibleEntries.packages())
        assertEquals(setOf("hidden", "still-missing"), returned.hiddenPackages)
    }

    @Test
    fun `visible reorder keeps hidden slots and restore uses merged full order`() {
        val catalog = listOf(entry("a"), entry("b"), entry("c"), entry("d"), entry("e"))
        val state = AppCatalogState(catalog, hiddenPackages = setOf("b", "d"))

        val reordered = state.reorderVisible(listOf(catalog[4], catalog[0], catalog[2]))

        assertEquals(listOf("e", "b", "a", "d", "c"), reordered.sortedCatalog.packages())
        assertEquals(listOf("e", "a", "c"), reordered.visibleEntries.packages())
        assertEquals(listOf("b", "d"), reordered.hiddenEntries.packages())
        assertEquals(
            listOf("e", "b", "a", "d", "c"),
            reordered.restore(listOf("b", "d")).visibleEntries.packages(),
        )
    }

    @Test
    fun `saved full order survives refresh while hidden entries remain in place`() {
        val catalog = listOf(entry("a"), entry("b"), entry("c"), entry("d"))
        val reordered = AppCatalogState(catalog, setOf("b"))
            .reorderVisible(listOf(catalog[3], catalog[2], catalog[0]))
        val refreshed = AppCatalogState.reconcile(
            catalog = catalog.reversed(),
            storedOrder = reordered.sortedCatalog.map(AppEntry::identity),
            hiddenPackages = reordered.hiddenPackages,
        )

        assertEquals(listOf("d", "b", "c", "a"), refreshed.sortedCatalog.packages())
        assertEquals(listOf("d", "b", "c", "a"), refreshed.restore(listOf("b")).visibleEntries.packages())
    }

    @Test
    fun `partial stale visible reorder cannot remove apps or insert hidden or unknown entries`() {
        val catalog = listOf(entry("a"), entry("b"), entry("c"), entry("d"))
        val state = AppCatalogState(catalog, setOf("b"))
        val staleA = entry("a", firstInstallTime = 99L)
        val reordered = state.reorderVisible(listOf(catalog[3], catalog[3], catalog[1], entry("unknown"), staleA))

        assertEquals(listOf("d", "b", "a", "c"), reordered.sortedCatalog.packages())
        assertEquals(catalog.toSet(), reordered.sortedCatalog.toSet())
    }

    @Test
    fun `restore removes only selected package names including absent packages`() {
        val state = AppCatalogState(listOf(entry("a"), entry("b")), setOf("a", "b", "absent"))
        val restored = state.restore(listOf("a", "absent"))

        assertEquals(setOf("b"), restored.hiddenPackages)
        assertEquals(listOf("a"), restored.visibleEntries.packages())
        assertEquals(listOf("b"), restored.hiddenEntries.packages())
    }

    @Test
    fun `reorder with every app hidden leaves full catalog unchanged`() {
        val catalog = listOf(entry("a"), entry("b"))
        val state = AppCatalogState(catalog, setOf("a", "b"))

        assertEquals(state, state.reorderVisible(emptyList()))
    }

    private fun List<AppEntry>.packages(): List<String> = map(AppEntry::packageName)

    private fun entry(packageName: String, firstInstallTime: Long = 1L) = AppEntry(
        packageName = packageName,
        componentName = "$packageName/Main",
        label = packageName,
        firstInstallTime = firstInstallTime,
        lastUpdateTime = 1L,
        isSelf = false,
        iconKey = packageName,
    )
}
