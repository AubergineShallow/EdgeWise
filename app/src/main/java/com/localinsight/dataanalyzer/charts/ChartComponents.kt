package com.localinsight.dataanalyzer.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ─────────────────────────────────────────
//  Color Palette
// ─────────────────────────────────────────

private val CHART_COLORS = listOf(
    Color(0xFF6C5CE7),  // purple
    Color(0xFF00B894),  // green
    Color(0xFFE17055),  // coral
    Color(0xFF0984E3),  // blue
    Color(0xFFFDAA5C),  // amber
    Color(0xFFE84393),  // pink
    Color(0xFF00CEC9),  // teal
    Color(0xFFFF7675),  // salmon
    Color(0xFF636E72),  // gray
    Color(0xFFA29BFE),  // lavender
    Color(0xFF55EFC4),  // mint
    Color(0xFFDFE6E9),  // light gray
)

// ─────────────────────────────────────────
//  Pie Chart
// ─────────────────────────────────────────

@Composable
fun PieChart(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier
) {
    if (values.isEmpty()) return
    val total = values.sum()
    if (total == 0f) return

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(modifier = Modifier.size(220.dp)) {
            val canvasSize = min(size.width, size.height)
            val radius = canvasSize / 2f * 0.85f
            val center = Offset(size.width / 2f, size.height / 2f)
            var startAngle = -90f

            values.forEachIndexed { index, value ->
                val sweepAngle = (value / total) * 360f
                val color = CHART_COLORS[index % CHART_COLORS.size]

                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = true,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Fill
                )

                // Draw percentage label on segment
                if (sweepAngle > 20f) {
                    val midAngle = Math.toRadians((startAngle + sweepAngle / 2f).toDouble())
                    val labelRadius = radius * 0.65f
                    val labelX = center.x + (labelRadius * cos(midAngle)).toFloat()
                    val labelY = center.y + (labelRadius * sin(midAngle)).toFloat()
                    val pct = (value / total * 100f).toInt()

                    drawContext.canvas.nativeCanvas.drawText(
                        "$pct%",
                        labelX,
                        labelY + 5f,
                        android.graphics.Paint().apply {
                            setColor(android.graphics.Color.WHITE)
                            textSize = 28f
                            textAlign = android.graphics.Paint.Align.CENTER
                            isFakeBoldText = true
                            isAntiAlias = true
                        }
                    )
                }

                startAngle += sweepAngle
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Legend
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            values.forEachIndexed { index, value ->
                if (index < labels.size) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Canvas(modifier = Modifier.size(12.dp)) {
                            drawCircle(color = CHART_COLORS[index % CHART_COLORS.size])
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${labels[index]} (${value.toInt()})",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────
//  Heatmap Chart
// ─────────────────────────────────────────

@Composable
fun HeatmapChart(
    matrix: List<List<Float>>,
    labels: List<String>,
    modifier: Modifier = Modifier
) {
    if (matrix.isEmpty() || labels.isEmpty()) return

    val rows = matrix.size
    val cols = if (matrix.isNotEmpty()) matrix[0].size else 0
    if (cols == 0) return

    // Find min and max for color scaling
    val allValues = matrix.flatten()
    val minVal = allValues.min()
    val maxVal = allValues.max()

    Row(modifier = modifier.horizontalScroll(rememberScrollState())) {
        // Y-axis labels
        Column(modifier = Modifier.padding(top = 40.dp)) {
            for (i in 0 until rows) {
                Box(
                    modifier = Modifier.height(40.dp).widthIn(min = 60.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        text = labels.getOrElse(i) { "" }.take(10),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }
        }

        Column {
            // X-axis labels (top)
            Row(modifier = Modifier.padding(start = 0.dp)) {
                for (j in 0 until cols) {
                    Box(
                        modifier = Modifier.width(40.dp).height(40.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Text(
                            text = labels.getOrElse(j) { "" }.take(6),
                            fontSize = 8.sp,
                            maxLines = 2
                        )
                    }
                }
            }

            // Grid cells
            Canvas(
                modifier = Modifier
                    .width((cols * 40).dp)
                    .height((rows * 40).dp)
            ) {
                val cellW = size.width / cols
                val cellH = size.height / rows

                for (i in 0 until rows) {
                    for (j in 0 until cols) {
                        val value = matrix[i].getOrElse(j) { 0f }
                        val color = heatmapColor(value, minVal, maxVal)

                        drawRect(
                            color = color,
                            topLeft = Offset(j * cellW, i * cellH),
                            size = Size(cellW - 2f, cellH - 2f)
                        )

                        // Draw value text
                        drawContext.canvas.nativeCanvas.drawText(
                            String.format("%.1f", value),
                            j * cellW + cellW / 2,
                            i * cellH + cellH / 2 + 5f,
                            android.graphics.Paint().apply {
                                this.color = if (kotlin.math.abs(value) > (maxVal - minVal) * 0.5f)
                                    android.graphics.Color.WHITE
                                else
                                    android.graphics.Color.BLACK
                                textSize = 22f
                                textAlign = android.graphics.Paint.Align.CENTER
                                isAntiAlias = true
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Map a value to a diverging color (blue → white → red).
 * Useful for correlation matrices where -1 = blue, 0 = white, 1 = red.
 */
private fun heatmapColor(value: Float, min: Float, max: Float): Color {
    val range = max - min
    if (range == 0f) return Color(0xFFDFE6E9)

    val normalized = (value - min) / range // 0..1

    return if (normalized < 0.5f) {
        // Blue to White
        val t = normalized * 2f
        Color(
            red = t,
            green = t,
            blue = 1f
        )
    } else {
        // White to Red
        val t = (normalized - 0.5f) * 2f
        Color(
            red = 1f,
            green = 1f - t,
            blue = 1f - t
        )
    }
}
