package com.ninepointnine.desktop.apps

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import com.ninepointnine.desktop.model.AppEntry
import java.io.ByteArrayOutputStream

/** Minimal read-only bridge with no commands, writes, or app controls. */
class AppCatalogBridgeProvider : ContentProvider() {
    private lateinit var repository: AppCatalogRepository
    private lateinit var iconCache: IconCache
    private var latestInventory: Map<String, AppCatalogBridgeEntry> = emptyMap()

    override fun onCreate(): Boolean {
        val appContext = context ?: return false
        repository = AppCatalogRepository(appContext)
        iconCache = IconCache(appContext)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        return when {
            uri.pathSegments == listOf(AppCatalogBridgeContract.PATH_APPLICATIONS) ->
                applicationInventoryCursor(projection)
            uri.pathSegments.size == 2 && uri.pathSegments.first() == AppCatalogBridgeContract.PATH_ICONS ->
                applicationIconCursor(uri.pathSegments[1], projection)
            else -> null
        }
    }

    private fun applicationInventoryCursor(projection: Array<out String>?): Cursor {
        val columns = projection?.toList().orEmpty().ifEmpty { APPLICATION_COLUMNS }
        val cursor = MatrixCursor(columns.toTypedArray())
        val entries = repository.loadUserInstalledApplications().map(AppCatalogBridgeWireCodec::encode)
        latestInventory = entries.associate { it.packageName to it.source }
        val digest = AppCatalogBridgeWireCodec.batchDigest(entries)
        cursor.addRow(columns.map { column -> summaryValue(column, entries.size, digest) }.toTypedArray())
        entries.forEach { entry ->
            cursor.addRow(columns.map { column ->
                when (column) {
                    AppCatalogBridgeContract.COLUMN_PROTOCOL_VERSION -> AppCatalogBridgeContract.PROTOCOL_VERSION
                    AppCatalogBridgeContract.COLUMN_ROW_TYPE -> AppCatalogBridgeContract.ROW_TYPE_APPLICATION
                    AppCatalogBridgeContract.COLUMN_BATCH_COUNT -> entries.size
                    AppCatalogBridgeContract.COLUMN_BATCH_DIGEST -> digest
                    AppCatalogBridgeContract.COLUMN_PACKAGE_NAME -> entry.packageName
                    AppCatalogBridgeContract.COLUMN_DISPLAY_NAME -> entry.encodedDisplayName
                    AppCatalogBridgeContract.COLUMN_VERSION_NAME -> entry.encodedVersionName
                    AppCatalogBridgeContract.COLUMN_VERSION_CODE -> entry.versionCode
                    AppCatalogBridgeContract.COLUMN_FIRST_INSTALL_TIME -> entry.firstInstallTime
                    AppCatalogBridgeContract.COLUMN_LAST_UPDATE_TIME -> entry.lastUpdateTime
                    AppCatalogBridgeContract.COLUMN_UID -> entry.uid
                    AppCatalogBridgeContract.COLUMN_LAUNCHER_COMPONENT -> entry.launcherComponent
                    else -> null
                }
            }.toTypedArray())
        }
        return cursor
    }

    private fun applicationIconCursor(packageName: String, projection: Array<out String>?): Cursor {
        val columns = projection?.toList().orEmpty().ifEmpty { ICON_COLUMNS }
        val cursor = MatrixCursor(columns.toTypedArray())
        val entry = latestInventory[packageName]
            ?: repository.loadUserInstalledApplications().firstOrNull { it.packageName == packageName }
            ?: return cursor
        val appEntry = AppEntry(
            packageName = entry.packageName,
            componentName = entry.launcherComponent ?: entry.packageName,
            label = entry.displayName,
            firstInstallTime = entry.firstInstallTime,
            lastUpdateTime = entry.lastUpdateTime,
            isSelf = entry.packageName == requireNotNull(context).packageName,
            iconKey = "${entry.packageName}:${entry.lastUpdateTime}",
        )
        val icon = iconCache.get(appEntry)?.let(::encodeIcon).orEmpty()
        cursor.addRow(columns.map { column ->
            when (column) {
                AppCatalogBridgeContract.COLUMN_PROTOCOL_VERSION -> AppCatalogBridgeContract.PROTOCOL_VERSION
                AppCatalogBridgeContract.COLUMN_ROW_TYPE -> AppCatalogBridgeContract.ROW_TYPE_ICON
                AppCatalogBridgeContract.COLUMN_PACKAGE_NAME -> entry.packageName
                AppCatalogBridgeContract.COLUMN_ICON_BASE64 -> icon
                else -> null
            }
        }.toTypedArray())
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.${AppCatalogBridgeContract.AUTHORITY}.application"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun encodeIcon(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        if (!bitmap.compress(CompressFormat.PNG, 100, output)) return ""
        val bytes = output.toByteArray()
        if (bytes.size > MAX_ICON_BYTES) return ""
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun summaryValue(column: String, count: Int, digest: String): Any = when (column) {
        AppCatalogBridgeContract.COLUMN_PROTOCOL_VERSION -> AppCatalogBridgeContract.PROTOCOL_VERSION
        AppCatalogBridgeContract.COLUMN_ROW_TYPE -> AppCatalogBridgeContract.ROW_TYPE_SUMMARY
        AppCatalogBridgeContract.COLUMN_BATCH_COUNT -> count
        AppCatalogBridgeContract.COLUMN_BATCH_DIGEST -> digest
        AppCatalogBridgeContract.COLUMN_VERSION_CODE,
        AppCatalogBridgeContract.COLUMN_FIRST_INSTALL_TIME,
        AppCatalogBridgeContract.COLUMN_LAST_UPDATE_TIME,
        AppCatalogBridgeContract.COLUMN_UID -> 0
        else -> ""
    }

    private companion object {
        const val MAX_ICON_BYTES = 384 * 1024

        val APPLICATION_COLUMNS = listOf(
            AppCatalogBridgeContract.COLUMN_PROTOCOL_VERSION,
            AppCatalogBridgeContract.COLUMN_ROW_TYPE,
            AppCatalogBridgeContract.COLUMN_BATCH_COUNT,
            AppCatalogBridgeContract.COLUMN_BATCH_DIGEST,
            AppCatalogBridgeContract.COLUMN_PACKAGE_NAME,
            AppCatalogBridgeContract.COLUMN_DISPLAY_NAME,
            AppCatalogBridgeContract.COLUMN_VERSION_NAME,
            AppCatalogBridgeContract.COLUMN_VERSION_CODE,
            AppCatalogBridgeContract.COLUMN_FIRST_INSTALL_TIME,
            AppCatalogBridgeContract.COLUMN_LAST_UPDATE_TIME,
            AppCatalogBridgeContract.COLUMN_UID,
            AppCatalogBridgeContract.COLUMN_LAUNCHER_COMPONENT,
        )
        val ICON_COLUMNS = listOf(
            AppCatalogBridgeContract.COLUMN_PROTOCOL_VERSION,
            AppCatalogBridgeContract.COLUMN_ROW_TYPE,
            AppCatalogBridgeContract.COLUMN_PACKAGE_NAME,
            AppCatalogBridgeContract.COLUMN_ICON_BASE64,
        )
    }

}
