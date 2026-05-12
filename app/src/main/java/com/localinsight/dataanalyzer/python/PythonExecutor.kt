package com.localinsight.dataanalyzer.python

import android.content.Context
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            // We use a built-in module 'sys' to dynamically execute the code
            val sys = py.getModule("sys")

            // To capture stdout from the generated script, we can redirect it
            val io = py.getModule("io")
            val textIOWrapper = io.callAttr("StringIO")
            sys["stdout"] = textIOWrapper

            // Execute the raw script
            py.getBuiltins().callAttr("exec", script)

            // Retrieve the output
            val output = textIOWrapper.callAttr("getvalue").toString()
            Log.d(TAG, "Python execution output: $output")

            // Restore stdout
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
