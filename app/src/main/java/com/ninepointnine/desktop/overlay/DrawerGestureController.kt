package com.ninepointnine.desktop.overlay

import android.view.MotionEvent
import com.ninepointnine.desktop.model.DrawerDock
import com.ninepointnine.desktop.model.GestureOrigin
import kotlin.math.abs
import kotlin.math.roundToInt

class DrawerGestureController(
    private val touchSlopPx: Float,
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
    }

    private enum class Direction {
        PENDING,
        HORIZONTAL,
        BLOCKED,
    }

    private var direction = Direction.PENDING
    private var downX = 0f
    private var downY = 0f
    private var startDistancePx = 0
    private var startDock = DrawerDock.CLOSED
    private var interruptedAnimation = false

    fun onTouch(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            interruptedAnimation = listener.onGestureDown()
            val snapshot = motionProvider()
            downX = event.rawX
            downY = event.rawY
            startDistancePx = DrawerGeometry.clampOpenDistance(snapshot.openDistancePx)
            startDock = snapshot.stableDock
            direction = Direction.PENDING
            true
        }

        MotionEvent.ACTION_MOVE -> {
            handleMove(event)
            true
        }

        MotionEvent.ACTION_UP -> {
            finishGesture(cancelled = false)
            true
        }

        MotionEvent.ACTION_CANCEL -> {
            finishGesture(cancelled = true)
            true
        }

        else -> true
    }

    private fun handleMove(event: MotionEvent) {
        val deltaX = event.rawX - downX
        val deltaY = event.rawY - downY

        if (direction == Direction.PENDING) {
            if (abs(deltaX) <= touchSlopPx && abs(deltaY) <= touchSlopPx) return
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

    private fun finishGesture(cancelled: Boolean) {
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
                if (!cancelled && !interruptedAnimation && startDock == DrawerDock.CLOSED) {
                    listener.onClosedTriggerTapped()
                } else if (interruptedAnimation) {
                    listener.onSettleRequested(startDock)
                }
            }

            Direction.BLOCKED -> if (interruptedAnimation) listener.onSettleRequested(startDock)
        }
        direction = Direction.PENDING
        interruptedAnimation = false
    }
}
