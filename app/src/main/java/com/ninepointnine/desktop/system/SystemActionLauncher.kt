package com.ninepointnine.desktop.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.ninepointnine.desktop.R
import com.ninepointnine.desktop.install.ApkInstallActivity
import com.ninepointnine.desktop.model.AppEntry

class SystemActionLauncher(
    private val context: Context,
) {
    private val standardFloatingWindowGateway = AndroidStandardFloatingWindowGateway(context)
    private val standardFloatingWindowCoordinator = StandardFloatingWindowLaunchCoordinator(
        gateway = standardFloatingWindowGateway,
        onFailure = { failure ->
            unavailable(
                when (failure) {
                    StandardFloatingWindowLaunchCoordinator.Failure.TARGET_UNAVAILABLE ->
                        R.string.app_unavailable

                    StandardFloatingWindowLaunchCoordinator.Failure.SYSTEM_ACTION_UNAVAILABLE ->
                        R.string.system_action_unavailable

                    StandardFloatingWindowLaunchCoordinator.Failure.WINDOW_STATE_UNAVAILABLE,
                    StandardFloatingWindowLaunchCoordinator.Failure.HOME_UNAVAILABLE,
                    StandardFloatingWindowLaunchCoordinator.Failure.TRANSITION_TIMEOUT,
                    -> R.string.standard_floating_window_switch_unavailable
                },
            )
        },
    )

    fun launchApp(entry: AppEntry): Boolean {
        val component = ComponentName.unflattenFromString(entry.componentName)
        if (component == null) {
            return unavailable(R.string.app_unavailable)
        }
        if (component == FileManagerContract.mainComponent) {
            return launchExclusiveWindow(FileManagerContract.createMainIntent())
        }
        return standardFloatingWindowCoordinator.launch(entry.componentName)
    }

    fun release() {
        standardFloatingWindowCoordinator.cancel()
    }

    fun requestUninstall(entry: AppEntry): Boolean {
        if (!entry.canRequestUninstall) return false
        return launchExclusiveWindow(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${entry.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun launchApkInstaller(): Boolean = launchExclusiveWindow(
        Intent(context, ApkInstallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    fun isExternalStorageEnhancementAvailable(): Boolean =
        FileManagerContract.supportsRemovableStorageEntry(context.packageManager)

    fun launchExternalStorage(onLaunched: () -> Unit): Boolean {
        if (!isExternalStorageEnhancementAvailable()) {
            return unavailable(R.string.file_manager_unavailable)
        }
        val target = standardFloatingWindowGateway.prepareExplicitTarget(
            FileManagerContract.createOpenRemovableStorageIntent(),
        )
            ?: return unavailable(R.string.file_manager_unavailable)
        return standardFloatingWindowCoordinator.launchExclusive(
            targetFailure = StandardFloatingWindowLaunchCoordinator.Failure.TARGET_UNAVAILABLE,
            startTarget = {
                standardFloatingWindowGateway.startExplicitTarget(target).also { started ->
                    if (started) onLaunched()
                }
            },
        )
    }

    private fun launchExclusiveWindow(intent: Intent): Boolean {
        val target = standardFloatingWindowGateway.prepareExplicitTarget(intent)
            ?: return unavailable(R.string.system_action_unavailable)
        return standardFloatingWindowCoordinator.launchExclusive(
            targetFailure = StandardFloatingWindowLaunchCoordinator.Failure.SYSTEM_ACTION_UNAVAILABLE,
            startTarget = { standardFloatingWindowGateway.startExplicitTarget(target) },
        )
    }

    private fun unavailable(text: Int): Boolean {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        return false
    }
}
