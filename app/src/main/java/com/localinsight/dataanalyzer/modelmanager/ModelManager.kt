package com.localinsight.dataanalyzer.modelmanager

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class ModelManager(private val context: Context) {
    private val TAG = "ModelManager"
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // Ungated LiteRT-LM community model (no auth required)
    private val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    private val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"

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

            scope.launch {
                var isFinished = false
                while (!isFinished) {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (statusIndex >= 0) {
                            when (cursor.getInt(statusIndex)) {
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    _downloadStatus.value = DownloadStatus.Completed
                                    _downloadProgress.value = 1f
                                    isFinished = true
                                }
                                DownloadManager.STATUS_FAILED -> {
                                    _downloadStatus.value = DownloadStatus.Error("Download failed")
                                    isFinished = true
                                }
                                DownloadManager.STATUS_RUNNING -> {
                                    val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                                    val downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                    if (totalIndex >= 0 && downloadedIndex >= 0) {
                                        val total = cursor.getLong(totalIndex)
                                        val downloaded = cursor.getLong(downloadedIndex)
                                        if (total > 0) {
                                            _downloadProgress.value = downloaded.toFloat() / total.toFloat()
                                        }
                                    }
                                }
                            }
                        }
                        cursor.close()
                    }
                    if (!isFinished) {
                        delay(1000)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error initiating model download", e)
            _downloadStatus.value = DownloadStatus.Error(e.message ?: "Unknown error initiating download")
        }
    }
}
