package com.ninepointnine.desktop.overlay

import com.ninepointnine.desktop.model.DrawerDock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerGeometryTest {
    private val design = DrawerGeometry.forDisplay(1920, 1080)
    private val x3p = DrawerGeometry.forDisplay(2560, 1440)

    @Test
    fun `design canvas preserves existing geometry`() {
        assertEquals(1f, design.scale, 0.001f)
        assertEquals(600, design.panelWidthPx)
        assertEquals(810, design.panelHeightPx)
        assertEquals(30, design.panelEdgeGapPx)
        assertEquals(1290, design.panelX)
        assertEquals(630, design.panelMotionWidthPx)
        assertEquals(20, design.panelClosedRevealPx)
        assertEquals(610, design.maxOpenDistancePx)
        assertEquals(90, design.panelY)
        assertEquals(60, design.triggerWidthPx)
        assertEquals(1870, design.closedTriggerX)
        assertEquals(1260, design.triggerX(design.maxOpenDistancePx))
        assertEquals(6, design.handleWidthPx)
        assertEquals(150, design.handleHeightPx)
    }

    @Test
    fun `x3p canvas scales the full design and keeps the right edge adaptive`() {
        assertEquals(4f / 3f, x3p.scale, 0.001f)
        assertEquals(800, x3p.panelWidthPx)
        assertEquals(1080, x3p.panelHeightPx)
        assertEquals(40, x3p.panelEdgeGapPx)
        assertEquals(1720, x3p.panelX)
        assertEquals(2560, x3p.panelX + x3p.panelWidthPx + x3p.panelEdgeGapPx)
        assertEquals(840, x3p.panelMotionWidthPx)
        assertEquals(27, x3p.panelClosedRevealPx)
        assertEquals(813, x3p.maxOpenDistancePx)
        assertEquals(80, x3p.triggerWidthPx)
        assertEquals(2493, x3p.closedTriggerX)
        assertEquals(8, x3p.handleWidthPx)
        assertEquals(200, x3p.handleHeightPx)
    }

    @Test
    fun `non design aspect ratio fits within both axes`() {
        val geometry = DrawerGeometry.forDisplay(1920, 1200)

        assertEquals(1f, geometry.scale, 0.001f)
        assertTrue(geometry.panelX >= 0)
        assertTrue(geometry.panelY + geometry.panelHeightPx <= geometry.screenHeightPx)
        assertEquals(30, geometry.panelEdgeGapPx)
    }

    @Test
    fun `smaller design canvas scales down without crossing the screen edge`() {
        val geometry = DrawerGeometry.forDisplay(1280, 720)

        assertEquals(2f / 3f, geometry.scale, 0.001f)
        assertEquals(400, geometry.panelWidthPx)
        assertEquals(540, geometry.panelHeightPx)
        assertEquals(20, geometry.panelEdgeGapPx)
        assertEquals(860, geometry.panelX)
        assertEquals(1280, geometry.panelX + geometry.panelWidthPx + geometry.panelEdgeGapPx)
        assertTrue(geometry.panelY + geometry.panelHeightPx <= geometry.screenHeightPx)
    }

    @Test
    fun `distance endpoints keep panel and trigger aligned`() {
        listOf(design, x3p).forEach { geometry ->
            listOf(0, 100, geometry.maxOpenDistancePx / 2, geometry.maxOpenDistancePx)
                .forEach { distancePx ->
                    val panelVisibleLeft = geometry.panelX + geometry.panelTranslationX(distancePx)
                    val handleLeft = geometry.triggerX(distancePx) + geometry.handleLeftPx
                    assertEquals(geometry.scale * 2f, handleLeft - panelVisibleLeft, 0.51f)
                }
        }
    }

    @Test
    fun `settle thresholds and trigger hit testing use runtime geometry`() {
        assertEquals(DrawerDock.CLOSED, x3p.settleFromClosed(0))
        assertEquals(DrawerDock.OPEN, x3p.settleFromClosed(x3p.openReleaseThresholdPx))
        assertEquals(DrawerDock.OPEN, x3p.settleFromOpen(x3p.closePullThresholdPx))
        assertEquals(DrawerDock.CLOSED, x3p.settleFromOpen(x3p.closePullThresholdPx + 1))
        assertEquals(DrawerDock.CLOSED, x3p.recoverDock(x3p.recoveryOpenThresholdPx - 1))
        assertEquals(DrawerDock.OPEN, x3p.recoverDock(x3p.recoveryOpenThresholdPx))

        assertTrue(
            x3p.isPointInsideTrigger(
                x3p.triggerX(x3p.maxOpenDistancePx).toFloat(),
                x3p.triggerY.toFloat(),
                x3p.maxOpenDistancePx,
            ),
        )
        assertTrue(
            x3p.isPointInsideTrigger(
                x3p.triggerX(x3p.maxOpenDistancePx) + x3p.triggerWidthPx - 0.1f,
                x3p.triggerY + x3p.triggerHeightPx - 0.1f,
                x3p.maxOpenDistancePx,
            ),
        )
    }
}
