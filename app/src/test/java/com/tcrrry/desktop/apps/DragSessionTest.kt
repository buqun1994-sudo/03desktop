package com.tcrrry.desktop.apps

import com.tcrrry.desktop.model.AppEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DragSessionTest {
    @Test
    fun `moves only memory until normal release commits once`() {
        val session = DragSession(entries())
        session.move(0, 2)

        val result = session.finish(cancelled = false, uninstallHit = false, canRequestUninstall = true)
        assertTrue(result is DragResult.Commit)
        assertEquals(listOf("b", "c", "a"), (result as DragResult.Commit).entries.map { it.packageName })
    }

    @Test
    fun `unchanged release writes nothing and cancellation restores snapshot`() {
        assertEquals(DragResult.NoChange, DragSession(entries()).finish(false, false, true))

        val session = DragSession(entries())
        session.move(0, 1)
        val result = session.finish(cancelled = true, uninstallHit = false, canRequestUninstall = true)
        assertEquals(listOf("a", "b", "c"), (result as DragResult.Restored).entries.map { it.packageName })
    }

    @Test
    fun `uninstall restores original ordering instead of persisting drag state`() {
        val session = DragSession(entries())
        session.move(0, 2)

        val result = session.finish(cancelled = false, uninstallHit = true, canRequestUninstall = true)
        assertEquals(listOf("a", "b", "c"), (result as DragResult.Uninstall).entries.map { it.packageName })
    }

    @Test
    fun `uninstall hit testing follows the dragged center`() {
        assertFalse(isDraggedCenterInsideBounds(1326, 170, 132, 154, 0f, 0f, 1290, 792, 1890, 900))
        assertTrue(isDraggedCenterInsideBounds(1326, 170, 132, 154, 150f, 620f, 1290, 792, 1890, 900))
        assertFalse(isDraggedCenterInsideBounds(1326, 170, 132, 154, 150f, 544f, 1290, 792, 1890, 900))
    }

    private fun entries() = listOf("a", "b", "c").map { packageName ->
        AppEntry(
            packageName = packageName,
            componentName = "$packageName/Main",
            label = packageName,
            firstInstallTime = 1L,
            lastUpdateTime = 1L,
            isSelf = false,
            iconKey = packageName,
        )
    }
}
