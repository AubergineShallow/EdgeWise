package com.localinsight.dataanalyzer

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localinsight.dataanalyzer.data.DataIngestion
import com.localinsight.dataanalyzer.llm.LlmPipeline
import com.localinsight.dataanalyzer.modelmanager.ModelManager
import com.localinsight.dataanalyzer.python.PythonExecutor
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.entryModelOf
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var modelManager: ModelManager
    private lateinit var llmPipeline: LlmPipeline
    private lateinit var pythonExecutor: PythonExecutor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        modelManager = ModelManager(this)
        pythonExecutor = PythonExecutor(this)
        llmPipeline = LlmPipeline(this, modelManager, pythonExecutor)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DataAnalyzerScreen(
                        modelManager = modelManager,
                        llmPipeline = llmPipeline
                    )
                }
            }
        }
    }
}

@Composable
fun DataAnalyzerScreen(
    modelManager: ModelManager,
    llmPipeline: LlmPipeline
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pipelineState by llmPipeline.pipelineState.collectAsState()
    val downloadProgress by modelManager.downloadProgress.collectAsState()
    val scrollState = rememberScrollState()

    var showDetailsDialog by remember { mutableStateOf(false) }
    var detailsContent by remember { mutableStateOf("") }
    var detailsTitle by remember { mutableStateOf("") }
    var customRequestText by remember { mutableStateOf("") }

    // Auto-scroll to bottom when streaming
    LaunchedEffect(pipelineState) {
        if (pipelineState is LlmPipeline.PipelineState.Streaming) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        Text("LocalInsight Data Analyzer", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        if (!modelManager.isModelDownloaded()) {
            // ── Download Screen ──
            Text("Downloading Gemma 4 E2B Model...")
            LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { modelManager.downloadModel() }) {
                Text("Start Download")
            }
        } else {
            // ── Main App ──
            val filePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                uri?.let {
                    coroutineScope.launch {
                        llmPipeline.initialize()
                        val metadata = DataIngestion.extractMetadata(context, it)
                        llmPipeline.runPipeline(metadata, it.toString(), it)
                    }
                }
            }

            Button(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = pipelineState is LlmPipeline.PipelineState.Idle
                        || pipelineState is LlmPipeline.PipelineState.AwaitingSatisfaction
                        || pipelineState is LlmPipeline.PipelineState.Error
            ) {
                Text("Select Data File (.csv, .xlsx, .pbix)")
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (val state = pipelineState) {

                // ── Idle ──
                is LlmPipeline.PipelineState.Idle -> {
                    Text("Ready", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // ── Loading Model ──
                is LlmPipeline.PipelineState.LoadingModel -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Loading LLM into memory...")
                    }
                }

                // ── Streaming (live token output) ──
                is LlmPipeline.PipelineState.Streaming -> {
                    StepHeader(number = state.stepNumber, title = state.stepName, inProgress = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    StreamingTextBox(text = state.partialText)
                }

                // ── Awaiting Suggestion Choice ──
                is LlmPipeline.PipelineState.AwaitingSuggestionChoice -> {
                    StepHeader(number = 3, title = "Choose an Analysis", inProgress = false)
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "The model has analyzed your data. Choose one of the suggested analyses, or describe your own:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    state.suggestions.forEachIndexed { index, suggestion ->
                        SuggestionCard(
                            index = index + 1,
                            suggestion = suggestion,
                            onClick = {
                                coroutineScope.launch {
                                    llmPipeline.submitSuggestionChoice(suggestion)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Or describe a custom analysis:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = customRequestText,
                        onValueChange = { customRequestText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g., Correlate sleep hours with screen time...") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (customRequestText.isNotBlank()) {
                                val request = customRequestText
                                customRequestText = ""
                                coroutineScope.launch {
                                    llmPipeline.submitCustomRequest(request)
                                }
                            }
                        }),
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (customRequestText.isNotBlank()) {
                                val request = customRequestText
                                customRequestText = ""
                                coroutineScope.launch {
                                    llmPipeline.submitCustomRequest(request)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = customRequestText.isNotBlank()
                    ) {
                        Text("Submit Custom Analysis")
                    }

                    // Quick-access to view schema/ER from earlier steps
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                detailsTitle = "Schema Profile"
                                detailsContent = state.schema
                                showDetailsDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("View Schema", fontSize = 12.sp) }

                        OutlinedButton(
                            onClick = {
                                detailsTitle = "ER Diagram"
                                detailsContent = state.erDiagram
                                showDetailsDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("View ER Diagram", fontSize = 12.sp) }
                    }
                }

                // ── Evaluating Custom Request ──
                is LlmPipeline.PipelineState.EvaluatingCustomRequest -> {
                    StepHeader(number = 3, title = "Evaluating Your Request", inProgress = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Your request:", style = MaterialTheme.typography.labelMedium)
                            Text(state.request, style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Checking plausibility...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // ── Custom Request Not Feasible ──
                is LlmPipeline.PipelineState.CustomRequestNotFeasible -> {
                    StepHeader(number = 3, title = "Request Not Feasible", inProgress = false)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                state.explanation,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Try one of these instead:", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(8.dp))

                    state.suggestions.forEachIndexed { index, suggestion ->
                        SuggestionCard(
                            index = index + 1,
                            suggestion = suggestion,
                            onClick = {
                                coroutineScope.launch {
                                    llmPipeline.submitSuggestionChoice(suggestion)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customRequestText,
                        onValueChange = { customRequestText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Try a different request...") },
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (customRequestText.isNotBlank()) {
                                val request = customRequestText
                                customRequestText = ""
                                coroutineScope.launch {
                                    llmPipeline.submitCustomRequest(request)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = customRequestText.isNotBlank()
                    ) {
                        Text("Submit Custom Analysis")
                    }
                }

                // ── Awaiting Plan Confirmation ──
                is LlmPipeline.PipelineState.AwaitingPlanConfirmation -> {
                    StepHeader(number = 3, title = "Confirm Analysis Plan", inProgress = false)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Your request:", style = MaterialTheme.typography.labelMedium)
                            Text(state.originalRequest, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(12.dp))
                            Divider()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Refined plan:", style = MaterialTheme.typography.labelMedium)
                            Text(
                                state.refinedPlan,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { llmPipeline.rejectPlan() },
                            modifier = Modifier.weight(1f)
                        ) { Text("Go Back") }

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    llmPipeline.confirmPlan(state.refinedPlan)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Looks Good") }
                    }
                }

                // ── Executing Code ──
                is LlmPipeline.PipelineState.ExecutingCode -> {
                    StepHeader(number = 5, title = "Running Analysis Script", inProgress = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Executing Python script...")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Attempt ${state.attempt} of ${state.maxAttempts}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // ── Validation Failed (showing error before retry) ──
                is LlmPipeline.PipelineState.ValidationFailed -> {
                    StepHeader(number = 5, title = "Self-Correcting...", inProgress = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                state.errorType,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                state.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 10,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Feeding error back to model... (attempt ${state.attempt}/${state.maxAttempts})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                // ── Awaiting Satisfaction ──
                is LlmPipeline.PipelineState.AwaitingSatisfaction -> {
                    StepHeader(number = 6, title = "Results", inProgress = false)
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "Analysis: ${state.analysisDescription}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Chart
                    var chartLabels by remember(state.executionOutput) { mutableStateOf<List<String>>(emptyList()) }
                    val chartModel = remember(state.executionOutput) {
                        try {
                            val json = JSONObject(state.executionOutput)
                            val values = json.optJSONArray("values")
                            val labelsArray = json.optJSONArray("labels")
                            
                            if (values != null && values.length() > 0) {
                                val entries = mutableListOf<FloatEntry>()
                                val parsedLabels = mutableListOf<String>()
                                
                                for (i in 0 until values.length()) {
                                    entries.add(FloatEntry(i.toFloat(), values.getDouble(i).toFloat()))
                                    parsedLabels.add(labelsArray?.optString(i) ?: i.toString())
                                }
                                chartLabels = parsedLabels
                                entryModelOf(entries)
                            } else null
                        } catch (e: Exception) { null }
                    }

                    if (chartModel != null) {
                        Text("Generated Chart", style = MaterialTheme.typography.titleMedium)
                        
                        val bottomAxisValueFormatter = com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter<com.patrykandpatrick.vico.core.axis.AxisPosition.Horizontal.Bottom> { value, _ ->
                            chartLabels.getOrNull(value.toInt()) ?: value.toString()
                        }
                        
                        Chart(
                            chart = columnChart(),
                            model = chartModel,
                            startAxis = rememberStartAxis(),
                            bottomAxis = rememberBottomAxis(valueFormatter = bottomAxisValueFormatter),
                            modifier = Modifier.height(250.dp)
                        )
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Raw Output", style = MaterialTheme.typography.labelMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    state.executionOutput.take(1000),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Detail cards
                    StepItem(title = "View Schema Profile") {
                        detailsTitle = "Schema Profile"
                        detailsContent = state.schema
                        showDetailsDialog = true
                    }
                    StepItem(title = "View ER Diagram") {
                        detailsTitle = "ER Diagram"
                        detailsContent = state.erDiagram
                        showDetailsDialog = true
                    }
                    StepItem(title = "View Generated Code") {
                        detailsTitle = "Python Code"
                        detailsContent = state.code
                        showDetailsDialog = true
                    }
                    StepItem(title = "View Execution Log") {
                        detailsTitle = "Execution Log"
                        detailsContent = state.executionLog
                        showDetailsDialog = true
                    }

                    // Show script file path so user can share it
                    if (state.scriptPath != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Script saved to:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    state.scriptPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Satisfaction prompt
                    Text(
                        "Are you satisfied with this analysis?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    llmPipeline.rejectSatisfaction()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Try Different") }

                        Button(
                            onClick = { /* User is done — stay on results */ },
                            modifier = Modifier.weight(1f)
                        ) { Text("Looks Good!") }
                    }
                }

                // ── Error ──
                is LlmPipeline.PipelineState.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Error",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                state.message,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Details Dialog ──
    if (showDetailsDialog) {
        AlertDialog(
            onDismissRequest = { showDetailsDialog = false },
            title = { Text(detailsTitle) },
            text = {
                Text(
                    detailsContent,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    fontFamily = if (detailsTitle == "Python Code" || detailsTitle == "Execution Log")
                        FontFamily.Monospace else FontFamily.Default,
                    fontSize = if (detailsTitle == "Python Code" || detailsTitle == "Execution Log")
                        12.sp else 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showDetailsDialog = false }) { Text("Close") }
            }
        )
    }
}

// ────────────────────────────────────────────
//  Reusable Composables
// ────────────────────────────────────────────

@Composable
fun StepHeader(number: Int, title: String, inProgress: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (inProgress) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primaryContainer
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$number",
                color = if (inProgress) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (inProgress) {
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
fun StreamingTextBox(text: String) {
    val innerScrollState = rememberScrollState()

    LaunchedEffect(text) {
        innerScrollState.animateScrollTo(innerScrollState.maxValue)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp, max = 300.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            text = text.ifEmpty { "Thinking..." },
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(innerScrollState),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
fun SuggestionCard(
    index: Int,
    suggestion: LlmPipeline.AnalysisSuggestion,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Option $index: ${suggestion.title}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                suggestion.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun StepItem(title: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() }
    ) {
        Text(
            title,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
