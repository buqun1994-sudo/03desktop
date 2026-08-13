package com.tcrrry.desktop.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceOccupancyLeaseCoordinatorTest {
    @Test
    fun `no providers leaves drawer behavior available`() {
        val gateway = FakeGateway(emptyList())
        val coordinator = SurfaceOccupancyLeaseCoordinator(gateway)

        coordinator.setOccupied(true)
        coordinator.setOccupied(false)
        coordinator.release()

        assertTrue(gateway.acquireCalls.isEmpty())
    }

    @Test
    fun `acquires each provider once and releases only after occupancy ends`() {
        val gateway = FakeGateway(listOf("one", "one", "two"))
        val coordinator = SurfaceOccupancyLeaseCoordinator(gateway)

        coordinator.setOccupied(true)
        coordinator.setOccupied(true)

        assertEquals(listOf("one", "two"), gateway.acquireCalls)
        assertTrue(gateway.releaseCalls.isEmpty())

        coordinator.setOccupied(false)
        coordinator.setOccupied(false)

        assertEquals(listOf("one", "two"), gateway.releaseCalls)
    }

    @Test
    fun `one provider failure cannot block another provider or release`() {
        val gateway = FakeGateway(listOf("broken-acquire", "broken-release", "healthy"))
        val coordinator = SurfaceOccupancyLeaseCoordinator(gateway)

        coordinator.setOccupied(true)
        coordinator.release()

        assertEquals(listOf("broken-acquire", "broken-release", "healthy"), gateway.acquireCalls)
        assertEquals(listOf("broken-release", "healthy"), gateway.releaseCalls)
    }

    @Test
    fun `valid motion holds through open close interruption and releases only at parked zero`() {
        val changes = mutableListOf<Boolean>()
        val tracker = DrawerSurfaceOccupancyTracker(changes::add)

        // Touch jitter never reaches the semantic visible-motion boundary.
        assertTrue(changes.isEmpty())

        tracker.onVisibleMotionStarted()
        tracker.onVisibleMotionStarted()
        tracker.onParked(610)
        tracker.onParked(300)
        tracker.onVisibleMotionStarted()

        assertEquals(listOf(true), changes)

        tracker.onParked(0)
        tracker.onParked(0)

        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `tracker drives the lease without reacting before valid visible motion`() {
        val gateway = FakeGateway(listOf("provider"))
        val coordinator = SurfaceOccupancyLeaseCoordinator(gateway)
        val tracker = DrawerSurfaceOccupancyTracker(coordinator::setOccupied)

        // ACTION_DOWN, touch jitter and rejected direction never call the valid-motion boundary.
        assertTrue(gateway.acquireCalls.isEmpty())

        tracker.onVisibleMotionStarted()
        tracker.onParked(610)
        tracker.onVisibleMotionStarted()

        assertEquals(listOf("provider"), gateway.acquireCalls)
        assertTrue(gateway.releaseCalls.isEmpty())

        tracker.onParked(0)

        assertEquals(listOf("provider"), gateway.releaseCalls)
    }

    @Test
    fun `service release is idempotent and clears an interrupted motion`() {
        val changes = mutableListOf<Boolean>()
        val tracker = DrawerSurfaceOccupancyTracker(changes::add)

        tracker.onVisibleMotionStarted()
        tracker.release()
        tracker.release()

        assertEquals(listOf(true, false), changes)
    }

    private class FakeGateway(
        private val providerIds: List<String>,
    ) : SurfaceOccupancyLeaseCoordinator.Gateway {
        val acquireCalls = mutableListOf<String>()
        val releaseCalls = mutableListOf<String>()

        override fun discoverProviderIds(): List<String> = providerIds

        override fun acquire(providerId: String): SurfaceOccupancyLeaseCoordinator.Lease? {
            acquireCalls += providerId
            if (providerId == "broken-acquire") throw IllegalStateException("broken acquire")
            return object : SurfaceOccupancyLeaseCoordinator.Lease {
                override fun release() {
                    releaseCalls += providerId
                    if (providerId == "broken-release") throw IllegalStateException("broken release")
                }
            }
        }
    }
}
