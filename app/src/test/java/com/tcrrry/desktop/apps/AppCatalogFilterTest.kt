package com.tcrrry.desktop.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCatalogFilterTest {
    @Test
    fun `filters system disabled self but keeps lyric app as normal third party`() {
        val lyricsPackage = "com.tcrrry.desktoplyrics"
        val entries = AppCatalogFilter.toEntries(
            candidates = listOf(
                candidate(packageName = "com.tcrrry.desktop", className = "SelfActivity"),
                candidate(packageName = lyricsPackage, className = "LyricsActivity"),
                candidate(packageName = "system.entry", className = "SystemActivity", isSystemApp = true),
                candidate(packageName = "updated.system", className = "UpdatedSystemActivity", isUpdatedSystemApp = true),
                candidate(packageName = "oem.integrated", className = "OemActivity", isOemIntegratedApp = true),
                candidate(packageName = "disabled.app", className = "DisabledAppActivity", applicationEnabled = false),
                candidate(packageName = "disabled.entry", className = "DisabledActivity", activityEnabled = false),
                candidate(packageName = "missing.entry", className = ""),
            ),
            selfPackageName = "com.tcrrry.desktop",
            launcherComponentForPackage = { null },
            iconSizePx = 96,
            densityDpi = 160,
        )

        assertEquals(listOf(lyricsPackage), entries.map { it.packageName })
        assertFalse(entries.single().isSelf)
        assertTrue(entries.single().canRequestUninstall)
        assertTrue(entries.single().iconKey.endsWith(":96:160"))
    }

    @Test
    fun `prefers valid launch component then deterministic priority order`() {
        val packageName = "example.apps"
        val preferred = candidate(packageName, "Preferred", priority = 1, preferredOrder = 1)
        val launcher = candidate(packageName, "Launcher", priority = 0, preferredOrder = 0)
        val selectedLaunch = AppCatalogFilter.toEntries(
            candidates = listOf(preferred, launcher),
            selfPackageName = "self",
            launcherComponentForPackage = { "$packageName/$packageName.Launcher" },
            iconSizePx = 96,
            densityDpi = 160,
        )
        assertEquals("$packageName/$packageName.Launcher", selectedLaunch.single().componentName)

        val selectedPriority = AppCatalogFilter.toEntries(
            candidates = listOf(launcher, preferred),
            selfPackageName = "self",
            launcherComponentForPackage = { null },
            iconSizePx = 96,
            densityDpi = 160,
        )
        assertEquals("$packageName/$packageName.Preferred", selectedPriority.single().componentName)
    }

    private fun candidate(
        packageName: String,
        className: String,
        isSystemApp: Boolean = false,
        isUpdatedSystemApp: Boolean = false,
        isOemIntegratedApp: Boolean = false,
        applicationEnabled: Boolean = true,
        activityEnabled: Boolean = true,
        priority: Int = 0,
        preferredOrder: Int = 0,
    ) = CatalogCandidate(
        packageName = packageName,
        className = className,
        label = className,
        applicationLabel = packageName,
        firstInstallTime = 10L,
        lastUpdateTime = 20L,
        isSystemApp = isSystemApp,
        isUpdatedSystemApp = isUpdatedSystemApp,
        isOemIntegratedApp = isOemIntegratedApp,
        applicationEnabled = applicationEnabled,
        activityEnabled = activityEnabled,
        priority = priority,
        preferredOrder = preferredOrder,
    )
}
