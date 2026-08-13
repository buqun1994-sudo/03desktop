package com.tcrrry.desktop.install

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tcrrry.desktop.R
import com.tcrrry.desktop.model.ApkEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ApkInstallActivity : Activity() {
    private lateinit var scanner: ApkScanner
    private lateinit var grants: StorageGrantStore
    private lateinit var listAdapter: ApkListAdapter
    private lateinit var loading: ProgressBar
    private lateinit var message: TextView
    private lateinit var requestDownloadPermission: View
    private lateinit var emptyState: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyMessage: TextView
    private var scanScope: CoroutineScope? = null
    private var scanJob: Job? = null
    private var pendingInstall: ApkEntry? = null
    private var pendingVolumeId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apk_install)
        scanner = ApkScanner(this)
        grants = StorageGrantStore(this)
        loading = findViewById(R.id.apk_loading)
        message = findViewById(R.id.apk_message)
        requestDownloadPermission = findViewById(R.id.request_download_permission)
        emptyState = findViewById(R.id.apk_empty_state)
        emptyTitle = findViewById(R.id.apk_empty_title)
        emptyMessage = findViewById(R.id.apk_empty_message)
        val list = findViewById<RecyclerView>(R.id.apk_list)
        list.layoutManager = LinearLayoutManager(this)
        listAdapter = ApkListAdapter(::installEntry)
        list.adapter = listAdapter

        requestDownloadPermission.setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_STORAGE)
        }
        findViewById<View>(R.id.request_external_tree).setOnClickListener {
            requestExternalTree()
        }
    }

    override fun onStart() {
        super.onStart()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE) startScanIfAllowed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_EXTERNAL_TREE -> {
                val uri = data?.data
                val volumeId = pendingVolumeId
                pendingVolumeId = null
                if (resultCode == RESULT_OK && uri != null && volumeId != null) {
                    var persisted = false
                    try {
                        val hasReadGrant = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
                        if (!hasReadGrant) throw SecurityException("Missing read grant")
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        persisted = true
                    } catch (_: SecurityException) {
                        grants.clearTreeUri(volumeId)
                    }
                    if (persisted) {
                        grants.saveTreeUri(volumeId, uri)
                        startScanIfAllowed()
                    } else {
                        Toast.makeText(this, R.string.apk_scan_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            REQUEST_UNKNOWN_SOURCES -> {
                val entry = pendingInstall
                pendingInstall = null
                if (entry != null && packageManager.canRequestPackageInstalls()) launchSystemInstaller(entry)
            }
        }
    }

    private fun startScanIfAllowed() {
        val canReadDownloads =
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val trees = authorizedTreeUris()
        if (!canReadDownloads && trees.isEmpty()) {
            loading.visibility = View.GONE
            message.setText(R.string.apk_storage_permission)
            requestDownloadPermission.visibility = View.VISIBLE
            listAdapter.submitEntries(emptyList())
            showEmptyState(R.string.apk_permission_title, R.string.apk_permission_message)
            return
        }

        requestDownloadPermission.visibility = if (canReadDownloads) View.GONE else View.VISIBLE
        scanJob?.cancel()
        val scope = scanScope ?: return
        scanJob = scope.launch {
            loading.visibility = View.VISIBLE
            message.setText(R.string.apk_scan_loading)
            hideEmptyState()
            try {
                val entries = withContext(Dispatchers.IO) {
                    scanner.scan(
                        downloadDirectory = if (canReadDownloads) {
                            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        } else {
                            null
                        },
                        externalTrees = trees,
                    )
                }
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

    private fun authorizedTreeUris(): List<Uri> {
        val volumes = storageVolumes()
        grants.retainVolumeIds(volumes.map(::volumeId).toSet()).forEach { staleUri ->
            runCatching {
                contentResolver.releasePersistableUriPermission(staleUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val persisted = contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
            .toSet()
        return volumes
        .mapNotNull { volume ->
            val volumeId = volumeId(volume)
            val uri = grants.readTreeUri(volumeId) ?: return@mapNotNull null
            if (uri in persisted) {
                uri
            } else {
                grants.clearTreeUri(volumeId)
                null
            }
        }
    }

    private fun requestExternalTree() {
        val volume = storageVolumes().firstOrNull { grants.readTreeUri(volumeId(it)) == null }
        if (volume == null) {
            Toast.makeText(this, R.string.apk_no_external_storage, Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("DEPRECATION")
        val intent = volume.createAccessIntent(null) ?: run {
            Toast.makeText(this, R.string.system_action_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        pendingVolumeId = volumeId(volume)
        startActivityForResult(intent, REQUEST_EXTERNAL_TREE)
    }

    private fun storageVolumes(): List<StorageVolume> =
        getSystemService(StorageManager::class.java).storageVolumes
            .filter { it.isRemovable && !it.isPrimary && it.state == android.os.Environment.MEDIA_MOUNTED }

    private fun volumeId(volume: StorageVolume): String =
        volume.uuid ?: volume.getDescription(this).ifBlank { "removable" }

    private fun installEntry(entry: ApkEntry) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstall = entry
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            )
            if (intent.resolveActivity(packageManager) == null) {
                pendingInstall = null
                Toast.makeText(this, R.string.system_action_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            try {
                startActivityForResult(intent, REQUEST_UNKNOWN_SOURCES)
            } catch (_: SecurityException) {
                pendingInstall = null
                Toast.makeText(this, R.string.system_action_unavailable, Toast.LENGTH_SHORT).show()
            } catch (_: android.content.ActivityNotFoundException) {
                pendingInstall = null
                Toast.makeText(this, R.string.system_action_unavailable, Toast.LENGTH_SHORT).show()
            }
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
            Toast.makeText(this, R.string.system_action_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(intent)
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.system_action_unavailable, Toast.LENGTH_SHORT).show()
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.system_action_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val REQUEST_STORAGE = 4001
        const val REQUEST_EXTERNAL_TREE = 4002
        const val REQUEST_UNKNOWN_SOURCES = 4003
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
