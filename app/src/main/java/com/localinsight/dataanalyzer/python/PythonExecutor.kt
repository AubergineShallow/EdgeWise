package com.localinsight.dataanalyzer.python

import android.content.Context
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PythonExecutor(private val context: Context) {
    private val TAG = "PythonExecutor"

    init {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }

    suspend fun executeScript(script: String): String = withContext(Dispatchers.IO) {
        try {
            val py = Python.getInstance()

            // To sandbox the execution and prevent prompt injection, we execute the script
            // inside a restricted dictionary that omits __builtins__ with __import__, eval, etc.
            val builtins = py.getBuiltins()
            val dict = builtins.callAttr("dict")

            val safeBuiltins = builtins.callAttr("dict")
            // Explicitly remove dangerous builtins
            safeBuiltins.put("__import__", null)
            safeBuiltins.put("eval", null)
            safeBuiltins.put("exec", null)
            safeBuiltins.put("open", null)

            dict.put("__builtins__", safeBuiltins)

            val sys = py.getModule("sys")
            val io = py.getModule("io")
            val textIOWrapper = io.callAttr("StringIO")

            sys["stdout"] = textIOWrapper

            // Execute the raw script within the sandboxed dictionary
            builtins.callAttr("exec", script, dict)

            // Retrieve the output
            val output = textIOWrapper.callAttr("getvalue").toString()
            Log.d(TAG, "Python execution output: $output")

            sys["stdout"] = sys["__stdout__"]

            output
        } catch (e: PyException) {
            Log.e(TAG, "Python execution failed", e)
            throw Exception("Python execution failed: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unknown error during Python execution", e)
            throw Exception("Error during Python execution: ${e.message}")
        }
    }
}
