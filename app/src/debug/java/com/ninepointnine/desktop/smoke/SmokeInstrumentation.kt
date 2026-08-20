package com.ninepointnine.desktop.smoke

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.ninepointnine.desktop.apps.AppCatalogRepository
import kotlinx.coroutines.runBlocking

class SmokeInstrumentation : Instrumentation() {
    private var smokeArguments: Bundle? = null

    override fun onCreate(arguments: Bundle?) {
        smokeArguments = arguments
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val action = smokeArguments?.getString(ARG_ACTION).orEmpty()
            when (action) {
                ACTION_CATALOG -> verifyCatalog(result)
                else -> error("Unknown smoke action")
            }
            result.putString(RESULT_STATUS, "passed")
            finish(Activity.RESULT_OK, result)
        } catch (throwable: Throwable) {
            result.putString(RESULT_STATUS, "failed")
            result.putString(RESULT_ERROR, throwable.javaClass.simpleName)
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun verifyCatalog(result: Bundle) {
        val entries = runBlocking { AppCatalogRepository(targetContext).load() }
        check(entries.none { it.packageName == SELF_PACKAGE })
        val lyrics = entries.singleOrNull { it.packageName == LYRICS_PACKAGE }
        check(lyrics != null && lyrics.canRequestUninstall)
        check(entries.distinctBy { it.packageName }.size == entries.size)
        result.putInt(RESULT_CATALOG_SIZE, entries.size)
        result.putString(RESULT_CATALOG_PACKAGES, entries.joinToString(",") { it.packageName })
    }

    private companion object {
        const val ARG_ACTION = "action"
        const val ACTION_CATALOG = "catalog"
        const val RESULT_STATUS = "status"
        const val RESULT_ERROR = "error"
        const val RESULT_CATALOG_SIZE = "catalog_size"
        const val RESULT_CATALOG_PACKAGES = "catalog_packages"
        const val SELF_PACKAGE = "com.ninepointnine.desktop"
        const val LYRICS_PACKAGE = "com.ninepointnine.desktoplyrics"
    }
}
