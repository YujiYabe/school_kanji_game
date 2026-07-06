package com.example.schoolkanjigame.kanji

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun KanjiScreen(
    viewModel: KanjiViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, uiState.mode, uiState.isFinished) {
        viewModel.setReadingScreenActive(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setReadingScreenActive(true)
                Lifecycle.Event.ON_STOP -> viewModel.setReadingScreenActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setReadingScreenActive(false)
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { paddingValues ->
        when {
            !uiState.isSessionStarted -> StartSettingsScreen(
                uiState = uiState,
                onGradeSelected = viewModel::setSelectedGrade,
                onQuestionCountChanged = viewModel::setQuestionCount,
                onTimerChanged = viewModel::setReadingSecondsPerQuestion,
                onStartReading = { viewModel.startReadingMode() },
                onStartWriting = viewModel::startWritingMode,
                modifier = Modifier.padding(paddingValues),
            )

            uiState.isFinished -> ResultScreen(
                uiState = uiState,
                onRetryWrongQuestions = viewModel::retryWrongReadingQuestions,
                onBackToSettings = viewModel::returnToSettings,
                modifier = Modifier.padding(paddingValues),
            )

            uiState.mode == LearningMode.Reading -> ReadingQuizScreen(
                uiState = uiState,
                onAnswerSelected = viewModel::submitReadingAnswer,
                modifier = Modifier.padding(paddingValues),
            )

            uiState.mode == LearningMode.Writing -> WritingQuizScreen(
                uiState = uiState,
                onStrokesChanged = viewModel::updateWritingStrokes,
                onClear = viewModel::clearWritingCanvas,
                onJudge = viewModel::judgeCurrentWritingCharacter,
                modifier = Modifier.padding(paddingValues),
            )
        }
    }
}

@Composable
private fun StartSettingsScreen(
    uiState: KanjiUiState,
    onGradeSelected: (Int) -> Unit,
    onQuestionCountChanged: (Int) -> Unit,
    onTimerChanged: (Int) -> Unit,
    onStartReading: () -> Unit,
    onStartWriting: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedMode by remember { mutableStateOf(LearningMode.Reading) }
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFB9E9FF),
                        Color(0xFF3F9DFF),
                        Color(0xFF1F73E8),
                    ),
                ),
            )
            .verticalScroll(scrollState)
            .padding(18.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xAAE9F7FF),
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Text(
                    text = "メニュー",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF16408F),
                )

                GradeSelector(
                    selectedGrade = uiState.selectedGrade,
                    onGradeSelected = onGradeSelected,
                )

                SettingsSlider(
                    title = "timer",
                    valueText = "${uiState.readingSecondsPerQuestion}秒",
                    value = uiState.readingSecondsPerQuestion,
                    valueRange = 1..30,
                    steps = 28,
                    onValueChanged = onTimerChanged,
                )

                SettingsSlider(
                    title = "questions",
                    valueText = "${uiState.questionCount}問",
                    value = uiState.questionCount,
                    valueRange = 5..100,
                    steps = 18,
                    stepSize = 5,
                    onValueChanged = onQuestionCountChanged,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { selectedMode = LearningMode.Reading },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedMode == LearningMode.Reading) {
                                Color(0xFF86DC23)
                            } else {
                                Color(0xFF1F73E8)
                            },
                            contentColor = if (selectedMode == LearningMode.Reading) {
                                Color(0xFF16408F)
                            } else {
                                Color.White
                            },
                        ),
                    ) {
                        Text(text = "読み", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                    Button(
                        onClick = { selectedMode = LearningMode.Writing },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedMode == LearningMode.Writing) {
                                Color(0xFF86DC23)
                            } else {
                                Color(0xFF1F73E8)
                            },
                            contentColor = if (selectedMode == LearningMode.Writing) {
                                Color(0xFF16408F)
                            } else {
                                Color.White
                            },
                        ),
                    ) {
                        Text(text = "書き", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                }

                Button(
                    onClick = {
                        when (selectedMode) {
                            LearningMode.Reading -> onStartReading()
                            LearningMode.Writing -> onStartWriting()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp),
                    shape = RoundedCornerShape(31.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF86DC23),
                        contentColor = Color.White,
                    ),
                ) {
                    Text(text = "スタート", fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun GradeSelector(
    selectedGrade: Int,
    onGradeSelected: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "grade",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = Color(0xFF16408F),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..6).chunked(3).forEach { rowGrades ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowGrades.forEach { grade ->
                        val selected = grade == selectedGrade
                        Button(
                            onClick = { onGradeSelected(grade) },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) Color(0xFF86DC23) else Color(0xFF1F73E8),
                                contentColor = if (selected) Color(0xFF16408F) else Color.White,
                            ),
                        ) {
                            Text(
                                text = "${grade}年",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Black,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSlider(
    title: String,
    valueText: String,
    value: Int,
    valueRange: IntRange,
    steps: Int,
    stepSize: Int = 1,
    onValueChanged: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = Color(0xFF16408F),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = Color(0xFF16408F),
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = {
                val steppedValue = (it / stepSize).roundToInt() * stepSize
                onValueChanged(steppedValue.coerceIn(valueRange))
            },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = steps,
        )
    }
}

@Composable
private fun ReadingQuizScreen(
    uiState: KanjiUiState,
    onAnswerSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val question = uiState.currentQuestion ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "読み ${uiState.currentQuestionIndex + 1} / ${uiState.questions.size}",
            style = MaterialTheme.typography.titleMedium,
        )
        LinearProgressIndicator(
            progress = { uiState.readingTimeRemaining / uiState.readingSecondsPerQuestion.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "残り ${uiState.readingTimeRemaining} 秒",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        RubySentence(
            sentence = question.fullSentence,
            sentenceReading = question.sentenceReading,
            markedSentence = question.markedSentence,
            markedSentenceReading = question.markedSentenceReading,
            targetText = question.targetText,
            targetReading = question.readingAnswers.firstOrNull().orEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.weight(1f))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(uiState.shuffledReadingAnswers) { answer ->
                Button(
                    onClick = { onAnswerSelected(answer) },
                    modifier = Modifier.height(64.dp),
                ) {
                    Text(answer, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun WritingQuizScreen(
    uiState: KanjiUiState,
    onStrokesChanged: (List<DrawnStroke>) -> Unit,
    onClear: () -> Unit,
    onJudge: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val question = uiState.currentQuestion ?: return
    val writingTargetReading = question.readingAnswers.firstOrNull().orEmpty()
    val writingPrompt = question.fullSentence.replaceFirst(question.targetText, writingTargetReading)
    val density = LocalDensity.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "書き ${uiState.currentQuestionIndex + 1} / ${uiState.questions.size}",
            style = MaterialTheme.typography.titleMedium,
        )
        RubySentence(
            sentence = writingPrompt,
            sentenceReading = question.sentenceReading,
            targetText = writingTargetReading,
            targetReading = writingTargetReading,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "${uiState.currentWritingCharIndex + 1}文字目 / ${question.writingAnswer.length}文字中",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val canvasWidth = with(density) { maxWidth.toPx() }
            val canvasHeight = with(density) { 280.dp.toPx() }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KanjiCanvasView(
                    strokes = uiState.strokes,
                    onStrokesChanged = onStrokesChanged,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onClear) {
                        Text("クリア")
                    }
                    Button(
                        onClick = { onJudge(canvasWidth, canvasHeight) },
                        enabled = uiState.recognitionState != RecognitionState.Loading,
                    ) {
                        Text("判定")
                    }
                }
            }
        }

        when (val state = uiState.recognitionState) {
            RecognitionState.Idle -> InkModelStatus(uiState.inkModelState)
            RecognitionState.Loading -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text("認識中...")
            }
            is RecognitionState.Success -> Text(
                text = if (state.isCorrect) {
                    "正解: ${state.recognizedText}"
                } else {
                    "もう一度: ${state.recognizedText}"
                },
                color = if (state.isCorrect) Color(0xFF047857) else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
            )
            is RecognitionState.Error -> Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun ResultScreen(
    uiState: KanjiUiState,
    onRetryWrongQuestions: () -> Unit,
    onBackToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRetryNeeded = uiState.mode == LearningMode.Reading &&
        uiState.resultPhase == ResultPhase.RetryNeeded

    if (isRetryNeeded) {
        RetryResultScreen(
            uiState = uiState,
            onRetryWrongQuestions = onRetryWrongQuestions,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "結果",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
        )

        if (uiState.mode == LearningMode.Reading) {
            Text(
                text = "${uiState.readingOriginalQuestionCount} / ${uiState.readingOriginalQuestionCount} 点",
                fontSize = 52.sp,
                lineHeight = 60.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                text = "全問完了",
                fontSize = 44.sp,
                lineHeight = 52.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
        }

        KanjiReviewList(
            uiState = uiState,
            showCorrectAnswer = true,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        Button(
            onClick = onBackToSettings,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(text = "もう一度", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RetryResultScreen(
    uiState: KanjiUiState,
    onRetryWrongQuestions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wrongReviews = uiState.readingAllReviews
        .ifEmpty { uiState.readingReviews }
        .filterNot { it.isCorrect }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "間違いチェック",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
        )

        Text(
            text = "${wrongReviews.size}問をもう一度",
            fontSize = 40.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )

        KanjiReviewList(
            uiState = uiState,
            showCorrectAnswer = false,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        Button(
            onClick = onRetryWrongQuestions,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(text = "間違えた問題を解く", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun KanjiReviewList(
    uiState: KanjiUiState,
    showCorrectAnswer: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (uiState.mode == LearningMode.Reading) {
                val allReviews = uiState.readingAllReviews.ifEmpty { uiState.readingReviews }
                val reviews = if (uiState.resultPhase == ResultPhase.RetryNeeded) {
                    allReviews.filterNot { it.isCorrect }
                } else {
                    allReviews
                }
                reviews.forEach { review ->
                    item(key = review.questionId) {
                        ReadingReviewRow(
                            review = review,
                            showCorrectAnswer = showCorrectAnswer,
                        )
                    }
                }
            } else {
                val reviews = uiState.writingReviews.ifEmpty {
                    uiState.questions.mapIndexed { index, question ->
                        KanjiWritingReview(
                            questionId = question.id,
                            questionNumber = index + 1,
                            sentence = question.fullSentence,
                            writtenAnswer = "",
                            correctAnswer = question.writingAnswer,
                            writtenStrokeGroups = emptyList(),
                        )
                    }
                }
                reviews.forEach { review ->
                    item(key = review.questionId) {
                        WritingReviewRow(review = review)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingReviewRow(
    review: KanjiAnswerReview,
    showCorrectAnswer: Boolean,
) {
    val textColor = if (review.isCorrect) Color.Black else Color(0xFFD00000)
    val selectedText = review.selectedAnswer ?: "未回答"
    val answerText = if (showCorrectAnswer) {
        "選択: $selectedText   正解: ${review.correctAnswer}"
    } else {
        "選択: $selectedText"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${review.questionNumber}問目",
                color = if (review.isCorrect) Color(0xFF666666) else Color(0xFFD00000),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Text(
                text = review.sentence,
                color = textColor,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                lineHeight = 24.sp,
            )
        }
        Text(
            text = answerText,
            color = textColor,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun WritingReviewRow(
    review: KanjiWritingReview,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${review.questionNumber}問目",
                color = Color(0xFF666666),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Text(
                text = review.sentence,
                color = Color.Black,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                lineHeight = 24.sp,
            )
        }
        Text(
            text = "書いた文字: ${review.writtenAnswer.ifBlank { "未記録" }}   正解: ${review.correctAnswer}",
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            lineHeight = 20.sp,
        )
        if (review.writtenStrokeGroups.isNotEmpty()) {
            WrittenAnswerPreview(
                strokeGroups = review.writtenStrokeGroups,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WrittenAnswerPreview(
    strokeGroups: List<List<DrawnStroke>>,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .height(92.dp)
            .background(Color(0xFFF9FAFB), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        if (strokeGroups.isEmpty()) return@Canvas

        val cellWidth = size.width / strokeGroups.size.coerceAtLeast(1)
        strokeGroups.forEachIndexed { index, strokes ->
            val points = strokes.flatMap { it.points }
            if (points.isEmpty()) return@forEachIndexed

            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val contentWidth = max(1f, maxX - minX)
            val contentHeight = max(1f, maxY - minY)
            val cellLeft = index * cellWidth
            val padding = 8f
            val drawableWidth = max(1f, cellWidth - padding * 2f)
            val drawableHeight = max(1f, size.height - padding * 2f)
            val scale = min(drawableWidth / contentWidth, drawableHeight / contentHeight)
            val offsetX = cellLeft + (cellWidth - contentWidth * scale) / 2f - minX * scale
            val offsetY = (size.height - contentHeight * scale) / 2f - minY * scale

            fun transformedPath(stroke: DrawnStroke): Path? {
                val strokePoints = stroke.points
                if (strokePoints.isEmpty()) return null
                return Path().apply {
                    moveTo(
                        strokePoints.first().x * scale + offsetX,
                        strokePoints.first().y * scale + offsetY,
                    )
                    strokePoints.drop(1).forEach { point ->
                        lineTo(point.x * scale + offsetX, point.y * scale + offsetY)
                    }
                }
            }

            strokes.forEach { stroke ->
                val strokePoints = stroke.points
                if (strokePoints.size == 1) {
                    val point = strokePoints.first()
                    drawCircle(
                        color = Color(0xFF111827),
                        radius = 4f,
                        center = androidx.compose.ui.geometry.Offset(
                            point.x * scale + offsetX,
                            point.y * scale + offsetY,
                        ),
                    )
                } else {
                    transformedPath(stroke)?.let { path ->
                        drawPath(
                            path = path,
                            color = Color(0xFF111827),
                            style = Stroke(
                                width = 5f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InkModelStatus(modelState: InkModelState) {
    when (modelState) {
        InkModelState.Idle -> Unit
        InkModelState.Downloading -> Text(
            text = "日本語認識モデルを準備中...",
            color = MaterialTheme.colorScheme.secondary,
        )
        InkModelState.Ready -> Text(
            text = "日本語認識モデル準備完了",
            color = Color(0xFF047857),
        )
        is InkModelState.Error -> Text(
            text = modelState.message,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private data class RubyToken(
    val text: String,
    val ruby: String = "",
    val isTarget: Boolean = false,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RubySentence(
    sentence: String,
    sentenceReading: String,
    markedSentence: String = "",
    markedSentenceReading: String = "",
    targetText: String,
    targetReading: String = targetText,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified,
    targetColor: Color = Color(0xFF2563EB),
    textSize: TextUnit = 24.sp,
) {
    val tokens = remember(sentence, sentenceReading, markedSentence, markedSentenceReading, targetText, targetReading) {
        rubyTokens(
            sentence = sentence,
            sentenceReading = sentenceReading,
            markedSentence = markedSentence,
            markedSentenceReading = markedSentenceReading,
            targetText = targetText,
            targetReading = targetReading,
        )
    }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tokens.forEachIndexed { index, token ->
            RubyTokenText(
                token = token,
                textColor = textColor,
                targetColor = targetColor,
                textSize = textSize,
                modifier = Modifier.padding(end = if (index == tokens.lastIndex) 0.dp else 1.dp),
            )
        }
    }
}

@Composable
private fun RubyTokenText(
    token: RubyToken,
    textColor: Color,
    targetColor: Color,
    textSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val bodyColor = if (token.isTarget) targetColor else textColor
    val bodyWeight = if (token.isTarget) FontWeight.Bold else FontWeight.Normal
    val rubyText = token.ruby.ifEmpty { "　" }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(
            text = rubyText,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            color = if (token.ruby.isBlank()) Color.Transparent else MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Visible,
        )
        Text(
            text = token.text,
            fontSize = textSize,
            lineHeight = 30.sp,
            color = bodyColor,
            fontWeight = bodyWeight,
        )
    }
}

private fun rubyTokens(
    sentence: String,
    sentenceReading: String,
    markedSentence: String,
    markedSentenceReading: String,
    targetText: String,
    targetReading: String,
): List<RubyToken> {
    if (sentence.isBlank()) return emptyList()
    explicitRubyTokens(markedSentence, markedSentenceReading)
        ?.let { return it.mergePlainNeighbors() }
    if (sentenceReading.isBlank()) return targetOnlyTokens(sentence, targetText)

    val targetStart = sentence.indexOf(targetText).takeIf { it >= 0 && targetText.isNotEmpty() }
    val targetRange = targetStart?.let { it until it + targetText.length } ?: IntRange.EMPTY
    val tokens = mutableListOf<RubyToken>()
    var sentenceIndex = 0
    var readingIndex = 0

    while (sentenceIndex < sentence.length) {
        val char = sentence[sentenceIndex]
        if (!char.isKanji()) {
            tokens.add(RubyToken(char.toString()))
            if (readingIndex < sentenceReading.length && sentenceReading[readingIndex].matchesReadingAnchor(char)) {
                readingIndex++
            }
            sentenceIndex++
            continue
        }

        val runStart = sentenceIndex
        while (
            sentenceIndex < sentence.length &&
            sentence[sentenceIndex].isKanji()
        ) {
            sentenceIndex++
        }
        val runEnd = sentenceIndex
        val runText = sentence.substring(runStart, runEnd)
        val nextAnchor = sentence.drop(sentenceIndex).firstOrNull { !it.isKanji() }
        val rubyEnd = if (nextAnchor == null) {
            sentenceReading.length
        } else {
            sentenceReading.indexOfReadingAnchor(nextAnchor, startIndex = readingIndex)
                .takeIf { it >= readingIndex }
                ?: readingIndex
        }
        val runReading = sentenceReading.substring(readingIndex, rubyEnd)
        readingIndex = rubyEnd

        if (targetStart != null && targetStart in runStart until runEnd) {
            tokens.addTargetRunTokens(
                runText = runText,
                runReading = runReading,
                targetOffset = targetStart - runStart,
                targetText = targetText,
                targetReading = targetReading,
            )
        } else {
            tokens.add(RubyToken(text = runText, ruby = runReading))
        }
    }

    return tokens.mergePlainNeighbors()
}

private data class MarkedSegment(
    val text: String,
    val marker: Char? = null,
)

private fun explicitRubyTokens(
    markedSentence: String,
    markedSentenceReading: String,
): List<RubyToken>? {
    if (!markedSentence.hasRubyMarkers() || !markedSentenceReading.hasRubyMarkers()) return null

    val sentenceSegments = markedSentence.toMarkedSegments() ?: return null
    val readingSegments = markedSentenceReading.toMarkedSegments() ?: return null
    val readingMarkedSegments = readingSegments.filter { it.marker != null }
    var readingMarkerIndex = 0

    return buildList {
        sentenceSegments.forEach { sentenceSegment ->
            when (sentenceSegment.marker) {
                '[' -> {
                    val readingSegment = readingMarkedSegments.getOrNull(readingMarkerIndex)
                    if (readingSegment?.marker != '[') return null
                    readingMarkerIndex++
                    add(RubyToken(sentenceSegment.text, isTarget = true))
                }
                '{' -> {
                    val readingSegment = readingMarkedSegments.getOrNull(readingMarkerIndex)
                    if (readingSegment?.marker != '{') return null
                    readingMarkerIndex++
                    add(RubyToken(sentenceSegment.text, ruby = readingSegment.text))
                }
                else -> add(RubyToken(sentenceSegment.text))
            }
        }
        if (readingMarkerIndex != readingMarkedSegments.size) return null
    }
}

private fun String.hasRubyMarkers(): Boolean =
    any { it == '[' || it == '{' }

private fun String.toMarkedSegments(): List<MarkedSegment>? {
    val segments = mutableListOf<MarkedSegment>()
    val plain = StringBuilder()
    var index = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            segments.add(MarkedSegment(plain.toString()))
            plain.clear()
        }
    }

    while (index < length) {
        val char = this[index]
        val closeMarker = when (char) {
            '[' -> ']'
            '{' -> '}'
            ']', '}' -> return null
            else -> null
        }

        if (closeMarker == null) {
            plain.append(char)
            index++
            continue
        }

        flushPlain()
        val closeIndex = indexOf(closeMarker, startIndex = index + 1)
        if (closeIndex < 0) return null
        segments.add(
            MarkedSegment(
                text = substring(index + 1, closeIndex),
                marker = char,
            ),
        )
        index = closeIndex + 1
    }
    flushPlain()
    return segments
}

private fun MutableList<RubyToken>.addTargetRunTokens(
    runText: String,
    runReading: String,
    targetOffset: Int,
    targetText: String,
    targetReading: String,
) {
    val prefixText = runText.take(targetOffset)
    val suffixText = runText.drop(targetOffset + targetText.length)
    val targetReadingStart = runReading.indexOf(targetReading)

    if (targetReadingStart < 0) {
        if (prefixText.isNotEmpty()) add(RubyToken(prefixText, runReading))
        add(RubyToken(targetText, isTarget = true))
        if (suffixText.isNotEmpty()) add(RubyToken(suffixText))
        return
    }

    val targetReadingEnd = targetReadingStart + targetReading.length
    val prefixReading = runReading.take(targetReadingStart)
    val suffixReading = runReading.drop(targetReadingEnd)

    if (prefixText.isNotEmpty()) {
        add(RubyToken(prefixText, prefixReading))
    }
    add(RubyToken(targetText, isTarget = true))
    if (suffixText.isNotEmpty()) {
        add(RubyToken(suffixText, suffixReading))
    }
}

private fun targetOnlyTokens(sentence: String, targetText: String): List<RubyToken> {
    val targetStart = sentence.indexOf(targetText)
    if (targetStart < 0 || targetText.isEmpty()) {
        return listOf(RubyToken(sentence))
    }
    return buildList {
        if (targetStart > 0) add(RubyToken(sentence.substring(0, targetStart)))
        add(RubyToken(sentence.substring(targetStart, targetStart + targetText.length), isTarget = true))
        if (targetStart + targetText.length < sentence.length) {
            add(RubyToken(sentence.substring(targetStart + targetText.length)))
        }
    }
}

private fun List<RubyToken>.mergePlainNeighbors(): List<RubyToken> {
    val merged = mutableListOf<RubyToken>()
    forEach { token ->
        val last = merged.lastOrNull()
        if (
            last != null &&
            last.ruby.isEmpty() &&
            !last.isTarget &&
            token.ruby.isEmpty() &&
            !token.isTarget
        ) {
            merged[merged.lastIndex] = last.copy(text = last.text + token.text)
        } else {
            merged.add(token)
        }
    }
    return merged
}

private fun Char.isKanji(): Boolean = this in '\u4E00'..'\u9FFF'

private fun String.indexOfReadingAnchor(anchor: Char, startIndex: Int): Int {
    for (index in startIndex until length) {
        if (this[index].matchesReadingAnchor(anchor)) return index
    }
    return -1
}

private fun Char.matchesReadingAnchor(anchor: Char): Boolean =
    this == anchor || this.toHiragana() == anchor.toHiragana()

private fun Char.toHiragana(): Char =
    if (this in '\u30A1'..'\u30F6') {
        this - 0x60
    } else {
        this
    }

@Composable
private fun highlightedSentence(
    fullSentence: String,
    targetText: String,
    highlightColor: Color = Color(0xFF2563EB),
) = buildAnnotatedString {
    val startIndex = fullSentence.indexOf(targetText)
    if (startIndex < 0 || targetText.isEmpty()) {
        append(fullSentence)
        return@buildAnnotatedString
    }

    append(fullSentence.substring(0, startIndex))
    withStyle(
        SpanStyle(
            color = highlightColor,
            fontWeight = FontWeight.Bold,
        ),
    ) {
        append(targetText)
    }
    append(fullSentence.substring(startIndex + targetText.length))
}
