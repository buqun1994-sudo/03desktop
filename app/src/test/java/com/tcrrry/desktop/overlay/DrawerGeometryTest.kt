package com.tcrrry.desktop.overlay

import com.tcrrry.desktop.model.DrawerDock
import com.tcrrry.desktop.model.DrawerMotion
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerGeometryTest {
    @Test
    fun `clamps all distances and maps endpoint coordinates`() {
        assertEquals(0, DrawerGeometry.clampOpenDistance(-1))
        assertEquals(0, DrawerGeometry.clampOpenDistance(0))
        assertEquals(610, DrawerGeometry.clampOpenDistance(610))
        assertEquals(610, DrawerGeometry.clampOpenDistance(611))
        assertEquals(DrawerGeometry.PANEL_TRAVEL_PX, DrawerMotion.MAX_OPEN_DISTANCE_PX)
        assertEquals(1890, DrawerGeometry.PANEL_X_PX + DrawerGeometry.PANEL_WIDTH_PX)
        assertEquals(30, DrawerGeometry.PANEL_EDGE_GAP_PX)
        assertEquals(1920, DrawerGeometry.PANEL_X_PX + DrawerGeometry.PANEL_MOTION_WIDTH_PX)
        assertEquals(1900f, DrawerGeometry.PANEL_X_PX + DrawerGeometry.panelTranslationX(0), 0.001f)
        assertEquals(1870, DrawerGeometry.triggerX(0))
        assertEquals(1770, DrawerGeometry.triggerX(100))
        assertEquals(1769, DrawerGeometry.triggerX(101))
        assertEquals(1571, DrawerGeometry.triggerX(299))
        assertEquals(1570, DrawerGeometry.triggerX(300))
        assertEquals(1260, DrawerGeometry.triggerX(610))
        assertEquals(610f, DrawerGeometry.panelTranslationX(0), 0.001f)
        assertEquals(510f, DrawerGeometry.panelTranslationX(100), 0.001f)
        assertEquals(509f, DrawerGeometry.panelTranslationX(101), 0.001f)
        assertEquals(311f, DrawerGeometry.panelTranslationX(299), 0.001f)
        assertEquals(310f, DrawerGeometry.panelTranslationX(300), 0.001f)
        assertEquals(0f, DrawerGeometry.panelTranslationX(610), 0.001f)

        listOf(0, 100, 101, 299, 300, 610).forEach { distancePx ->
            val panelVisibleLeft = DrawerGeometry.PANEL_X_PX +
                DrawerGeometry.panelTranslationX(distancePx)
            val handleLeft = DrawerGeometry.triggerX(distancePx) + DrawerGeometry.HANDLE_LEFT_PX
            assertEquals(2f, handleLeft - panelVisibleLeft, 0.51f)
        }
    }

    @Test
    fun `settles boundaries exactly as specified`() {
        assertEquals(DrawerDock.CLOSED, DrawerGeometry.settleFromClosed(0))
        assertEquals(DrawerDock.OPEN, DrawerGeometry.settleFromClosed(1))
        assertEquals(DrawerDock.OPEN, DrawerGeometry.settleFromOpen(100))
        assertEquals(DrawerDock.CLOSED, DrawerGeometry.settleFromOpen(101))
    }

    @Test
    fun `identifies the open trigger without expanding its touch rectangle`() {
        assertEquals(true, DrawerGeometry.isPointInsideTrigger(1260f, 90f, 610))
        assertEquals(true, DrawerGeometry.isPointInsideTrigger(1319.9f, 899.9f, 610))
        assertEquals(false, DrawerGeometry.isPointInsideTrigger(1259.9f, 90f, 610))
        assertEquals(false, DrawerGeometry.isPointInsideTrigger(1320f, 90f, 610))
        assertEquals(false, DrawerGeometry.isPointInsideTrigger(1260f, 89.9f, 610))
        assertEquals(false, DrawerGeometry.isPointInsideTrigger(1260f, 900f, 610))
    }

    @Test
    fun `unknown intermediary states recover at midpoint`() {
        assertEquals(DrawerDock.CLOSED, DrawerGeometry.recoverDock(299))
        assertEquals(DrawerDock.OPEN, DrawerGeometry.recoverDock(300))
    }

}
