package com.tcrrry.desktop

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.tcrrry.desktop.overlay.OverlayService

class MainActivity : Activity() {
    private var overlayPermissionRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<android.view.View>(R.id.request_overlay_permission).setOnClickListener {
            requestOverlayPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
            finish()
            return
        }

        if (!overlayPermissionRequested) {
            overlayPermissionRequested = true
            requestOverlayPermission()
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return

        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        if (intent.resolveActivity(packageManager) == null) return
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            // The explanatory screen remains available if this firmware removes the settings handler.
        } catch (_: SecurityException) {
            // The explanatory screen remains available if settings access is restricted.
        }
    }
}
