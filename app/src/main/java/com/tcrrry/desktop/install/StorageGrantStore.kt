package com.tcrrry.desktop.install

import android.content.Context
import android.net.Uri

class StorageGrantStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun readTreeUri(volumeId: String): Uri? =
        preferences.getString(keyFor(volumeId), null)?.let(Uri::parse)

    fun saveTreeUri(volumeId: String, treeUri: Uri) {
        preferences.edit().putString(keyFor(volumeId), treeUri.toString()).apply()
    }

    fun clearTreeUri(volumeId: String) {
        preferences.edit().remove(keyFor(volumeId)).apply()
    }

    fun retainVolumeIds(volumeIds: Set<String>): List<Uri> {
        val staleKeys = preferences.all.keys.filter { key ->
            key.startsWith(KEY_PREFIX) && key.removePrefix(KEY_PREFIX) !in volumeIds
        }
        if (staleKeys.isEmpty()) return emptyList()
        val staleUris = staleKeys.mapNotNull { preferences.getString(it, null)?.let(Uri::parse) }
        preferences.edit().apply {
            staleKeys.forEach(::remove)
        }.apply()
        return staleUris
    }

    private fun keyFor(volumeId: String): String = "$KEY_PREFIX$volumeId"

    private companion object {
        const val PREFERENCES_NAME = "storage_grants_v1"
        const val KEY_PREFIX = "tree_uri_"
    }
}
