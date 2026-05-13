package com.localinsight.dataanalyzer.python

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExecutionResult(
    val stdout: String,
    val stderr: String,
    val success: Boolean
)

class PythonExecutor(private val context: Context) {
    private val TAG = "PythonExecutor"

    // Note: We intentionally do NOT use an import denylist. 
    // Data science libraries like pandas and numpy are massive and internally
    // import everything from `ctypes` and `subprocess` to `urllib` and `socket`.
    // Instead of playing whack-a-mole and breaking valid library internals,
    // we rely on the Android OS application sandbox and our `builtins.open` patch
    // to maintain security.

    init {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }

    /**
     * Save the generated script to the public Downloads/EdgeWise directory
     * using MediaStore so it's visible in any file manager.
     * Returns the display path, or null on failure.
     */
    fun saveScriptToFile(script: String, label: String = "analysis"): String? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "edgewise_${label}_$timestamp.py"
            val displayPath = "Download/EdgeWise/$fileName"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: use MediaStore
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/x-python")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/EdgeWise")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return null

                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(script.toByteArray())
                }
                Log.d(TAG, "Script saved via MediaStore: $displayPath")
            } else {
                // Older Android: write directly
                @Suppress("DEPRECATION")
                val dir = java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "EdgeWise"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = java.io.File(dir, fileName)
                file.writeText(script)
                Log.d(TAG, "Script saved to: ${file.absolutePath}")
            }

            displayPath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save script", e)
            null
        }
    }

    /**
     * Execute a Python script with the file path.
     * The file path is injected as a global variable FILE_PATH.
     * A load_data() function is injected into builtins to handle CSV or XLSX reading automatically.
     *
     * Security: instead of an allowlist (which breaks pandas/numpy transitive imports),
     * we use a DENYLIST of explicitly dangerous modules.
     */
    suspend fun executeScript(
        script: String,
        filePath: String = ""
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val py = Python.getInstance()
            val builtins = py.getBuiltins()

            val globalDict = builtins.callAttr("dict")

            // Python wrapper that temporarily patches builtins.open
            // with safety checks, runs the script, then restores originals.
            // We rely on the OS sandbox instead of an import denylist to avoid
            // breaking complex data science libraries like pandas and numpy.
            val wrapperCode = """
import builtins
import sys
import pandas as pd

_original_open = builtins.open

def _safe_open(file, *args, **kwargs):
    if isinstance(file, str) and ("chaquopy" in file or "/data/" in file):
        return _original_open(file, *args, **kwargs)
    raise IOError(f"File access to '{file}' is restricted.")

def _load_data(path=None):
    if path is None:
        path = builtins.FILE_PATH

    paths = path.split('|')
    dfs = []

    for p in paths:
        if p.lower().endswith('.xlsx'):
            dfs.append(pd.read_excel(p))
        else:
            dfs.append(pd.read_csv(p))

    if len(dfs) == 1:
        df = dfs[0]
    else:
        # Smart Merge Logic
        common_cols = set(dfs[0].columns)
        for d in dfs[1:]:
            common_cols = common_cols.intersection(set(d.columns))

        if common_cols:
            df = dfs[0]
            for d in dfs[1:]:
                df = pd.merge(df, d, on=list(common_cols), how='outer')
        else:
            df = pd.concat(dfs, ignore_index=True)

    # Safety limit to prevent edge device OOM
    if len(df) > 10000:
        df = df.sample(n=10000, random_state=42)

    return df

def run_script(script, file_path):
    exec_dict = {"__name__": "__main__"}
    if file_path:
        exec_dict["FILE_PATH"] = file_path
        builtins.FILE_PATH = file_path
    
    builtins.open = _safe_open
    builtins.load_data = _load_data
    
    try:
        exec(script, exec_dict)
    finally:
        builtins.open = _original_open
        if hasattr(builtins, 'load_data'):
            del builtins.load_data
        if hasattr(builtins, 'FILE_PATH'):
            del builtins.FILE_PATH
""".trimIndent()

            // Define the wrapper function
            builtins.callAttr("exec", wrapperCode, globalDict)
            val runScript = globalDict.callAttr("get", "run_script")

            // Capture stdout and stderr
            val sys = py.getModule("sys")
            val ioModule = py.getModule("io")
            val stdoutCapture = ioModule.callAttr("StringIO")
            val stderrCapture = ioModule.callAttr("StringIO")

            val origStdout = sys["stdout"]
            val origStderr = sys["stderr"]

            sys["stdout"] = stdoutCapture
            sys["stderr"] = stderrCapture

            try {
                // Execute the script, passing file_path
                runScript.call(script, filePath)
            } finally {
                // Always restore original stdout/stderr
                sys["stdout"] = origStdout
                sys["stderr"] = origStderr
            }

            val stdout = stdoutCapture.callAttr("getvalue").toString()
            val stderr = stderrCapture.callAttr("getvalue").toString()

            Log.d(TAG, "Python stdout: $stdout")
            if (stderr.isNotEmpty()) {
                Log.w(TAG, "Python stderr: $stderr")
            }

            ExecutionResult(
                stdout = stdout,
                stderr = stderr,
                success = true
            )

        } catch (e: PyException) {
            Log.e(TAG, "Python execution failed", e)
            ExecutionResult(
                stdout = "",
                stderr = e.message ?: "Unknown Python error",
                success = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unknown error during Python execution", e)
            ExecutionResult(
                stdout = "",
                stderr = e.message ?: "Unknown execution error",
                success = false
            )
        }
    }

    /**
     * Helper to run lightweight background python scripts (e.g., metadata extraction)
     * returning the raw output string or error.
     */
    suspend fun executeBackgroundScript(script: String, filePath: String): String = withContext(Dispatchers.IO) {
        val result = executeScript(script, filePath)
        if (result.success) {
            result.stdout.trim()
        } else {
            Log.e(TAG, "Background script failed: ${result.stderr}")
            "Error: ${result.stderr}"
        }
    }
}
