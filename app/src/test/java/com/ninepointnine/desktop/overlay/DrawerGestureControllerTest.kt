package com.ninepointnine.desktop.overlay

import com.ninepointnine.desktop.model.DrawerDock
import com.ninepointnine.desktop.model.GestureOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerGestureControllerTest {
    @Test
    fun `closed tap requests back and does not schedule home`() {
        val harness = Harness()

        harness.controller.onDown(1875f, 400f)
        harness.controller.onUp(1875f, 400f)

        assertEquals(listOf("tap"), harness.events)
        assertTrue(harness.pending.isEmpty())
    }

    @Test
    fun `closed long press requests home and suppresses tap on release`() {
        val harness = Harness()

        harness.controller.onDown(1875f, 400f)
        harness.runLongPress()
        harness.controller.onUp(1875f, 400f)

        assertEquals(listOf("long"), harness.events)
        assertTrue(harness.pending.isEmpty())
    }

    @Test
    fun `horizontal swipe cancels long press and keeps drawer motion`() {
        val harness = Harness()

        harness.controller.onDown(1875f, 400f)
        harness.controller.onMove(1860f, 400f)
        harness.controller.onMove(1775f, 400f)
        harness.controller.onUp(1775f, 400f)
        harness.runLongPress()

        assertEquals(listOf("start:CLOSED_TRIGGER", "distance:15", "distance:100", "settle:OPEN"), harness.events)
        assertTrue(harness.pending.isEmpty())
    }

    @Test
    fun `vertical movement blocks tap and long press`() {
        val harness = Harness()

        harness.controller.onDown(1875f, 400f)
        harness.controller.onMove(1875f, 430f)
        harness.controller.onUp(1875f, 430f)
        harness.runLongPress()

        assertTrue(harness.events.isEmpty())
    }

    private class Harness {
        val events = mutableListOf<String>()
        val pending = mutableListOf<Runnable>()
        var snapshot = DrawerGestureController.MotionSnapshot(0, DrawerDock.CLOSED)
        val controller = DrawerGestureController(
            touchSlopPx = 10f,
            longPressTimeoutMillis = 500L,
            postDelayed = { callback, _ -> pending += callback },
            removeCallbacks = { callback -> pending.remove(callback) },
            motionProvider = { snapshot },
            listener = object : DrawerGestureController.Listener {
                override fun onGestureDown(): Boolean = false

                override fun onHorizontalGestureStarted(origin: GestureOrigin) {
                    events += "start:$origin"
                }

                override fun onDistanceChanged(openDistancePx: Int) {
                    snapshot = snapshot.copy(openDistancePx = openDistancePx)
                    events += "distance:$openDistancePx"
                }

                override fun onSettleRequested(dock: DrawerDock) {
                    events += "settle:$dock"
                }

                override fun onClosedTriggerTapped() {
                    events += "tap"
                }

                override fun onClosedTriggerLongPressed() {
                    events += "long"
                }
            },
        )

        fun runLongPress() {
            pending.toList().forEach(Runnable::run)
            pending.clear()
        }
    }
}
