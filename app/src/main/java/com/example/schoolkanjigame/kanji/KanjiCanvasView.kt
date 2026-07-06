package com.example.schoolkanjigame.kanji

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun KanjiCanvasView(
    strokes: List<DrawnStroke>,
    onStrokesChanged: (List<DrawnStroke>) -> Unit,
    modifier: Modifier = Modifier,
    strokeColor: Color = Color(0xFF111827),
    strokeWidth: Float = 12f,
) {
    var currentStroke by remember { mutableStateOf<List<InkPoint>>(emptyList()) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .background(Color.White, RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFD1D5DB), RoundedCornerShape(8.dp))
            .pointerInput(strokes) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val activePoints = mutableListOf(down.toInkPoint())
                    currentStroke = activePoints.toList()
                    down.consume()

                    drag(down.id) { change ->
                        activePoints += change.toInkPoint()
                        currentStroke = activePoints.toList()
                        change.consume()
                    }

                    if (activePoints.isNotEmpty()) {
                        onStrokesChanged(strokes + DrawnStroke(activePoints.toList()))
                    }
                    currentStroke = emptyList()
                }
            },
    ) {
        fun drawInkStroke(points: List<InkPoint>) {
            if (points.isEmpty()) return
            if (points.size == 1) {
                drawCircle(
                    color = strokeColor,
                    radius = strokeWidth / 2f,
                    center = Offset(points.first().x, points.first().y),
                )
                return
            }

            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { point -> lineTo(point.x, point.y) }
            }
            drawPath(
                path = path,
                color = strokeColor,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }

        strokes.forEach { drawInkStroke(it.points) }
        drawInkStroke(currentStroke)
    }
}

private fun PointerInputChange.toInkPoint(): InkPoint =
    InkPoint(
        x = position.x,
        y = position.y,
        timestampMillis = System.currentTimeMillis(),
    )
