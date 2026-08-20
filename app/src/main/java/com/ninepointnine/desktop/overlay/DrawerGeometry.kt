package com.ninepointnine.desktop.overlay

import com.ninepointnine.desktop.model.DrawerDock
import com.ninepointnine.desktop.model.DrawerMotion

object DrawerGeometry {
    const val SCREEN_WIDTH_PX = 1920
    const val SCREEN_HEIGHT_PX = 1080

    const val PANEL_WIDTH_PX = 600
    const val PANEL_HEIGHT_PX = 810
    const val PANEL_EDGE_GAP_PX = 30
    const val PANEL_X_PX = SCREEN_WIDTH_PX - PANEL_EDGE_GAP_PX - PANEL_WIDTH_PX
    const val PANEL_MOTION_WIDTH_PX = PANEL_WIDTH_PX + PANEL_EDGE_GAP_PX
    const val PANEL_CLOSED_REVEAL_PX = 20
    const val PANEL_TRAVEL_PX = PANEL_MOTION_WIDTH_PX - PANEL_CLOSED_REVEAL_PX
    const val PANEL_Y_PX = 90

    const val TRIGGER_WIDTH_PX = 60
    const val TRIGGER_HEIGHT_PX = 810
    const val CLOSED_TRIGGER_X_PX = 1870
    const val OPEN_TRIGGER_X_PX = PANEL_X_PX - 30
    const val TRIGGER_Y_PX = 90

    const val HANDLE_WIDTH_PX = 6
    const val HANDLE_HEIGHT_PX = 150
    const val HANDLE_LEFT_PX = 32
    const val HANDLE_TOP_PX = 330
    const val OPEN_RELEASE_THRESHOLD_PX = 1
    const val RECOVERY_OPEN_THRESHOLD_PX = 300
    const val CLOSE_PULL_THRESHOLD_PX = 100
    const val SETTLE_DURATION_MS = 200L

    fun clampOpenDistance(distancePx: Int): Int =
        distancePx.coerceIn(DrawerMotion.MIN_OPEN_DISTANCE_PX, DrawerMotion.MAX_OPEN_DISTANCE_PX)

    fun panelTranslationX(distancePx: Int): Float =
        (PANEL_TRAVEL_PX - clampOpenDistance(distancePx)).toFloat()

    fun triggerX(distancePx: Int): Int {
        return CLOSED_TRIGGER_X_PX - clampOpenDistance(distancePx)
    }

    fun isPointInsideTrigger(rawX: Float, rawY: Float, distancePx: Int): Boolean {
        val left = triggerX(distancePx).toFloat()
        val top = TRIGGER_Y_PX.toFloat()
        return rawX >= left && rawX < left + TRIGGER_WIDTH_PX &&
            rawY >= top && rawY < top + TRIGGER_HEIGHT_PX
    }

    fun settleFromClosed(distancePx: Int): DrawerDock =
        if (clampOpenDistance(distancePx) >= OPEN_RELEASE_THRESHOLD_PX) DrawerDock.OPEN else DrawerDock.CLOSED

    fun settleFromOpen(closePullPx: Int): DrawerDock =
        if (closePullPx > CLOSE_PULL_THRESHOLD_PX) DrawerDock.CLOSED else DrawerDock.OPEN

    fun recoverDock(distancePx: Int): DrawerDock =
        if (clampOpenDistance(distancePx) >= RECOVERY_OPEN_THRESHOLD_PX) DrawerDock.OPEN else DrawerDock.CLOSED
}
