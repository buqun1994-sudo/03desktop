package com.tcrrry.desktop.overlay

class SurfaceOccupancyLeaseCoordinator(
    private val gateway: Gateway,
) {
    interface Lease {
        fun release()
    }

    interface Gateway {
        fun discoverProviderIds(): List<String>
        fun acquire(providerId: String): Lease?
    }

    private val leases = linkedMapOf<String, Lease>()
    private var occupied = false

    fun setOccupied(nextOccupied: Boolean) {
        if (occupied == nextOccupied) return
        occupied = nextOccupied
        if (nextOccupied) acquireAll() else releaseAll()
    }

    fun release() {
        occupied = false
        releaseAll()
    }

    private fun acquireAll() {
        val providerIds = try {
            gateway.discoverProviderIds().distinct()
        } catch (_: RuntimeException) {
            emptyList()
        }
        providerIds.forEach { providerId ->
            if (providerId in leases) return@forEach
            val lease = try {
                gateway.acquire(providerId)
            } catch (_: RuntimeException) {
                null
            }
            if (lease != null) leases[providerId] = lease
        }
    }

    private fun releaseAll() {
        val activeLeases = leases.values.toList()
        leases.clear()
        activeLeases.forEach { lease ->
            try {
                lease.release()
            } catch (_: RuntimeException) {
                // One provider cannot prevent the remaining leases from being released.
            }
        }
    }
}

class DrawerSurfaceOccupancyTracker(
    private val onChanged: (Boolean) -> Unit,
) {
    private var occupied = false

    fun onVisibleMotionStarted() {
        update(true)
    }

    fun onParked(openDistancePx: Int) {
        if (openDistancePx == 0) update(false)
    }

    fun release() {
        update(false)
    }

    private fun update(nextOccupied: Boolean) {
        if (occupied == nextOccupied) return
        occupied = nextOccupied
        onChanged(nextOccupied)
    }
}
