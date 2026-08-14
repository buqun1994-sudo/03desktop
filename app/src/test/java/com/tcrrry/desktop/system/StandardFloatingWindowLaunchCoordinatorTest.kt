package com.tcrrry.desktop.system

import com.tcrrry.desktop.system.StandardFloatingWindowLaunchCoordinator.Failure
import com.tcrrry.desktop.system.StandardFloatingWindowLaunchCoordinator.TargetKind
import com.tcrrry.desktop.system.StandardFloatingWindowLaunchCoordinator.WindowModeRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardFloatingWindowLaunchCoordinatorTest {
    @Test
    fun `metadata false launches directly without HOME`() {
        val gateway = FakeGateway(targetKind = TargetKind.REGULAR, windowMode = 2)
        val failures = mutableListOf<Failure>()
        val coordinator = StandardFloatingWindowLaunchCoordinator(gateway, failures::add)

        assertTrue(coordinator.launch(TARGET))

        assertEquals(0, gateway.homeStarts)
        assertEquals(listOf(TARGET), gateway.targetStarts)
        assertEquals(0, gateway.observationStarts)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `standard target in mode zero launches exactly once`() {
        val gateway = FakeGateway(TargetKind.STANDARD_FLOATING_WINDOW, windowMode = 0)
        val coordinator = StandardFloatingWindowLaunchCoordinator(gateway) { error("Unexpected $it") }

        assertTrue(coordinator.launch(TARGET))

        assertEquals(0, gateway.homeStarts)
        assertEquals(listOf(TARGET), gateway.targetStarts)
        assertFalse(coordinator.hasPendingLaunch())
    }

    @Test
    fun `standard target with only ADAS card launches directly`() {
        val gateway = FakeGateway(TargetKind.STANDARD_FLOATING_WINDOW, windowMode = 1)
        val coordinator = StandardFloatingWindowLaunchCoordinator(gateway) { error("Unexpected $it") }

        assertTrue(coordinator.launch(TARGET))

        assertEquals(0, gateway.homeStarts)
        assertEquals(listOf(TARGET), gateway.targetStarts)
        assertEquals(0, gateway.observationStarts)
        assertFalse(coordinator.hasPendingLaunch())
    }

    @Test
    fun `standard target in mode two waits for HOME transition before one launch`() {
        val gateway = FakeGateway(TargetKind.STANDARD_FLOATING_WINDOW, windowMode = 2)
        val coordinator = StandardFloatingWindowLaunchCoordinator(gateway) { error("Unexpected $it") }

        assertTrue(coordinator.launch(TARGET))
        assertTrue(coordinator.hasPendingLaunch())
        assertEquals(1, gateway.homeStarts)
        assertTrue(gateway.targetStarts.isEmpty())
        assertFalse(coordinator.launch(OTHER_TARGET))

        gateway.windowMode = 0
        gateway.notifyWindowModeChanged()
        gateway.notifyWindowModeChanged()

        assertEquals(listOf(TARGET), gateway.targetStarts)
        assertFalse(coordinator.hasPendingLaunch())
        assertEquals(1, gateway.observationCancels)
        assertEquals(1, gateway.timeoutCancels)
    }

    @Test
    fun `combined ADAS and standard window waits until only ADAS remains`() {
        val gateway = FakeGateway(TargetKind.STANDARD_FLOATING_WINDOW, windowMode = 3)
        val coordinator = StandardFloatingWindowLaunchCoordinator(gateway) { error("Unexpected $it") }

        assertTrue(coordinator.launch(TARGET))
        assertEquals(1, gateway.homeStarts)
        assertTrue(coordinator.hasPendingLaunch())
        assertTrue(gateway.targetStarts.isEmpty())

        gateway.notifyWindowModeChanged()
        assertTrue(coordinator.hasPendingLaunch())
        assertTrue(gateway.targetStarts.isEmpty())

        gateway.windowMode = 1
        gateway.notifyWindowModeChanged()
        gateway.notifyWindowModeChanged()

        assertEquals(listOf(TARGET), gateway.targetStarts)
        assertEquals(1, gateway.observationCancels)
        assertEquals(1, gateway.timeoutCancels)
        assertFalse(coordinator.hasPendingLaunch())
    }

    @Test
    fun `ADAS appearing while HOME clears a standard window keeps waiting`() {
        val gateway = FakeGateway(TargetKind.STANDARD_FLOATING_WINDOW, windowMode = 2)
        val coordinator = StandardFloatingWindowLaunchCoordinator(gateway) { error("Unexpected $it") }

        assertTrue(coordinator.launch(TARGET))

        gateway.windowMode = 3
        gateway.notifyWindowModeChanged()

        assertTrue(coordinator.hasPendingLaunch())
        assertTrue(gateway.targetStarts.isEmpty())

        gateway.windowMode = 1
        gateway.notifyWindowModeChanged()

        assertEquals(listOf(TARGET), gateway.targetStarts)
        assertFalse(coordinator.hasPendingLaunch())
    }

    @Test
    fun `exclusive top level window in mode zero starts supplied action exactly once`() {
        val gateway = FakeGateway(TargetKind.REGULAR, windowMode = 0)
        val coordinator = StandardFloatingWindowLaunchCoordinator(gateway) { error("Unexpected $it") }
        var starts = 0

        assertTrue(
            coordinator.launchExclusive {
                starts += 1
                true
            },
        )

        assertEquals(1, starts)
        assertEquals(0, gateway.homeStarts)
        assertFalse(coordinator.hasPendingLaunch())
    }

    @Test
    fun `exclusive top level window in mode two waits for HOME and rejects duplicates`() {
        val gateway = FakeGateway(TargetKind.REGULAR, windowMode = 2)
        val coordinator = StandardFloatingWindowLaunchCoordinator(gateway) { error("Unexpected $it") }
        var starts = 0

        assertTrue(
            coordinator.launchExclusive {
                starts += 1
                true
            },
        )
        assertEquals(1, gateway.homeStarts)
        assertEquals(0, starts)
        assertTrue(coordinator.hasPendingLaunch())
        assertFalse(coordinator.launchExclusive { error("Duplicate exclusive launch") })
        assertFalse(coordinator.launch(OTHER_TARGET))

        gateway.windowMode = 0
        gateway.notifyWindowModeChanged()
        gateway.notifyWindowModeChanged()

        assertEquals(1, starts)
        assertEquals(1, gateway.observationCancels)
        assertEquals(1, gateway.timeoutCancels)
        assertFalse(coordinator.hasPendingLaunch())
    }

    @Test
    fun `exclusive system action reports its own target failure`() {
        val gateway = FakeGateway(TargetKind.REGULAR, windowMode = 0)
        val failures = mutableListOf<Failure>()
        val coordinator = StandardFloatingWindowLaunchCoordinator(gateway, failures::add)

        assertFalse(
            coordinator.launchExclusive(Failure.SYSTEM_ACTION_UNAVAILABLE) {
                false
            },
        )

        assertEquals(listOf(Failure.SYSTEM_ACTION_UNAVAILABLE), failures)
        assertEquals(0, gateway.homeStarts)
        assertFalse(coordinator.hasPendingLaunch())
    }

    @Test
    fun `synchronous HOME state change reports the single target launch correctly`() {
        val gateway = FakeGateway(TargetKind.STANDARD_FLOATING_WINDOW, windowMode = 2).apply {
            onHomeStarted = {
                windowMode = 0
                notifyWindowModeChanged()
            }
        }
        val coordinator = StandardFloatingWindowLaunchCoordinator(gateway) { error("Unexpected $it") }

        assertTrue(coordinator.launch(TARGET))

        assertEquals(1, gateway.homeStarts)
        assertEquals(listOf(TARGET), gateway.targetStarts)
        assertEquals(1, gateway.observationCancels)
        assertEquals(1, gateway.timeoutCancels)
        assertFalse(coordinator.hasPendingLaunch())
    }

    @Test
    fun `HOME failure clears observer timeout and pending request`() {
        val gateway = FakeGateway(TargetKind.STANDARD_FLOATING_WINDOW, windowMode = 2).apply {
            homeSucceeds = false
        }
        val failures = mutableListOf<Failure>()
        val coordinator = StandardFloatingWindowLaunchCoordinator(gateway, failures::add)

        assertFalse(coordinator.launch(TARGET))

        assertEquals(listOf(Failure.HOME_UNAVAILABLE), failures)
        assertEquals(1, gateway.observationCancels)
        assertEquals(1, gateway.timeoutCancels)
        assertFalse(coordinator.hasPendingLaunch())
    }

    @Test
    fun `target failure after observed zero leaves no listener or duplicate launch`() {
        val gateway = FakeGateway(TargetKind.STANDARD_FLOATING_WINDOW, windowMode = 2).apply {
            targetSucceeds = false
        }
        val failures = mutableListOf<Failure>()
        val coordinator = StandardFloatingWindowLaunchCoordinator(gateway, failures::add)

        assertTrue(coordinator.launch(TARGET))
        gateway.windowMode = 0
        gateway.notifyWindowModeChanged()
        gateway.notifyWindowModeChanged()

        assertEquals(listOf(TARGET), gateway.targetStarts)
        assertEquals(listOf(Failure.TARGET_UNAVAILABLE), failures)
        assertEquals(1, gateway.observationCancels)
        assertEquals(1, gateway.timeoutCancels)
        assertFalse(coordinator.hasPendingLaunch())
    }

    @Test
    fun `timeout and explicit cancellation both unregister exactly once`() {
        val timeoutGateway = FakeGateway(TargetKind.STANDARD_FLOATING_WINDOW, windowMode = 2)
        val failures = mutableListOf<Failure>()
        val timedCoordinator = StandardFloatingWindowLaunchCoordinator(timeoutGateway, failures::add)
        assertTrue(timedCoordinator.launch(TARGET))

        timeoutGateway.fireTimeout()

        assertEquals(listOf(Failure.TRANSITION_TIMEOUT), failures)
        assertEquals(1, timeoutGateway.observationCancels)
        assertEquals(1, timeoutGateway.timeoutCancels)
        assertFalse(timedCoordinator.hasPendingLaunch())

        val cancelGateway = FakeGateway(TargetKind.STANDARD_FLOATING_WINDOW, windowMode = 2)
        val cancelledCoordinator = StandardFloatingWindowLaunchCoordinator(cancelGateway) { error("Unexpected $it") }
        assertTrue(cancelledCoordinator.launch(TARGET))
        cancelledCoordinator.cancel()
        cancelledCoordinator.cancel()

        assertEquals(1, cancelGateway.observationCancels)
        assertEquals(1, cancelGateway.timeoutCancels)
        assertFalse(cancelledCoordinator.hasPendingLaunch())
    }

    @Test
    fun `unknown or unreadable mode fails conservatively without HOME`() {
        listOf(
            WindowModeRead.Value(-1),
            WindowModeRead.Value(4),
            WindowModeRead.Unavailable,
        ).forEach { mode ->
            val gateway = FakeGateway(TargetKind.STANDARD_FLOATING_WINDOW, windowMode = 0).apply {
                modeRead = mode
            }
            val failures = mutableListOf<Failure>()
            val coordinator = StandardFloatingWindowLaunchCoordinator(gateway, failures::add)

            assertFalse(coordinator.launch(TARGET))
            assertEquals(listOf(Failure.WINDOW_STATE_UNAVAILABLE), failures)
            assertEquals(0, gateway.homeStarts)
            assertTrue(gateway.targetStarts.isEmpty())
        }
    }

    @Test
    fun `observer and timeout setup failures clear the request for retry`() {
        val observationGateway = FakeGateway(TargetKind.STANDARD_FLOATING_WINDOW, windowMode = 2).apply {
            observationStartsSuccessfully = false
        }
        val observationFailures = mutableListOf<Failure>()
        val observationCoordinator = StandardFloatingWindowLaunchCoordinator(
            observationGateway,
            observationFailures::add,
        )

        assertFalse(observationCoordinator.launch(TARGET))
        assertEquals(listOf(Failure.WINDOW_STATE_UNAVAILABLE), observationFailures)
        assertFalse(observationCoordinator.hasPendingLaunch())
        assertEquals(0, observationGateway.homeStarts)

        val timeoutGateway = FakeGateway(TargetKind.STANDARD_FLOATING_WINDOW, windowMode = 2).apply {
            timeoutSchedulesSuccessfully = false
        }
        val timeoutFailures = mutableListOf<Failure>()
        val timeoutCoordinator = StandardFloatingWindowLaunchCoordinator(timeoutGateway, timeoutFailures::add)

        assertFalse(timeoutCoordinator.launch(TARGET))
        assertEquals(listOf(Failure.WINDOW_STATE_UNAVAILABLE), timeoutFailures)
        assertEquals(1, timeoutGateway.observationCancels)
        assertFalse(timeoutCoordinator.hasPendingLaunch())
        assertEquals(0, timeoutGateway.homeStarts)
    }

    @Test
    fun `unexpected mode while waiting releases observer and timeout`() {
        val gateway = FakeGateway(TargetKind.STANDARD_FLOATING_WINDOW, windowMode = 2)
        val failures = mutableListOf<Failure>()
        val coordinator = StandardFloatingWindowLaunchCoordinator(gateway, failures::add)
        assertTrue(coordinator.launch(TARGET))

        gateway.windowMode = 4
        gateway.notifyWindowModeChanged()

        assertEquals(listOf(Failure.WINDOW_STATE_UNAVAILABLE), failures)
        assertEquals(1, gateway.observationCancels)
        assertEquals(1, gateway.timeoutCancels)
        assertFalse(coordinator.hasPendingLaunch())
        assertTrue(gateway.targetStarts.isEmpty())
    }

    private class FakeGateway(
        var targetKind: TargetKind,
        windowMode: Int,
    ) : StandardFloatingWindowLaunchCoordinator.Gateway {
        var modeRead: WindowModeRead = WindowModeRead.Value(windowMode)
        var windowMode: Int
            get() = (modeRead as WindowModeRead.Value).value
            set(value) {
                modeRead = WindowModeRead.Value(value)
            }
        var homeSucceeds = true
        var onHomeStarted: (() -> Unit)? = null
        var observationStartsSuccessfully = true
        var timeoutSchedulesSuccessfully = true
        var targetSucceeds = true
        var homeStarts = 0
        val targetStarts = mutableListOf<String>()
        var observationStarts = 0
        var observationCancels = 0
        var timeoutCancels = 0
        private var observationCallback: (() -> Unit)? = null
        private var observationActive = false
        private var timeoutCallback: (() -> Unit)? = null
        private var timeoutActive = false

        override fun targetKind(componentName: String): TargetKind = targetKind

        override fun readWindowMode(): WindowModeRead = modeRead

        override fun startHome(): Boolean {
            homeStarts += 1
            onHomeStarted?.invoke()
            return homeSucceeds
        }

        override fun startTarget(componentName: String): Boolean {
            targetStarts += componentName
            return targetSucceeds
        }

        override fun createWindowModeObservation(
            onChanged: () -> Unit,
        ): StandardFloatingWindowLaunchCoordinator.WindowModeObservation =
            object : StandardFloatingWindowLaunchCoordinator.WindowModeObservation {
                override fun start(): Boolean {
                    observationStarts += 1
                    if (!observationStartsSuccessfully) return false
                    observationCallback = onChanged
                    observationActive = true
                    return true
                }

                override fun cancel() {
                    if (!observationActive) return
                    observationActive = false
                    observationCancels += 1
                }
            }

        override fun scheduleTimeout(
            delayMillis: Long,
            onTimeout: () -> Unit,
        ): StandardFloatingWindowLaunchCoordinator.Cancellable? {
            assertEquals(StandardFloatingWindowLaunchCoordinator.TRANSITION_TIMEOUT_MS, delayMillis)
            if (!timeoutSchedulesSuccessfully) return null
            timeoutCallback = onTimeout
            timeoutActive = true
            return object : StandardFloatingWindowLaunchCoordinator.Cancellable {
                override fun cancel() {
                    if (!timeoutActive) return
                    timeoutActive = false
                    timeoutCancels += 1
                }
            }
        }

        fun notifyWindowModeChanged() {
            if (observationActive) observationCallback?.invoke()
        }

        fun fireTimeout() {
            if (timeoutActive) timeoutCallback?.invoke()
        }
    }

    private companion object {
        const val TARGET = "example/Target"
        const val OTHER_TARGET = "example/Other"
    }
}
