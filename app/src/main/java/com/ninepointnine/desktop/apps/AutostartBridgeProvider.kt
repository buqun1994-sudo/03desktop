package com.ninepointnine.desktop.apps

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Base64

/** ADB-shell-only bridge; it accepts a package name and a boolean state. */
class AutostartBridgeProvider : ContentProvider() {
    private lateinit var store: AutostartStore
    private lateinit var resolver: AutostartResolver

    override fun onCreate(): Boolean {
        val appContext = context ?: return false
        store = AutostartStore(appContext)
        resolver = AutostartResolver(appContext)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != SHELL_UID) {
            return failure(AutostartBridgeContract.REASON_NOT_AUTHORIZED)
        }
        return when (method) {
            AutostartBridgeContract.METHOD_GET_CAPABILITY -> Bundle().apply {
                putBoolean(AutostartBridgeContract.KEY_SUPPORTED, true)
            }
            AutostartBridgeContract.METHOD_GET_STATUS -> status(arg)
            AutostartBridgeContract.METHOD_GET_STATUS_BATCH -> statusBatch(arg)
            AutostartBridgeContract.METHOD_SET_STATUS -> setStatus(arg)
            else -> failure(AutostartBridgeContract.REASON_INVALID_REQUEST)
        }
    }

    /**
     * Reads a caller-supplied inventory in one provider call. The helper still
     * owns the inventory; this bridge only resolves each exact package locally
     * and returns the same three-state result used by the single-package API.
     */
    private fun statusBatch(arg: String?): Bundle {
        val packages = arg.orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (packages.isEmpty() || packages.size > MAX_BATCH_PACKAGES || packages.any { !PACKAGE_NAME_PATTERN.matches(it) }) {
            return failure(AutostartBridgeContract.REASON_INVALID_REQUEST)
        }
        val rows = packages.joinToString("\n") { packageName ->
            val result = status(packageName)
            val state = result.getString(AutostartBridgeContract.KEY_STATE)
                ?: AutostartBridgeContract.STATE_UNAVAILABLE
            val reason = result.getString(AutostartBridgeContract.KEY_REASON).orEmpty()
            listOf(packageName, state, encode(reason)).joinToString("|")
        }
        val encoded = Base64.encodeToString(
            rows.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        if (encoded.length > MAX_BATCH_OUTPUT_LENGTH) {
            return failure(AutostartBridgeContract.REASON_INVALID_REQUEST)
        }
        return Bundle().apply {
            putBoolean(AutostartBridgeContract.KEY_SUPPORTED, true)
            putString(AutostartBridgeContract.KEY_BATCH, encoded)
        }
    }

    private fun status(packageName: String?): Bundle {
        if (packageName.isNullOrBlank()) return failure(AutostartBridgeContract.REASON_INVALID_PACKAGE)
        return when (val target = resolver.resolve(packageName)) {
            is AutostartTargetResult.Unsupported -> Bundle().apply {
                putString(AutostartBridgeContract.KEY_PACKAGE_NAME, packageName)
                putString(AutostartBridgeContract.KEY_STATE, AutostartBridgeContract.STATE_UNSUPPORTED)
                putBoolean(AutostartBridgeContract.KEY_ENABLED, false)
                putBoolean(AutostartBridgeContract.KEY_SUPPORTED, false)
                putString(AutostartBridgeContract.KEY_REASON, target.reason)
            }
            is AutostartTargetResult.Supported -> Bundle().apply {
                val enabled = store.isEnabled(packageName)
                putString(AutostartBridgeContract.KEY_PACKAGE_NAME, packageName)
                putString(AutostartBridgeContract.KEY_STATE, if (enabled) AutostartBridgeContract.STATE_ENABLED else AutostartBridgeContract.STATE_DISABLED)
                putBoolean(AutostartBridgeContract.KEY_ENABLED, enabled)
                putBoolean(AutostartBridgeContract.KEY_SUPPORTED, true)
                putString(AutostartBridgeContract.KEY_REASON, target.target.component.flattenToString())
            }
        }
    }

    private fun setStatus(arg: String?): Bundle {
        val value = arg ?: return failure(AutostartBridgeContract.REASON_INVALID_REQUEST)
        val separator = value.lastIndexOf(':')
        if (separator <= 0) return failure(AutostartBridgeContract.REASON_INVALID_REQUEST)
        val packageName = value.substring(0, separator)
        val enabled = when (value.substring(separator + 1)) {
            "true" -> true
            "false" -> false
            else -> return failure(AutostartBridgeContract.REASON_INVALID_REQUEST)
        }
        return when (resolver.resolve(packageName)) {
            is AutostartTargetResult.Unsupported -> status(packageName)
            is AutostartTargetResult.Supported -> {
                store.setEnabled(packageName, enabled)
                status(packageName)
            }
        }
    }

    private fun failure(reason: String) = Bundle().apply {
        putBoolean(AutostartBridgeContract.KEY_SUPPORTED, false)
        putString(AutostartBridgeContract.KEY_STATE, AutostartBridgeContract.STATE_UNAVAILABLE)
        putString(AutostartBridgeContract.KEY_REASON, reason)
    }

    private fun encode(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val SHELL_UID = 2000
        const val MAX_BATCH_PACKAGES = 1_000
        const val MAX_BATCH_OUTPUT_LENGTH = 512 * 1024
        val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
