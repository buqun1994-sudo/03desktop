package com.ninepointnine.desktop.apps

import android.content.Context
import com.ninepointnine.desktop.model.AppIdentity

/** Stores apps that remain installed but are intentionally removed from the 03 desktop. */
class AppVisibilityStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun readHidden(): StoredOrder {
        val raw = preferences.getString(KEY_HIDDEN_APPS_JSON, null) ?: return StoredOrder(emptyList())
        val hidden = AppOrderJsonCodec.decode(raw)
        if (hidden.hadInvalidEntries) {
            android.util.Log.w(TAG, "Ignoring invalid saved hidden-app entries: ${hidden.invalidEntryCount}")
        }
        return hidden
    }

    fun hide(identity: AppIdentity) {
        val current = readHidden().entries
        if (identity !in current) writeHidden(current + identity)
    }

    fun restore(identities: List<AppIdentity>) {
        if (identities.isEmpty()) return
        val restored = identities.toSet()
        writeHidden(readHidden().entries.filterNot(restored::contains))
    }

    fun writeHiddenForRefresh(entries: List<AppIdentity>) {
        writeHidden(entries)
    }

    private fun writeHidden(entries: List<AppIdentity>) {
        preferences.edit()
            .putString(KEY_HIDDEN_APPS_JSON, AppOrderJsonCodec.encode(StoredOrder(entries)))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "app_visibility_v1"
        const val KEY_HIDDEN_APPS_JSON = "hidden_apps_json"
        const val TAG = "AppVisibilityStore"
    }
}
