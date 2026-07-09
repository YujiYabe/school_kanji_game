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
import org.json.JSONArray
import org.json.JSONObject

data class KanjiQuestion(
    val id: String,
    val grade: Int,
    val fullSentence: String,
    val sentenceReading: String = "",
    val markedSentence: String = "",
    val markedSentenceReading: String = "",
    val englishSentence: String = "",
    val spanishSentence: String = "",
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
    val sentenceReading: String = "",
    val markedSentence: String = "",
    val markedSentenceReading: String = "",
    val targetText: String = "",
    val targetReading: String = "",
    val englishSentence: String,
    val spanishSentence: String,
    val selectedAnswer: String?,
    val correctAnswer: String,
    val isCorrect: Boolean,
)

data class KanjiWritingReview(
    val questionId: String,
    val questionNumber: Int,
    val sentence: String,
    val sentenceReading: String = "",
    val markedSentence: String = "",
    val markedSentenceReading: String = "",
    val targetText: String = "",
    val targetReading: String = "",
    val englishSentence: String,
    val spanishSentence: String,
    val writtenAnswer: String,
    val correctAnswer: String,
    val writtenStrokeGroups: List<List<DrawnStroke>>,
)

data class KanjiHistoryEntry(
    val id: String,
    val completedAtMillis: Long,
    val grade: Int,
    val questionCount: Int,
    val readingSecondsPerQuestion: Int,
    val readingReviews: List<KanjiAnswerReview>,
    val writingReviews: List<KanjiWritingReview>,
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
    val writingQuestions: List<KanjiQuestion> = emptyList(),
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
    val historyEntries: List<KanjiHistoryEntry> = emptyList(),
    val isHistoryVisible: Boolean = false,
    val selectedHistoryEntry: KanjiHistoryEntry? = null,
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
    private val settingsStore: KanjiSettingsStore,
    private val historyStore: KanjiHistoryStore,
) : ViewModel() {
    constructor() : this(
        recognizerClient = JapaneseMlKitDigitalInkRecognizerClient(),
        questionBank = emptyList(),
        questionAttemptStore = InMemoryQuestionAttemptStore(),
        settingsStore = InMemoryKanjiSettingsStore(),
        historyStore = InMemoryKanjiHistoryStore(),
    )

    companion object {
        fun factory(
            questionBank: List<KanjiQuestion>,
            questionAttemptStore: QuestionAttemptStore,
            settingsStore: KanjiSettingsStore,
            historyStore: KanjiHistoryStore,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    KanjiViewModel(
                        recognizerClient = JapaneseMlKitDigitalInkRecognizerClient(),
                        questionBank = questionBank,
                        questionAttemptStore = questionAttemptStore,
                        settingsStore = settingsStore,
                        historyStore = historyStore,
                    ) as T
            }
    }

    private val initialSettings = settingsStore.loadSettings()

    private val _uiState = MutableStateFlow(
        KanjiUiState(
            selectedGrade = initialSettings.selectedGrade,
            questionCount = initialSettings.questionCount,
            readingSecondsPerQuestion = initialSettings.readingSecondsPerQuestion,
            readingTimeRemaining = initialSettings.readingSecondsPerQuestion,
            questions = buildQuestionsForGrade(
                grade = initialSettings.selectedGrade,
                count = initialSettings.questionCount,
            ),
            shuffledReadingAnswers = questionsForGrade(initialSettings.selectedGrade)
                .firstOrNull()
                ?.readingAnswers
                .orEmpty()
                .shuffled(),
            historyEntries = historyStore.loadHistory(),
        ),
    )
    val uiState: StateFlow<KanjiUiState> = _uiState.asStateFlow()

    private var readingTimerJob: Job? = null
    private var readingScreenActive = false

    fun setSelectedGrade(grade: Int) {
        val safeGrade = grade.coerceIn(1, 6)
        settingsStore.saveSelectedGrade(safeGrade)
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
        val safeSeconds = seconds.toReadingTimerSeconds()
        settingsStore.saveReadingSecondsPerQuestion(safeSeconds)
        _uiState.update {
            it.copy(
                readingSecondsPerQuestion = safeSeconds,
                readingTimeRemaining = safeSeconds,
            )
        }
    }

    fun setQuestionCount(count: Int) {
        val safeCount = count.coerceIn(5, 100)
        settingsStore.saveQuestionCount(safeCount)
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
        val safeSeconds = secondsPerQuestion.toReadingTimerSeconds()
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
                writingQuestions = selectedQuestions,
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
                isHistoryVisible = false,
                selectedHistoryEntry = null,
            )
        }
        startReadingTimer()
    }

    fun showHistory() {
        readingTimerJob?.cancel()
        readingScreenActive = false
        _uiState.update {
            it.copy(
                historyEntries = historyStore.loadHistory(),
                isHistoryVisible = true,
                selectedHistoryEntry = null,
            )
        }
    }

    fun hideHistory() {
        _uiState.update {
            it.copy(
                isHistoryVisible = false,
                selectedHistoryEntry = null,
            )
        }
    }

    fun showHistoryDetail(entryId: String) {
        val entry = historyStore.loadHistory().firstOrNull { it.id == entryId }
        _uiState.update {
            it.copy(
                historyEntries = historyStore.loadHistory(),
                isHistoryVisible = true,
                selectedHistoryEntry = entry,
            )
        }
    }

    fun hideHistoryDetail() {
        _uiState.update {
            it.copy(selectedHistoryEntry = null)
        }
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

        val questionsById = (state.writingQuestions + state.questions)
            .distinctBy { it.id }
            .associateBy { it.id }
        val retryQuestions = state.readingReviews
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
                writingQuestions = selectedQuestions,
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
            sentenceReading = question.sentenceReading,
            markedSentence = question.markedSentence,
            markedSentenceReading = question.markedSentenceReading,
            targetText = question.targetText,
            targetReading = correctAnswer,
            englishSentence = question.englishSentence,
            spanishSentence = question.spanishSentence,
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
        if (finished) {
            finishReadingRound(
                wasCorrect = wasCorrect,
                updatedReviews = updatedReviews,
                updatedAllReviews = updatedAllReviews,
            )
        } else {
            _uiState.update {
                it.copy(
                    currentQuestionIndex = nextIndex,
                    shuffledReadingAnswers = it.questions[nextIndex].readingAnswers.shuffled(),
                    readingTimeRemaining = it.readingSecondsPerQuestion,
                    readingCorrectCount = it.readingCorrectCount + if (wasCorrect) 1 else 0,
                    readingReviews = updatedReviews,
                    readingAllReviews = updatedAllReviews,
                    resultPhase = ResultPhase.Final,
                    isFinished = false,
                )
            }
            startReadingTimer()
        }
    }

    private fun finishReadingRound(
        wasCorrect: Boolean,
        updatedReviews: List<KanjiAnswerReview>,
        updatedAllReviews: List<KanjiAnswerReview>,
    ) {
        val state = uiState.value
        val allSessionQuestions = (state.writingQuestions + state.questions).distinctBy { it.id }
        val questionsById = allSessionQuestions.associateBy { it.id }
        val retryQuestions = updatedReviews
            .filterNot { it.isCorrect }
            .mapNotNull { questionsById[it.questionId] }

        readingTimerJob?.cancel()
        readingTimerJob = null

        if (retryQuestions.isNotEmpty()) {
            readingScreenActive = false
            _uiState.update {
                it.copy(
                    currentQuestionIndex = it.questions.size,
                    shuffledReadingAnswers = emptyList(),
                    readingTimeRemaining = it.readingSecondsPerQuestion,
                    readingCorrectCount = it.readingCorrectCount + if (wasCorrect) 1 else 0,
                    readingReviews = updatedReviews,
                    readingAllReviews = updatedAllReviews,
                    resultPhase = ResultPhase.RetryNeeded,
                    isFinished = true,
                )
            }
            return
        }

        readingScreenActive = false
        val writingQuestions = state.writingQuestions.ifEmpty { state.questions }
        _uiState.update {
            it.copy(
                mode = LearningMode.Writing,
                questions = writingQuestions,
                currentQuestionIndex = 0,
                shuffledReadingAnswers = emptyList(),
                readingTimeRemaining = it.readingSecondsPerQuestion,
                readingCorrectCount = it.readingOriginalQuestionCount,
                readingReviews = updatedReviews,
                readingAllReviews = updatedAllReviews,
                resultPhase = ResultPhase.Final,
                currentWritingCharIndex = 0,
                currentWritingAnswer = "",
                currentWritingStrokeGroups = emptyList(),
                writingReviews = emptyList(),
                strokes = emptyList(),
                recognitionState = RecognitionState.Idle,
                isFinished = writingQuestions.isEmpty(),
            )
        }
        prepareInkModel()
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
                sentenceReading = question.sentenceReading,
                markedSentence = question.markedSentence,
                markedSentenceReading = question.markedSentenceReading,
                targetText = question.targetText,
                targetReading = question.readingAnswers.firstOrNull().orEmpty(),
                englishSentence = question.englishSentence,
                spanishSentence = question.spanishSentence,
                writtenAnswer = updatedWritingAnswer,
                correctAnswer = question.writingAnswer,
                writtenStrokeGroups = updatedWritingStrokeGroups,
            )
        }
        if (finished) {
            saveCompletedHistory(state, updatedWritingReviews)
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
                historyEntries = if (finished) historyStore.loadHistory() else it.historyEntries,
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
                writingQuestions = emptyList(),
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
                isHistoryVisible = false,
                selectedHistoryEntry = null,
            )
        }
    }

    private fun saveCompletedHistory(
        state: KanjiUiState,
        writingReviews: List<KanjiWritingReview>,
    ) {
        val completedAtMillis = System.currentTimeMillis()
        historyStore.saveEntry(
            KanjiHistoryEntry(
                id = "history_$completedAtMillis",
                completedAtMillis = completedAtMillis,
                grade = state.selectedGrade,
                questionCount = state.readingOriginalQuestionCount.takeIf { it > 0 }
                    ?: state.questionCount,
                readingSecondsPerQuestion = state.readingSecondsPerQuestion,
                readingReviews = state.readingAllReviews.ifEmpty { state.readingReviews },
                writingReviews = writingReviews.map { it.copy(writtenStrokeGroups = emptyList()) },
            ),
        )
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

data class KanjiSettings(
    val selectedGrade: Int = 1,
    val questionCount: Int = 10,
    val readingSecondsPerQuestion: Int = 10,
)

interface KanjiSettingsStore {
    fun loadSettings(): KanjiSettings
    fun saveSelectedGrade(selectedGrade: Int)
    fun saveQuestionCount(questionCount: Int)
    fun saveReadingSecondsPerQuestion(readingSecondsPerQuestion: Int)
}

interface KanjiHistoryStore {
    fun loadHistory(): List<KanjiHistoryEntry>
    fun saveEntry(entry: KanjiHistoryEntry)
}

class SharedPreferencesKanjiHistoryStore(
    private val sharedPreferences: SharedPreferences,
) : KanjiHistoryStore {
    override fun loadHistory(): List<KanjiHistoryEntry> {
        val rawHistory = sharedPreferences.getString(KEY_HISTORY_ENTRIES, null).orEmpty()
        if (rawHistory.isBlank()) return emptyList()

        return runCatching {
            val jsonArray = JSONArray(rawHistory)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val json = jsonArray.optJSONObject(index) ?: continue
                    add(json.toHistoryEntry())
                }
            }.sortedByDescending { it.completedAtMillis }
        }.getOrDefault(emptyList())
    }

    override fun saveEntry(entry: KanjiHistoryEntry) {
        val updatedHistory = (listOf(entry) + loadHistory())
            .distinctBy { it.id }
            .sortedByDescending { it.completedAtMillis }
            .take(MAX_HISTORY_COUNT)
        val jsonArray = JSONArray()
        updatedHistory.forEach { jsonArray.put(it.toJson()) }
        sharedPreferences.edit()
            .putString(KEY_HISTORY_ENTRIES, jsonArray.toString())
            .apply()
    }

    private fun JSONObject.toHistoryEntry(): KanjiHistoryEntry =
        KanjiHistoryEntry(
            id = optString("id"),
            completedAtMillis = optLong("completedAtMillis"),
            grade = optInt("grade").coerceIn(1, 6),
            questionCount = optInt("questionCount").coerceIn(1, 100),
            readingSecondsPerQuestion = optInt("readingSecondsPerQuestion").toReadingTimerSeconds(),
            readingReviews = optJSONArray("readingReviews").toReadingReviews(),
            writingReviews = optJSONArray("writingReviews").toWritingReviews(),
        )

    private fun KanjiHistoryEntry.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("completedAtMillis", completedAtMillis)
            .put("grade", grade)
            .put("questionCount", questionCount)
            .put("readingSecondsPerQuestion", readingSecondsPerQuestion)
            .put("readingReviews", readingReviews.toReadingJsonArray())
            .put("writingReviews", writingReviews.toWritingJsonArray())

    private fun JSONArray?.toReadingReviews(): List<KanjiAnswerReview> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                add(
                    KanjiAnswerReview(
                        questionId = json.optString("questionId"),
                        questionNumber = json.optInt("questionNumber"),
                        sentence = json.optString("sentence"),
                        sentenceReading = json.optString("sentenceReading"),
                        markedSentence = json.optString("markedSentence"),
                        markedSentenceReading = json.optString("markedSentenceReading"),
                        targetText = json.optString("targetText"),
                        targetReading = json.optString("targetReading"),
                        englishSentence = json.optString("englishSentence"),
                        spanishSentence = json.optString("spanishSentence"),
                        selectedAnswer = json.optStringOrNull("selectedAnswer"),
                        correctAnswer = json.optString("correctAnswer"),
                        isCorrect = json.optBoolean("isCorrect"),
                    ),
                )
            }
        }
    }

    private fun List<KanjiAnswerReview>.toReadingJsonArray(): JSONArray =
        JSONArray().also { jsonArray ->
            forEach { review ->
                jsonArray.put(
                    JSONObject()
                        .put("questionId", review.questionId)
                        .put("questionNumber", review.questionNumber)
                        .put("sentence", review.sentence)
                        .put("sentenceReading", review.sentenceReading)
                        .put("markedSentence", review.markedSentence)
                        .put("markedSentenceReading", review.markedSentenceReading)
                        .put("targetText", review.targetText)
                        .put("targetReading", review.targetReading)
                        .put("englishSentence", review.englishSentence)
                        .put("spanishSentence", review.spanishSentence)
                        .put("selectedAnswer", review.selectedAnswer)
                        .put("correctAnswer", review.correctAnswer)
                        .put("isCorrect", review.isCorrect),
                )
            }
        }

    private fun JSONArray?.toWritingReviews(): List<KanjiWritingReview> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                add(
                    KanjiWritingReview(
                        questionId = json.optString("questionId"),
                        questionNumber = json.optInt("questionNumber"),
                        sentence = json.optString("sentence"),
                        sentenceReading = json.optString("sentenceReading"),
                        markedSentence = json.optString("markedSentence"),
                        markedSentenceReading = json.optString("markedSentenceReading"),
                        targetText = json.optString("targetText"),
                        targetReading = json.optString("targetReading"),
                        englishSentence = json.optString("englishSentence"),
                        spanishSentence = json.optString("spanishSentence"),
                        writtenAnswer = json.optString("writtenAnswer"),
                        correctAnswer = json.optString("correctAnswer"),
                        writtenStrokeGroups = emptyList(),
                    ),
                )
            }
        }
    }

    private fun List<KanjiWritingReview>.toWritingJsonArray(): JSONArray =
        JSONArray().also { jsonArray ->
            forEach { review ->
                jsonArray.put(
                    JSONObject()
                        .put("questionId", review.questionId)
                        .put("questionNumber", review.questionNumber)
                        .put("sentence", review.sentence)
                        .put("sentenceReading", review.sentenceReading)
                        .put("markedSentence", review.markedSentence)
                        .put("markedSentenceReading", review.markedSentenceReading)
                        .put("targetText", review.targetText)
                        .put("targetReading", review.targetReading)
                        .put("englishSentence", review.englishSentence)
                        .put("spanishSentence", review.spanishSentence)
                        .put("writtenAnswer", review.writtenAnswer)
                        .put("correctAnswer", review.correctAnswer),
                )
            }
        }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name)

    private companion object {
        const val KEY_HISTORY_ENTRIES = "history_entries"
        const val MAX_HISTORY_COUNT = 50
    }
}

class SharedPreferencesKanjiSettingsStore(
    private val sharedPreferences: SharedPreferences,
) : KanjiSettingsStore {
    override fun loadSettings(): KanjiSettings =
        KanjiSettings(
            selectedGrade = sharedPreferences.getInt(KEY_SELECTED_GRADE, 1).coerceIn(1, 6),
            questionCount = sharedPreferences.getInt(KEY_QUESTION_COUNT, 10).coerceIn(5, 100),
            readingSecondsPerQuestion = sharedPreferences
                .getInt(KEY_READING_SECONDS_PER_QUESTION, 10)
                .toReadingTimerSeconds(),
        )

    override fun saveSelectedGrade(selectedGrade: Int) {
        sharedPreferences.edit()
            .putInt(KEY_SELECTED_GRADE, selectedGrade.coerceIn(1, 6))
            .apply()
    }

    override fun saveQuestionCount(questionCount: Int) {
        sharedPreferences.edit()
            .putInt(KEY_QUESTION_COUNT, questionCount.coerceIn(5, 100))
            .apply()
    }

    override fun saveReadingSecondsPerQuestion(readingSecondsPerQuestion: Int) {
        sharedPreferences.edit()
            .putInt(KEY_READING_SECONDS_PER_QUESTION, readingSecondsPerQuestion.toReadingTimerSeconds())
            .apply()
    }

    private companion object {
        const val KEY_SELECTED_GRADE = "selected_grade"
        const val KEY_QUESTION_COUNT = "question_count"
        const val KEY_READING_SECONDS_PER_QUESTION = "reading_seconds_per_question"
    }
}

private class InMemoryKanjiSettingsStore : KanjiSettingsStore {
    private var settings = KanjiSettings()

    override fun loadSettings(): KanjiSettings = settings

    override fun saveSelectedGrade(selectedGrade: Int) {
        settings = settings.copy(selectedGrade = selectedGrade.coerceIn(1, 6))
    }

    override fun saveQuestionCount(questionCount: Int) {
        settings = settings.copy(questionCount = questionCount.coerceIn(5, 100))
    }

    override fun saveReadingSecondsPerQuestion(readingSecondsPerQuestion: Int) {
        settings = settings.copy(
            readingSecondsPerQuestion = readingSecondsPerQuestion.toReadingTimerSeconds(),
        )
    }
}

private class InMemoryKanjiHistoryStore : KanjiHistoryStore {
    private var historyEntries = emptyList<KanjiHistoryEntry>()

    override fun loadHistory(): List<KanjiHistoryEntry> = historyEntries

    override fun saveEntry(entry: KanjiHistoryEntry) {
        historyEntries = (listOf(entry) + historyEntries)
            .distinctBy { it.id }
            .sortedByDescending { it.completedAtMillis }
            .take(50)
    }
}

private fun Int.toReadingTimerSeconds(): Int {
    val rounded = ((this + 5) / 10) * 10
    return rounded.coerceIn(10, 180)
}

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
