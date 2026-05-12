package com.localinsight.dataanalyzer.modelmanager

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelManager(private val context: Context) {
    private val TAG = "ModelManager"

    // Replace with a valid public URL to the quantized Gemma model
    private val MODEL_URL = "https://example.com/gemma-2b-it-gpu-int4.bin"
    private val MODEL_FILE_NAME = "gemma-2b-it-gpu-int4.bin"

    private val _downloadProgress = MutableStateFlow<Float>(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _downloadStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val downloadStatus: StateFlow<DownloadStatus> = _downloadStatus.asStateFlow()

    sealed class DownloadStatus {
        object Idle : DownloadStatus()
        object Downloading : DownloadStatus()
        object Completed : DownloadStatus()
        data class Error(val message: String) : DownloadStatus()
    }

    fun getModelFile(): File {
        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        return File(modelDir, MODEL_FILE_NAME)
    }

    fun isModelDownloaded(): Boolean {
        return getModelFile().exists()
    }

    suspend fun downloadModel() = withContext(Dispatchers.IO) {
        if (isModelDownloaded()) {
            _downloadStatus.value = DownloadStatus.Completed
            _downloadProgress.value = 1f
            return@withContext
        }

        try {
            _downloadStatus.value = DownloadStatus.Downloading
            val url = URL(MODEL_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                _downloadStatus.value = DownloadStatus.Error("Server returned HTTP ${connection.responseCode} ${connection.responseMessage}")
                return@withContext
            }

            val fileLength = connection.contentLength
            val modelFile = getModelFile()

            val input = connection.inputStream
            val output = FileOutputStream(modelFile)

            val data = ByteArray(4096)
            var total: Long = 0
            var count: Int

            while (input.read(data).also { count = it } != -1) {
                total += count
                if (fileLength > 0) {
                    val progress = (total * 100 / fileLength).toFloat() / 100f
                    _downloadProgress.value = progress
                }
                output.write(data, 0, count)
            }

            output.flush()
            output.close()
            input.close()

            _downloadProgress.value = 1f
            _downloadStatus.value = DownloadStatus.Completed
            Log.d(TAG, "Model downloaded successfully to ${modelFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Error downloading model", e)
            _downloadStatus.value = DownloadStatus.Error(e.message ?: "Unknown error downloading model")
            // Clean up potentially corrupted file
            val modelFile = getModelFile()
            if (modelFile.exists()) {
                modelFile.delete()
            }
        }
    }
}
