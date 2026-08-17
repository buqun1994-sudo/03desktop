package com.tcrrry.desktop.model

data class AppIdentity(
    val packageName: String,
    val firstInstallTime: Long,
)

object AppManagementPolicy {
    fun canRequestUninstall(isSelf: Boolean): Boolean = !isSelf
}

data class AppEntry(
    val packageName: String,
    val componentName: String,
    val label: String,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val isSelf: Boolean,
    val iconKey: String,
) {
    val identity: AppIdentity
        get() = AppIdentity(packageName, firstInstallTime)

    val canRequestUninstall: Boolean
        get() = AppManagementPolicy.canRequestUninstall(isSelf)
}
