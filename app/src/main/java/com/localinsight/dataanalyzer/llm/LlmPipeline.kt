package com.localinsight.dataanalyzer.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.localinsight.dataanalyzer.modelmanager.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class LlmPipeline(private val context: Context, private val modelManager: ModelManager) {
    private val TAG = "LlmPipeline"
    private var llmInference: LlmInference? = null

    private val _pipelineState = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val pipelineState: StateFlow<PipelineState> = _pipelineState.asStateFlow()

    sealed class PipelineState {
        object Idle : PipelineState()
        object LoadingModel : PipelineState()
        object ProfilingSchema : PipelineState()
        object MappingRelations : PipelineState()
        object GeneratingCode : PipelineState()
        data class Completed(val schema: String, val erDiagram: String, val code: String) : PipelineState()
        data class Error(val message: String) : PipelineState()
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (llmInference != null) return@withContext
        try {
            _pipelineState.value = PipelineState.LoadingModel
            val modelFile = modelManager.getModelFile()
            if (!modelFile.exists()) {
                _pipelineState.value = PipelineState.Error("Model not found")
                return@withContext
            }

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(1024)
                .setTemperature(0.1f)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            _pipelineState.value = PipelineState.Idle
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing LLM", e)
            _pipelineState.value = PipelineState.Error(e.message ?: "Failed to initialize LLM")
        }
    }

    suspend fun runPipeline(metadata: String, dataFilePath: String) = withContext(Dispatchers.IO) {
        if (llmInference == null) {
            _pipelineState.value = PipelineState.Error("LLM not initialized")
            return@withContext
        }

        try {
            // Step 1: Schema Profiling
            _pipelineState.value = PipelineState.ProfilingSchema
            val schemaPrompt = """
                Analyze the following data file metadata and structure sample:
                $metadata

                Identify the primary data types for each column, detect missing value patterns, and output a structured schema profile.
            """.trimIndent()
            val schemaProfile = llmInference!!.generateResponse(schemaPrompt)

            // Step 2: Relational Mapping
            _pipelineState.value = PipelineState.MappingRelations
            val relationalPrompt = """
                Based on the following schema profile, generate a text-based Entity-Relationship (ER) diagram mapping the structural topology:
                $schemaProfile
            """.trimIndent()
            val erDiagram = llmInference!!.generateResponse(relationalPrompt)

            // Step 3: Code Generation
            _pipelineState.value = PipelineState.GeneratingCode
            val codePrompt = """
                You are a Python data engineer. Based on the schema profile and the data file path '$dataFilePath', generate a Python script using pandas and numpy.
                The script must:
                1. Read the data file.
                2. Clean the data (handle missing values appropriately based on type).
                3. Perform basic statistical analysis or aggregations relevant to the schema.
                4. Output the result strictly as a valid JSON string containing the aggregated data points suitable for charting (e.g. lists of labels and lists of values).

                Schema: $schemaProfile

                Respond ONLY with the Python code, do not include markdown formatting or explanations.
            """.trimIndent()
            var pythonCode = llmInference!!.generateResponse(codePrompt)

            // Cleanup markdown if LLM includes it
            pythonCode = pythonCode.replace("```python", "").replace("```", "").trim()

            _pipelineState.value = PipelineState.Completed(schemaProfile, erDiagram, pythonCode)

        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error", e)
            _pipelineState.value = PipelineState.Error(e.message ?: "Pipeline execution failed")
        }
    }
}
