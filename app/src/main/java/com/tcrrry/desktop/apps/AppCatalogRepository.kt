package com.tcrrry.desktop.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.tcrrry.desktop.model.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppCatalogRepository(
    private val context: Context,
) {
    suspend fun load(): List<AppEntry> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = packageManager.queryIntentActivities(launcherIntent, 0)
        AppCatalogFilter.toEntries(
            candidates = candidates.mapNotNull { resolveInfo ->
                resolveInfo.toCandidate(packageManager)
            },
            selfPackageName = context.packageName,
            launcherComponentForPackage = { packageName ->
                packageManager.getLaunchIntentForPackage(packageName)?.component?.let { component ->
                    canonicalComponentName(component.packageName, component.className)
                }
            },
            iconSizePx = context.resources.getDimensionPixelSize(com.tcrrry.desktop.R.dimen.drawer_app_icon_size),
            densityDpi = context.resources.displayMetrics.densityDpi,
        )
    }

    private fun ResolveInfo.toCandidate(packageManager: PackageManager): CatalogCandidate? {
        val activityInfo = activityInfo ?: return null
        val applicationInfo = activityInfo.applicationInfo ?: return null
        val packageInfo = try {
            packageManager.getPackageInfo(activityInfo.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return CatalogCandidate(
            packageName = activityInfo.packageName,
            className = activityInfo.name,
            label = loadLabel(packageManager).toString(),
            applicationLabel = applicationInfo.loadLabel(packageManager).toString(),
            firstInstallTime = packageInfo.firstInstallTime,
            lastUpdateTime = packageInfo.lastUpdateTime,
            isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            isUpdatedSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
            applicationEnabled = applicationInfo.enabled,
            activityEnabled = activityInfo.enabled,
            priority = priority,
            preferredOrder = preferredOrder,
        )
    }
}
