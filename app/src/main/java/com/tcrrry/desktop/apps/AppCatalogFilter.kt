package com.tcrrry.desktop.apps

import com.tcrrry.desktop.model.AppEntry

data class CatalogCandidate(
    val packageName: String,
    val className: String,
    val label: String,
    val applicationLabel: String,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val isSystemApp: Boolean,
    val isUpdatedSystemApp: Boolean,
    val isOemIntegratedApp: Boolean,
    val applicationEnabled: Boolean,
    val activityEnabled: Boolean,
    val priority: Int,
    val preferredOrder: Int,
) {
    val componentName: String
        get() = canonicalComponentName(packageName, className)
}

internal fun canonicalComponentName(packageName: String, className: String): String {
    val qualifiedClassName = when {
        className.startsWith(".") -> packageName + className
        '.' !in className -> "$packageName.$className"
        else -> className
    }
    return "$packageName/$qualifiedClassName"
}

object AppCatalogFilter {
    fun toEntries(
        candidates: List<CatalogCandidate>,
        selfPackageName: String,
        launcherComponentForPackage: (String) -> String?,
        iconSizePx: Int,
        densityDpi: Int,
    ): List<AppEntry> = candidates
        .asSequence()
        .filter(::isEligible)
        .groupBy(CatalogCandidate::packageName)
        .mapNotNull { (packageName, packageCandidates) ->
            val selected = selectPrimaryEntry(packageCandidates, launcherComponentForPackage(packageName))
                ?: return@mapNotNull null
            AppEntry(
                packageName = packageName,
                componentName = selected.componentName,
                label = selected.label.ifBlank { selected.applicationLabel }.ifBlank { packageName },
                firstInstallTime = selected.firstInstallTime,
                lastUpdateTime = selected.lastUpdateTime,
                isSelf = packageName == selfPackageName,
                iconKey = "$packageName:${selected.lastUpdateTime}:$iconSizePx:$densityDpi",
            )
        }
        .filterNot(AppEntry::isSelf)
        .sortedWith(compareBy<AppEntry> { it.componentName }.thenBy(AppEntry::packageName))
        .toList()

    fun isEligible(candidate: CatalogCandidate): Boolean =
        !candidate.isSystemApp &&
            !candidate.isUpdatedSystemApp &&
            !candidate.isOemIntegratedApp &&
            candidate.applicationEnabled &&
            candidate.activityEnabled &&
            candidate.className.isNotBlank()

    fun selectPrimaryEntry(
        entries: List<CatalogCandidate>,
        launcherComponent: String?,
    ): CatalogCandidate? {
        entries.firstOrNull { it.componentName == launcherComponent }?.let { return it }
        return entries.sortedWith(
            compareByDescending<CatalogCandidate> { it.priority }
                .thenByDescending { it.preferredOrder }
                .thenBy(CatalogCandidate::componentName),
        ).firstOrNull()
    }
}
