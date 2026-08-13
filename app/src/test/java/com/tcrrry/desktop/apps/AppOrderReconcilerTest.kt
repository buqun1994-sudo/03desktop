package com.tcrrry.desktop.apps

import com.tcrrry.desktop.model.AppEntry
import com.tcrrry.desktop.model.AppIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class AppOrderReconcilerTest {
    @Test
    fun `retains matching identities and appends new or reinstalled apps`() {
        val update = entry("update", firstInstallTime = 1L, lastUpdateTime = 99L)
        val reinstall = entry("reinstall", firstInstallTime = 4L)
        val newEntry = entry("new", firstInstallTime = 5L)

        val result = AppOrderReconciler.reconcile(
            storedOrder = listOf(
                AppIdentity("removed", 2L),
                AppIdentity("update", 1L),
                AppIdentity("reinstall", 3L),
            ),
            currentEntries = listOf(newEntry, reinstall, update),
        )

        assertEquals(listOf("update", "new", "reinstall"), result.map { it.packageName })
        assertEquals(99L, result.first().lastUpdateTime)
    }

    @Test
    fun `drops duplicates and invalid storage values without breaking current catalog`() {
        val decoded = AppOrderJsonCodec.decode(
            """{"schemaVersion":1,"entries":[{"packageName":"a","firstInstallTime":1},{"packageName":"a","firstInstallTime":1},{"packageName":"bad","firstInstallTime":-1}]}""",
        )
        assertEquals(listOf(AppIdentity("a", 1L)), decoded.entries)
        assertEquals(2, decoded.invalidEntryCount)
        assertEquals(emptyList<AppIdentity>(), AppOrderJsonCodec.decode("not json").entries)

        val current = listOf(entry("a", 1L), entry("b", 2L))
        assertEquals(listOf("a", "b"), AppOrderReconciler.reconcile(decoded.entries, current).map { it.packageName })
    }

    private fun entry(packageName: String, firstInstallTime: Long, lastUpdateTime: Long = 1L) = AppEntry(
        packageName = packageName,
        componentName = "$packageName/Main",
        label = packageName,
        firstInstallTime = firstInstallTime,
        lastUpdateTime = lastUpdateTime,
        isSelf = false,
        iconKey = packageName,
    )
}
