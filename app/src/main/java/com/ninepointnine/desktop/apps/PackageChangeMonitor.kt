package com.ninepointnine.desktop.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

class PackageChangeMonitor(
    private val context: Context,
    private val onDirty: () -> Unit,
) {
    private var registered = false
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onDirty()
        }
    }
    private val externalAppsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onDirty()
        }
    }

    fun register() {
        if (registered) return
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        context.registerReceiver(packageReceiver, packageFilter)
        try {
            context.registerReceiver(
                externalAppsReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
                    addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
                },
            )
            registered = true
        } catch (exception: RuntimeException) {
            runCatching { context.unregisterReceiver(packageReceiver) }
            throw exception
        }
    }

    fun unregister() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(packageReceiver) }
        runCatching { context.unregisterReceiver(externalAppsReceiver) }
    }
}
