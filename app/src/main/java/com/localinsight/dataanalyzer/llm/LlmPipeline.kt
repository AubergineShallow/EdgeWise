package com.localinsight.dataanalyzer.llm

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.localinsight.dataanalyzer.data.DataIngestion
import com.localinsight.dataanalyzer.modelmanager.ModelManager
import com.localinsight.dataanalyzer.python.ExecutionResult
import com.localinsight.dataanalyzer.python.PythonExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class LlmPipeline(
    private val context: Context,
    private val modelManager: ModelManager,
    private val pythonExecutor: PythonExecutor
) {
    private val TAG = "LlmPipeline"
    private var engine: Engine? = null

    companion object {
        private const val MAX_COMPILE_RETRIES = 3
        private const val MAX_OUTPUT_RETRIES = 2
        private const val STREAM_UPDATE_INTERVAL_MS = 200L
        private const val NUM_SUGGESTIONS = 3
    }

    // ────────────────────────────────────────────
    //  Pipeline State
    // ────────────────────────────────────────────

    private val _pipelineState = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val pipelineState: StateFlow<PipelineState> = _pipelineState.asStateFlow()

    sealed class PipelineState {
        object Idle : PipelineState()
        object LoadingModel : PipelineState()

        /** Live token output visible to user */
        data class Streaming(
            val stepName: String,
            val stepNumber: Int,
            val partialText: String
        ) : PipelineState()

        /** Model has generated suggestions; waiting for user to choose or type custom */
        data class AwaitingSuggestionChoice(
            val suggestions: List<AnalysisSuggestion>,
            val schema: String,
            val erDiagram: String
        ) : PipelineState()

        /** Model is evaluating feasibility of a custom user request */
        data class EvaluatingCustomRequest(
            val request: String
        ) : PipelineState()

        /** Model refined the custom request; waiting for user confirmation */
        data class AwaitingPlanConfirmation(
            val originalRequest: String,
            val refinedPlan: String,
            val schema: String,
            val erDiagram: String
        ) : PipelineState()

        /** Model informed user that the request isn't feasible */
        data class CustomRequestNotFeasible(
            val explanation: String,
            val suggestions: List<AnalysisSuggestion>,
            val schema: String,
            val erDiagram: String
        ) : PipelineState()

        /** Python script is being executed */
        data class ExecutingCode(
            val attempt: Int,
            val maxAttempts: Int
        ) : PipelineState()

        /** Code execution failed; showing error before retry */
        data class ValidationFailed(
            val errorType: String,
            val errorMessage: String,
            val attempt: Int,
            val maxAttempts: Int
        ) : PipelineState()

        /** Pipeline completed successfully; waiting for user satisfaction */
        data class AwaitingSatisfaction(
            val schema: String,
            val erDiagram: String,
            val analysisDescription: String,
            val code: String,
            val executionOutput: String,
            val executionLog: String,
            val scriptPath: String? = null
        ) : PipelineState()

        data class Error(val message: String, val scriptPath: String? = null) : PipelineState()
    }

    data class AnalysisSuggestion(
        val title: String,
        val description: String
    )

    // ────────────────────────────────────────────
    //  Internal state preserved across interactions
    // ────────────────────────────────────────────

    private var cachedSchema: String = ""
    private var cachedErDiagram: String = ""
    private var cachedDataFilePath: String = ""
    private var cachedFileContent: String = ""
    private var cachedMetadata: String = ""
    private var currentAnalysisDescription: String = ""

    // ────────────────────────────────────────────
    //  System prompt (guardrails for the 2B model)
    // ────────────────────────────────────────────

    private val SYSTEM_PROMPT = """
You are a data analysis assistant running on a mobile device.
You help users understand and analyze tabular data files (CSV, Excel).

STRICT RULES:
1. You can ONLY work with data that exists in the columns provided in the schema.
2. You must NEVER fabricate column names, data points, or relationships that do not exist.
3. When generating Python code, use pandas, numpy, json, io, math, statistics, collections, re, datetime, csv, and their dependencies.
4. BLOCKED modules (will cause ImportError): os, subprocess, shutil, socket, http, urllib, requests, ctypes, signal, multiprocessing, pathlib, importlib.
5. All Python scripts MUST print their final result to stdout as a valid JSON string.
6. The JSON output MUST contain a "values" key with a list of numbers suitable for charting.
7. Keep responses concise. You are running on a resource-constrained edge device.
8. When asked to think step by step, show your reasoning clearly before giving the final answer.
""".trimIndent()

    // ────────────────────────────────────────────
    //  Engine Lifecycle
    // ────────────────────────────────────────────

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext
        try {
            _pipelineState.value = PipelineState.LoadingModel
            val modelFile = modelManager.getModelFile()
            if (!modelFile.exists()) {
                _pipelineState.value = PipelineState.Error("Model not found")
                return@withContext
            }

            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU()
            )

            engine = Engine(config)
            engine!!.initialize()
            _pipelineState.value = PipelineState.Idle
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing LLM", e)
            _pipelineState.value = PipelineState.Error(e.message ?: "Failed to initialize LLM")
        }
    }

    // ────────────────────────────────────────────
    //  Streaming Response (token-by-token with 200ms buffer)
    // ────────────────────────────────────────────

    private suspend fun streamResponse(
        prompt: String,
        stepName: String,
        stepNumber: Int
    ): String = withContext(Dispatchers.IO) {
        val conversation = engine!!.createConversation()
        try {
            val fullPrompt = "$SYSTEM_PROMPT\n\n$prompt"
            val accumulated = StringBuilder()
            var lastUpdateTime = 0L

            conversation.sendMessageAsync(fullPrompt).collect { messageFragment ->
                val fragmentText = messageFragment.toString()
                accumulated.append(fragmentText)

                val now = System.currentTimeMillis()
                if (now - lastUpdateTime >= STREAM_UPDATE_INTERVAL_MS) {
                    _pipelineState.value = PipelineState.Streaming(
                        stepName = stepName,
                        stepNumber = stepNumber,
                        partialText = accumulated.toString()
                    )
                    lastUpdateTime = now
                }
            }

            // Final update to ensure we show the complete text
            _pipelineState.value = PipelineState.Streaming(
                stepName = stepName,
                stepNumber = stepNumber,
                partialText = accumulated.toString()
            )

            accumulated.toString()
        } finally {
            conversation.close()
        }
    }

    /** Non-streaming response for short internal calls (plausibility checks) */
    private suspend fun generateResponse(prompt: String): String = withContext(Dispatchers.IO) {
        val conversation = engine!!.createConversation()
        try {
            val fullPrompt = "$SYSTEM_PROMPT\n\n$prompt"
            val response = conversation.sendMessage(fullPrompt)
            response.toString()
        } finally {
            conversation.close()
        }
    }

    // ────────────────────────────────────────────
    //  Pipeline Entry Point
    // ────────────────────────────────────────────

    suspend fun runPipeline(metadata: String, dataFilePath: String, fileUri: Uri) = withContext(Dispatchers.IO) {
        if (engine == null) {
            _pipelineState.value = PipelineState.Error("LLM not initialized")
            return@withContext
        }

        cachedMetadata = metadata
        cachedDataFilePath = dataFilePath
        cachedFileContent = DataIngestion.readFullContent(context, fileUri)

        try {
            // ── Step 1: Schema Profiling (streamed with CoT) ──
            val schemaPrompt = """
Think step by step to analyze this data file.

DATA METADATA:
$metadata

INSTRUCTIONS:
1. First, list every column header you see.
2. For each column, examine the sample values and reason about the most likely data type (integer, float, categorical string, date, boolean).
3. Check for any missing, null, or empty values in the sample. Note the pattern.
4. Summarize your findings as a structured schema profile with a table of: Column Name | Data Type | Nullable | Notes.
""".trimIndent()
            cachedSchema = streamResponse(schemaPrompt, "Profiling Schema", 1)

            // ── Step 2: Relational Mapping (streamed with CoT) ──
            val relationalPrompt = """
Think step by step to create an Entity-Relationship mapping.

SCHEMA PROFILE:
$cachedSchema

INSTRUCTIONS:
1. Identify the primary entities represented in this data.
2. For each entity, list its attributes (the columns that belong to it).
3. Identify any relationships between entities (e.g., one-to-many, many-to-many).
4. If the data is a single flat table, describe it as one entity with all columns as attributes.
5. Output a clear text-based ER diagram.
""".trimIndent()
            cachedErDiagram = streamResponse(relationalPrompt, "Mapping Relational Structure", 2)

            // ── Step 3: Generate Suggestions ──
            val suggestionsPrompt = """
Based on the schema and structure below, suggest exactly $NUM_SUGGESTIONS different analyses that would provide useful insights from this data.

SCHEMA:
$cachedSchema

ER STRUCTURE:
$cachedErDiagram

For each suggestion, respond in EXACTLY this format (one per line):
SUGGESTION 1: [Short Title] | [One sentence description of what this analysis reveals]
SUGGESTION 2: [Short Title] | [One sentence description of what this analysis reveals]
SUGGESTION 3: [Short Title] | [One sentence description of what this analysis reveals]

Only suggest analyses that can be performed with the columns that exist in the schema. Do not suggest anything requiring external data.
""".trimIndent()

            val suggestionsRaw = streamResponse(suggestionsPrompt, "Generating Analysis Suggestions", 3)
            val suggestions = parseSuggestions(suggestionsRaw)

            _pipelineState.value = PipelineState.AwaitingSuggestionChoice(
                suggestions = suggestions,
                schema = cachedSchema,
                erDiagram = cachedErDiagram
            )

        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error", e)
            _pipelineState.value = PipelineState.Error(e.message ?: "Pipeline execution failed")
        }
    }

    // ────────────────────────────────────────────
    //  User Interaction: Choose a Suggestion
    // ────────────────────────────────────────────

    suspend fun submitSuggestionChoice(suggestion: AnalysisSuggestion) = withContext(Dispatchers.IO) {
        currentAnalysisDescription = "${suggestion.title}: ${suggestion.description}"
        generateAndValidateCode()
    }

    // ────────────────────────────────────────────
    //  User Interaction: Custom Request
    // ────────────────────────────────────────────

    suspend fun submitCustomRequest(request: String) = withContext(Dispatchers.IO) {
        try {
            _pipelineState.value = PipelineState.EvaluatingCustomRequest(request)

            val plausibilityPrompt = """
A user wants to perform this analysis on their data:
"$request"

AVAILABLE SCHEMA:
$cachedSchema

AVAILABLE COLUMNS (these are the ONLY columns that exist):
${cachedSchema}

Think step by step:
1. Does this request relate to columns that exist in the schema?
2. Can this analysis be performed using only pandas, numpy, and basic statistics?
3. Is the request clear enough to implement?

Respond in EXACTLY this format:
FEASIBLE: YES or NO
REASON: [One sentence explaining why or why not]
REFINED_PLAN: [If feasible, restate the analysis as a clear, specific plan referencing actual column names. If not feasible, write N/A]
""".trimIndent()

            val plausibilityResult = generateResponse(plausibilityPrompt)

            if (plausibilityResult.uppercase().contains("FEASIBLE: YES")) {
                // Extract refined plan
                val planRegex = Regex("REFINED_PLAN:\\s*(.+)", RegexOption.DOT_MATCHES_ALL)
                val refinedPlan = planRegex.find(plausibilityResult)?.groupValues?.get(1)?.trim()
                    ?: request

                _pipelineState.value = PipelineState.AwaitingPlanConfirmation(
                    originalRequest = request,
                    refinedPlan = refinedPlan,
                    schema = cachedSchema,
                    erDiagram = cachedErDiagram
                )
            } else {
                // Not feasible — explain why and re-show suggestions
                val reasonRegex = Regex("REASON:\\s*(.+?)(?=REFINED_PLAN|$)", RegexOption.DOT_MATCHES_ALL)
                val explanation = reasonRegex.find(plausibilityResult)?.groupValues?.get(1)?.trim()
                    ?: "This analysis cannot be performed with the available data columns."

                // Re-generate suggestions
                val suggestionsPrompt = """
The user asked for: "$request"
This was not feasible because: $explanation

Based on the schema below, suggest $NUM_SUGGESTIONS alternative analyses that ARE feasible.

SCHEMA:
$cachedSchema

Respond in EXACTLY this format:
SUGGESTION 1: [Short Title] | [One sentence description]
SUGGESTION 2: [Short Title] | [One sentence description]
SUGGESTION 3: [Short Title] | [One sentence description]
""".trimIndent()

                val altSuggestionsRaw = generateResponse(suggestionsPrompt)
                val altSuggestions = parseSuggestions(altSuggestionsRaw)

                _pipelineState.value = PipelineState.CustomRequestNotFeasible(
                    explanation = explanation,
                    suggestions = altSuggestions,
                    schema = cachedSchema,
                    erDiagram = cachedErDiagram
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Custom request evaluation failed", e)
            _pipelineState.value = PipelineState.Error(e.message ?: "Failed to evaluate request")
        }
    }

    // ────────────────────────────────────────────
    //  User Interaction: Confirm / Reject Plan
    // ────────────────────────────────────────────

    suspend fun confirmPlan(refinedPlan: String) = withContext(Dispatchers.IO) {
        currentAnalysisDescription = refinedPlan
        generateAndValidateCode()
    }

    fun rejectPlan() {
        // Go back to suggestions
        _pipelineState.value = PipelineState.AwaitingSuggestionChoice(
            suggestions = emptyList(), // Will trigger re-generation
            schema = cachedSchema,
            erDiagram = cachedErDiagram
        )
    }

    // ────────────────────────────────────────────
    //  User Interaction: Satisfaction Loop
    // ────────────────────────────────────────────

    suspend fun rejectSatisfaction() = withContext(Dispatchers.IO) {
        // User wants a different analysis — go back to suggestion generation
        try {
            val suggestionsPrompt = """
The user was not satisfied with the previous analysis: "$currentAnalysisDescription"
Suggest $NUM_SUGGESTIONS different analyses they might find more useful.

SCHEMA:
$cachedSchema

Respond in EXACTLY this format:
SUGGESTION 1: [Short Title] | [One sentence description]
SUGGESTION 2: [Short Title] | [One sentence description]
SUGGESTION 3: [Short Title] | [One sentence description]
""".trimIndent()

            val suggestionsRaw = generateResponse(suggestionsPrompt)
            val suggestions = parseSuggestions(suggestionsRaw)

            _pipelineState.value = PipelineState.AwaitingSuggestionChoice(
                suggestions = suggestions,
                schema = cachedSchema,
                erDiagram = cachedErDiagram
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to regenerate suggestions", e)
            _pipelineState.value = PipelineState.Error(e.message ?: "Failed to regenerate suggestions")
        }
    }

    // ────────────────────────────────────────────
    //  Code Generation + Self-Correcting Execution
    // ────────────────────────────────────────────

    private suspend fun generateAndValidateCode() {
        try {
            var pythonCode = generateCode(currentAnalysisDescription)
            var executionLog = StringBuilder()

            // Save the generated script so the user can access/share it
            val scriptPath = pythonExecutor.saveScriptToFile(pythonCode, "generated")

            // ── Compile/Runtime retry loop (max 3 attempts) ──
            var compileAttempt = 0
            var executionResult: ExecutionResult

            while (true) {
                compileAttempt++
                _pipelineState.value = PipelineState.ExecutingCode(
                    attempt = compileAttempt,
                    maxAttempts = MAX_COMPILE_RETRIES
                )

                executionResult = pythonExecutor.executeScript(pythonCode, cachedFileContent)
                executionLog.appendLine("── Attempt $compileAttempt ──")
                executionLog.appendLine("stdout: ${executionResult.stdout}")
                executionLog.appendLine("stderr: ${executionResult.stderr}")
                executionLog.appendLine("success: ${executionResult.success}")
                executionLog.appendLine()

                if (executionResult.success) {
                    break // Code ran without crashing
                }

                if (compileAttempt >= MAX_COMPILE_RETRIES) {
                    _pipelineState.value = PipelineState.Error(
                        "Code failed after $MAX_COMPILE_RETRIES attempts.\nLast error: ${executionResult.stderr}"
                    )
                    return
                }

                // Show the error before retrying
                _pipelineState.value = PipelineState.ValidationFailed(
                    errorType = "Compilation/Runtime Error",
                    errorMessage = executionResult.stderr,
                    attempt = compileAttempt,
                    maxAttempts = MAX_COMPILE_RETRIES
                )
                delay(1500) // Brief pause so user can see the error

                // Feed error back to model for self-correction
                pythonCode = selfCorrectCode(pythonCode, executionResult.stderr, "runtime")
                pythonExecutor.saveScriptToFile(pythonCode, "retry_$compileAttempt")
            }

            // ── Output validation retry loop (max 2 attempts) ──
            var outputAttempt = 0
            var validOutput = executionResult.stdout

            while (true) {
                outputAttempt++

                val isValidJson = try {
                    val json = org.json.JSONObject(validOutput)
                    json.has("values") && json.optJSONArray("values") != null
                } catch (e: Exception) {
                    false
                }

                if (isValidJson) {
                    break // Output is valid JSON with "values" key
                }

                if (outputAttempt >= MAX_OUTPUT_RETRIES) {
                    // Output is still bad after retries, but code ran — show what we have
                    executionLog.appendLine("── Output validation failed after $MAX_OUTPUT_RETRIES attempts ──")
                    executionLog.appendLine("Last output: $validOutput")
                    break
                }

                _pipelineState.value = PipelineState.ValidationFailed(
                    errorType = "Invalid Output Format",
                    errorMessage = "Expected JSON with a 'values' array but got:\n${validOutput.take(500)}",
                    attempt = outputAttempt,
                    maxAttempts = MAX_OUTPUT_RETRIES
                )
                delay(1500)

                // Feed output problem back to model
                pythonCode = selfCorrectCode(
                    pythonCode,
                    "Script ran but output is not valid JSON with a 'values' key. Actual output:\n${validOutput.take(500)}",
                    "output"
                )

                // Re-execute the corrected code
                _pipelineState.value = PipelineState.ExecutingCode(
                    attempt = compileAttempt,
                    maxAttempts = MAX_COMPILE_RETRIES
                )
                executionResult = pythonExecutor.executeScript(pythonCode, cachedFileContent)
                executionLog.appendLine("── Output fix attempt $outputAttempt ──")
                executionLog.appendLine("stdout: ${executionResult.stdout}")
                executionLog.appendLine("stderr: ${executionResult.stderr}")

                if (!executionResult.success) {
                    // Corrected code now crashes — treat as final error
                    _pipelineState.value = PipelineState.Error(
                        "Self-corrected code crashed:\n${executionResult.stderr}"
                    )
                    return
                }

                validOutput = executionResult.stdout
            }

            // ── Success — ask if user is satisfied ──
            _pipelineState.value = PipelineState.AwaitingSatisfaction(
                schema = cachedSchema,
                erDiagram = cachedErDiagram,
                analysisDescription = currentAnalysisDescription,
                code = pythonCode,
                executionOutput = validOutput,
                executionLog = executionLog.toString(),
                scriptPath = scriptPath
            )

        } catch (e: Exception) {
            Log.e(TAG, "Code generation/validation failed", e)
            _pipelineState.value = PipelineState.Error(e.message ?: "Code generation failed")
        }
    }

    private suspend fun generateCode(analysisDescription: String): String {
        val codePrompt = """
Think step by step to write a Python data analysis script.

TASK: $analysisDescription

DATA ACCESS: A string variable called DATA_CSV is already pre-loaded in the script's global scope. It contains the full CSV file content. To load it into a DataFrame, use:
  import pandas as pd
  from io import StringIO
  df = pd.read_csv(StringIO(DATA_CSV))

Do NOT simulate, fabricate, or hardcode any data. You MUST use the DATA_CSV variable to read the real data.

SCHEMA:
$cachedSchema

INSTRUCTIONS:
1. First, plan what pandas operations are needed for this analysis.
2. Then write the complete Python script.
3. The script MUST start by loading the real data from DATA_CSV using pd.read_csv(StringIO(DATA_CSV)).
4. Use pandas, numpy, json, io, math, statistics, and their standard dependencies.
5. BLOCKED modules (will crash): os, subprocess, shutil, socket, http, urllib, requests, ctypes, matplotlib, pathlib.
6. The script MUST print exactly ONE line to stdout: a valid JSON object.
7. The JSON object MUST have a "values" key containing a list of numbers suitable for a bar chart.
8. The JSON object SHOULD also have a "labels" key containing a list of string labels for each bar.
9. Do NOT use matplotlib or any plotting libraries.
10. Do NOT use open() to read files. Use DATA_CSV instead.
11. Do NOT simulate or generate fake data. The real data is in DATA_CSV.

Respond ONLY with the Python code inside a ```python ``` block. No other text outside the code block.
""".trimIndent()

        val rawResponse = streamResponse(codePrompt, "Generating Analysis Script", 4)

        // Extract code from markdown code block
        val codeRegex = Regex("```python\\s*([\\s\\S]*?)\\s*```")
        val matchResult = codeRegex.find(rawResponse)
        return matchResult?.groupValues?.get(1)?.trim() ?: rawResponse.trim()
    }

    private suspend fun selfCorrectCode(
        previousCode: String,
        errorMessage: String,
        errorType: String
    ): String {
        val correctionPrompt = """
The Python script you generated has a $errorType error.

PREVIOUS CODE:
```python
$previousCode
```

ERROR:
$errorMessage

Fix the script. Remember:
- The real data is available as a pre-loaded string variable called DATA_CSV. Load it with: df = pd.read_csv(StringIO(DATA_CSV))
- Do NOT simulate, fabricate, or hardcode data. Use DATA_CSV.
- Print exactly ONE line of valid JSON to stdout with a "values" key containing a list of numbers.
- Use pandas, numpy, json, io, math, statistics, and their standard dependencies.
- BLOCKED modules (will crash): os, subprocess, shutil, socket, http, urllib, requests, ctypes, matplotlib, pathlib.
- Do NOT use open() or matplotlib.

Respond ONLY with the corrected Python code inside a ```python ``` block.
""".trimIndent()

        val rawResponse = streamResponse(correctionPrompt, "Self-Correcting Script", 4)

        val codeRegex = Regex("```python\\s*([\\s\\S]*?)\\s*```")
        val matchResult = codeRegex.find(rawResponse)
        return matchResult?.groupValues?.get(1)?.trim() ?: rawResponse.trim()
    }

    // ────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────

    private fun parseSuggestions(raw: String): List<AnalysisSuggestion> {
        val suggestions = mutableListOf<AnalysisSuggestion>()
        val regex = Regex("SUGGESTION\\s*\\d+:\\s*(.+?)\\s*\\|\\s*(.+)")

        for (match in regex.findAll(raw)) {
            suggestions.add(
                AnalysisSuggestion(
                    title = match.groupValues[1].trim(),
                    description = match.groupValues[2].trim()
                )
            )
        }

        // Fallback if model didn't follow the format perfectly
        if (suggestions.isEmpty()) {
            suggestions.add(
                AnalysisSuggestion(
                    title = "Basic Statistical Summary",
                    description = "Compute mean, median, and standard deviation for all numeric columns."
                )
            )
        }

        return suggestions.take(NUM_SUGGESTIONS)
    }
}
