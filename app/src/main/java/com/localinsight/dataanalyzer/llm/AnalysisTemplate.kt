package com.localinsight.dataanalyzer.llm

/**
 * A pre-built Python script template with placeholder slots.
 * The LLM only needs to fill in column names — not write code from scratch.
 */
data class AnalysisTemplate(
    val id: String,
    val name: String,
    val chartType: String,          // "bar", "line", "pie", "histogram", "heatmap"
    val description: String,
    val keywords: List<String>,
    val requiredParams: List<String>,
    val templateCode: String        // Python code with {{placeholder}} slots
) {
    /**
     * Render the template by substituting parameter placeholders.
     * Returns null if any required parameter is missing.
     */
    fun render(params: Map<String, String>): String? {
        var code = templateCode
        for (param in requiredParams) {
            val value = params[param] ?: return null
            code = code.replace("{{$param}}", value)
        }
        return code
    }
}

/**
 * Registry of all pre-built analysis templates.
 * Each template is a tested Python script that reads data via load_data() and outputs JSON.
 */
object TemplateRegistry {

    fun get(id: String): AnalysisTemplate? = templates.find { it.id == id }

    fun all(): List<AnalysisTemplate> = templates

    private val templates = listOf(

        // ───────────────── BAR CHARTS ─────────────────

        AnalysisTemplate(
            id = "bar_grouped_mean",
            name = "Average by Category",
            chartType = "bar",
            description = "Mean of a numeric column grouped by a categorical column",
            keywords = listOf("average", "mean", "avg", "by", "per", "group"),
            requiredParams = listOf("groupCol", "valueCol"),
            templateCode = """
import pandas as pd

import json

df = load_data()
grouped = df.groupby('{{groupCol}}')['{{valueCol}}'].mean().round(2)
result = {"values": grouped.tolist(), "labels": grouped.index.astype(str).tolist(), "chart_type": "bar"}
print(json.dumps(result))
""".trimIndent()
        ),

        AnalysisTemplate(
            id = "bar_grouped_sum",
            name = "Sum by Category",
            chartType = "bar",
            description = "Sum of a numeric column grouped by a categorical column",
            keywords = listOf("sum", "total", "aggregate", "by", "per"),
            requiredParams = listOf("groupCol", "valueCol"),
            templateCode = """
import pandas as pd

import json

df = load_data()
grouped = df.groupby('{{groupCol}}')['{{valueCol}}'].sum().round(2)
result = {"values": grouped.tolist(), "labels": grouped.index.astype(str).tolist(), "chart_type": "bar"}
print(json.dumps(result))
""".trimIndent()
        ),

        AnalysisTemplate(
            id = "bar_grouped_median",
            name = "Median by Category",
            chartType = "bar",
            description = "Median of a numeric column grouped by a categorical column",
            keywords = listOf("median", "middle", "by", "per"),
            requiredParams = listOf("groupCol", "valueCol"),
            templateCode = """
import pandas as pd

import json

df = load_data()
grouped = df.groupby('{{groupCol}}')['{{valueCol}}'].median().round(2)
result = {"values": grouped.tolist(), "labels": grouped.index.astype(str).tolist(), "chart_type": "bar"}
print(json.dumps(result))
""".trimIndent()
        ),

        AnalysisTemplate(
            id = "bar_value_counts",
            name = "Category Counts",
            chartType = "bar",
            description = "Count occurrences of each category in a column",
            keywords = listOf("count", "frequency", "how many", "occurrences", "number of"),
            requiredParams = listOf("categoryCol"),
            templateCode = """
import pandas as pd

import json

df = load_data()
counts = df['{{categoryCol}}'].value_counts()
result = {"values": counts.tolist(), "labels": counts.index.astype(str).tolist(), "chart_type": "bar"}
print(json.dumps(result))
""".trimIndent()
        ),

        AnalysisTemplate(
            id = "bar_top_n",
            name = "Top N Rankings",
            chartType = "bar",
            description = "Top N values of a metric grouped by category",
            keywords = listOf("top", "highest", "best", "ranking", "most", "largest"),
            requiredParams = listOf("groupCol", "valueCol"),
            templateCode = """
import pandas as pd

import json

df = load_data()
top = df.groupby('{{groupCol}}')['{{valueCol}}'].mean().nlargest(10).round(2)
result = {"values": top.tolist(), "labels": top.index.astype(str).tolist(), "chart_type": "bar"}
print(json.dumps(result))
""".trimIndent()
        ),

        AnalysisTemplate(
            id = "bar_bottom_n",
            name = "Bottom N Rankings",
            chartType = "bar",
            description = "Bottom N values of a metric grouped by category",
            keywords = listOf("bottom", "lowest", "worst", "least", "smallest"),
            requiredParams = listOf("groupCol", "valueCol"),
            templateCode = """
import pandas as pd

import json

df = load_data()
bottom = df.groupby('{{groupCol}}')['{{valueCol}}'].mean().nsmallest(10).round(2)
result = {"values": bottom.tolist(), "labels": bottom.index.astype(str).tolist(), "chart_type": "bar"}
print(json.dumps(result))
""".trimIndent()
        ),

        AnalysisTemplate(
            id = "bar_comparison",
            name = "Column Comparison",
            chartType = "bar",
            description = "Compare means of multiple numeric columns side by side",
            keywords = listOf("compare", "versus", "vs", "difference", "side by side"),
            requiredParams = listOf("columns"),
            templateCode = """
import pandas as pd

import json

df = load_data()
cols = {{columns}}
means = [round(float(df[c].mean()), 2) for c in cols]
result = {"values": means, "labels": cols, "chart_type": "bar"}
print(json.dumps(result))
""".trimIndent()
        ),

        AnalysisTemplate(
            id = "bar_missing_data",
            name = "Missing Data Audit",
            chartType = "bar",
            description = "Count of missing/null values per column",
            keywords = listOf("missing", "null", "empty", "incomplete", "na", "data quality"),
            requiredParams = emptyList(),
            templateCode = """
import pandas as pd

import json

df = load_data()
missing = df.isnull().sum()
missing = missing[missing > 0]
if len(missing) == 0:
    result = {"values": [0], "labels": ["No missing data"], "chart_type": "bar"}
else:
    result = {"values": missing.tolist(), "labels": missing.index.tolist(), "chart_type": "bar"}
print(json.dumps(result))
""".trimIndent()
        ),

        AnalysisTemplate(
            id = "bar_outlier_counts",
            name = "Outlier Detection",
            chartType = "bar",
            description = "Count outliers per numeric column using IQR method",
            keywords = listOf("outlier", "anomaly", "unusual", "extreme", "iqr"),
            requiredParams = emptyList(),
            templateCode = """
import pandas as pd

import json
import numpy as np

df = load_data()
numeric_cols = df.select_dtypes(include=[np.number]).columns.tolist()
outlier_counts = []
for col in numeric_cols:
    Q1 = df[col].quantile(0.25)
    Q3 = df[col].quantile(0.75)
    IQR = Q3 - Q1
    outliers = int(((df[col] < Q1 - 1.5 * IQR) | (df[col] > Q3 + 1.5 * IQR)).sum())
    outlier_counts.append(outliers)
result = {"values": outlier_counts, "labels": numeric_cols, "chart_type": "bar"}
print(json.dumps(result))
""".trimIndent()
        ),

        AnalysisTemplate(
            id = "bar_percentile",
            name = "Percentile Breakdown",
            chartType = "bar",
            description = "25th, 50th, and 75th percentile of a numeric column",
            keywords = listOf("percentile", "quartile", "quantile", "p25", "p50", "p75"),
            requiredParams = listOf("numericCol"),
            templateCode = """
import pandas as pd

import json

df = load_data()
col = '{{numericCol}}'
p25 = round(float(df[col].quantile(0.25)), 2)
p50 = round(float(df[col].quantile(0.50)), 2)
p75 = round(float(df[col].quantile(0.75)), 2)
result = {"values": [p25, p50, p75], "labels": ["25th %ile", "50th %ile", "75th %ile"], "chart_type": "bar"}
print(json.dumps(result))
""".trimIndent()
        ),

        AnalysisTemplate(
            id = "bar_cross_tab",
            name = "Cross-Tabulation",
            chartType = "bar",
            description = "Cross-tabulation counts of two categorical columns",
            keywords = listOf("cross", "crosstab", "contingency", "two way", "pivot"),
            requiredParams = listOf("rowCol", "colCol"),
            templateCode = """
import pandas as pd

import json

df = load_data()
ct = pd.crosstab(df['{{rowCol}}'], df['{{colCol}}'])
labels = []
values = []
for row in ct.index:
    for col in ct.columns:
        labels.append(str(row) + " x " + str(col))
        values.append(int(ct.loc[row, col]))
result = {"values": values, "labels": labels, "chart_type": "bar"}
print(json.dumps(result))
""".trimIndent()
        ),

        // ───────────────── LINE CHARTS ─────────────────

        AnalysisTemplate(
            id = "line_trend",
            name = "Trend Analysis",
            chartType = "line",
            description = "Trend of a numeric column over a sequential/ordered column",
            keywords = listOf("trend", "over time", "change", "progression", "growth", "decline"),
            requiredParams = listOf("xCol", "yCol"),
            templateCode = """
import pandas as pd

import json

df = load_data()
df = df.sort_values('{{xCol}}')
grouped = df.groupby('{{xCol}}')['{{yCol}}'].mean().round(2)
result = {"values": grouped.tolist(), "labels": grouped.index.astype(str).tolist(), "chart_type": "line"}
print(json.dumps(result))
""".trimIndent()
        ),

        AnalysisTemplate(
            id = "line_cumulative",
            name = "Cumulative Sum",
            chartType = "line",
            description = "Running cumulative sum of a numeric column over an axis",
            keywords = listOf("cumulative", "running total", "accumulated", "running sum"),
            requiredParams = listOf("xCol", "yCol"),
            templateCode = """
import pandas as pd

import json

df = load_data()
df = df.sort_values('{{xCol}}')
cumsum = df.groupby('{{xCol}}')['{{yCol}}'].sum().cumsum().round(2)
result = {"values": cumsum.tolist(), "labels": cumsum.index.astype(str).tolist(), "chart_type": "line"}
print(json.dumps(result))
""".trimIndent()
        ),

        // ───────────────── PIE CHARTS ─────────────────

        AnalysisTemplate(
            id = "pie_distribution",
            name = "Proportional Distribution",
            chartType = "pie",
            description = "Proportional breakdown of a categorical column",
            keywords = listOf("distribution", "proportion", "percentage", "breakdown", "share", "pie"),
            requiredParams = listOf("categoryCol"),
            templateCode = """
import pandas as pd

import json

df = load_data()
counts = df['{{categoryCol}}'].value_counts()
result = {"values": counts.tolist(), "labels": counts.index.astype(str).tolist(), "chart_type": "pie"}
print(json.dumps(result))
""".trimIndent()
        ),

        AnalysisTemplate(
            id = "pie_binary",
            name = "Binary Split",
            chartType = "pie",
            description = "Distribution of a binary/boolean column",
            keywords = listOf("binary", "yes no", "true false", "positive negative", "split"),
            requiredParams = listOf("binaryCol"),
            templateCode = """
import pandas as pd

import json

df = load_data()
counts = df['{{binaryCol}}'].value_counts()
result = {"values": counts.tolist(), "labels": counts.index.astype(str).tolist(), "chart_type": "pie"}
print(json.dumps(result))
""".trimIndent()
        ),

        // ───────────────── HISTOGRAM ─────────────────

        AnalysisTemplate(
            id = "histogram_distribution",
            name = "Frequency Distribution",
            chartType = "histogram",
            description = "Frequency histogram of a numeric column",
            keywords = listOf("histogram", "frequency", "bins", "distribution", "spread"),
            requiredParams = listOf("numericCol"),
            templateCode = """
import pandas as pd

import json
import numpy as np

df = load_data()
col = df['{{numericCol}}'].dropna()
counts, edges = np.histogram(col, bins=10)
labels = [f"{edges[i]:.1f}-{edges[i+1]:.1f}" for i in range(len(counts))]
result = {"values": counts.tolist(), "labels": labels, "chart_type": "histogram"}
print(json.dumps(result))
""".trimIndent()
        ),

        // ───────────────── HEATMAP ─────────────────

        AnalysisTemplate(
            id = "heatmap_correlation",
            name = "Correlation Heatmap",
            chartType = "heatmap",
            description = "Correlation matrix across all numeric columns",
            keywords = listOf("correlation", "correlate", "relationship", "heatmap", "matrix"),
            requiredParams = emptyList(),
            templateCode = """
import pandas as pd

import json
import numpy as np

df = load_data()
numeric_df = df.select_dtypes(include=[np.number])
corr = numeric_df.corr().round(2)
matrix = corr.values.tolist()
labels = corr.columns.tolist()
result = {"values": matrix, "labels": labels, "chart_type": "heatmap"}
print(json.dumps(result))
""".trimIndent()
        )
    )
}
