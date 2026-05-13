package com.localinsight.dataanalyzer.python

import android.content.Context
import android.os.Environment
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

    // Modules the LLM-generated scripts are allowed to import
    private val ALLOWED_MODULES = setOf(
        "pandas", "numpy", "json", "io", "math", "statistics",
        "collections", "re", "datetime", "csv", "functools",
        "itertools", "operator", "string", "textwrap"
    )

    init {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }

    /**
     * Save the generated script to the app's external Documents directory
     * so the user can access and share it for debugging.
     * Returns the file path, or null on failure.
     */
    fun saveScriptToFile(script: String, label: String = "analysis"): String? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "edgewise_${label}_$timestamp.py"
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: return null
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.writeText(script)
            Log.d(TAG, "Script saved to: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save script", e)
            null
        }
    }

    /**
     * Execute a Python script with an optional pre-loaded CSV data string.
     * The CSV content is injected as a global variable called DATA_CSV.
     */
    suspend fun executeScript(
        script: String,
        dataCsvContent: String = ""
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val py = Python.getInstance()
            val builtins = py.getBuiltins()

            val globalDict = builtins.callAttr("dict")

            // We use a Python wrapper function to handle the execution.
            // This safely patches the global builtins.__import__ and builtins.open
            // only for the duration of the script, avoiding Chaquopy compatibility issues
            // with custom __builtins__ objects.
            //
            // DATA_CSV is passed as a direct string argument to avoid Chaquopy dict
            // interop issues (dict.update with a Chaquopy-constructed dict fails).
            val wrapperCode = """
import builtins
import sys

_allowed = ${ALLOWED_MODULES.joinToString(", ") { "'$it'" }.let { "{$it}" }}
_original_import = builtins.__import__
_original_open = builtins.open

def _safe_import(name, *args, **kwargs):
    top_level = name.split('.')[0]
    if top_level not in _allowed and top_level not in sys.builtin_module_names:
        raise ImportError(f"Import of '{name}' is not allowed. Permitted modules: {sorted(_allowed)}")
    return _original_import(name, *args, **kwargs)

def _safe_open(file, *args, **kwargs):
    if isinstance(file, str) and ("chaquopy" in file or "/data/user" in file):
        return _original_open(file, *args, **kwargs)
    raise IOError(f"File access to '{file}' is restricted.")

def run_script(script, data_csv_content):
    exec_dict = {"__name__": "__main__"}
    if data_csv_content:
        exec_dict["DATA_CSV"] = data_csv_content
    
    builtins.__import__ = _safe_import
    builtins.open = _safe_open
    
    try:
        exec(script, exec_dict)
    finally:
        builtins.__import__ = _original_import
        builtins.open = _original_open
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
                // Execute the script, passing DATA_CSV as a direct string argument
                runScript.call(script, dataCsvContent)
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
}
