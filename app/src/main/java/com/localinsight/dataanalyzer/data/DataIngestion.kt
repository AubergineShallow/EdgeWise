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

    fun extractMetadata(context: Context, uri: Uri): String {
        return try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: ""
            val fileName = getFileName(context, uri).lowercase()

            when {
                mimeType.contains("csv") || mimeType.contains("comma-separated-values") || fileName.endsWith(".csv") -> extractCsvMetadata(context, uri)
                mimeType.contains("zip") || fileName.endsWith(".pbix") -> extractPbixMetadata(context, uri)
                else -> "Unsupported file type or MIME type: $mimeType for file: $fileName"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting metadata", e)
            "Error extracting metadata: ${e.message}"
        }
    }

    private fun extractCsvMetadata(context: Context, uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return "Unable to read file"
        val reader = BufferedReader(InputStreamReader(inputStream))

        val header = reader.readLine() ?: return "Empty file"
        val sampleData = mutableListOf<String>()

        for (i in 0 until SAMPLE_ROWS) {
            val line = reader.readLine()
            if (line != null) {
                sampleData.add(line)
            } else {
                break
            }
        }

        reader.close()
        inputStream.close()

        return """
            File Type: CSV
            Headers: $header
            Sample Data ($SAMPLE_ROWS rows):
            ${sampleData.joinToString("\n")}
        """.trimIndent()
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
     * Read the full file content as a string using ContentResolver.
     * This is used to inject the actual data into the Python execution context.
     */
    fun readFullContent(context: Context, uri: Uri): String {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ""
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()
            inputStream.close()
            content
        } catch (e: Exception) {
            Log.e(TAG, "Error reading full file content", e)
            ""
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
