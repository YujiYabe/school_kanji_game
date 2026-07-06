package com.example.schoolkanjigame.kanji

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

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
            .padding(18.dp),
        contentAlignment = Alignment.Center,
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
                    valueRange = 1..50,
                    steps = 48,
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
            onValueChange = { onValueChanged((it + 0.5f).toInt().coerceIn(valueRange)) },
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
        Text(
            text = highlightedSentence(question.fullSentence, question.targetText),
            style = MaterialTheme.typography.headlineSmall,
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
        Text(
            text = highlightedSentence(writingPrompt, writingTargetReading),
            style = MaterialTheme.typography.headlineSmall,
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
    val wrongReviews = uiState.readingReviews.filterNot { it.isCorrect }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (uiState.mode == LearningMode.Reading) {
                val reviews = if (uiState.resultPhase == ResultPhase.RetryNeeded) {
                    uiState.readingReviews.filterNot { it.isCorrect }
                } else {
                    uiState.readingReviews
                }
                reviews.forEach { review ->
                    ReadingReviewRow(
                        review = review,
                        showCorrectAnswer = showCorrectAnswer,
                    )
                }
            } else {
                uiState.questions.forEachIndexed { index, question ->
                    WritingReviewRow(questionNumber = index + 1, question = question)
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
    questionNumber: Int,
    question: KanjiQuestion,
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
                text = "${questionNumber}問目",
                color = Color(0xFF666666),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Text(
                text = question.fullSentence,
                color = Color.Black,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                lineHeight = 24.sp,
            )
        }
        Text(
            text = "答え: ${question.writingAnswer}",
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            lineHeight = 20.sp,
        )
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
