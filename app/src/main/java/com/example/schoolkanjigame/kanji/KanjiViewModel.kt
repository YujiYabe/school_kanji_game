package com.example.schoolkanjigame.kanji

import android.content.SharedPreferences
import android.util.Base64
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
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_READING_ATTEMPTS = 3

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
    val attemptNumber: Int = 1,
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
    val isUnrecoverable: Boolean = false,
    val correctAnswerIndex: Int = -1,
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
    val isSkipped: Boolean = false,
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

data class KanjiGradeProgress(
    val grade: Int,
    val solvedCount: Int,
    val questionPoolCount: Int,
) {
    val achievementPercent: Int
        get() = if (questionPoolCount == 0) 0 else (solvedCount * 100 / questionPoolCount)
}

data class YoutubeWifiNetwork(
    val ssid: String,
    val password: String,
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
    val isParentAdminVisible: Boolean = false,
    val isParentAuthenticated: Boolean = false,
    val isParentPasswordConfigured: Boolean = false,
    val parentAuthError: String? = null,
    val parentPasswordMessage: String? = null,
    val youtubeMinutesPer100Correct: Int = 30,
    val youtubeRewardTotalScore: Int = 0,
    val youtubeRewardAvailableSeconds: Int = 0,
    val isYoutubeRewardVisible: Boolean = false,
    val isYoutubeInAppEnabled: Boolean = true,
    val isYoutubeRewardChargeable: Boolean = false,
    val gradeProgress: List<KanjiGradeProgress> = emptyList(),
    val enabledGrades: Set<Int> = (1..6).toSet(),
    val youtubeWifiSsid: String = "",
    val youtubeWifiPassword: String = "",
    val youtubeWifiNetworks: List<YoutubeWifiNetwork> = emptyList(),
    val youtubeLastPlaybackUrl: String = "",
    val youtubeLastPlaybackSeconds: Int = 0,
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
    private val initialHistory = historyStore.loadHistory()
    private val initialEnabledGrades = initialSettings.enabledGrades.sanitizedEnabledGrades()
    private val initialSelectedGrade = initialSettings.selectedGrade
        .takeIf { it in initialEnabledGrades }
        ?: initialEnabledGrades.first()

    private val _uiState = MutableStateFlow(
        KanjiUiState(
            selectedGrade = initialSelectedGrade,
            questionCount = initialSettings.questionCount,
            readingSecondsPerQuestion = initialSettings.readingSecondsPerQuestion,
            readingTimeRemaining = initialSettings.readingSecondsPerQuestion,
            questions = buildQuestionsForGrade(
                grade = initialSelectedGrade,
                count = initialSettings.questionCount,
            ),
            shuffledReadingAnswers = questionsForGrade(initialSelectedGrade)
                .firstOrNull()
                ?.readingAnswers
                .orEmpty()
                .shuffled(),
            historyEntries = initialHistory,
            isParentPasswordConfigured = true,
            youtubeMinutesPer100Correct = initialSettings.youtubeMinutesPer100Correct,
            youtubeRewardTotalScore = initialHistory.totalRewardScore(),
            youtubeRewardAvailableSeconds = youtubeRewardAvailableSeconds(initialHistory, initialSettings),
            isYoutubeInAppEnabled = initialSettings.isYoutubeInAppEnabled,
            gradeProgress = buildGradeProgress(initialSettings.gradeSolvedCounts),
            enabledGrades = initialEnabledGrades,
            youtubeWifiSsid = initialSettings.youtubeWifiSsid,
            youtubeWifiPassword = initialSettings.youtubeWifiPassword,
            youtubeWifiNetworks = initialSettings.youtubeWifiNetworks,
            youtubeLastPlaybackUrl = initialSettings.youtubeLastPlaybackUrl,
            youtubeLastPlaybackSeconds = initialSettings.youtubeLastPlaybackSeconds,
        ),
    )
    val uiState: StateFlow<KanjiUiState> = _uiState.asStateFlow()

    private var readingTimerJob: Job? = null
    private var youtubeRewardTimerJob: Job? = null
    private var readingScreenActive = false

    fun setSelectedGrade(grade: Int) {
        val enabledGrades = settingsStore.loadSettings().enabledGrades.sanitizedEnabledGrades()
        if (grade !in enabledGrades) return

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

    fun setGradeEnabled(grade: Int, enabled: Boolean) {
        val safeGrade = grade.coerceIn(1, 6)
        val currentEnabledGrades = settingsStore.loadSettings().enabledGrades.sanitizedEnabledGrades()
        val updatedEnabledGrades = if (enabled) {
            currentEnabledGrades + safeGrade
        } else {
            (currentEnabledGrades - safeGrade).takeIf { it.isNotEmpty() } ?: currentEnabledGrades
        }.sanitizedEnabledGrades()
        val selectedGrade = uiState.value.selectedGrade
            .takeIf { it in updatedEnabledGrades }
            ?: updatedEnabledGrades.first()

        settingsStore.saveEnabledGrades(updatedEnabledGrades)
        settingsStore.saveSelectedGrade(selectedGrade)
        _uiState.update { state ->
            val previewQuestions = buildQuestionsForGrade(selectedGrade, state.questionCount)
            state.copy(
                selectedGrade = selectedGrade,
                enabledGrades = updatedEnabledGrades,
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

    fun setYoutubeMinutesPer100Correct(minutes: Int) {
        val safeMinutes = minutes.coerceIn(0, 120)
        settingsStore.saveYoutubeMinutesPer100Correct(safeMinutes)
        val settings = settingsStore.loadSettings()
        _uiState.update { state ->
            state.copy(
                youtubeMinutesPer100Correct = safeMinutes,
                youtubeRewardAvailableSeconds = youtubeRewardAvailableSeconds(state.historyEntries, settings),
                gradeProgress = buildGradeProgress(settings.gradeSolvedCounts),
            )
        }
    }

    fun setYoutubeInAppEnabled(enabled: Boolean) {
        settingsStore.saveYoutubeInAppEnabled(enabled)
        if (!enabled) {
            if (uiState.value.isYoutubeRewardChargeable) {
                reconcileYoutubeRewardUsage()
            }
            youtubeRewardTimerJob?.cancel()
            youtubeRewardTimerJob = null
        }
        _uiState.update {
            it.copy(
                isYoutubeInAppEnabled = enabled,
                isYoutubeRewardVisible = if (enabled) it.isYoutubeRewardVisible else false,
                isYoutubeRewardChargeable = if (enabled) it.isYoutubeRewardChargeable else false,
            )
        }
    }

    fun setYoutubeRewardAvailableSeconds(seconds: Int) {
        val safeSeconds = seconds.coerceIn(0, MAX_YOUTUBE_REWARD_SECONDS)
        youtubeRewardTimerJob?.cancel()
        youtubeRewardTimerJob = null
        val settings = settingsStore.loadSettings()
        val historyEntries = historyStore.loadHistory()
        val earnedSeconds = youtubeRewardEarnedSeconds(historyEntries, settings)
        settingsStore.saveYoutubeRewardUsedSeconds(earnedSeconds - safeSeconds)
        settingsStore.saveYoutubeRewardStartedAtMillis(0L)
        refreshYoutubeRewardState()
        _uiState.update {
            it.copy(
                isYoutubeRewardVisible = false,
                isYoutubeRewardChargeable = false,
            )
        }
    }

    fun saveYoutubeWifiSettings(ssid: String, password: String) {
        val safeSsid = ssid.trim()
        settingsStore.saveYoutubeWifiSettings(safeSsid, password)
        val settings = settingsStore.loadSettings()
        _uiState.update {
            it.copy(
                youtubeWifiSsid = safeSsid,
                youtubeWifiPassword = password,
                youtubeWifiNetworks = settings.youtubeWifiNetworks,
            )
        }
    }

    fun deleteYoutubeWifiSettings(ssid: String) {
        val safeSsid = ssid.trim()
        if (safeSsid.isBlank()) return

        settingsStore.deleteYoutubeWifiSettings(safeSsid)
        val settings = settingsStore.loadSettings()
        _uiState.update {
            it.copy(
                youtubeWifiSsid = settings.youtubeWifiSsid,
                youtubeWifiPassword = settings.youtubeWifiPassword,
                youtubeWifiNetworks = settings.youtubeWifiNetworks,
            )
        }
    }

    fun saveYoutubePlaybackProgress(url: String, seconds: Int) {
        val safeUrl = url.trim()
        val safeSeconds = seconds.coerceAtLeast(0)
        if (safeUrl.isBlank()) return
        settingsStore.saveYoutubePlaybackProgress(safeUrl, safeSeconds)
        _uiState.update {
            it.copy(
                youtubeLastPlaybackUrl = safeUrl,
                youtubeLastPlaybackSeconds = safeSeconds,
            )
        }
    }

    fun startYoutubeRewardSession() {
        reconcileYoutubeRewardUsage()
        if (uiState.value.youtubeRewardAvailableSeconds <= 0) return
        readingTimerJob?.cancel()
        readingScreenActive = false
        _uiState.update {
            it.copy(
                isYoutubeRewardVisible = true,
                isHistoryVisible = false,
                isParentAdminVisible = false,
                selectedHistoryEntry = null,
                isYoutubeRewardChargeable = false,
            )
        }
        startYoutubeRewardTimer()
    }

    fun hideYoutubeReward() {
        if (uiState.value.isYoutubeRewardChargeable) {
            reconcileYoutubeRewardUsage()
        }
        youtubeRewardTimerJob?.cancel()
        youtubeRewardTimerJob = null
        _uiState.update {
            it.copy(
                isYoutubeRewardVisible = false,
                isYoutubeRewardChargeable = false,
            )
        }
    }

    fun setYoutubeRewardChargeable(chargeable: Boolean) {
        val wasChargeable = uiState.value.isYoutubeRewardChargeable
        if (chargeable && !wasChargeable) {
            settingsStore.saveYoutubeRewardStartedAtMillis(System.currentTimeMillis())
        } else if (!chargeable && wasChargeable) {
            reconcileYoutubeRewardUsage()
        }
        _uiState.update {
            it.copy(isYoutubeRewardChargeable = chargeable && it.isYoutubeRewardVisible)
        }
    }

    fun reconcileYoutubeRewardUsage() {
        val settings = settingsStore.loadSettings()
        val startedAtMillis = settings.youtubeRewardStartedAtMillis
        if (startedAtMillis <= 0L) return

        val historyEntries = historyStore.loadHistory()
        val availableSeconds = youtubeRewardAvailableSeconds(historyEntries, settings)
        val elapsedSeconds = ((System.currentTimeMillis() - startedAtMillis) / 1_000L)
            .toInt()
            .coerceAtLeast(0)
        val consumedSeconds = elapsedSeconds.coerceAtMost(availableSeconds)
        settingsStore.saveYoutubeRewardUsedSeconds(settings.youtubeRewardUsedSeconds + consumedSeconds)
        settingsStore.saveYoutubeRewardStartedAtMillis(0L)
        refreshYoutubeRewardState()
    }

    private fun startYoutubeRewardTimer() {
        youtubeRewardTimerJob?.cancel()
        youtubeRewardTimerJob = viewModelScope.launch {
            while (uiState.value.isYoutubeRewardVisible) {
                delay(1_000)
                if (!uiState.value.isYoutubeRewardChargeable) continue
                val settings = settingsStore.loadSettings()
                val historyEntries = historyStore.loadHistory()
                val availableSeconds = youtubeRewardAvailableSeconds(historyEntries, settings)
                if (availableSeconds <= 1) {
                    settingsStore.saveYoutubeRewardUsedSeconds(
                        settings.youtubeRewardUsedSeconds + availableSeconds.coerceAtLeast(0),
                    )
                    settingsStore.saveYoutubeRewardStartedAtMillis(0L)
                    _uiState.update {
                        it.copy(
                            isYoutubeRewardVisible = false,
                            isYoutubeRewardChargeable = false,
                            youtubeRewardTotalScore = historyEntries.totalRewardScore(),
                            youtubeRewardAvailableSeconds = 0,
                        )
                    }
                    break
                }

                settingsStore.saveYoutubeRewardUsedSeconds(settings.youtubeRewardUsedSeconds + 1)
                settingsStore.saveYoutubeRewardStartedAtMillis(System.currentTimeMillis())
                _uiState.update {
                    it.copy(
                        youtubeRewardTotalScore = historyEntries.totalRewardScore(),
                        youtubeRewardAvailableSeconds = availableSeconds - 1,
                    )
                }
            }
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
        val historyEntries = historyStore.loadHistory()
        _uiState.update {
            it.copy(
                historyEntries = historyEntries,
                isHistoryVisible = true,
                selectedHistoryEntry = null,
                isParentAdminVisible = false,
                isYoutubeRewardVisible = false,
                youtubeRewardTotalScore = historyEntries.totalRewardScore(),
                youtubeRewardAvailableSeconds = youtubeRewardAvailableSeconds(
                    historyEntries,
                    settingsStore.loadSettings(),
                ),
                gradeProgress = buildGradeProgress(settingsStore.loadSettings().gradeSolvedCounts),
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

    fun showParentAdmin() {
        readingTimerJob?.cancel()
        readingScreenActive = false
        _uiState.update {
            it.copy(
                isParentAdminVisible = true,
                isParentAuthenticated = false,
                isHistoryVisible = false,
                selectedHistoryEntry = null,
                parentAuthError = null,
                parentPasswordMessage = null,
                isYoutubeRewardVisible = false,
            )
        }
    }

    fun hideParentAdmin() {
        _uiState.update {
            it.copy(
                isParentAdminVisible = false,
                isParentAuthenticated = false,
                parentAuthError = null,
                parentPasswordMessage = null,
                isYoutubeRewardVisible = false,
            )
        }
    }

    fun unlockParentAdmin(password: String) {
        val settings = settingsStore.loadSettings()
        val isAuthenticated = verifyParentPassword(password, settings)
        _uiState.update {
            it.copy(
                isParentAuthenticated = isAuthenticated,
                parentAuthError = if (isAuthenticated) null else "パスワードが違います。",
                parentPasswordMessage = null,
            )
        }
    }

    fun saveParentPassword(currentPassword: String, newPassword: String): Boolean {
        val settings = settingsStore.loadSettings()
        if (!newPassword.all(Char::isDigit)) {
            _uiState.update {
                it.copy(
                    parentAuthError = null,
                    parentPasswordMessage = "数字だけで設定してください。",
                )
            }
            return false
        }
        if (newPassword.length < MIN_PARENT_PASSWORD_LENGTH) {
            _uiState.update {
                it.copy(
                    parentAuthError = null,
                    parentPasswordMessage = "${MIN_PARENT_PASSWORD_LENGTH}桁以上で設定してください。",
                )
            }
            return false
        }
        if (!verifyParentPassword(currentPassword, settings)) {
            _uiState.update {
                it.copy(
                    parentAuthError = null,
                    parentPasswordMessage = "現在のパスワードが違います。",
                )
            }
            return false
        }

        val passwordHash = ParentPasswordHash.create(newPassword)
        settingsStore.saveParentPasswordHash(
            salt = passwordHash.salt,
            hash = passwordHash.hash,
        )
        _uiState.update {
            it.copy(
                isParentPasswordConfigured = true,
                isParentAuthenticated = true,
                parentAuthError = null,
                parentPasswordMessage = "パスワードを保存しました。",
            )
        }
        return true
    }

    private fun refreshYoutubeRewardState() {
        val historyEntries = historyStore.loadHistory()
        val settings = settingsStore.loadSettings()
        _uiState.update {
            it.copy(
                historyEntries = historyEntries,
                youtubeMinutesPer100Correct = settings.youtubeMinutesPer100Correct,
                youtubeRewardTotalScore = historyEntries.totalRewardScore(),
                youtubeRewardAvailableSeconds = youtubeRewardAvailableSeconds(historyEntries, settings),
                isYoutubeInAppEnabled = settings.isYoutubeInAppEnabled,
                gradeProgress = buildGradeProgress(settings.gradeSolvedCounts),
                youtubeWifiSsid = settings.youtubeWifiSsid,
                youtubeWifiPassword = settings.youtubeWifiPassword,
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

    fun deleteHistoryEntry(entryId: String) {
        historyStore.deleteEntry(entryId)
        val updatedHistory = historyStore.loadHistory()
        _uiState.update {
            it.copy(
                historyEntries = updatedHistory,
                selectedHistoryEntry = it.selectedHistoryEntry?.takeUnless { entry -> entry.id == entryId },
                youtubeRewardTotalScore = updatedHistory.totalRewardScore(),
                youtubeRewardAvailableSeconds = youtubeRewardAvailableSeconds(
                    updatedHistory,
                    settingsStore.loadSettings(),
                ),
                gradeProgress = buildGradeProgress(settingsStore.loadSettings().gradeSolvedCounts),
            )
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

        val previousReviewsById = state.readingAllReviews.associateBy { it.questionId }
        val questionsById = (state.writingQuestions + state.questions)
            .distinctBy { it.id }
            .associateBy { it.id }
        val retryQuestions = state.readingReviews
            .filter { !it.isCorrect && !it.isUnrecoverable }
            .mapNotNull { questionsById[it.questionId] }

        if (retryQuestions.isEmpty()) {
            _uiState.update {
                it.copy(
                    resultPhase = ResultPhase.Final,
                    readingCorrectCount = it.readingAllReviews.count { review -> review.isCorrect },
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
                shuffledReadingAnswers = retryQuestions.firstOrNull()
                    ?.shuffledReadingAnswersAvoiding(previousReviewsById)
                    .orEmpty(),
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

    fun skipCurrentWritingQuestion() {
        val state = uiState.value
        if (
            state.mode != LearningMode.Writing ||
            state.isFinished ||
            state.recognitionState == RecognitionState.Loading
        ) {
            return
        }

        val question = state.currentQuestion ?: return
        val nextQuestionIndex = state.currentQuestionIndex + 1
        val finished = nextQuestionIndex >= state.questions.size
        val skippedStrokeGroups = if (state.strokes.isEmpty()) {
            state.currentWritingStrokeGroups
        } else {
            state.currentWritingStrokeGroups + listOf(state.strokes)
        }
        val updatedWritingReviews = state.writingReviews + question.toWritingReview(
            questionNumber = state.currentQuestionIndex + 1,
            writtenAnswer = state.currentWritingAnswer,
            writtenStrokeGroups = skippedStrokeGroups,
            isSkipped = true,
        )

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
                recognitionState = RecognitionState.Idle,
                isFinished = finished,
                historyEntries = if (finished) historyStore.loadHistory() else it.historyEntries,
            )
        }
        if (finished) refreshYoutubeRewardState()
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
        val attemptNumber = (previousReview?.attemptNumber ?: 0) + 1
        val review = KanjiAnswerReview(
            questionId = question.id,
            questionNumber = previousReview?.questionNumber ?: state.currentQuestionIndex + 1,
            attemptNumber = attemptNumber,
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
            isUnrecoverable = !wasCorrect && attemptNumber >= MAX_READING_ATTEMPTS,
            correctAnswerIndex = state.shuffledReadingAnswers.indexOf(correctAnswer),
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
                val previousReviewsById = updatedAllReviews.associateBy { review -> review.questionId }
                it.copy(
                    currentQuestionIndex = nextIndex,
                    shuffledReadingAnswers = it.questions[nextIndex]
                        .shuffledReadingAnswersAvoiding(previousReviewsById),
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
            .filter { !it.isCorrect && !it.isUnrecoverable }
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
        val unrecoverableReadingQuestionIds = updatedAllReviews
            .filter { it.isUnrecoverable }
            .map { it.questionId }
            .toSet()
        val writingQuestions = state.writingQuestions
            .ifEmpty { state.questions }
            .filterNot { it.id in unrecoverableReadingQuestionIds }

        if (writingQuestions.isEmpty()) {
            saveCompletedHistory(
                state = state,
                writingReviews = emptyList(),
                readingReviews = updatedAllReviews.ifEmpty { updatedReviews },
            )
        }

        _uiState.update {
            it.copy(
                mode = LearningMode.Writing,
                questions = writingQuestions,
                writingQuestions = writingQuestions,
                currentQuestionIndex = 0,
                shuffledReadingAnswers = emptyList(),
                readingTimeRemaining = it.readingSecondsPerQuestion,
                readingCorrectCount = updatedAllReviews.count { review -> review.isCorrect },
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
                historyEntries = if (writingQuestions.isEmpty()) historyStore.loadHistory() else it.historyEntries,
            )
        }
        if (writingQuestions.isEmpty()) refreshYoutubeRewardState()
        if (writingQuestions.isNotEmpty()) {
            prepareInkModel()
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
            state.writingReviews + question.toWritingReview(
                questionNumber = state.currentQuestionIndex + 1,
                writtenAnswer = updatedWritingAnswer,
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
        if (finished) refreshYoutubeRewardState()
    }

    private fun KanjiQuestion.toWritingReview(
        questionNumber: Int,
        writtenAnswer: String,
        writtenStrokeGroups: List<List<DrawnStroke>>,
        isSkipped: Boolean = false,
    ): KanjiWritingReview =
        KanjiWritingReview(
            questionId = id,
            questionNumber = questionNumber,
            sentence = fullSentence,
            sentenceReading = sentenceReading,
            markedSentence = markedSentence,
            markedSentenceReading = markedSentenceReading,
            targetText = targetText,
            targetReading = readingAnswers.firstOrNull().orEmpty(),
            englishSentence = englishSentence,
            spanishSentence = spanishSentence,
            writtenAnswer = writtenAnswer,
            correctAnswer = writingAnswer,
            writtenStrokeGroups = writtenStrokeGroups,
            isSkipped = isSkipped,
        )

    override fun onCleared() {
        readingTimerJob?.cancel()
        youtubeRewardTimerJob?.cancel()
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
                isParentAdminVisible = false,
                isParentAuthenticated = false,
                parentAuthError = null,
                parentPasswordMessage = null,
                isYoutubeRewardVisible = false,
            )
        }
    }

    private fun saveCompletedHistory(
        state: KanjiUiState,
        writingReviews: List<KanjiWritingReview>,
        readingReviews: List<KanjiAnswerReview> = state.readingAllReviews.ifEmpty { state.readingReviews },
    ) {
        val completedAtMillis = System.currentTimeMillis()
        val completedQuestionCount = state.readingOriginalQuestionCount
            .takeIf { it > 0 }
            ?: state.questionCount
        settingsStore.addSolvedQuestions(
            grade = state.selectedGrade,
            solvedCount = completedQuestionCount,
        )
        historyStore.saveEntry(
            KanjiHistoryEntry(
                id = "history_$completedAtMillis",
                completedAtMillis = completedAtMillis,
                grade = state.selectedGrade,
                questionCount = completedQuestionCount,
                readingSecondsPerQuestion = state.readingSecondsPerQuestion,
                readingReviews = readingReviews,
                writingReviews = writingReviews,
            ),
        )
    }

    private fun KanjiQuestion.shuffledReadingAnswersAvoiding(
        previousReviewsById: Map<String, KanjiAnswerReview>,
    ): List<String> {
        val previousCorrectAnswerIndex = previousReviewsById[id]?.correctAnswerIndex ?: -1
        val answers = readingAnswers.shuffled().toMutableList()
        val currentCorrectAnswerIndex = answers.indexOf(readingAnswers.firstOrNull().orEmpty())
        if (
            previousCorrectAnswerIndex >= 0 &&
            currentCorrectAnswerIndex == previousCorrectAnswerIndex &&
            answers.size > 1
        ) {
            val swapIndex = if (currentCorrectAnswerIndex == answers.lastIndex) 0 else currentCorrectAnswerIndex + 1
            val correctAnswer = answers[currentCorrectAnswerIndex]
            answers[currentCorrectAnswerIndex] = answers[swapIndex]
            answers[swapIndex] = correctAnswer
        }
        return answers
    }

    private fun questionsForGrade(grade: Int): List<KanjiQuestion> =
        questionBank.filter { it.grade == grade.coerceIn(1, 6) }

    private fun buildGradeProgress(gradeSolvedCounts: Map<Int, Int>): List<KanjiGradeProgress> =
        (1..6).map { grade ->
            KanjiGradeProgress(
                grade = grade,
                solvedCount = gradeSolvedCounts[grade] ?: 0,
                questionPoolCount = questionsForGrade(grade).size,
            )
        }

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
    val parentPasswordSalt: String = "",
    val parentPasswordHash: String = "",
    val youtubeMinutesPer100Correct: Int = 30,
    val youtubeRewardUsedSeconds: Int = 0,
    val youtubeRewardStartedAtMillis: Long = 0L,
    val isYoutubeInAppEnabled: Boolean = true,
    val gradeSolvedCounts: Map<Int, Int> = emptyMap(),
    val enabledGrades: Set<Int> = (1..6).toSet(),
    val youtubeWifiSsid: String = "",
    val youtubeWifiPassword: String = "",
    val youtubeWifiNetworks: List<YoutubeWifiNetwork> = emptyList(),
    val youtubeLastPlaybackUrl: String = "",
    val youtubeLastPlaybackSeconds: Int = 0,
)

interface KanjiSettingsStore {
    fun loadSettings(): KanjiSettings
    fun saveSelectedGrade(selectedGrade: Int)
    fun saveQuestionCount(questionCount: Int)
    fun saveReadingSecondsPerQuestion(readingSecondsPerQuestion: Int)
    fun saveParentPasswordHash(salt: String, hash: String)
    fun saveYoutubeMinutesPer100Correct(minutes: Int)
    fun saveYoutubeRewardUsedSeconds(seconds: Int)
    fun saveYoutubeRewardStartedAtMillis(startedAtMillis: Long)
    fun saveYoutubeInAppEnabled(enabled: Boolean)
    fun addSolvedQuestions(grade: Int, solvedCount: Int)
    fun saveEnabledGrades(enabledGrades: Set<Int>)
    fun saveYoutubeWifiSettings(ssid: String, password: String)
    fun deleteYoutubeWifiSettings(ssid: String)
    fun saveYoutubePlaybackProgress(url: String, seconds: Int)
}

interface KanjiHistoryStore {
    fun loadHistory(): List<KanjiHistoryEntry>
    fun saveEntry(entry: KanjiHistoryEntry)
    fun deleteEntry(entryId: String)
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
        saveHistory(updatedHistory)
    }

    override fun deleteEntry(entryId: String) {
        val updatedHistory = loadHistory().filterNot { it.id == entryId }
        saveHistory(updatedHistory)
    }

    private fun saveHistory(historyEntries: List<KanjiHistoryEntry>) {
        val jsonArray = JSONArray()
        historyEntries.forEach { jsonArray.put(it.toJson()) }
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
                        attemptNumber = json.optInt("attemptNumber", 1).coerceAtLeast(1),
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
                        isUnrecoverable = json.optBoolean("isUnrecoverable"),
                        correctAnswerIndex = json.optInt("correctAnswerIndex", -1),
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
                        .put("attemptNumber", review.attemptNumber)
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
                        .put("isCorrect", review.isCorrect)
                        .put("isUnrecoverable", review.isUnrecoverable)
                        .put("correctAnswerIndex", review.correctAnswerIndex),
                )
            }
        }

    private fun JSONArray?.toWritingReviews(): List<KanjiWritingReview> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                val writtenAnswer = json.optString("writtenAnswer")
                val writtenStrokeGroups = json.optJSONArray("writtenStrokeGroups").toStrokeGroups()
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
                        writtenAnswer = writtenAnswer,
                        correctAnswer = json.optString("correctAnswer"),
                        writtenStrokeGroups = writtenStrokeGroups,
                        isSkipped = json.optBoolean(
                            "isSkipped",
                            writtenAnswer.isBlank() && writtenStrokeGroups.isEmpty(),
                        ),
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
                        .put("correctAnswer", review.correctAnswer)
                        .put("writtenStrokeGroups", review.writtenStrokeGroups.toStrokeGroupsJsonArray())
                        .put("isSkipped", review.isSkipped),
                )
            }
        }

    private fun JSONArray?.toStrokeGroups(): List<List<DrawnStroke>> {
        if (this == null) return emptyList()
        return buildList {
            for (groupIndex in 0 until length()) {
                val strokeGroupJson = optJSONArray(groupIndex) ?: continue
                add(strokeGroupJson.toStrokes())
            }
        }
    }

    private fun JSONArray.toStrokes(): List<DrawnStroke> =
        buildList {
            for (strokeIndex in 0 until length()) {
                val strokeJson = optJSONArray(strokeIndex) ?: continue
                add(DrawnStroke(points = strokeJson.toInkPoints()))
            }
        }

    private fun JSONArray.toInkPoints(): List<InkPoint> =
        buildList {
            for (pointIndex in 0 until length()) {
                val pointJson = optJSONObject(pointIndex) ?: continue
                add(
                    InkPoint(
                        x = pointJson.optDouble("x").toFloat(),
                        y = pointJson.optDouble("y").toFloat(),
                        timestampMillis = pointJson.optLong("timestampMillis"),
                    ),
                )
            }
        }

    private fun List<List<DrawnStroke>>.toStrokeGroupsJsonArray(): JSONArray =
        JSONArray().also { strokeGroupsJson ->
            forEach { strokes ->
                strokeGroupsJson.put(strokes.toStrokesJsonArray())
            }
        }

    private fun List<DrawnStroke>.toStrokesJsonArray(): JSONArray =
        JSONArray().also { strokesJson ->
            forEach { stroke ->
                strokesJson.put(stroke.points.toInkPointsJsonArray())
            }
        }

    private fun List<InkPoint>.toInkPointsJsonArray(): JSONArray =
        JSONArray().also { pointsJson ->
            forEach { point ->
                pointsJson.put(
                    JSONObject()
                        .put("x", point.x)
                        .put("y", point.y)
                        .put("timestampMillis", point.timestampMillis),
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
            parentPasswordSalt = sharedPreferences.getString(KEY_PARENT_PASSWORD_SALT, null).orEmpty(),
            parentPasswordHash = sharedPreferences.getString(KEY_PARENT_PASSWORD_HASH, null).orEmpty(),
            youtubeMinutesPer100Correct = sharedPreferences
                .getInt(KEY_YOUTUBE_MINUTES_PER_100_CORRECT, 30)
                .coerceIn(0, 120),
            youtubeRewardUsedSeconds = sharedPreferences.getInt(KEY_YOUTUBE_REWARD_USED_SECONDS, 0),
            youtubeRewardStartedAtMillis = sharedPreferences.getLong(KEY_YOUTUBE_REWARD_STARTED_AT_MILLIS, 0L),
            isYoutubeInAppEnabled = sharedPreferences.getBoolean(KEY_YOUTUBE_IN_APP_ENABLED, true),
            gradeSolvedCounts = (1..6).associateWith { grade ->
                sharedPreferences.getInt(gradeSolvedCountKey(grade), 0).coerceAtLeast(0)
            },
            enabledGrades = sharedPreferences
                .getString(KEY_ENABLED_GRADES, null)
                .toEnabledGrades(),
            youtubeWifiSsid = sharedPreferences.getString(KEY_YOUTUBE_WIFI_SSID, null).orEmpty(),
            youtubeWifiPassword = sharedPreferences.getString(KEY_YOUTUBE_WIFI_PASSWORD, null).orEmpty(),
            youtubeWifiNetworks = loadYoutubeWifiNetworks(),
            youtubeLastPlaybackUrl = sharedPreferences.getString(KEY_YOUTUBE_LAST_PLAYBACK_URL, null).orEmpty(),
            youtubeLastPlaybackSeconds = sharedPreferences
                .getInt(KEY_YOUTUBE_LAST_PLAYBACK_SECONDS, 0)
                .coerceAtLeast(0),
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

    override fun saveParentPasswordHash(salt: String, hash: String) {
        sharedPreferences.edit()
            .putString(KEY_PARENT_PASSWORD_SALT, salt)
            .putString(KEY_PARENT_PASSWORD_HASH, hash)
            .apply()
    }

    override fun saveYoutubeMinutesPer100Correct(minutes: Int) {
        sharedPreferences.edit()
            .putInt(KEY_YOUTUBE_MINUTES_PER_100_CORRECT, minutes.coerceIn(0, 120))
            .apply()
    }

    override fun saveYoutubeRewardUsedSeconds(seconds: Int) {
        sharedPreferences.edit()
            .putInt(KEY_YOUTUBE_REWARD_USED_SECONDS, seconds)
            .apply()
    }

    override fun saveYoutubeRewardStartedAtMillis(startedAtMillis: Long) {
        sharedPreferences.edit()
            .putLong(KEY_YOUTUBE_REWARD_STARTED_AT_MILLIS, startedAtMillis.coerceAtLeast(0L))
            .apply()
    }

    override fun saveYoutubeInAppEnabled(enabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean(KEY_YOUTUBE_IN_APP_ENABLED, enabled)
            .apply()
    }

    override fun addSolvedQuestions(grade: Int, solvedCount: Int) {
        if (solvedCount <= 0) return

        val safeGrade = grade.coerceIn(1, 6)
        val key = gradeSolvedCountKey(safeGrade)
        sharedPreferences.edit()
            .putInt(
                key,
                sharedPreferences.getInt(key, 0).coerceAtLeast(0) + solvedCount,
            )
            .apply()
    }

    override fun saveEnabledGrades(enabledGrades: Set<Int>) {
        sharedPreferences.edit()
            .putString(KEY_ENABLED_GRADES, enabledGrades.sanitizedEnabledGrades().joinToString(","))
            .apply()
    }

    override fun saveYoutubeWifiSettings(ssid: String, password: String) {
        val safeSsid = ssid.trim()
        if (safeSsid.isBlank()) return

        val updatedNetworks = (listOf(YoutubeWifiNetwork(safeSsid, password)) + loadYoutubeWifiNetworks())
            .distinctBy { it.ssid }
            .take(MAX_YOUTUBE_WIFI_NETWORK_COUNT)
        sharedPreferences.edit()
            .putString(KEY_YOUTUBE_WIFI_SSID, safeSsid)
            .putString(KEY_YOUTUBE_WIFI_PASSWORD, password)
            .putString(KEY_YOUTUBE_WIFI_NETWORKS, updatedNetworks.toYoutubeWifiNetworksJsonArray().toString())
            .apply()
    }

    override fun deleteYoutubeWifiSettings(ssid: String) {
        val safeSsid = ssid.trim()
        if (safeSsid.isBlank()) return

        val updatedNetworks = loadYoutubeWifiNetworks().filterNot { it.ssid == safeSsid }
        val selectedNetwork = sharedPreferences.getString(KEY_YOUTUBE_WIFI_SSID, null).orEmpty()
        val fallbackNetwork = updatedNetworks.firstOrNull()
        sharedPreferences.edit()
            .putString(KEY_YOUTUBE_WIFI_NETWORKS, updatedNetworks.toYoutubeWifiNetworksJsonArray().toString())
            .putString(
                KEY_YOUTUBE_WIFI_SSID,
                if (selectedNetwork == safeSsid) fallbackNetwork?.ssid.orEmpty() else selectedNetwork,
            )
            .putString(
                KEY_YOUTUBE_WIFI_PASSWORD,
                if (selectedNetwork == safeSsid) fallbackNetwork?.password.orEmpty() else {
                    sharedPreferences.getString(KEY_YOUTUBE_WIFI_PASSWORD, null).orEmpty()
                },
            )
            .apply()
    }

    override fun saveYoutubePlaybackProgress(url: String, seconds: Int) {
        sharedPreferences.edit()
            .putString(KEY_YOUTUBE_LAST_PLAYBACK_URL, url.trim())
            .putInt(KEY_YOUTUBE_LAST_PLAYBACK_SECONDS, seconds.coerceAtLeast(0))
            .apply()
    }

    private companion object {
        const val KEY_SELECTED_GRADE = "selected_grade"
        const val KEY_QUESTION_COUNT = "question_count"
        const val KEY_READING_SECONDS_PER_QUESTION = "reading_seconds_per_question"
        const val KEY_PARENT_PASSWORD_SALT = "parent_password_salt"
        const val KEY_PARENT_PASSWORD_HASH = "parent_password_hash"
        const val KEY_YOUTUBE_MINUTES_PER_100_CORRECT = "youtube_minutes_per_100_correct"
        const val KEY_YOUTUBE_REWARD_USED_SECONDS = "youtube_reward_used_seconds"
        const val KEY_YOUTUBE_REWARD_STARTED_AT_MILLIS = "youtube_reward_started_at_millis"
        const val KEY_YOUTUBE_IN_APP_ENABLED = "youtube_in_app_enabled"
        const val KEY_ENABLED_GRADES = "enabled_grades"
        const val KEY_YOUTUBE_WIFI_SSID = "youtube_wifi_ssid"
        const val KEY_YOUTUBE_WIFI_PASSWORD = "youtube_wifi_password"
        const val KEY_YOUTUBE_WIFI_NETWORKS = "youtube_wifi_networks"
        const val KEY_YOUTUBE_LAST_PLAYBACK_URL = "youtube_last_playback_url"
        const val KEY_YOUTUBE_LAST_PLAYBACK_SECONDS = "youtube_last_playback_seconds"
        const val MAX_YOUTUBE_WIFI_NETWORK_COUNT = 20

        fun gradeSolvedCountKey(grade: Int): String = "grade_${grade}_solved_count"
    }

    private fun loadYoutubeWifiNetworks(): List<YoutubeWifiNetwork> {
        val rawNetworks = sharedPreferences.getString(KEY_YOUTUBE_WIFI_NETWORKS, null).orEmpty()
        val savedNetworks = rawNetworks.toYoutubeWifiNetworks()
        val selectedSsid = sharedPreferences.getString(KEY_YOUTUBE_WIFI_SSID, null).orEmpty().trim()
        val selectedPassword = sharedPreferences.getString(KEY_YOUTUBE_WIFI_PASSWORD, null).orEmpty()
        val selectedNetwork = selectedSsid
            .takeIf { it.isNotBlank() }
            ?.let { YoutubeWifiNetwork(it, selectedPassword) }

        return (listOfNotNull(selectedNetwork) + savedNetworks)
            .distinctBy { it.ssid }
            .take(MAX_YOUTUBE_WIFI_NETWORK_COUNT)
    }
}

class FileBackedKanjiSettingsStore(
    private val delegate: KanjiSettingsStore,
    private val settingsFile: File,
) : KanjiSettingsStore {
    override fun loadSettings(): KanjiSettings {
        val fileSettings = settingsFile.readKanjiSettingsOrNull()
        if (fileSettings != null) return fileSettings

        return delegate.loadSettings().also(::saveSettingsFile)
    }

    override fun saveSelectedGrade(selectedGrade: Int) {
        delegate.saveSelectedGrade(selectedGrade)
        syncSettingsFile()
    }

    override fun saveQuestionCount(questionCount: Int) {
        delegate.saveQuestionCount(questionCount)
        syncSettingsFile()
    }

    override fun saveReadingSecondsPerQuestion(readingSecondsPerQuestion: Int) {
        delegate.saveReadingSecondsPerQuestion(readingSecondsPerQuestion)
        syncSettingsFile()
    }

    override fun saveParentPasswordHash(salt: String, hash: String) {
        delegate.saveParentPasswordHash(salt, hash)
        syncSettingsFile()
    }

    override fun saveYoutubeMinutesPer100Correct(minutes: Int) {
        delegate.saveYoutubeMinutesPer100Correct(minutes)
        syncSettingsFile()
    }

    override fun saveYoutubeRewardUsedSeconds(seconds: Int) {
        delegate.saveYoutubeRewardUsedSeconds(seconds)
        syncSettingsFile()
    }

    override fun saveYoutubeRewardStartedAtMillis(startedAtMillis: Long) {
        delegate.saveYoutubeRewardStartedAtMillis(startedAtMillis)
        syncSettingsFile()
    }

    override fun saveYoutubeInAppEnabled(enabled: Boolean) {
        delegate.saveYoutubeInAppEnabled(enabled)
        syncSettingsFile()
    }

    override fun addSolvedQuestions(grade: Int, solvedCount: Int) {
        delegate.addSolvedQuestions(grade, solvedCount)
        syncSettingsFile()
    }

    override fun saveEnabledGrades(enabledGrades: Set<Int>) {
        delegate.saveEnabledGrades(enabledGrades)
        syncSettingsFile()
    }

    override fun saveYoutubeWifiSettings(ssid: String, password: String) {
        delegate.saveYoutubeWifiSettings(ssid, password)
        syncSettingsFile()
    }

    override fun deleteYoutubeWifiSettings(ssid: String) {
        delegate.deleteYoutubeWifiSettings(ssid)
        syncSettingsFile()
    }

    override fun saveYoutubePlaybackProgress(url: String, seconds: Int) {
        delegate.saveYoutubePlaybackProgress(url, seconds)
        syncSettingsFile()
    }

    private fun syncSettingsFile() {
        saveSettingsFile(delegate.loadSettings())
    }

    private fun saveSettingsFile(settings: KanjiSettings) {
        runCatching {
            settingsFile.parentFile?.mkdirs()
            settingsFile.writeText(settings.toJson().toString())
        }
    }
}

private fun File.readKanjiSettingsOrNull(): KanjiSettings? =
    runCatching {
        if (!exists()) return@runCatching null
        JSONObject(readText()).toKanjiSettings()
    }.getOrNull()

private fun KanjiSettings.toJson(): JSONObject =
    JSONObject()
        .put("selectedGrade", selectedGrade)
        .put("questionCount", questionCount)
        .put("readingSecondsPerQuestion", readingSecondsPerQuestion)
        .put("parentPasswordSalt", parentPasswordSalt)
        .put("parentPasswordHash", parentPasswordHash)
        .put("youtubeMinutesPer100Correct", youtubeMinutesPer100Correct)
        .put("youtubeRewardUsedSeconds", youtubeRewardUsedSeconds)
        .put("youtubeRewardStartedAtMillis", youtubeRewardStartedAtMillis)
        .put("isYoutubeInAppEnabled", isYoutubeInAppEnabled)
        .put("gradeSolvedCounts", gradeSolvedCounts.toGradeSolvedCountsJson())
        .put("enabledGrades", enabledGrades.sanitizedEnabledGrades().toIntJsonArray())
        .put("youtubeWifiSsid", youtubeWifiSsid)
        .put("youtubeWifiPassword", youtubeWifiPassword)
        .put("youtubeWifiNetworks", youtubeWifiNetworks.toYoutubeWifiNetworksJsonArray())
        .put("youtubeLastPlaybackUrl", youtubeLastPlaybackUrl)
        .put("youtubeLastPlaybackSeconds", youtubeLastPlaybackSeconds)

private fun JSONObject.toKanjiSettings(): KanjiSettings =
    KanjiSettings(
        selectedGrade = optInt("selectedGrade", 1).coerceIn(1, 6),
        questionCount = optInt("questionCount", 10).coerceIn(5, 100),
        readingSecondsPerQuestion = optInt("readingSecondsPerQuestion", 10).toReadingTimerSeconds(),
        parentPasswordSalt = optString("parentPasswordSalt").orEmpty(),
        parentPasswordHash = optString("parentPasswordHash").orEmpty(),
        youtubeMinutesPer100Correct = optInt("youtubeMinutesPer100Correct", 30).coerceIn(0, 120),
        youtubeRewardUsedSeconds = optInt("youtubeRewardUsedSeconds", 0),
        youtubeRewardStartedAtMillis = optLong("youtubeRewardStartedAtMillis", 0L).coerceAtLeast(0L),
        isYoutubeInAppEnabled = optBoolean("isYoutubeInAppEnabled", true),
        gradeSolvedCounts = optJSONObject("gradeSolvedCounts").toGradeSolvedCounts(),
        enabledGrades = optJSONArray("enabledGrades").toEnabledGradesSet(),
        youtubeWifiSsid = optString("youtubeWifiSsid").orEmpty(),
        youtubeWifiPassword = optString("youtubeWifiPassword").orEmpty(),
        youtubeWifiNetworks = optJSONArray("youtubeWifiNetworks")
            .toYoutubeWifiNetworks(
                selectedSsid = optString("youtubeWifiSsid").orEmpty(),
                selectedPassword = optString("youtubeWifiPassword").orEmpty(),
            ),
        youtubeLastPlaybackUrl = optString("youtubeLastPlaybackUrl").orEmpty(),
        youtubeLastPlaybackSeconds = optInt("youtubeLastPlaybackSeconds", 0).coerceAtLeast(0),
    )

private fun Map<Int, Int>.toGradeSolvedCountsJson(): JSONObject =
    JSONObject().also { json ->
        (1..6).forEach { grade ->
            json.put(grade.toString(), (this[grade] ?: 0).coerceAtLeast(0))
        }
    }

private fun JSONObject?.toGradeSolvedCounts(): Map<Int, Int> =
    (1..6).associateWith { grade ->
        this?.optInt(grade.toString(), 0)?.coerceAtLeast(0) ?: 0
    }

private fun Set<Int>.toIntJsonArray(): JSONArray =
    JSONArray().also { jsonArray ->
        forEach { jsonArray.put(it) }
    }

private fun JSONArray?.toEnabledGradesSet(): Set<Int> {
    if (this == null) return (1..6).toSet()
    return buildSet {
        for (index in 0 until length()) {
            add(optInt(index))
        }
    }.sanitizedEnabledGrades()
}

private fun String.toYoutubeWifiNetworks(): List<YoutubeWifiNetwork> =
    runCatching {
        if (isBlank()) return@runCatching emptyList()
        JSONArray(this).toYoutubeWifiNetworks()
    }.getOrDefault(emptyList())

private fun JSONArray?.toYoutubeWifiNetworks(
    selectedSsid: String = "",
    selectedPassword: String = "",
): List<YoutubeWifiNetwork> {
    val selectedNetwork = selectedSsid.trim()
        .takeIf { it.isNotBlank() }
        ?.let { YoutubeWifiNetwork(it, selectedPassword) }
    if (this == null) return listOfNotNull(selectedNetwork)

    val savedNetworks = buildList {
        for (index in 0 until length()) {
            val json = optJSONObject(index) ?: continue
            val ssid = json.optString("ssid").trim()
            if (ssid.isBlank()) continue
            add(
                YoutubeWifiNetwork(
                    ssid = ssid,
                    password = json.optString("password"),
                ),
            )
        }
    }

    return (listOfNotNull(selectedNetwork) + savedNetworks).distinctBy { it.ssid }
}

private fun List<YoutubeWifiNetwork>.toYoutubeWifiNetworksJsonArray(): JSONArray =
    JSONArray().also { jsonArray ->
        forEach { network ->
            val ssid = network.ssid.trim()
            if (ssid.isNotBlank()) {
                jsonArray.put(
                    JSONObject()
                        .put("ssid", ssid)
                        .put("password", network.password),
                )
            }
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

    override fun saveParentPasswordHash(salt: String, hash: String) {
        settings = settings.copy(
            parentPasswordSalt = salt,
            parentPasswordHash = hash,
        )
    }

    override fun saveYoutubeMinutesPer100Correct(minutes: Int) {
        settings = settings.copy(youtubeMinutesPer100Correct = minutes.coerceIn(0, 120))
    }

    override fun saveYoutubeRewardUsedSeconds(seconds: Int) {
        settings = settings.copy(youtubeRewardUsedSeconds = seconds)
    }

    override fun saveYoutubeRewardStartedAtMillis(startedAtMillis: Long) {
        settings = settings.copy(youtubeRewardStartedAtMillis = startedAtMillis.coerceAtLeast(0L))
    }

    override fun saveYoutubeInAppEnabled(enabled: Boolean) {
        settings = settings.copy(isYoutubeInAppEnabled = enabled)
    }

    override fun addSolvedQuestions(grade: Int, solvedCount: Int) {
        if (solvedCount <= 0) return

        val safeGrade = grade.coerceIn(1, 6)
        val updatedCounts = settings.gradeSolvedCounts.toMutableMap()
        updatedCounts[safeGrade] = (updatedCounts[safeGrade] ?: 0) + solvedCount
        settings = settings.copy(gradeSolvedCounts = updatedCounts)
    }

    override fun saveEnabledGrades(enabledGrades: Set<Int>) {
        settings = settings.copy(enabledGrades = enabledGrades.sanitizedEnabledGrades())
    }

    override fun saveYoutubeWifiSettings(ssid: String, password: String) {
        val safeSsid = ssid.trim()
        if (safeSsid.isBlank()) return
        val updatedNetworks = (listOf(YoutubeWifiNetwork(safeSsid, password)) + settings.youtubeWifiNetworks)
            .distinctBy { it.ssid }
            .take(20)
        settings = settings.copy(
            youtubeWifiSsid = safeSsid,
            youtubeWifiPassword = password,
            youtubeWifiNetworks = updatedNetworks,
        )
    }

    override fun deleteYoutubeWifiSettings(ssid: String) {
        val safeSsid = ssid.trim()
        if (safeSsid.isBlank()) return

        val updatedNetworks = settings.youtubeWifiNetworks.filterNot { it.ssid == safeSsid }
        val fallbackNetwork = updatedNetworks.firstOrNull()
        settings = if (settings.youtubeWifiSsid == safeSsid) {
            settings.copy(
                youtubeWifiSsid = fallbackNetwork?.ssid.orEmpty(),
                youtubeWifiPassword = fallbackNetwork?.password.orEmpty(),
                youtubeWifiNetworks = updatedNetworks,
            )
        } else {
            settings.copy(youtubeWifiNetworks = updatedNetworks)
        }
    }

    override fun saveYoutubePlaybackProgress(url: String, seconds: Int) {
        settings = settings.copy(
            youtubeLastPlaybackUrl = url.trim(),
            youtubeLastPlaybackSeconds = seconds.coerceAtLeast(0),
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

    override fun deleteEntry(entryId: String) {
        historyEntries = historyEntries.filterNot { it.id == entryId }
    }
}

private fun Int.toReadingTimerSeconds(): Int {
    val rounded = ((this + 5) / 10) * 10
    return rounded.coerceIn(10, 180)
}

private fun String?.toEnabledGrades(): Set<Int> =
    orEmpty()
        .split(",")
        .mapNotNull { it.toIntOrNull() }
        .toSet()
        .sanitizedEnabledGrades()

private fun Set<Int>.sanitizedEnabledGrades(): Set<Int> {
    val validGrades = filter { it in 1..6 }.toSet()
    return validGrades.ifEmpty { (1..6).toSet() }
}

private fun List<KanjiHistoryEntry>.totalRewardScore(): Int =
    sumOf { entry ->
        entry.readingReviews.count { it.isCorrect } +
            entry.writingReviews.count { it.isCorrectRewardWritingReview() }
    }

private fun KanjiWritingReview.isCorrectRewardWritingReview(): Boolean =
    !isSkipped && writtenAnswer == correctAnswer

private fun youtubeRewardAvailableSeconds(
    historyEntries: List<KanjiHistoryEntry>,
    settings: KanjiSettings,
): Int {
    val earnedSeconds = youtubeRewardEarnedSeconds(historyEntries, settings)
    return (earnedSeconds - settings.youtubeRewardUsedSeconds).coerceAtLeast(0)
}

private fun youtubeRewardEarnedSeconds(
    historyEntries: List<KanjiHistoryEntry>,
    settings: KanjiSettings,
): Int =
    historyEntries.totalRewardScore() * settings.youtubeMinutesPer100Correct * 60 / 100

private const val MAX_YOUTUBE_REWARD_SECONDS = 2 * 60 * 60

private const val MIN_PARENT_PASSWORD_LENGTH = 4
private const val DEFAULT_PARENT_PASSWORD = ""

private data class ParentPasswordHash(
    val salt: String,
    val hash: String,
) {
    companion object {
        fun create(password: String): ParentPasswordHash {
            val saltBytes = ByteArray(16)
            SecureRandom().nextBytes(saltBytes)
            val salt = saltBytes.toBase64()
            return ParentPasswordHash(
                salt = salt,
                hash = hashParentPassword(password, salt),
            )
        }
    }
}

private fun verifyParentPassword(password: String, settings: KanjiSettings): Boolean {
    if (settings.parentPasswordSalt.isBlank() || settings.parentPasswordHash.isBlank()) {
        return password == DEFAULT_PARENT_PASSWORD
    }
    return hashParentPassword(password, settings.parentPasswordSalt) == settings.parentPasswordHash
}

private fun hashParentPassword(password: String, salt: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest("$salt:$password".toByteArray(Charsets.UTF_8))
    return bytes.toBase64()
}

private fun ByteArray.toBase64(): String =
    Base64.encodeToString(this, Base64.NO_WRAP)

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
