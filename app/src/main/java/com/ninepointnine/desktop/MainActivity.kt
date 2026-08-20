package com.ninepointnine.desktop

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.ninepointnine.desktop.overlay.OverlayService

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        } catch (_: IllegalStateException) {
            // Android can temporarily reject a foreground-service start.
        } catch (_: SecurityException) {
            // The service keeps its own permission boundary and stops safely when unavailable.
        }
        finish()
    }
}
