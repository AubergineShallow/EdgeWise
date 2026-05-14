package com.localinsight.dataanalyzer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

object DataExporter {
    private const val TAG = "DataExporter"

    fun exportDataSaf(
        context: Context,
        treeUri: Uri,
        exportData: Boolean,
        dataContent: String,
        exportScript: Boolean,
        scriptContent: String,
        exportReport: Boolean,
        reportContent: String
    ): Result<Unit> {
        return try {
            val directory = DocumentFile.fromTreeUri(context, treeUri)
                ?: return Result.failure(IOException("Invalid directory selected"))

            if (exportData) {
                saveFile(context, directory, "data_output.json", "application/json", dataContent)
            }
            if (exportScript) {
                saveFile(context, directory, "analysis_script.py", "text/x-python", scriptContent)
            }
            if (exportReport) {
                saveFile(context, directory, "full_report.txt", "text/plain", reportContent)
            }

            Log.d(TAG, "Files exported successfully to $treeUri")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export data via SAF", e)
            Result.failure(e)
        }
    }

    private fun saveFile(
        context: Context,
        directory: DocumentFile,
        filename: String,
        mimeType: String,
        content: String
    ) {
        // Handle name collisions by appending a number
        var uniqueFilename = filename
        val name = filename.substringBeforeLast(".")
        val ext = filename.substringAfterLast(".", "")
        val extPart = if (ext.isNotEmpty()) ".$ext" else ""

        var existingFile = directory.findFile(uniqueFilename)
        var counter = 1
        while (existingFile != null) {
            uniqueFilename = "${name}_${counter}${extPart}"
            existingFile = directory.findFile(uniqueFilename)
            counter++
        }

        val file = directory.createFile(mimeType, uniqueFilename)
            ?: throw IOException("Failed to create file $uniqueFilename")

        context.contentResolver.openOutputStream(file.uri)?.use { out ->
            out.write(content.toByteArray())
        } ?: throw IOException("Failed to open OutputStream for ${file.uri}")
    }
}
