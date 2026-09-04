package com.ninepointnine.desktop.apps

import java.security.MessageDigest
import java.util.Base64

/** Stable, read-only protocol consumed by 03helper over the car's ADB shell. */
object AppCatalogBridgeContract {
    const val AUTHORITY = "com.ninepointnine.desktop.appcatalog"
    const val PATH_APPLICATIONS = "applications"
    const val PATH_ICONS = "icons"
    const val PROTOCOL_VERSION = 2
    const val URI = "content://$AUTHORITY/$PATH_APPLICATIONS"

    const val COLUMN_ROW_TYPE = "rowType"
    const val ROW_TYPE_SUMMARY = "summary"
    const val ROW_TYPE_APPLICATION = "application"
    const val ROW_TYPE_ICON = "icon"
    const val COLUMN_BATCH_COUNT = "batchCount"
    const val COLUMN_BATCH_DIGEST = "batchDigest"
    const val COLUMN_PACKAGE_NAME = "packageName"
    const val COLUMN_PROTOCOL_VERSION = "protocolVersion"
    const val COLUMN_DISPLAY_NAME = "displayName"
    const val COLUMN_VERSION_NAME = "versionName"
    const val COLUMN_VERSION_CODE = "versionCode"
    const val COLUMN_FIRST_INSTALL_TIME = "firstInstallTime"
    const val COLUMN_LAST_UPDATE_TIME = "lastUpdateTime"
    const val COLUMN_UID = "uid"
    const val COLUMN_ICON_BASE64 = "iconBase64"
    const val COLUMN_LAUNCHER_COMPONENT = "launcherComponent"
}

/** Owns the wire encoding shared by the provider and protocol tests. */
internal object AppCatalogBridgeWireCodec {
    fun encode(entry: AppCatalogBridgeEntry): AppCatalogBridgeWireEntry = AppCatalogBridgeWireEntry(
        source = entry,
        packageName = entry.packageName,
        encodedDisplayName = encodeText(entry.displayName),
        encodedVersionName = encodeText(entry.versionName),
        versionCode = entry.versionCode,
        firstInstallTime = entry.firstInstallTime,
        lastUpdateTime = entry.lastUpdateTime,
        uid = entry.uid,
        launcherComponent = entry.launcherComponent.orEmpty(),
    )

    fun batchDigest(entries: List<AppCatalogBridgeWireEntry>): String {
        val canonical = entries.sortedBy { it.packageName }.joinToString(RECORD_SEPARATOR) { entry ->
            listOf(
                entry.packageName,
                entry.encodedDisplayName,
                entry.encodedVersionName,
                entry.versionCode.toString(),
                entry.firstInstallTime.toString(),
                entry.lastUpdateTime.toString(),
                entry.uid.toString(),
                entry.launcherComponent,
            ).joinToString(FIELD_SEPARATOR)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun encodeText(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private const val FIELD_SEPARATOR = "\u001f"
    private const val RECORD_SEPARATOR = "\u001e"
}

internal data class AppCatalogBridgeWireEntry(
    val source: AppCatalogBridgeEntry,
    val packageName: String,
    val encodedDisplayName: String,
    val encodedVersionName: String,
    val versionCode: Long,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val uid: Int,
    val launcherComponent: String,
)
