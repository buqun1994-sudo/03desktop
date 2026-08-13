package com.tcrrry.desktop.overlay

import android.app.Notification
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import android.os.Handler
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.tcrrry.desktop.R
import com.tcrrry.desktop.apps.PackageChangeMonitor

class OverlayService : Service() {
    private var drawerWindowController: DrawerWindowController? = null
    private var panelController: DrawerPanelController? = null
    private var surfaceOccupancyLeaseClient: SurfaceOccupancyLeaseClient? = null
    private var packageChangeMonitor: PackageChangeMonitor? = null
    private var overlayOpListener: AppOpsManager.OnOpChangedListener? = null
    private val mainHandler by lazy { Handler(mainLooper) }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        surfaceOccupancyLeaseClient = SurfaceOccupancyLeaseClient(this)
        packageChangeMonitor = PackageChangeMonitor(this) {
            panelController?.onPackageDirty()
        }.also { it.register() }
        val appOps = getSystemService(AppOpsManager::class.java)
        overlayOpListener = AppOpsManager.OnOpChangedListener { operation, changedPackage ->
            mainHandler.post {
                if (operation == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW &&
                    changedPackage == packageName &&
                    !Settings.canDrawOverlays(this)
                ) {
                    releaseAndStop()
                }
            }
        }.also { listener ->
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, listener)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            releaseAndStop()
            return START_NOT_STICKY
        }

        try {
            if (drawerWindowController == null) {
                panelController = DrawerPanelController(this) { afterClosed ->
                    drawerWindowController?.close(afterClosed)
                }
                drawerWindowController = DrawerWindowController(
                    context = this,
                    panelFactory = { requireNotNull(panelController).createPanelView() },
                    onPanelRemoved = { panelController?.onPanelRemoved() },
                    onDesktopSurfaceOccupancyChanged = { occupied ->
                        surfaceOccupancyLeaseClient?.setOccupied(occupied)
                    },
                    onWindowFailure = { releaseAndStop() },
                ).also { it.showClosedTrigger() }
            }
        } catch (_: RuntimeException) {
            releaseAndStop()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        drawerWindowController?.release()
        drawerWindowController = null
        surfaceOccupancyLeaseClient?.release()
        surfaceOccupancyLeaseClient = null
        panelController = null
        packageChangeMonitor?.unregister()
        packageChangeMonitor = null
        overlayOpListener?.let { getSystemService(AppOpsManager::class.java).stopWatchingMode(it) }
        overlayOpListener = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTrimMemory(level: Int) {
        panelController?.onTrimMemory(level)
        super.onTrimMemory(level)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        drawerWindowController?.onConfigurationChanged()
    }

    fun closeDrawer(afterClosed: (() -> Unit)? = null) {
        drawerWindowController?.close(afterClosed)
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.overlay_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            },
        )
        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_message))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun releaseAndStop() {
        drawerWindowController?.release()
        drawerWindowController = null
        surfaceOccupancyLeaseClient?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private companion object {
        const val NOTIFICATION_CHANNEL_ID = "overlay_service"
        const val NOTIFICATION_ID = 1001
    }
}
