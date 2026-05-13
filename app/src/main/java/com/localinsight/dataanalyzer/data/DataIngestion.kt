package com.localinsight.dataanalyzer.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object DataIngestion {
    private const val TAG = "DataIngestion"
    private const val SAMPLE_ROWS = 5

    /**
     * Extract metadata for a list of URIs. Loops through all files to provide
     * basic file info. Robust schema extraction is still handled by Python later.
     */
    fun extractMetadata(context: Context, uris: List<Uri>): String {
        if (uris.isEmpty()) return "No files provided."

        val sb = StringBuilder()
        sb.appendLine("${uris.size} file(s) selected:")

        for ((index, uri) in uris.withIndex()) {
            try {
                val contentResolver = context.contentResolver
                val mimeType = contentResolver.getType(uri) ?: ""
                val fileName = getFileName(context, uri).lowercase()

                val fileInfo = when {
                    mimeType.contains("csv") || mimeType.contains("comma-separated-values") || fileName.endsWith(".csv") -> {
                        "File ${index + 1}: CSV - $fileName"
                    }
                    fileName.endsWith(".xlsx") || mimeType.contains("spreadsheetml") -> {
                        "File ${index + 1}: Excel (.xlsx) - $fileName"
                    }
                    mimeType.contains("zip") || fileName.endsWith(".pbix") -> extractPbixMetadata(context, uri)
                    else -> "File ${index + 1}: Unsupported ($mimeType) - $fileName"
                }
                sb.appendLine(fileInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting metadata for uri: $uri", e)
                sb.appendLine("File ${index + 1}: Error - ${e.message}")
            }
        }
        return sb.toString().trimEnd()
    }

    private fun extractPbixMetadata(context: Context, uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return "Unable to read file"
        val zipInputStream = ZipInputStream(inputStream)

        var entry: ZipEntry? = zipInputStream.nextEntry
        val filesList = mutableListOf<String>()
        var dataModelSchemaFound = false

        while (entry != null) {
            filesList.add(entry.name)
            if (entry.name.contains("DataModelSchema", ignoreCase = true)) {
                dataModelSchemaFound = true
                // In a full implementation, we'd extract the JSON schema here
            }
            zipInputStream.closeEntry()
            entry = zipInputStream.nextEntry
        }

        zipInputStream.close()
        inputStream.close()

        return """
            File Type: Power BI (.pbix)
            DataModelSchema Found: $dataModelSchemaFound
            Internal Structure Preview:
            ${filesList.take(10).joinToString("\n")}
        """.trimIndent()
    }

    /**
     * Cache multiple data files into the app's internal cache directory.
     * Clears old cached files to prevent growth.
     * Names them sequentially (e.g., part_0.csv, part_1.xlsx).
     * Returns a pipe-delimited string of absolute file paths.
     */
    fun cacheMultipleDataFiles(context: Context, uris: List<Uri>): String? {
        if (uris.isEmpty()) return null

        return try {
            val cacheDir = java.io.File(context.cacheDir, "data_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            } else {
                // Clear old cached files
                cacheDir.listFiles()?.forEach { it.delete() }
            }

            val cachedPaths = mutableListOf<String>()

            for ((index, uri) in uris.withIndex()) {
                val fileName = getFileName(context, uri)
                val extension = fileName.substringAfterLast('.', "csv")
                val safeExtension = if (extension.isNotEmpty()) extension else "csv"

                val cachedFile = java.io.File(cacheDir, "part_$index.$safeExtension")

                val inputStream = context.contentResolver.openInputStream(uri) ?: continue
                val outputStream = java.io.FileOutputStream(cachedFile)

                inputStream.copyTo(outputStream)

                inputStream.close()
                outputStream.close()

                cachedPaths.add(cachedFile.absolutePath)
            }

            if (cachedPaths.isEmpty()) null else cachedPaths.joinToString("|")
        } catch (e: Exception) {
            Log.e(TAG, "Error caching data files", e)
            null
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "unknown_file"
    }
}
