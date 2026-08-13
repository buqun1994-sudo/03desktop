package com.tcrrry.desktop.apps

import android.content.Context
import com.tcrrry.desktop.model.AppIdentity
import org.json.JSONArray
import org.json.JSONObject

class AppOrderStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): StoredOrder {
        val raw = preferences.getString(KEY_ORDERED_APPS_JSON, null) ?: return StoredOrder(emptyList())
        val order = AppOrderJsonCodec.decode(raw)
        if (order.hadInvalidEntries) {
            android.util.Log.w(TAG, "Ignoring invalid saved app-order entries: ${order.invalidEntryCount}")
        }
        return order
    }

    fun write(entries: List<AppIdentity>) {
        preferences.edit().putString(KEY_ORDERED_APPS_JSON, AppOrderJsonCodec.encode(StoredOrder(entries))).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "app_order_v1"
        const val KEY_ORDERED_APPS_JSON = "ordered_apps_json"
        const val TAG = "AppOrderStore"
    }
}

data class StoredOrder(
    val entries: List<AppIdentity>,
    val invalidEntryCount: Int = 0,
) {
    val hadInvalidEntries: Boolean
        get() = invalidEntryCount > 0
}

internal object AppOrderJsonCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(order: StoredOrder): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put(
            "entries",
            JSONArray().apply {
                order.entries.forEach { identity ->
                    put(
                        JSONObject()
                            .put("packageName", identity.packageName)
                            .put("firstInstallTime", identity.firstInstallTime),
                    )
                }
            },
        ).toString()

    fun decode(raw: String): StoredOrder {
        return try {
            val root = JSONObject(raw)
            if (root.optInt("schemaVersion") != SCHEMA_VERSION) return StoredOrder(emptyList(), invalidEntryCount = 1)
            val values = root.optJSONArray("entries") ?: return StoredOrder(emptyList(), invalidEntryCount = 1)
            val identities = ArrayList<AppIdentity>(values.length())
            val seenPackages = HashSet<String>()
            var invalidEntryCount = 0
            repeat(values.length()) { index ->
                val item = values.optJSONObject(index) ?: run {
                    invalidEntryCount += 1
                    return@repeat
                }
                val packageName = item.optString("packageName")
                val firstInstallTime = item.optLong("firstInstallTime", -1L)
                if (packageName.isBlank() || firstInstallTime < 0L || !seenPackages.add(packageName)) {
                    invalidEntryCount += 1
                    return@repeat
                }
                identities += AppIdentity(packageName, firstInstallTime)
            }
            StoredOrder(identities, invalidEntryCount)
        } catch (_: Exception) {
            StoredOrder(emptyList(), invalidEntryCount = 1)
        }
    }
}
