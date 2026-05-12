package com.localinsight.dataanalyzer.modelmanager

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class ModelManager(private val context: Context) {
    private val TAG = "ModelManager"

    // Replace with a valid public URL or authenticated mechanism
    private val MODEL_URL = "https://example.com/gemma-2b-it-gpu-int4.bin"
    private val MODEL_FILE_NAME = "gemma-2b-it-gpu-int4.bin"

    private val _downloadProgress = MutableStateFlow<Float>(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _downloadStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val downloadStatus: StateFlow<DownloadStatus> = _downloadStatus.asStateFlow()

    private var downloadId: Long = -1

    sealed class DownloadStatus {
        object Idle : DownloadStatus()
        object Downloading : DownloadStatus()
        object Completed : DownloadStatus()
        data class Error(val message: String) : DownloadStatus()
    }

    fun getModelFile(): File {
        return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), MODEL_FILE_NAME)
    }

    fun isModelDownloaded(): Boolean {
        return getModelFile().exists()
    }

    fun downloadModel() {
        if (isModelDownloaded()) {
            _downloadStatus.value = DownloadStatus.Completed
            _downloadProgress.value = 1f
            return
        }

        try {
            _downloadStatus.value = DownloadStatus.Downloading
            val request = DownloadManager.Request(Uri.parse(MODEL_URL))
                .setTitle("Downloading AI Model")
                .setDescription("Fetching Gemma 4-bit edge model...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, MODEL_FILE_NAME)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = downloadManager.enqueue(request)

            // Register receiver to listen for completion
            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(ctxt: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        _downloadStatus.value = DownloadStatus.Completed
                        _downloadProgress.value = 1f
                        context.unregisterReceiver(this)
                    }
                }
            }
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)

            // Note: In a production app, we would poll the DownloadManager query to update _downloadProgress.
            // For simplicity here, we simulate it being in progress until completed.
            _downloadProgress.value = 0.5f

        } catch (e: Exception) {
            Log.e(TAG, "Error initiating model download", e)
            _downloadStatus.value = DownloadStatus.Error(e.message ?: "Unknown error initiating download")
        }
    }
}
