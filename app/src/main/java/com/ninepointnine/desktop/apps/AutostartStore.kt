package com.ninepointnine.desktop.apps

import android.content.Context

/** Persists explicit package preferences; launcher activities are resolved at use time. */
class AutostartStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isEnabled(packageName: String): Boolean = packageName in configuredPackages()

    fun setEnabled(packageName: String, enabled: Boolean) {
        val explicitlyEnabled = AutostartProxyPolicy.reconcile(
            preferences.getStringSet(KEY_PACKAGES, emptySet()).orEmpty(),
        ).toMutableSet()
        if (enabled && AutostartProxyPolicy.canProxyLaunch(packageName)) {
            explicitlyEnabled += packageName
        } else {
            explicitlyEnabled -= packageName
        }
        preferences.edit()
            .putStringSet(KEY_PACKAGES, explicitlyEnabled)
            .remove(KEY_DISABLED_PACKAGES)
            .apply()
    }

    fun configuredPackages(): Set<String> {
        val stored = preferences.getStringSet(KEY_PACKAGES, emptySet()).orEmpty()
        val reconciled = AutostartProxyPolicy.reconcile(stored)
        if (stored != reconciled || preferences.contains(KEY_DISABLED_PACKAGES)) {
            preferences.edit()
                .putStringSet(KEY_PACKAGES, reconciled)
                .remove(KEY_DISABLED_PACKAGES)
                .apply()
        }
        return reconciled
    }

    private companion object {
        const val FILE_NAME = "autostart"
        const val KEY_PACKAGES = "packages"
        const val KEY_DISABLED_PACKAGES = "disabled_packages"
    }
}

/**
 * The proxy list contains only applications whose launch is owned by 03桌面.
 * 03桌面 and 03歌词 own their boot lifecycle and must never be launched again
 * by this proxy, including after an upgrade from the former default list.
 */
internal object AutostartProxyPolicy {
    private val applicationManagedPackages = setOf(
        "com.tcrrry.desktop",
        "com.tcrrry.desktop.test",
        "com.ninepointnine.desktop",
        "com.ninepointnine.desktop.test",
        "com.tcrrry.desktoplyrics",
        "com.tcrrry.desktoplyrics.test",
        "com.ninepointnine.desktoplyrics",
        "com.ninepointnine.desktoplyrics.test",
    )

    fun canProxyLaunch(packageName: String): Boolean = packageName !in applicationManagedPackages

    fun reconcile(packageNames: Set<String>): Set<String> = packageNames.filterTo(linkedSetOf(), ::canProxyLaunch)
}
