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

            val setupDict = builtins.callAttr("dict")

            // Create an import allowlist wrapper in Python and put it in a ModuleType.
            // This fixes the issue where some packages expect __builtins__ to be a module rather than a dict.
            val setupCode = """
import builtins
import types

_allowed = ${ALLOWED_MODULES.joinToString(", ") { "'$it'" }.let { "{$it}" }}
_original_import = builtins.__import__

def _safe_import(name, *args, **kwargs):
    top_level = name.split('.')[0]
    if top_level not in _allowed:
        raise ImportError(f"Import of '{name}' is not allowed. Permitted modules: {sorted(_allowed)}")
    return _original_import(name, *args, **kwargs)

safe_builtins = types.ModuleType("builtins")
safe_builtins.__dict__.update(vars(builtins))
safe_builtins.__import__ = _safe_import
safe_builtins.open = None
""".trimIndent()

            // Run the setup code
            builtins.callAttr("exec", setupCode, setupDict)
            
            // Extract the constructed safe builtins module
            val safeBuiltins = setupDict.callAttr("get", "safe_builtins")

            // Build the execution dictionary for the LLM script
            val execDict = builtins.callAttr("dict")
            execDict.put("__builtins__", safeBuiltins)

            // Inject pre-loaded data variables into the execution dict
            for ((key, value) in dataVariables) {
                execDict.put(key, value)
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
                builtins.callAttr("exec", script, execDict)
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
