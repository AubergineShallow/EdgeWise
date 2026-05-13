package com.localinsight.dataanalyzer.llm

import android.util.Log

/**
 * Expert system / decision tree that classifies analysis descriptions
 * into the best-matching template from the TemplateRegistry.
 *
 * Works in two layers:
 * 1. Keyword-based scoring (deterministic, zero-cost)
 * 2. Schema-aware column type matching (refines template selection)
 *
 * If no template scores above the confidence threshold, returns null
 * so the caller can fall back to freeform LLM code generation.
 */
class TemplateSelector {

    private val TAG = "TemplateSelector"

    data class TemplateMatch(
        val template: AnalysisTemplate,
        val score: Float,
        val inferredParams: Map<String, String>
    )

    // ── Keyword → Template ID mapping with weights ──

    private data class KeywordRule(
        val keywords: List<String>,
        val templateId: String,
        val weight: Float = 1.0f
    )

    private val rules = listOf(
        // Aggregation patterns
        KeywordRule(listOf("average", "mean", "avg"), "bar_grouped_mean", 2.0f),
        KeywordRule(listOf("sum", "total"), "bar_grouped_sum", 2.0f),
        KeywordRule(listOf("median", "middle value"), "bar_grouped_median", 2.0f),
        KeywordRule(listOf("count", "how many", "number of", "occurrences"), "bar_value_counts", 2.0f),

        // Ranking patterns
        KeywordRule(listOf("top", "highest", "best", "most", "largest"), "bar_top_n", 2.0f),
        KeywordRule(listOf("bottom", "lowest", "worst", "least", "smallest"), "bar_bottom_n", 2.0f),

        // Comparison
        KeywordRule(listOf("compare", "versus", "vs", "side by side"), "bar_comparison", 1.5f),

        // Data quality
        KeywordRule(listOf("missing", "null", "empty", "data quality", "incomplete"), "bar_missing_data", 2.5f),
        KeywordRule(listOf("outlier", "anomaly", "unusual", "extreme"), "bar_outlier_counts", 2.5f),

        // Statistical
        KeywordRule(listOf("percentile", "quartile", "quantile"), "bar_percentile", 2.0f),
        KeywordRule(listOf("cross tab", "crosstab", "contingency", "pivot"), "bar_cross_tab", 2.0f),

        // Trend / Line
        KeywordRule(listOf("trend", "over time", "change over", "progression", "growth"), "line_trend", 2.0f),
        KeywordRule(listOf("cumulative", "running total", "accumulated"), "line_cumulative", 2.0f),

        // Distribution
        KeywordRule(listOf("pie", "proportion", "share", "percentage breakdown"), "pie_distribution", 2.0f),
        KeywordRule(listOf("binary", "yes no", "true false", "positive negative"), "pie_binary", 2.0f),
        KeywordRule(listOf("histogram", "frequency distribution", "bins"), "histogram_distribution", 2.0f),

        // Correlation / Heatmap
        KeywordRule(listOf("correlation", "correlate", "heatmap", "relationship between", "matrix"), "heatmap_correlation", 2.0f),

        // Contextual boosters (these add score to already-matched templates)
        KeywordRule(listOf("by", "per", "group by", "grouped", "for each"), "bar_grouped_mean", 0.5f),
        KeywordRule(listOf("distribution", "spread"), "histogram_distribution", 1.0f),
        KeywordRule(listOf("distribution", "breakdown"), "pie_distribution", 0.8f)
    )

    // Minimum score needed to consider a template match valid
    private val CONFIDENCE_THRESHOLD = 1.5f

    /**
     * Classify an analysis description into the best-matching template.
     *
     * @param description The analysis task description (e.g. "average sleep hours by gender")
     * @param columnNames List of column names from the dataset
     * @param columnTypes Map of column name to type ("numeric" or "categorical")
     * @return The best TemplateMatch, or null if no template fits
     */
    fun classify(
        description: String,
        columnNames: List<String>,
        columnTypes: Map<String, String>
    ): TemplateMatch? {
        val desc = description.lowercase()
        val scores = mutableMapOf<String, Float>()

        // Score each rule
        for (rule in rules) {
            for (keyword in rule.keywords) {
                if (desc.contains(keyword)) {
                    scores[rule.templateId] = (scores[rule.templateId] ?: 0f) + rule.weight
                }
            }
        }

        if (scores.isEmpty()) {
            Log.d(TAG, "No keyword matches for: $description")
            return null
        }

        // Find the best template
        val bestEntry = scores.maxByOrNull { it.value } ?: return null

        if (bestEntry.value < CONFIDENCE_THRESHOLD) {
            Log.d(TAG, "Best score ${bestEntry.value} below threshold for: $description")
            return null
        }

        val template = TemplateRegistry.get(bestEntry.key)
        if (template == null) {
            Log.e(TAG, "Template ${bestEntry.key} not found in registry")
            return null
        }

        // Try to infer parameters from description + schema
        val inferredParams = inferParameters(desc, template, columnNames, columnTypes)

        Log.d(TAG, "Matched template: ${template.id} (score=${bestEntry.value}), params=$inferredParams")

        return TemplateMatch(
            template = template,
            score = bestEntry.value,
            inferredParams = inferredParams
        )
    }

    /**
     * Attempt to deterministically infer template parameters from the description
     * and column metadata. Returns partial params — LLM fills in the gaps.
     */
    private fun inferParameters(
        description: String,
        template: AnalysisTemplate,
        columnNames: List<String>,
        columnTypes: Map<String, String>
    ): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val numericCols = columnTypes.filter { it.value == "numeric" }.keys.toList()
        val categoricalCols = columnTypes.filter { it.value == "categorical" }.keys.toList()

        // Check which column names appear directly in the description
        val mentionedColumns = columnNames.filter { col ->
            description.contains(col.lowercase().replace("_", " ")) ||
            description.contains(col.lowercase())
        }

        for (param in template.requiredParams) {
            when (param) {
                "groupCol" -> {
                    // Prefer a mentioned categorical column, else first categorical
                    val mentioned = mentionedColumns.firstOrNull { it in categoricalCols }
                    if (mentioned != null) params[param] = mentioned
                }
                "valueCol", "yCol", "numericCol" -> {
                    // Prefer a mentioned numeric column
                    val mentioned = mentionedColumns.firstOrNull { it in numericCols }
                    if (mentioned != null) params[param] = mentioned
                }
                "categoryCol" -> {
                    val mentioned = mentionedColumns.firstOrNull { it in categoricalCols }
                    if (mentioned != null) params[param] = mentioned
                }
                "binaryCol" -> {
                    val mentioned = mentionedColumns.firstOrNull { it in categoricalCols }
                    if (mentioned != null) params[param] = mentioned
                }
                "xCol" -> {
                    // For trends, pick the first mentioned column or a likely sequential column
                    val mentioned = mentionedColumns.firstOrNull()
                    if (mentioned != null) params[param] = mentioned
                }
                "rowCol" -> {
                    if (mentionedColumns.size >= 2) {
                        val cat = mentionedColumns.firstOrNull { it in categoricalCols }
                        if (cat != null) params[param] = cat
                    }
                }
                "colCol" -> {
                    if (mentionedColumns.size >= 2) {
                        val cats = mentionedColumns.filter { it in categoricalCols }
                        if (cats.size >= 2) params[param] = cats[1]
                    }
                }
                // "columns" param for bar_comparison is handled by LLM
            }
        }

        return params
    }

    /**
     * Build the LLM prompt to extract any missing template parameters.
     * This is a very short, focused prompt — much more reliable than freeform code gen.
     */
    fun buildParamExtractionPrompt(
        template: AnalysisTemplate,
        analysisDescription: String,
        columnNames: List<String>,
        columnTypes: Map<String, String>,
        alreadyInferred: Map<String, String>
    ): String? {
        val missingParams = template.requiredParams.filter { it !in alreadyInferred }
        if (missingParams.isEmpty()) return null // All params already inferred

        val numericCols = columnTypes.filter { it.value == "numeric" }.keys.toList()
        val categoricalCols = columnTypes.filter { it.value == "categorical" }.keys.toList()

        val colInfo = buildString {
            appendLine("Numeric columns: ${numericCols.joinToString(", ")}")
            appendLine("Categorical columns: ${categoricalCols.joinToString(", ")}")
        }

        val paramDescriptions = missingParams.joinToString("\n") { param ->
            when (param) {
                "groupCol", "categoryCol", "rowCol", "colCol", "binaryCol" ->
                    "$param=<pick a categorical column>"
                "valueCol", "yCol", "numericCol" ->
                    "$param=<pick a numeric column>"
                "xCol" ->
                    "$param=<pick the x-axis column>"
                "columns" ->
                    "$param=[\"col1\", \"col2\", \"col3\"] (pick 2-5 numeric columns as a Python list)"
                else -> "$param=<value>"
            }
        }

        return """
Given this analysis: "$analysisDescription"

$colInfo

Pick the best column for each parameter below.
Respond EXACTLY in this format, one per line:
$paramDescriptions
""".trimIndent()
    }

    /**
     * Parse the LLM's parameter extraction response.
     * Expects lines like: groupCol=gender, valueCol=sleep_hours
     */
    fun parseParamResponse(
        response: String,
        requiredParams: List<String>
    ): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val lines = response.lines()

        for (param in requiredParams) {
            for (line in lines) {
                val regex = Regex("""$param\s*=\s*(.+)""", RegexOption.IGNORE_CASE)
                val match = regex.find(line.trim())
                if (match != null) {
                    params[param] = match.groupValues[1].trim()
                    break
                }
            }
        }

        return params
    }
}
