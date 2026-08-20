package com.ninepointnine.desktop.system

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager

object FileManagerContract {
    const val PACKAGE_NAME = "org.fossify.filemanager.debug"
    const val MAIN_ACTIVITY_NAME = "org.fossify.filemanager.activities.MainActivity"
    const val OPEN_REMOVABLE_STORAGE_ACTION =
        "org.fossify.filemanager.action.OPEN_REMOVABLE_STORAGE"
    const val CAR_INTEGRATION_VERSION_METADATA =
        "org.fossify.filemanager.CAR_INTEGRATION_VERSION"

    const val MINIMUM_CAR_INTEGRATION_VERSION = 1

    val mainComponent: ComponentName
        get() = ComponentName(PACKAGE_NAME, MAIN_ACTIVITY_NAME)

    fun createMainIntent(): Intent =
        Intent()
            .setComponent(mainComponent)
            .addFlags(FILE_MANAGER_LAUNCH_FLAGS)

    fun createOpenRemovableStorageIntent(): Intent =
        Intent(OPEN_REMOVABLE_STORAGE_ACTION)
            .setComponent(mainComponent)
            .addFlags(FILE_MANAGER_LAUNCH_FLAGS)

    fun supportsRemovableStorageEntry(packageManager: PackageManager): Boolean {
        val activityInfo = try {
            packageManager.getActivityInfo(mainComponent, PackageManager.GET_META_DATA)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        } catch (_: SecurityException) {
            return false
        } catch (_: RuntimeException) {
            return false
        }
        val contractVersion = try {
            activityInfo.metaData?.getInt(CAR_INTEGRATION_VERSION_METADATA, 0) ?: 0
        } catch (_: RuntimeException) {
            return false
        }
        return activityInfo.exported &&
            activityInfo.enabled &&
            activityInfo.applicationInfo.enabled &&
            contractVersion >= MINIMUM_CAR_INTEGRATION_VERSION
    }

    private val FILE_MANAGER_LAUNCH_FLAGS =
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
}
