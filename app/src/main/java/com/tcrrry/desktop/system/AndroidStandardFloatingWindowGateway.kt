package com.tcrrry.desktop.system

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings

class AndroidStandardFloatingWindowGateway(
    context: Context,
) : StandardFloatingWindowLaunchCoordinator.Gateway {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val contentResolver = appContext.contentResolver
    private val mainHandler = Handler(appContext.mainLooper)

    override fun targetKind(componentName: String): StandardFloatingWindowLaunchCoordinator.TargetKind {
        val component = ComponentName.unflattenFromString(componentName)
            ?: return StandardFloatingWindowLaunchCoordinator.TargetKind.UNAVAILABLE
        val activityInfo = try {
            packageManager.getActivityInfo(component, PackageManager.GET_META_DATA)
        } catch (_: PackageManager.NameNotFoundException) {
            return StandardFloatingWindowLaunchCoordinator.TargetKind.UNAVAILABLE
        } catch (_: SecurityException) {
            return StandardFloatingWindowLaunchCoordinator.TargetKind.UNAVAILABLE
        } catch (_: RuntimeException) {
            return StandardFloatingWindowLaunchCoordinator.TargetKind.UNAVAILABLE
        }
        val isStandardFloatingWindow = try {
            activityInfo.metaData?.getBoolean(
                StandardFloatingWindowLaunchCoordinator.STANDARD_FLOATING_WINDOW_METADATA,
                false,
            ) == true
        } catch (_: RuntimeException) {
            return StandardFloatingWindowLaunchCoordinator.TargetKind.UNAVAILABLE
        }
        return if (isStandardFloatingWindow) {
            StandardFloatingWindowLaunchCoordinator.TargetKind.STANDARD_FLOATING_WINDOW
        } else {
            StandardFloatingWindowLaunchCoordinator.TargetKind.REGULAR
        }
    }

    override fun readWindowMode(): StandardFloatingWindowLaunchCoordinator.WindowModeRead = try {
        StandardFloatingWindowLaunchCoordinator.WindowModeRead.Value(
            Settings.Secure.getInt(
                contentResolver,
                StandardFloatingWindowLaunchCoordinator.WINDOW_MODE_SETTING,
            ),
        )
    } catch (_: Settings.SettingNotFoundException) {
        StandardFloatingWindowLaunchCoordinator.WindowModeRead.Unavailable
    } catch (_: SecurityException) {
        StandardFloatingWindowLaunchCoordinator.WindowModeRead.Unavailable
    } catch (_: RuntimeException) {
        StandardFloatingWindowLaunchCoordinator.WindowModeRead.Unavailable
    }

    override fun startHome(): Boolean = startActivity(
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    override fun startTarget(componentName: String): Boolean {
        val component = ComponentName.unflattenFromString(componentName) ?: return false
        return startActivity(
            Intent()
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun prepareExplicitTarget(intent: Intent): Intent? {
        val component = try {
            intent.resolveActivity(packageManager)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        return Intent(intent)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun startExplicitTarget(intent: Intent): Boolean = startActivity(intent)

    override fun createWindowModeObservation(
        onChanged: () -> Unit,
    ): StandardFloatingWindowLaunchCoordinator.WindowModeObservation =
        SecureWindowModeObservation(contentResolver, mainHandler, onChanged)

    override fun scheduleTimeout(
        delayMillis: Long,
        onTimeout: () -> Unit,
    ): StandardFloatingWindowLaunchCoordinator.Cancellable? {
        val callback = Runnable(onTimeout)
        if (!mainHandler.postDelayed(callback, delayMillis)) return null
        return object : StandardFloatingWindowLaunchCoordinator.Cancellable {
            private var active = true

            override fun cancel() {
                if (!active) return
                active = false
                mainHandler.removeCallbacks(callback)
            }
        }
    }

    private fun startActivity(intent: Intent): Boolean {
        if (intent.resolveActivity(packageManager) == null) return false
        return try {
            appContext.startActivity(intent)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: android.content.ActivityNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private class SecureWindowModeObservation(
        private val contentResolver: ContentResolver,
        handler: Handler,
        private val onChanged: () -> Unit,
    ) : ContentObserver(handler), StandardFloatingWindowLaunchCoordinator.WindowModeObservation {
        private var registered = false

        override fun start(): Boolean {
            if (registered) return true
            return try {
                contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(StandardFloatingWindowLaunchCoordinator.WINDOW_MODE_SETTING),
                    false,
                    this,
                )
                registered = true
                true
            } catch (_: SecurityException) {
                false
            } catch (_: RuntimeException) {
                false
            }
        }

        override fun onChange(selfChange: Boolean) {
            onChanged()
        }

        override fun cancel() {
            if (!registered) return
            registered = false
            try {
                contentResolver.unregisterContentObserver(this)
            } catch (_: RuntimeException) {
                // Registration is already gone; local state is still fully released.
            }
        }
    }
}
