package com.ninepointnine.desktop.overlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder

class SurfaceOccupancyLeaseClient(
    context: Context,
) {
    private val coordinator = SurfaceOccupancyLeaseCoordinator(
        AndroidLeaseGateway(context.applicationContext),
    )

    fun setOccupied(occupied: Boolean) {
        coordinator.setOccupied(occupied)
    }

    fun release() {
        coordinator.release()
    }

    private class AndroidLeaseGateway(
        private val context: Context,
    ) : SurfaceOccupancyLeaseCoordinator.Gateway {
        override fun discoverProviderIds(): List<String> {
            val resolveInfos = try {
                context.packageManager.queryIntentServices(
                Intent(ACTION_ACQUIRE_OCCUPANCY_LEASE),
                PackageManager.GET_META_DATA,
                )
            } catch (_: RuntimeException) {
                emptyList()
            }
            return resolveInfos.mapNotNull { resolveInfo ->
                try {
                    val serviceInfo = resolveInfo.serviceInfo ?: return@mapNotNull null
                    if (!serviceInfo.exported ||
                        serviceInfo.protocolVersion() != PROTOCOL_VERSION
                    ) {
                        return@mapNotNull null
                    }
                    ComponentName(serviceInfo.packageName, serviceInfo.name).flattenToString()
                } catch (_: RuntimeException) {
                    null
                }
            }
        }

        override fun acquire(providerId: String): SurfaceOccupancyLeaseCoordinator.Lease? {
            val component = ComponentName.unflattenFromString(providerId) ?: return null
            return BoundServiceLease(context, component).takeIf { it.start() }
        }

        private fun android.content.pm.ServiceInfo.protocolVersion(): Int {
            val serviceVersion = try {
                metaData?.getInt(METADATA_PROTOCOL_VERSION, -1) ?: -1
            } catch (_: RuntimeException) {
                return -1
            }
            if (serviceVersion >= 0) return serviceVersion
            return try {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.GET_META_DATA,
                ).metaData?.getInt(METADATA_PROTOCOL_VERSION, -1) ?: -1
            } catch (_: PackageManager.NameNotFoundException) {
                -1
            } catch (_: SecurityException) {
                -1
            } catch (_: RuntimeException) {
                -1
            }
        }
    }

    private class BoundServiceLease(
        private val context: Context,
        private val component: ComponentName,
    ) : ServiceConnection, SurfaceOccupancyLeaseCoordinator.Lease {
        private var active = false
        private var bound = false

        fun start(): Boolean {
            if (active) return bound
            active = true
            bound = bind()
            if (!bound) active = false
            return bound
        }

        override fun release() {
            if (!active && !bound) return
            active = false
            unbind()
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder?) = Unit

        override fun onServiceDisconnected(name: ComponentName) = Unit

        override fun onBindingDied(name: ComponentName) {
            if (!active) return
            unbind()
            bound = bind()
            if (!bound) active = false
        }

        override fun onNullBinding(name: ComponentName) {
            active = false
            unbind()
        }

        private fun bind(): Boolean = try {
            context.bindService(
                Intent(ACTION_ACQUIRE_OCCUPANCY_LEASE).setComponent(component),
                this,
                Context.BIND_AUTO_CREATE,
            )
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }

        private fun unbind() {
            if (!bound) return
            bound = false
            try {
                context.unbindService(this)
            } catch (_: IllegalArgumentException) {
                // The remote side already discarded this binding.
            } catch (_: RuntimeException) {
                // Local lease state is released even if the provider disappeared.
            }
        }
    }

    companion object {
        const val ACTION_ACQUIRE_OCCUPANCY_LEASE =
            "com.tcrrry.icar.surface.action.ACQUIRE_OCCUPANCY_LEASE"
        const val METADATA_PROTOCOL_VERSION =
            "com.tcrrry.icar.surface.OCCUPANCY_PROTOCOL_VERSION"
        const val PROTOCOL_VERSION = 1
    }
}
