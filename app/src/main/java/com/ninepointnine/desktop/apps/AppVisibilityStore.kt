package com.ninepointnine.desktop.apps

import android.content.Context

/** Package-level desktop blacklist, independent of app order and installation identity. */
class AppVisibilityStore(context: Context) {
    private val preferences = context.getSharedPreferences("app_visibility_v1", Context.MODE_PRIVATE)

    fun readHidden(): Set<String> {
        if (!preferences.contains(KEY_HIDDEN_PACKAGES) && preferences.contains("hidden_apps_json")) {
            val legacy = preferences.getString("hidden_apps_json", null)
            val packages = legacy?.let(AppOrderJsonCodec::decode)?.entries
                ?.mapTo(linkedSetOf()) { it.packageName }.orEmpty()
            preferences.edit().putStringSet(KEY_HIDDEN_PACKAGES, packages)
                .remove("hidden_apps_json").apply()
        }
        return preferences.getStringSet(KEY_HIDDEN_PACKAGES, emptySet()).orEmpty().toSet()
    }

    fun hidePackage(packageName: String) {
        if (packageName.isBlank()) return
        val current = readHidden()
        if (packageName !in current) writeHidden(current + packageName)
    }

    fun restorePackages(packageNames: Collection<String>) {
        if (packageNames.isEmpty()) return
        val current = readHidden()
        val next = current - packageNames.toSet()
        if (next != current) writeHidden(next)
    }

    private fun writeHidden(packageNames: Set<String>) {
        preferences.edit().putStringSet(KEY_HIDDEN_PACKAGES, packageNames.toSet()).apply()
    }

    private companion object {
        const val KEY_HIDDEN_PACKAGES = "hidden_packages"
    }
}
