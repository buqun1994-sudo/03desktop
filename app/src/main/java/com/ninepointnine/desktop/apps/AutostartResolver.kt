package com.ninepointnine.desktop.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class AutostartTarget(
    val packageName: String,
    val component: ComponentName,
)

sealed interface AutostartTargetResult {
    data class Supported(val target: AutostartTarget) : AutostartTargetResult
    data class Unsupported(val reason: String) : AutostartTargetResult
}

class AutostartResolver(private val context: Context) {
    fun resolve(packageName: String): AutostartTargetResult {
        if (!PACKAGE_NAME_PATTERN.matches(packageName)) {
            return AutostartTargetResult.Unsupported(AutostartBridgeContract.REASON_INVALID_PACKAGE)
        }
        if (!AutostartProxyPolicy.canProxyLaunch(packageName)) {
            return AutostartTargetResult.Unsupported(
                AutostartBridgeContract.REASON_APPLICATION_MANAGES_OWN_AUTOSTART,
            )
        }
        return try {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName)
            // Launcher filters commonly declare MAIN/LAUNCHER without DEFAULT.
            val activity = context.packageManager.queryIntentActivities(intent, 0)
                .asSequence()
                .mapNotNull { it.activityInfo }
                .firstOrNull { it.exported && it.enabled }
                ?: return AutostartTargetResult.Unsupported(AutostartBridgeContract.REASON_NO_LAUNCHER)
            AutostartTargetResult.Supported(
                AutostartTarget(packageName, ComponentName(activity.packageName, activity.name)),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            AutostartTargetResult.Unsupported(AutostartBridgeContract.REASON_PACKAGE_NOT_FOUND)
        } catch (_: SecurityException) {
            AutostartTargetResult.Unsupported(AutostartBridgeContract.REASON_PACKAGE_NOT_FOUND)
        } catch (_: RuntimeException) {
            AutostartTargetResult.Unsupported(AutostartBridgeContract.REASON_PACKAGE_NOT_FOUND)
        }
    }

    private companion object {
        val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
