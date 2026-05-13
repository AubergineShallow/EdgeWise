package com.localinsight.dataanalyzer.python

import android.content.Context
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     * Execute a Python script with optional pre-loaded data variables.
     * Variables in [dataVariables] are injected into the script's global dict
     * so the LLM-generated code can reference actual file data without reading files.
     */
    suspend fun executeScript(
        script: String,
        dataVariables: Map<String, String> = emptyMap()
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val py = Python.getInstance()
            val builtins = py.getBuiltins()

            // Build a sandboxed global dict for exec()
            val globalDict = builtins.callAttr("dict")

            // Create an import allowlist wrapper in Python
            // This replaces the original approach of nulling out __import__,
            // which broke ALL imports including pandas/numpy.
            val allowlistCode = """
import builtins as _builtins

_original_import = _builtins.__import__
_allowed = ${ALLOWED_MODULES.joinToString(", ") { "'$it'" }.let { "{$it}" }}

def _safe_import(name, *args, **kwargs):
    top_level = name.split('.')[0]
    if top_level not in _allowed:
        raise ImportError(f"Import of '{name}' is not allowed. Permitted modules: {sorted(_allowed)}")
    return _original_import(name, *args, **kwargs)
""".trimIndent()

            // Install the safe import hook
            builtins.callAttr("exec", allowlistCode, globalDict)

            // Now replace __import__ in the sandbox with our safe version
            val safeImport = globalDict.callAttr("get", "_safe_import")
            val sandboxBuiltins = builtins.callAttr("dict", builtins.callAttr("vars", py.getModule("builtins")))
            sandboxBuiltins.put("__import__", safeImport)
            sandboxBuiltins.put("open", null)  // Block filesystem access
            globalDict.put("__builtins__", sandboxBuiltins)

            // Inject pre-loaded data variables into the global dict
            for ((key, value) in dataVariables) {
                globalDict.put(key, value)
            }

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
                builtins.callAttr("exec", script, globalDict)
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
