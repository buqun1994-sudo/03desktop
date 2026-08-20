package com.ninepointnine.desktop.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ninepointnine.desktop.overlay.OverlayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> if (Settings.canDrawOverlays(context)) {
                try {
                    ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
                } catch (_: IllegalStateException) {
                    // Android can temporarily reject background starts; START_STICKY remains the allowed recovery path.
                } catch (_: SecurityException) {
                    // No alternate or privileged recovery path is allowed.
                }
            }
        }
    }
}
