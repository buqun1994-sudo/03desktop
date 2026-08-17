package com.tcrrry.desktop.install

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tcrrry.desktop.R
import com.tcrrry.desktop.model.ApkEntry
import com.tcrrry.desktop.system.SystemActionLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ApkInstallActivity : Activity() {
    private lateinit var scanner: ApkScanner
    private lateinit var listAdapter: ApkListAdapter
    private lateinit var loading: ProgressBar
    private lateinit var message: TextView
    private lateinit var openExternalStorage: View
    private lateinit var emptyState: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyMessage: TextView
    private lateinit var actionLauncher: SystemActionLauncher
    private var scanScope: CoroutineScope? = null
    private var scanJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apk_install)
        scanner = ApkScanner(this)
        actionLauncher = SystemActionLauncher(applicationContext)
        loading = findViewById(R.id.apk_loading)
        message = findViewById(R.id.apk_message)
        openExternalStorage = findViewById(R.id.open_external_storage)
        emptyState = findViewById(R.id.apk_empty_state)
        emptyTitle = findViewById(R.id.apk_empty_title)
        emptyMessage = findViewById(R.id.apk_empty_message)
        val list = findViewById<RecyclerView>(R.id.apk_list)
        list.layoutManager = LinearLayoutManager(this)
        listAdapter = ApkListAdapter(::installEntry)
        list.adapter = listAdapter

        openExternalStorage.setOnClickListener {
            openExternalStorage.isEnabled = false
            val accepted = actionLauncher.launchExternalStorage {
                runOnUiThread(::finishAndRemoveTask)
            }
            if (!accepted) openExternalStorage.isEnabled = true
        }
    }

    override fun onStart() {
        super.onStart()
        openExternalStorage.isEnabled = true
        openExternalStorage.visibility =
            if (actionLauncher.isExternalStorageEnhancementAvailable()) View.VISIBLE else View.GONE
        scanScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        startScanIfAllowed()
    }

    override fun onStop() {
        scanJob?.cancel()
        scanJob = null
        scanScope?.cancel()
        scanScope = null
        listAdapter.submitEntries(emptyList())
        super.onStop()
    }

    override fun onDestroy() {
        actionLauncher.release()
        super.onDestroy()
    }

    private fun startScanIfAllowed() {
        val canReadDownloads =
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        if (!canReadDownloads) {
            loading.visibility = View.GONE
            message.setText(R.string.apk_storage_permission)
            listAdapter.submitEntries(emptyList())
            showEmptyState(R.string.apk_permission_title, R.string.apk_permission_message)
            return
        }

        scanJob?.cancel()
        val scope = scanScope ?: return
        scanJob = scope.launch {
            loading.visibility = View.VISIBLE
            message.setText(R.string.apk_scan_loading)
            hideEmptyState()
            try {
                val entries = withContext(Dispatchers.IO) { scanner.scan() }
                listAdapter.submitEntries(entries)
                message.text = if (entries.isEmpty()) {
                    getString(R.string.apk_scan_empty)
                } else {
                    getString(R.string.apk_scan_count, entries.size)
                }
                if (entries.isEmpty()) {
                    showEmptyState(R.string.apk_empty_title, R.string.apk_empty_message)
                } else {
                    hideEmptyState()
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                message.setText(R.string.apk_scan_error)
                listAdapter.submitEntries(emptyList())
                showEmptyState(R.string.apk_error_title, R.string.apk_error_message)
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    private fun showEmptyState(title: Int, detail: Int) {
        emptyTitle.setText(title)
        emptyMessage.setText(detail)
        emptyState.visibility = View.VISIBLE
    }

    private fun hideEmptyState() {
        emptyState.visibility = View.GONE
    }

    private fun installEntry(entry: ApkEntry) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.apk_install_permission_missing, Toast.LENGTH_SHORT).show()
            return
        }
        launchSystemInstaller(entry)
    }

    private fun launchSystemInstaller(entry: ApkEntry) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(entry.contentUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) == null) {
            unavailable()
            return
        }
        try {
            startActivity(intent)
        } catch (_: SecurityException) {
            unavailable()
        } catch (_: android.content.ActivityNotFoundException) {
            unavailable()
        }
    }

    private fun unavailable() {
        Toast.makeText(this, R.string.system_action_unavailable, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
