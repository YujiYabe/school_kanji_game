package com.example.schoolkanjigame.kanji

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.common.RecognitionResult
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class KanjiQuestion(
    val id: String,
    val grade: Int,
    val fullSentence: String,
    val targetText: String,
    val readingAnswers: List<String>,
    val writingAnswer: String,
)

data class InkPoint(
    val x: Float,
    val y: Float,
    val timestampMillis: Long,
)

data class DrawnStroke(
    val points: List<InkPoint>,
)

data class KanjiAnswerReview(
    val questionId: String,
    val questionNumber: Int,
    val sentence: String,
    val selectedAnswer: String?,
    val correctAnswer: String,
    val isCorrect: Boolean,
)

data class KanjiWritingReview(
    val questionId: String,
    val questionNumber: Int,
    val sentence: String,
    val writtenAnswer: String,
    val correctAnswer: String,
    val writtenStrokeGroups: List<List<DrawnStroke>>,
)

enum class ResultPhase {
    Final,
    RetryNeeded,
}

enum class LearningMode {
    Reading,
    Writing,
}

sealed interface RecognitionState {
    data object Idle : RecognitionState
    data object Loading : RecognitionState
    data class Success(val recognizedText: String, val isCorrect: Boolean) : RecognitionState
    data class Error(val message: String) : RecognitionState
}

sealed interface InkModelState {
    data object Idle : InkModelState
    data object Downloading : InkModelState
    data object Ready : InkModelState
    data class Error(val message: String) : InkModelState
}

data class KanjiUiState(
    val mode: LearningMode = LearningMode.Reading,
    val isSessionStarted: Boolean = false,
    val selectedGrade: Int = 1,
    val questionCount: Int = 10,
    val questions: List<KanjiQuestion> = emptyList(),
    val currentQuestionIndex: Int = 0,
    val shuffledReadingAnswers: List<String> = emptyList(),
    val readingSecondsPerQuestion: Int = 10,
    val readingTimeRemaining: Int = 10,
    val readingCorrectCount: Int = 0,
    val readingOriginalQuestionCount: Int = 0,
    val readingRetryRound: Int = 0,
    val resultPhase: ResultPhase = ResultPhase.Final,
    val readingReviews: List<KanjiAnswerReview> = emptyList(),
    val readingAllReviews: List<KanjiAnswerReview> = emptyList(),
    val currentWritingCharIndex: Int = 0,
    val currentWritingAnswer: String = "",
    val currentWritingStrokeGroups: List<List<DrawnStroke>> = emptyList(),
    val writingReviews: List<KanjiWritingReview> = emptyList(),
    val strokes: List<DrawnStroke> = emptyList(),
    val recognitionState: RecognitionState = RecognitionState.Idle,
    val inkModelState: InkModelState = InkModelState.Idle,
    val isFinished: Boolean = false,
) {
    val currentQuestion: KanjiQuestion?
        get() = questions.getOrNull(currentQuestionIndex)

    val currentWritingTargetChar: String
        get() = currentQuestion?.writingAnswer
            ?.getOrNull(currentWritingCharIndex)
            ?.toString()
            .orEmpty()
}

interface DigitalInkRecognizerClient {
    suspend fun ensureJapaneseModel()
    suspend fun recognize(
        strokes: List<DrawnStroke>,
        writingAreaWidth: Float,
        writingAreaHeight: Float,
        preContext: String = "",
    ): List<String>
}

class JapaneseMlKitDigitalInkRecognizerClient(
    private val languageTags: List<String> = listOf("ja", "ja-JP"),
) : DigitalInkRecognizerClient {
    private val remoteModelManager = RemoteModelManager.getInstance()
    private val model: DigitalInkRecognitionModel by lazy { createModel() }
    private val recognizer: DigitalInkRecognizer by lazy {
        DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).build(),
        )
    }

    private fun createModel(): DigitalInkRecognitionModel {
        val modelIdentifier = languageTags.firstNotNullOfOrNull { languageTag ->
            try {
                DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
            } catch (_: MlKitException) {
                null
            }
        } ?: throw IllegalArgumentException(
            "No ML Kit digital ink model for: ${languageTags.joinToString()}",
        )

        return DigitalInkRecognitionModel.builder(modelIdentifier).build()
    }

    override suspend fun ensureJapaneseModel() {
        val alreadyDownloaded = remoteModelManager.isModelDownloaded(model).await()
        if (!alreadyDownloaded) {
            remoteModelManager.download(model, DownloadConditions.Builder().build()).await()
        }
    }

    override suspend fun recognize(
        strokes: List<DrawnStroke>,
        writingAreaWidth: Float,
        writingAreaHeight: Float,
        preContext: String,
    ): List<String> {
        val ink = strokes.toInk()
        val context = RecognitionContext.builder()
            .setPreContext(preContext.takeLast(20))
            .setWritingArea(WritingArea(writingAreaWidth, writingAreaHeight))
            .build()

        val result: RecognitionResult = recognizer.recognize(ink, context).await()
        return result.candidates.map { it.text }
    }

    private fun List<DrawnStroke>.toInk(): Ink {
        val inkBuilder = Ink.builder()
        forEach { stroke ->
            val strokeBuilder = Ink.Stroke.builder()
            stroke.points.forEach { point ->
                strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, point.timestampMillis))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }
        return inkBuilder.build()
    }
}

class KanjiViewModel(
    private val recognizerClient: DigitalInkRecognizerClient,
    private val questionBank: List<KanjiQuestion>,
    private val questionAttemptStore: QuestionAttemptStore,
) : ViewModel() {
    constructor() : this(
        recognizerClient = JapaneseMlKitDigitalInkRecognizerClient(),
        questionBank = emptyList(),
        questionAttemptStore = InMemoryQuestionAttemptStore(),
    )

    companion object {
        fun factory(
            questionBank: List<KanjiQuestion>,
            questionAttemptStore: QuestionAttemptStore,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    KanjiViewModel(
                        recognizerClient = JapaneseMlKitDigitalInkRecognizerClient(),
                        questionBank = questionBank,
                        questionAttemptStore = questionAttemptStore,
                    ) as T
            }
    }

    private val _uiState = MutableStateFlow(
        KanjiUiState(
            questions = buildQuestionsForGrade(grade = 1, count = 10),
            shuffledReadingAnswers = questionsForGrade(1).firstOrNull()?.readingAnswers.orEmpty().shuffled(),
        ),
    )
    val uiState: StateFlow<KanjiUiState> = _uiState.asStateFlow()

    private var readingTimerJob: Job? = null
    private var readingScreenActive = false

    fun setSelectedGrade(grade: Int) {
        val safeGrade = grade.coerceIn(1, 6)
        _uiState.update { state ->
            val previewQuestions = buildQuestionsForGrade(safeGrade, state.questionCount)
            state.copy(
                selectedGrade = safeGrade,
                questions = previewQuestions,
                shuffledReadingAnswers = previewQuestions.firstOrNull()?.readingAnswers.orEmpty().shuffled(),
            )
        }
    }

    fun setReadingSecondsPerQuestion(seconds: Int) {
        val safeSeconds = seconds.coerceIn(1, 30)
        _uiState.update {
            it.copy(
                readingSecondsPerQuestion = safeSeconds,
                readingTimeRemaining = safeSeconds,
            )
        }
    }

    fun setQuestionCount(count: Int) {
        val safeCount = count.coerceIn(5, 100)
        _uiState.update { state ->
            val previewQuestions = buildQuestionsForGrade(state.selectedGrade, safeCount)
            state.copy(
                questionCount = safeCount,
                questions = previewQuestions,
                shuffledReadingAnswers = previewQuestions.firstOrNull()?.readingAnswers.orEmpty().shuffled(),
            )
        }
    }

    fun startReadingMode() {
        startReadingMode(uiState.value.readingSecondsPerQuestion)
    }

    fun startReadingMode(secondsPerQuestion: Int) {
        val safeSeconds = secondsPerQuestion.coerceAtLeast(1)
        val selectedQuestions = buildQuestionsForGrade(
            grade = uiState.value.selectedGrade,
            count = uiState.value.questionCount,
        )
        questionAttemptStore.recordAttempts(selectedQuestions.map { it.id })
        readingScreenActive = true
        readingTimerJob?.cancel()
        _uiState.update { state ->
            state.copy(
                mode = LearningMode.Reading,
                isSessionStarted = true,
                questions = selectedQuestions,
                currentQuestionIndex = 0,
                shuffledReadingAnswers = selectedQuestions.firstOrNull()?.readingAnswers.orEmpty().shuffled(),
                readingSecondsPerQuestion = safeSeconds,
                readingTimeRemaining = safeSeconds,
                readingCorrectCount = 0,
                readingOriginalQuestionCount = selectedQuestions.size,
                readingRetryRound = 0,
                resultPhase = ResultPhase.Final,
                readingReviews = emptyList(),
                readingAllReviews = emptyList(),
                currentWritingCharIndex = 0,
                currentWritingAnswer = "",
                currentWritingStrokeGroups = emptyList(),
                writingReviews = emptyList(),
                strokes = emptyList(),
                recognitionState = RecognitionState.Idle,
                isFinished = selectedQuestions.isEmpty(),
            )
        }
        startReadingTimer()
    }

    fun setReadingScreenActive(active: Boolean) {
        readingScreenActive = active
        if (
            active &&
            uiState.value.isSessionStarted &&
            uiState.value.mode == LearningMode.Reading &&
            !uiState.value.isFinished
        ) {
            startReadingTimer()
        } else {
            readingTimerJob?.cancel()
            readingTimerJob = null
        }
    }

    fun submitReadingAnswer(answer: String) {
        val state = uiState.value
        if (state.mode != LearningMode.Reading || state.isFinished) return

        moveToNextReadingQuestion(selectedAnswer = answer)
    }

    fun retryWrongReadingQuestions() {
        val state = uiState.value
        if (state.mode != LearningMode.Reading || state.resultPhase != ResultPhase.RetryNeeded) return

        val questionsById = state.questions.associateBy { it.id }
        val retryQuestions = state.readingAllReviews
            .filterNot { it.isCorrect }
            .mapNotNull { questionsById[it.questionId] }

        if (retryQuestions.isEmpty()) {
            _uiState.update {
                it.copy(
                    resultPhase = ResultPhase.Final,
                    readingCorrectCount = it.readingOriginalQuestionCount,
                )
            }
            return
        }

        readingScreenActive = true
        readingTimerJob?.cancel()
        _uiState.update {
            it.copy(
                questions = retryQuestions,
                currentQuestionIndex = 0,
                shuffledReadingAnswers = retryQuestions.firstOrNull()?.readingAnswers.orEmpty().shuffled(),
                readingTimeRemaining = it.readingSecondsPerQuestion,
                readingCorrectCount = 0,
                readingRetryRound = it.readingRetryRound + 1,
                resultPhase = ResultPhase.Final,
                readingReviews = emptyList(),
                isFinished = false,
            )
        }
        startReadingTimer()
    }

    fun startWritingMode() {
        val selectedQuestions = buildQuestionsForGrade(
            grade = uiState.value.selectedGrade,
            count = uiState.value.questionCount,
        )
        questionAttemptStore.recordAttempts(selectedQuestions.map { it.id })
        readingTimerJob?.cancel()
        readingScreenActive = false
        _uiState.update { state ->
            state.copy(
                mode = LearningMode.Writing,
                isSessionStarted = true,
                questions = selectedQuestions,
                currentQuestionIndex = 0,
                currentWritingCharIndex = 0,
                readingReviews = emptyList(),
                readingAllReviews = emptyList(),
                currentWritingAnswer = "",
                currentWritingStrokeGroups = emptyList(),
                writingReviews = emptyList(),
                resultPhase = ResultPhase.Final,
                strokes = emptyList(),
                recognitionState = RecognitionState.Idle,
                isFinished = selectedQuestions.isEmpty(),
            )
        }
        prepareInkModel()
    }

    fun updateWritingStrokes(strokes: List<DrawnStroke>) {
        _uiState.update { state ->
            state.copy(strokes = strokes, recognitionState = RecognitionState.Idle)
        }
    }

    fun clearWritingCanvas() {
        _uiState.update { state ->
            state.copy(strokes = emptyList(), recognitionState = RecognitionState.Idle)
        }
    }

    fun judgeCurrentWritingCharacter(canvasWidth: Float, canvasHeight: Float) {
        val state = uiState.value
        if (state.mode != LearningMode.Writing || state.isFinished) return
        if (state.strokes.isEmpty()) {
            _uiState.update {
                it.copy(recognitionState = RecognitionState.Error("文字を書いてから判定してください。"))
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    recognitionState = RecognitionState.Loading,
                    inkModelState = if (it.inkModelState == InkModelState.Ready) {
                        InkModelState.Ready
                    } else {
                        InkModelState.Downloading
                    },
                )
            }

            runCatching {
                recognizerClient.ensureJapaneseModel()
                _uiState.update { it.copy(inkModelState = InkModelState.Ready) }
                recognizerClient.recognize(
                    strokes = state.strokes,
                    writingAreaWidth = canvasWidth,
                    writingAreaHeight = canvasHeight,
                    preContext = state.currentQuestion?.writingAnswer
                        ?.take(state.currentWritingCharIndex)
                        .orEmpty(),
                )
            }.onSuccess { recognizedCandidates ->
                handleWritingRecognitionSuccess(recognizedCandidates)
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        recognitionState = RecognitionState.Error(
                            throwable.message ?: "手書き認識に失敗しました。",
                        ),
                        inkModelState = InkModelState.Error(
                            throwable.message ?: "日本語モデルを準備できませんでした。",
                        ),
                    )
                }
            }
        }
    }

    private fun prepareInkModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(inkModelState = InkModelState.Downloading) }
            runCatching { recognizerClient.ensureJapaneseModel() }
                .onSuccess {
                    _uiState.update { state -> state.copy(inkModelState = InkModelState.Ready) }
                }
                .onFailure { throwable ->
                    _uiState.update { state ->
                        state.copy(
                            inkModelState = InkModelState.Error(
                                throwable.message ?: "日本語モデルを準備できませんでした。",
                            ),
                        )
                    }
                }
        }
    }

    private fun startReadingTimer() {
        readingTimerJob?.cancel()
        if (!readingScreenActive) return

        readingTimerJob = viewModelScope.launch {
            while (uiState.value.mode == LearningMode.Reading && !uiState.value.isFinished) {
                delay(1_000)
                val state = uiState.value
                if (state.mode != LearningMode.Reading || state.isFinished) break

                if (state.readingTimeRemaining <= 1) {
                    moveToNextReadingQuestion(selectedAnswer = null)
                } else {
                    _uiState.update { it.copy(readingTimeRemaining = it.readingTimeRemaining - 1) }
                }
            }
        }
    }

    private fun moveToNextReadingQuestion(selectedAnswer: String?) {
        val state = uiState.value
        val question = state.currentQuestion ?: return
        val correctAnswer = question.readingAnswers.firstOrNull().orEmpty()
        val wasCorrect = selectedAnswer == correctAnswer
        val previousReview = state.readingAllReviews.firstOrNull { it.questionId == question.id }
        val review = KanjiAnswerReview(
            questionId = question.id,
            questionNumber = previousReview?.questionNumber ?: state.currentQuestionIndex + 1,
            sentence = question.fullSentence,
            selectedAnswer = selectedAnswer,
            correctAnswer = correctAnswer,
            isCorrect = wasCorrect,
        )
        val nextIndex = state.currentQuestionIndex + 1
        val finished = nextIndex >= state.questions.size
        val updatedReviews = state.readingReviews + review
        val updatedAllReviews = (state.readingAllReviews
            .filterNot { it.questionId == review.questionId } + review)
            .sortedBy { it.questionNumber }
        val hasWrongAnswers = updatedAllReviews.any { !it.isCorrect }
        _uiState.update {
            it.copy(
                currentQuestionIndex = nextIndex.coerceAtMost(it.questions.size),
                shuffledReadingAnswers = if (finished) {
                    emptyList()
                } else {
                    it.questions[nextIndex].readingAnswers.shuffled()
                },
                readingTimeRemaining = it.readingSecondsPerQuestion,
                readingCorrectCount = it.readingCorrectCount + if (wasCorrect) 1 else 0,
                readingReviews = updatedReviews,
                readingAllReviews = updatedAllReviews,
                resultPhase = if (finished && hasWrongAnswers) {
                    ResultPhase.RetryNeeded
                } else {
                    ResultPhase.Final
                },
                isFinished = finished,
            )
        }

        if (finished) {
            readingTimerJob?.cancel()
            readingTimerJob = null
        } else {
            startReadingTimer()
        }
    }

    private fun handleWritingRecognitionSuccess(recognizedCandidates: List<String>) {
        val state = uiState.value
        val expected = state.currentWritingTargetChar
        val recognizedText = recognizedCandidates
            .withoutRejectedWritingCandidates(expected)
            .firstOrNull()
            .orEmpty()
        val recognizedFirstChar = recognizedText.trim().firstOrNull()?.toString().orEmpty()
        val isCorrect = recognizedFirstChar == expected

        if (!isCorrect) {
            _uiState.update {
                it.copy(recognitionState = RecognitionState.Success(recognizedText, isCorrect = false))
            }
            return
        }

        val answerLength = state.currentQuestion?.writingAnswer?.length ?: 0
        val updatedWritingAnswer = state.currentWritingAnswer + recognizedFirstChar
        val updatedWritingStrokeGroups = state.currentWritingStrokeGroups + listOf(state.strokes)
        val nextCharIndex = state.currentWritingCharIndex + 1
        if (nextCharIndex < answerLength) {
            _uiState.update {
                it.copy(
                    currentWritingCharIndex = nextCharIndex,
                    currentWritingAnswer = updatedWritingAnswer,
                    currentWritingStrokeGroups = updatedWritingStrokeGroups,
                    strokes = emptyList(),
                    recognitionState = RecognitionState.Success(recognizedText, isCorrect = true),
                )
            }
            return
        }

        val nextQuestionIndex = state.currentQuestionIndex + 1
        val finished = nextQuestionIndex >= state.questions.size
        val question = state.currentQuestion
        val updatedWritingReviews = if (question == null) {
            state.writingReviews
        } else {
            state.writingReviews + KanjiWritingReview(
                questionId = question.id,
                questionNumber = state.currentQuestionIndex + 1,
                sentence = question.fullSentence,
                writtenAnswer = updatedWritingAnswer,
                correctAnswer = question.writingAnswer,
                writtenStrokeGroups = updatedWritingStrokeGroups,
            )
        }
        _uiState.update {
            it.copy(
                currentQuestionIndex = nextQuestionIndex.coerceAtMost(it.questions.size),
                currentWritingCharIndex = 0,
                currentWritingAnswer = "",
                currentWritingStrokeGroups = emptyList(),
                writingReviews = updatedWritingReviews,
                strokes = emptyList(),
                recognitionState = if (finished) {
                    RecognitionState.Success(recognizedText, isCorrect = true)
                } else {
                    RecognitionState.Idle
                },
                isFinished = finished,
            )
        }
    }

    override fun onCleared() {
        readingTimerJob?.cancel()
        super.onCleared()
    }

    fun returnToSettings() {
        readingTimerJob?.cancel()
        readingScreenActive = false
        _uiState.update { state ->
            state.copy(
                isSessionStarted = false,
                isFinished = false,
                currentQuestionIndex = 0,
                questions = buildQuestionsForGrade(state.selectedGrade, state.questionCount),
                shuffledReadingAnswers = questionsForGrade(state.selectedGrade)
                    .firstOrNull()
                    ?.readingAnswers
                    .orEmpty()
                    .shuffled(),
                readingTimeRemaining = state.readingSecondsPerQuestion,
                readingCorrectCount = 0,
                readingOriginalQuestionCount = 0,
                readingRetryRound = 0,
                resultPhase = ResultPhase.Final,
                readingReviews = emptyList(),
                readingAllReviews = emptyList(),
                currentWritingCharIndex = 0,
                currentWritingAnswer = "",
                currentWritingStrokeGroups = emptyList(),
                writingReviews = emptyList(),
                strokes = emptyList(),
                recognitionState = RecognitionState.Idle,
            )
        }
    }

    private fun questionsForGrade(grade: Int): List<KanjiQuestion> =
        questionBank.filter { it.grade == grade.coerceIn(1, 6) }

    private fun buildQuestionsForGrade(grade: Int, count: Int): List<KanjiQuestion> {
        val pool = questionsForGrade(grade)
        if (pool.isEmpty()) return emptyList()

        val safeCount = count.coerceIn(5, 100)
        val simulatedAttemptCounts = pool.associate { question ->
            question.id to questionAttemptStore.attemptCount(question.id)
        }.toMutableMap()

        return buildList {
            while (size < safeCount) {
                val fewestAttempts = pool.minOf { question ->
                    simulatedAttemptCounts.getValue(question.id)
                }
                val candidates = pool.filter { question ->
                    simulatedAttemptCounts.getValue(question.id) == fewestAttempts
                }
                val selectedQuestion = candidates.random()
                add(selectedQuestion)
                simulatedAttemptCounts[selectedQuestion.id] =
                    simulatedAttemptCounts.getValue(selectedQuestion.id) + 1
            }
        }
    }
}

private fun List<String>.withoutRejectedWritingCandidates(expected: String): List<String> =
    if (expected.firstOrNull()?.isKana() == false) {
        filterNot { candidate ->
            candidate.trim().firstOrNull()?.isKana() == true
        }
    } else {
        this
    }

private fun Char.isKana(): Boolean =
    this in '\u3040'..'\u309F' || this in '\u30A0'..'\u30FF'

interface QuestionAttemptStore {
    fun attemptCount(questionId: String): Int
    fun recordAttempts(questionIds: List<String>)
}

class SharedPreferencesQuestionAttemptStore(
    private val sharedPreferences: SharedPreferences,
) : QuestionAttemptStore {
    override fun attemptCount(questionId: String): Int =
        sharedPreferences.getInt(questionId.toPreferenceKey(), 0)

    override fun recordAttempts(questionIds: List<String>) {
        if (questionIds.isEmpty()) return

        sharedPreferences.edit().apply {
            questionIds.groupingBy { it }.eachCount().forEach { (questionId, addedCount) ->
                val key = questionId.toPreferenceKey()
                putInt(key, sharedPreferences.getInt(key, 0) + addedCount)
            }
        }.apply()
    }

    private fun String.toPreferenceKey(): String = "question_attempt_count_$this"
}

private class InMemoryQuestionAttemptStore : QuestionAttemptStore {
    private val attemptCounts = mutableMapOf<String, Int>()

    override fun attemptCount(questionId: String): Int =
        attemptCounts[questionId] ?: 0

    override fun recordAttempts(questionIds: List<String>) {
        questionIds.forEach { questionId ->
            attemptCounts[questionId] = (attemptCounts[questionId] ?: 0) + 1
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        if (continuation.isActive) continuation.resume(result)
    }
    addOnFailureListener { exception ->
        if (continuation.isActive) continuation.resumeWithException(exception)
    }
    addOnCanceledListener {
        continuation.cancel()
    }
}
