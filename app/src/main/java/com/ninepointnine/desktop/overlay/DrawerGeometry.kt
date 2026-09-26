package com.ninepointnine.desktop.overlay

import com.ninepointnine.desktop.model.DrawerDock
import kotlin.math.min
import kotlin.math.roundToInt

object DrawerGeometry {
    const val DESIGN_SCREEN_WIDTH_PX = 1920
    const val DESIGN_SCREEN_HEIGHT_PX = 1080

    const val DESIGN_PANEL_WIDTH_PX = 600
    const val DESIGN_PANEL_HEIGHT_PX = 810
    const val DESIGN_PANEL_EDGE_GAP_PX = 30
    const val DESIGN_PANEL_CLOSED_REVEAL_PX = 20
    const val DESIGN_PANEL_TOP_PX = 90

    const val DESIGN_TRIGGER_WIDTH_PX = 60
    const val DESIGN_TRIGGER_HEIGHT_PX = 810
    const val DESIGN_TRIGGER_RIGHT_INSET_PX = 50
    const val DESIGN_TRIGGER_TOP_PX = 90

    const val DESIGN_HANDLE_WIDTH_PX = 6
    const val DESIGN_HANDLE_HEIGHT_PX = 150
    const val DESIGN_HANDLE_LEFT_PX = 32
    const val DESIGN_HANDLE_TOP_PX = 330
    const val DESIGN_OPEN_RELEASE_THRESHOLD_PX = 1
    const val DESIGN_RECOVERY_OPEN_THRESHOLD_PX = 300
    const val DESIGN_CLOSE_PULL_THRESHOLD_PX = 100
    const val SETTLE_DURATION_MS = 200L

    data class Spec(
        val screenWidthPx: Int,
        val screenHeightPx: Int,
        val scale: Float,
        val panelWidthPx: Int,
        val panelHeightPx: Int,
        val panelEdgeGapPx: Int,
        val panelX: Int,
        val panelMotionWidthPx: Int,
        val panelClosedRevealPx: Int,
        val panelTravelPx: Int,
        val panelY: Int,
        val triggerWidthPx: Int,
        val triggerHeightPx: Int,
        val closedTriggerX: Int,
        val triggerY: Int,
        val handleWidthPx: Int,
        val handleHeightPx: Int,
        val handleLeftPx: Int,
        val handleTopPx: Int,
        val openReleaseThresholdPx: Int,
        val recoveryOpenThresholdPx: Int,
        val closePullThresholdPx: Int,
    ) {
        val maxOpenDistancePx: Int
            get() = panelTravelPx

        fun clampOpenDistance(distancePx: Int): Int = distancePx.coerceIn(0, maxOpenDistancePx)

        fun panelTranslationX(distancePx: Int): Float =
            (panelTravelPx - clampOpenDistance(distancePx)).toFloat()

        fun triggerX(distancePx: Int): Int = closedTriggerX - clampOpenDistance(distancePx)

        fun isPointInsideTrigger(rawX: Float, rawY: Float, distancePx: Int): Boolean {
            val left = triggerX(distancePx).toFloat()
            val top = triggerY.toFloat()
            return rawX >= left && rawX < left + triggerWidthPx &&
                rawY >= top && rawY < top + triggerHeightPx
        }

        fun settleFromClosed(distancePx: Int): DrawerDock =
            if (clampOpenDistance(distancePx) >= openReleaseThresholdPx) DrawerDock.OPEN else DrawerDock.CLOSED

        fun settleFromOpen(closePullPx: Int): DrawerDock =
            if (closePullPx > closePullThresholdPx) DrawerDock.CLOSED else DrawerDock.OPEN

        fun recoverDock(distancePx: Int): DrawerDock =
            if (clampOpenDistance(distancePx) >= recoveryOpenThresholdPx) DrawerDock.OPEN else DrawerDock.CLOSED
    }

    fun forDisplay(screenWidthPx: Int, screenHeightPx: Int): Spec {
        require(screenWidthPx > 0) { "screenWidthPx must be positive" }
        require(screenHeightPx > 0) { "screenHeightPx must be positive" }

        val scale = min(
            screenWidthPx.toFloat() / DESIGN_SCREEN_WIDTH_PX,
            screenHeightPx.toFloat() / DESIGN_SCREEN_HEIGHT_PX,
        )
        fun scaled(valuePx: Int): Int = (valuePx * scale).roundToInt().coerceAtLeast(1)

        val panelWidthPx = scaled(DESIGN_PANEL_WIDTH_PX)
        val panelHeightPx = scaled(DESIGN_PANEL_HEIGHT_PX)
        val panelEdgeGapPx = scaled(DESIGN_PANEL_EDGE_GAP_PX)
        val panelMotionWidthPx = panelWidthPx + panelEdgeGapPx
        val panelClosedRevealPx = scaled(DESIGN_PANEL_CLOSED_REVEAL_PX)
        val panelTravelPx = panelMotionWidthPx - panelClosedRevealPx

        return Spec(
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            scale = scale,
            panelWidthPx = panelWidthPx,
            panelHeightPx = panelHeightPx,
            panelEdgeGapPx = panelEdgeGapPx,
            panelX = screenWidthPx - panelEdgeGapPx - panelWidthPx,
            panelMotionWidthPx = panelMotionWidthPx,
            panelClosedRevealPx = panelClosedRevealPx,
            panelTravelPx = panelTravelPx,
            panelY = scaled(DESIGN_PANEL_TOP_PX),
            triggerWidthPx = scaled(DESIGN_TRIGGER_WIDTH_PX),
            triggerHeightPx = panelHeightPx,
            closedTriggerX = screenWidthPx - scaled(DESIGN_TRIGGER_RIGHT_INSET_PX),
            triggerY = scaled(DESIGN_TRIGGER_TOP_PX),
            handleWidthPx = scaled(DESIGN_HANDLE_WIDTH_PX),
            handleHeightPx = scaled(DESIGN_HANDLE_HEIGHT_PX),
            handleLeftPx = scaled(DESIGN_HANDLE_LEFT_PX),
            handleTopPx = scaled(DESIGN_HANDLE_TOP_PX),
            openReleaseThresholdPx = scaled(DESIGN_OPEN_RELEASE_THRESHOLD_PX),
            recoveryOpenThresholdPx = scaled(DESIGN_RECOVERY_OPEN_THRESHOLD_PX),
            closePullThresholdPx = scaled(DESIGN_CLOSE_PULL_THRESHOLD_PX),
        )
    }
}
