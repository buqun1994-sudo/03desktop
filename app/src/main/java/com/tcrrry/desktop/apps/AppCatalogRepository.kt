package com.tcrrry.desktop.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.tcrrry.desktop.model.AppEntry
import com.tcrrry.desktop.system.FileManagerContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class AppCatalogRepository(
    private val context: Context,
) {
    suspend fun load(): List<AppEntry> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val deviceProfile = OemDeviceProfile(
            model = Build.MODEL,
            device = Build.DEVICE,
            sdkInt = Build.VERSION.SDK_INT,
            fingerprint = Build.FINGERPRINT,
        )
        val candidates = packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                resolveInfo.toCandidate(packageManager, deviceProfile)
            }
            .toMutableList()
        fileManagerCandidate(packageManager, deviceProfile)?.let(candidates::add)
        AppCatalogFilter.toEntries(
            candidates = candidates,
            selfPackageName = context.packageName,
            launcherComponentForPackage = { packageName ->
                if (packageName == FileManagerContract.PACKAGE_NAME) {
                    canonicalComponentName(
                        FileManagerContract.PACKAGE_NAME,
                        FileManagerContract.MAIN_ACTIVITY_NAME,
                    )
                } else {
                    packageManager.getLaunchIntentForPackage(packageName)?.component?.let { component ->
                        canonicalComponentName(component.packageName, component.className)
                    }
                }
            },
            iconSizePx = context.resources.getDimensionPixelSize(com.tcrrry.desktop.R.dimen.drawer_app_icon_size),
            densityDpi = context.resources.displayMetrics.densityDpi,
        )
    }

    private fun ResolveInfo.toCandidate(
        packageManager: PackageManager,
        deviceProfile: OemDeviceProfile,
    ): CatalogCandidate? {
        val activityInfo = activityInfo ?: return null
        return activityInfo.toCandidate(
            packageManager = packageManager,
            deviceProfile = deviceProfile,
            label = loadLabel(packageManager).toString(),
            priority = priority,
            preferredOrder = preferredOrder,
        )
    }

    private fun fileManagerCandidate(
        packageManager: PackageManager,
        deviceProfile: OemDeviceProfile,
    ): CatalogCandidate? {
        val activityInfo = try {
            packageManager.getActivityInfo(FileManagerContract.mainComponent, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        } catch (_: SecurityException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }
        if (!activityInfo.exported) return null
        return activityInfo.toCandidate(
            packageManager = packageManager,
            deviceProfile = deviceProfile,
            label = activityInfo.applicationInfo.loadLabel(packageManager).toString(),
            priority = 0,
            preferredOrder = 0,
        )
    }

    private fun ActivityInfo.toCandidate(
        packageManager: PackageManager,
        deviceProfile: OemDeviceProfile,
        label: String,
        priority: Int,
        preferredOrder: Int,
    ): CatalogCandidate? {
        val activityInfo = this
        val applicationInfo = activityInfo.applicationInfo ?: return null
        val requiresOemEvidence = OemIntegratedAppPolicy.requiresEvidence(
            deviceProfile = deviceProfile,
            packageName = activityInfo.packageName,
        )
        val packageInfoFlags = if (requiresOemEvidence) {
            PackageManager.GET_PROVIDERS or PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            0
        }
        val packageInfo = try {
            packageManager.getPackageInfo(activityInfo.packageName, packageInfoFlags)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return CatalogCandidate(
            packageName = activityInfo.packageName,
            className = activityInfo.name,
            label = label,
            applicationLabel = applicationInfo.loadLabel(packageManager).toString(),
            firstInstallTime = packageInfo.firstInstallTime,
            lastUpdateTime = packageInfo.lastUpdateTime,
            isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            isUpdatedSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
            isOemIntegratedApp = requiresOemEvidence && OemIntegratedAppPolicy.shouldExclude(
                deviceProfile = deviceProfile,
                evidence = packageInfo.toOemAppEvidence(activityInfo.packageName),
            ),
            applicationEnabled = applicationInfo.enabled,
            activityEnabled = activityInfo.enabled,
            priority = priority,
            preferredOrder = preferredOrder,
        )
    }

    private fun PackageInfo.toOemAppEvidence(packageName: String): OemAppEvidence = OemAppEvidence(
        packageName = packageName,
        signingCertificateSha256Digests = signingCertificateSha256Digests(),
        providerClassNames = providers.orEmpty().mapNotNull { providerInfo -> providerInfo.name }.toSet(),
    )

    private fun PackageInfo.signingCertificateSha256Digests(): Set<String> {
        val signingInfo = signingInfo ?: return emptySet()
        val signatures = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }.toSet()
    }
}
