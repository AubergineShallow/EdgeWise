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
    private val templateSelector = TemplateSelector()

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
3. AVAILABLE Python libraries: pandas, numpy, json, io, math, statistics, collections, re, datetime, csv.
4. NOT INSTALLED (will crash): scipy, sklearn, matplotlib, seaborn, plotly. Do NOT import these.
5. BLOCKED modules: subprocess, shutil, socket, http, urllib, requests, ctypes, signal, multiprocessing, importlib.
6. All Python scripts MUST print their final result to stdout as a valid JSON string.
7. The JSON output MUST contain a "values" key with a list of numbers suitable for charting.
8. Keep code SHORT (under 40 lines). You are on a resource-constrained edge device with limited token output.
9. When asked to think step by step, show your reasoning clearly before giving the final answer.
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
            // ── Try template-based generation first ──
            var pythonCode = generateFromTemplateOrFreeform(currentAnalysisDescription)
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

    /**
     * Try template-based generation first. If no template matches or the template
     * rendering fails, fall back to freeform LLM code generation.
     */
    private suspend fun generateFromTemplateOrFreeform(analysisDescription: String): String {
        val columnNames = extractColumnNames()
        val columnTypes = classifyColumns()

        // Layer 1: Keyword-based template matching
        val match = templateSelector.classify(analysisDescription, columnNames, columnTypes)

        if (match != null) {
            Log.d(TAG, "Template matched: ${match.template.id} (score=${match.score})")

            // Layer 2: Fill in missing params via short LLM prompt
            val allParams = mutableMapOf<String, String>()
            allParams.putAll(match.inferredParams)

            val extractionPrompt = templateSelector.buildParamExtractionPrompt(
                match.template, analysisDescription, columnNames, columnTypes, allParams
            )

            if (extractionPrompt != null) {
                val paramResponse = generateResponse(extractionPrompt)
                val llmParams = templateSelector.parseParamResponse(
                    paramResponse, match.template.requiredParams
                )
                allParams.putAll(llmParams)
            }

            // Render the template
            val rendered = match.template.render(allParams)
            if (rendered != null) {
                Log.d(TAG, "Template rendered successfully with params: $allParams")
                _pipelineState.value = PipelineState.Streaming(
                    stepName = "Generating Analysis Script",
                    stepNumber = 4,
                    partialText = "Using template: ${match.template.name}\n\n$rendered"
                )
                return rendered
            }

            Log.w(TAG, "Template render failed (missing params: ${match.template.requiredParams - allParams.keys}), falling back to freeform")
        } else {
            Log.d(TAG, "No template match, using freeform generation")
        }

        // Layer 3: Freeform fallback
        return generateCodeFreeform(analysisDescription)
    }

    private suspend fun generateCodeFreeform(analysisDescription: String): String {
        val codePrompt = """
Write a short Python script for this data analysis task.

TASK: $analysisDescription

DATA ACCESS: The variable DATA_CSV is ALREADY DEFINED in the global scope. Do NOT redefine it. Load with:
  import pandas as pd
  from io import StringIO
  df = pd.read_csv(StringIO(DATA_CSV))

SCHEMA:
$cachedSchema

RULES:
1. DATA_CSV is already defined. Do NOT write DATA_CSV = anything. Just read it.
2. AVAILABLE: pandas, numpy, json, io, math, statistics, collections, re, datetime, csv.
3. NOT INSTALLED (will crash if imported): scipy, sklearn, matplotlib, seaborn, plotly.
4. The chart MUST contain MULTIPLE data points. Group or bin the data (e.g. df.groupby).
5. Print exactly ONE line: a JSON object with "values" (list of numbers), "labels" (list of strings), and "chart_type" (one of: bar, line, pie, histogram, heatmap).
6. Keep the script under 30 lines total. No comments needed.
7. Do NOT use open() to read files.

Respond ONLY with the Python code inside a ```python ``` block.
""".trimIndent()

        val rawResponse = streamResponse(codePrompt, "Generating Analysis Script", 4)

        val codeRegex = Regex("```python\\s*([\\s\\S]*?)\\s*```")
        val matchResult = codeRegex.find(rawResponse)
        val code = matchResult?.groupValues?.get(1)?.trim() ?: rawResponse.trim()
        return sanitizeCode(code)
    }

    // ────────────────────────────────────────────
    //  Column Helpers
    // ────────────────────────────────────────────

    private fun extractColumnNames(): List<String> {
        val firstLine = cachedFileContent.lineSequence().firstOrNull() ?: return emptyList()
        return firstLine.split(",").map { it.trim() }
    }

    private fun classifyColumns(): Map<String, String> {
        val lines = cachedFileContent.lines()
        if (lines.size < 2) return emptyMap()
        val headers = lines[0].split(",").map { it.trim() }
        val sampleRows = lines.drop(1).take(5) // Check first 5 data rows

        return headers.mapIndexed { index, header ->
            val sampleValues = sampleRows.mapNotNull { row ->
                row.split(",").getOrNull(index)?.trim()
            }
            val numericCount = sampleValues.count { it.toDoubleOrNull() != null }
            val type = if (numericCount > sampleValues.size / 2) "numeric" else "categorical"
            header to type
        }.toMap()
    }

    /**
     * Strip any DATA_CSV = "..." or DATA_CSV = '''...''' assignments that the model
     * might hardcode. The real DATA_CSV is injected by PythonExecutor at runtime.
     */
    private fun sanitizeCode(code: String): String {
        // Remove multi-line DATA_CSV = """...""" or DATA_CSV = '''...'''
        var cleaned = code.replace(
            Regex("""DATA_CSV\s*=\s*"{3}[\s\S]*?"{3}"""), ""
        )
        cleaned = cleaned.replace(
            Regex("""DATA_CSV\s*=\s*'{3}[\s\S]*?'{3}"""), ""
        )
        // Remove single-line DATA_CSV = "..." or DATA_CSV = '...'
        cleaned = cleaned.replace(
            Regex("""DATA_CSV\s*=\s*"[^"]*""""), ""
        )
        cleaned = cleaned.replace(
            Regex("""DATA_CSV\s*=\s*'[^']*'"""), ""
        )
        // Remove any remaining DATA_CSV = ... (single line, e.g. DATA_CSV = some_var)
        cleaned = cleaned.replace(
            Regex("""(?m)^DATA_CSV\s*=\s*[^\n]*$"""), ""
        )
        // Clean up excessive blank lines left behind
        cleaned = cleaned.replace(Regex("""\n{3,}"""), "\n\n")
        return cleaned.trim()
    }

    private suspend fun selfCorrectCode(
        previousCode: String,
        errorMessage: String,
        errorType: String
    ): String {
        val correctionPrompt = """
The Python script has a $errorType error.

PREVIOUS CODE:
```python
$previousCode
```

ERROR:
$errorMessage

Fix the script. Key rules:
- DATA_CSV is ALREADY DEFINED. Do NOT redefine it. Just use: df = pd.read_csv(StringIO(DATA_CSV))
- AVAILABLE: pandas, numpy, json, io, math, statistics.
- NOT INSTALLED (do NOT import): scipy, sklearn, matplotlib, seaborn, plotly.
- The chart MUST contain MULTIPLE data points (e.g. df.groupby). Do NOT just output a single number like a correlation coefficient.
- Print ONE line of JSON with a "values" key (list of numbers) and "labels" key (list of strings). The lists MUST have at least 3 items.
- Keep code under 30 lines. No comments.

Respond ONLY with the corrected Python code inside a ```python ``` block.
""".trimIndent()

        val rawResponse = streamResponse(correctionPrompt, "Self-Correcting Script", 4)

        val codeRegex = Regex("```python\\s*([\\s\\S]*?)\\s*```")
        val matchResult = codeRegex.find(rawResponse)
        val code = matchResult?.groupValues?.get(1)?.trim() ?: rawResponse.trim()
        return sanitizeCode(code)
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
