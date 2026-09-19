package com.ninepointnine.desktop.overlay

import com.ninepointnine.desktop.model.DrawerDock
import com.ninepointnine.desktop.model.GestureOrigin
import kotlin.math.abs
import kotlin.math.roundToInt

class DrawerGestureController(
    private val touchSlopPx: Float,
    private val longPressTimeoutMillis: Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val motionProvider: () -> MotionSnapshot,
    private val listener: Listener,
) {
    data class MotionSnapshot(
        val openDistancePx: Int,
        val stableDock: DrawerDock,
    )

    interface Listener {
        fun onGestureDown(): Boolean
        fun onHorizontalGestureStarted(origin: GestureOrigin)
        fun onDistanceChanged(openDistancePx: Int)
        fun onSettleRequested(dock: DrawerDock)
        fun onClosedTriggerTapped()
        fun onClosedTriggerLongPressed()
    }

    private enum class Direction {
        PENDING,
        HORIZONTAL,
        BLOCKED,
        LONG_PRESSED,
    }

    private var direction = Direction.PENDING
    private var downX = 0f
    private var downY = 0f
    private var startDistancePx = 0
    private var startDock = DrawerDock.CLOSED
    private var interruptedAnimation = false
    private var gestureActive = false
    private val longPressCallback = Runnable {
        if (gestureActive && direction == Direction.PENDING && allowsNavigation()) {
            direction = Direction.LONG_PRESSED
            listener.onClosedTriggerLongPressed()
        }
    }

    fun onDown(rawX: Float, rawY: Float) {
        cancel()
        interruptedAnimation = listener.onGestureDown()
        val snapshot = motionProvider()
        downX = rawX
        downY = rawY
        startDistancePx = DrawerGeometry.clampOpenDistance(snapshot.openDistancePx)
        startDock = snapshot.stableDock
        gestureActive = true
        if (allowsNavigation()) postDelayed(longPressCallback, longPressTimeoutMillis)
    }

    fun onMove(rawX: Float, rawY: Float) {
        if (!gestureActive) return
        val deltaX = rawX - downX
        val deltaY = rawY - downY

        if (direction == Direction.PENDING) {
            if (abs(deltaX) <= touchSlopPx && abs(deltaY) <= touchSlopPx) return
            removeCallbacks(longPressCallback)
            if (abs(deltaX) < abs(deltaY) * 1.2f) {
                direction = Direction.BLOCKED
                return
            }

            val allowsDirection =
                (startDock == DrawerDock.CLOSED && deltaX < 0f) ||
                    (startDock == DrawerDock.OPEN && deltaX > 0f)
            if (!allowsDirection) {
                direction = Direction.BLOCKED
                return
            }

            direction = Direction.HORIZONTAL
            listener.onHorizontalGestureStarted(
                if (startDock == DrawerDock.CLOSED) GestureOrigin.CLOSED_TRIGGER else GestureOrigin.OPEN_TRIGGER,
            )
        }

        if (direction == Direction.HORIZONTAL) {
            listener.onDistanceChanged(DrawerGeometry.clampOpenDistance((startDistancePx - deltaX).roundToInt()))
        }
    }

    fun onUp(rawX: Float, rawY: Float) {
        // The final position can cross touch slop even without a preceding MOVE.
        if (direction == Direction.PENDING) onMove(rawX, rawY)
        finishGesture(cancelled = false)
    }

    fun onCancel() {
        finishGesture(cancelled = true)
    }

    fun cancel() {
        removeCallbacks(longPressCallback)
        gestureActive = false
        direction = Direction.PENDING
        interruptedAnimation = false
    }

    private fun allowsNavigation(): Boolean =
        !interruptedAnimation && startDock == DrawerDock.CLOSED && startDistancePx == 0

    private fun finishGesture(cancelled: Boolean) {
        if (!gestureActive) return
        removeCallbacks(longPressCallback)
        gestureActive = false
        when (direction) {
            Direction.HORIZONTAL -> {
                val destination = if (cancelled) {
                    startDock
                } else if (startDock == DrawerDock.CLOSED) {
                    DrawerGeometry.settleFromClosed(motionProvider().openDistancePx)
                } else {
                    DrawerGeometry.settleFromOpen(startDistancePx - motionProvider().openDistancePx)
                }
                listener.onSettleRequested(destination)
            }

            Direction.PENDING -> {
                if (!cancelled && allowsNavigation()) {
                    listener.onClosedTriggerTapped()
                } else if (interruptedAnimation) {
                    listener.onSettleRequested(startDock)
                }
            }

            Direction.BLOCKED -> if (interruptedAnimation) listener.onSettleRequested(startDock)

            Direction.LONG_PRESSED -> Unit
        }
        direction = Direction.PENDING
        interruptedAnimation = false
    }
}
