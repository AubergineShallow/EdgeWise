package com.localinsight.dataanalyzer

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var modelManager: ModelManager
    private lateinit var llmPipeline: LlmPipeline
    private lateinit var pythonExecutor: PythonExecutor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        modelManager = ModelManager(this)
        llmPipeline = LlmPipeline(this, modelManager)
        pythonExecutor = PythonExecutor(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DataAnalyzerScreen(
                        modelManager = modelManager,
                        llmPipeline = llmPipeline,
                        pythonExecutor = pythonExecutor
                    )
                }
            }
        }
    }
}

@Composable
fun DataAnalyzerScreen(
    modelManager: ModelManager,
    llmPipeline: LlmPipeline,
    pythonExecutor: PythonExecutor
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pipelineState by llmPipeline.pipelineState.collectAsState()
    val downloadProgress by modelManager.downloadProgress.collectAsState()

    var showDetailsDialog by remember { mutableStateOf(false) }
    var detailsContent by remember { mutableStateOf("") }
    var detailsTitle by remember { mutableStateOf("") }

    var chartEntryModel by remember { mutableStateOf(entryModelOf(1f to 2f, 2f to 5f, 3f to 4f, 4f to 8f, 5f to 6f)) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                llmPipeline.initialize()
                val metadata = DataIngestion.extractMetadata(context, it)
                llmPipeline.runPipeline(metadata, it.toString())

                // Assuming pipeline completes, we would then execute the python code
                if (llmPipeline.pipelineState.value is LlmPipeline.PipelineState.Completed) {
                    val code = (llmPipeline.pipelineState.value as LlmPipeline.PipelineState.Completed).code
                    try {
                        val outputJson = pythonExecutor.executeScript(code)
                        val json = JSONObject(outputJson)
                        val values = json.optJSONArray("values")
                        if (values != null && values.length() > 0) {
                            val entries = mutableListOf<FloatEntry>()
                            for (i in 0 until values.length()) {
                                entries.add(FloatEntry(i.toFloat(), values.getDouble(i).toFloat()))
                            }
                            chartEntryModel = entryModelOf(entries)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to parse json chart data", e)
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("LocalInsight Data Analyzer", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        if (!modelManager.isModelDownloaded()) {
            Text("Downloading Gemma 4 E2B Model...")
            LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                modelManager.downloadModel()
            }) {
                Text("Start Download")
            }
        } else {
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Select Data File (.csv, .xlsx, .pbix)")
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (val state = pipelineState) {
                is LlmPipeline.PipelineState.Idle -> Text("Ready")
                is LlmPipeline.PipelineState.LoadingModel -> Text("Loading LLM into Memory...")
                is LlmPipeline.PipelineState.ProfilingSchema -> Text("1. Profiling Schema...", style = MaterialTheme.typography.bodyLarge)
                is LlmPipeline.PipelineState.MappingRelations -> Text("2. Mapping Relational Structure...", style = MaterialTheme.typography.bodyLarge)
                is LlmPipeline.PipelineState.GeneratingCode -> Text("3. Generating Analytical Script...", style = MaterialTheme.typography.bodyLarge)
                is LlmPipeline.PipelineState.Error -> Text("Error: ${state.message}", color = MaterialTheme.colorScheme.error)
                is LlmPipeline.PipelineState.Completed -> {
                    Text("Pipeline Completed!", style = MaterialTheme.typography.titleLarge)

                    Spacer(modifier = Modifier.height(8.dp))
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

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Generated Chart", style = MaterialTheme.typography.titleMedium)
                    Chart(
                        chart = columnChart(),
                        model = chartEntryModel,
                        startAxis = rememberStartAxis(),
                        bottomAxis = rememberBottomAxis(),
                        modifier = Modifier.height(250.dp)
                    )
                }
            }
        }
    }

    if (showDetailsDialog) {
        AlertDialog(
            onDismissRequest = { showDetailsDialog = false },
            title = { Text(detailsTitle) },
            text = { Text(detailsContent, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { showDetailsDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
fun StepItem(title: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() }
    ) {
        Text(title, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
