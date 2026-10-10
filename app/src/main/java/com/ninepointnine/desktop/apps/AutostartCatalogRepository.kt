package com.ninepointnine.desktop.apps

import android.content.Context
import android.content.pm.PackageManager
import com.ninepointnine.desktop.R
import com.ninepointnine.desktop.model.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Configured proxy-autostart apps, independent of desktop eligibility and visibility. */
class AutostartCatalogRepository(
    private val context: Context,
    private val store: AutostartStore = AutostartStore(context),
    private val resolver: AutostartResolver = AutostartResolver(context),
) {
    suspend fun load(): List<AppEntry> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val iconSizePx = context.resources.getDimensionPixelSize(R.dimen.drawer_app_icon_size)
        val densityDpi = context.resources.displayMetrics.densityDpi
        store.configuredPackages().mapNotNull { packageName ->
            ensureActive()
            val resolved = resolver.resolve(packageName) as? AutostartTargetResult.Supported
                ?: return@mapNotNull null
            try {
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                val applicationInfo = packageInfo.applicationInfo ?: return@mapNotNull null
                val component = resolved.target.component
                AppEntry(
                    packageName = packageName,
                    componentName = canonicalComponentName(component.packageName, component.className),
                    label = applicationInfo.loadLabel(packageManager).toString().ifBlank { packageName },
                    firstInstallTime = packageInfo.firstInstallTime,
                    lastUpdateTime = packageInfo.lastUpdateTime,
                    isSelf = packageName == context.packageName,
                    iconKey = "$packageName:${packageInfo.lastUpdateTime}:$iconSizePx:$densityDpi",
                )
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: RuntimeException) {
                // An uninstall/update can race a supported launcher resolution.
                null
            }
        }.sortedWith(compareBy<AppEntry>(AppEntry::label).thenBy(AppEntry::packageName))
    }
}
