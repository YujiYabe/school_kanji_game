package com.example.schoolkanjigame.kanji

import androidx.lifecycle.ViewModel
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
    val currentWritingCharIndex: Int = 0,
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
    ): String
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
    ): String {
        val ink = strokes.toInk()
        val context = RecognitionContext.builder()
            .setPreContext(preContext.takeLast(20))
            .setWritingArea(WritingArea(writingAreaWidth, writingAreaHeight))
            .build()

        val result: RecognitionResult = recognizer.recognize(ink, context).await()
        return result.candidates.firstOrNull()?.text.orEmpty()
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
) : ViewModel() {
    constructor() : this(
        recognizerClient = JapaneseMlKitDigitalInkRecognizerClient(),
        questionBank = pdfKanjiQuestions,
    )

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
        val safeCount = count.coerceIn(1, 50)
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
                currentWritingCharIndex = 0,
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
            }.onSuccess { recognizedText ->
                handleWritingRecognitionSuccess(recognizedText)
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
        val review = KanjiAnswerReview(
            questionId = question.id,
            questionNumber = state.currentQuestionIndex + 1,
            sentence = question.fullSentence,
            selectedAnswer = selectedAnswer,
            correctAnswer = correctAnswer,
            isCorrect = wasCorrect,
        )
        val nextIndex = state.currentQuestionIndex + 1
        val finished = nextIndex >= state.questions.size
        val updatedReviews = state.readingReviews + review
        val hasWrongAnswers = updatedReviews.any { !it.isCorrect }
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

    private fun handleWritingRecognitionSuccess(recognizedText: String) {
        val state = uiState.value
        val expected = state.currentWritingTargetChar
        val recognizedFirstChar = recognizedText.trim().firstOrNull()?.toString().orEmpty()
        val isCorrect = recognizedFirstChar == expected

        if (!isCorrect) {
            _uiState.update {
                it.copy(recognitionState = RecognitionState.Success(recognizedText, isCorrect = false))
            }
            return
        }

        val answerLength = state.currentQuestion?.writingAnswer?.length ?: 0
        val nextCharIndex = state.currentWritingCharIndex + 1
        if (nextCharIndex < answerLength) {
            _uiState.update {
                it.copy(
                    currentWritingCharIndex = nextCharIndex,
                    strokes = emptyList(),
                    recognitionState = RecognitionState.Success(recognizedText, isCorrect = true),
                )
            }
            return
        }

        val nextQuestionIndex = state.currentQuestionIndex + 1
        val finished = nextQuestionIndex >= state.questions.size
        _uiState.update {
            it.copy(
                currentQuestionIndex = nextQuestionIndex.coerceAtMost(it.questions.size),
                currentWritingCharIndex = 0,
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
                currentWritingCharIndex = 0,
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

        val safeCount = count.coerceIn(1, 50)
        return buildList {
            while (size < safeCount) {
                val remaining = safeCount - size
                addAll(pool.shuffled().take(remaining))
            }
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

private data class KanjiSeed(val kanji: String, val reading: String)

val sampleKanjiQuestions: List<KanjiQuestion> by lazy { buildKanjiQuestionsFromSeeds() }

private fun buildKanjiQuestionsFromSeeds(): List<KanjiQuestion> =
    gradeKanjiSeeds.flatMap { (grade, seeds) ->
        seeds.mapIndexed { index, seed ->
            val distractors = seeds
                .asSequence()
                .drop(index + 1)
                .plus(seeds.asSequence().take(index))
                .map { it.reading }
                .filter { it != seed.reading }
                .distinct()
                .take(3)
                .toList()
            KanjiQuestion(
                id = "g${grade}_${index}_${seed.kanji}",
                grade = grade,
                fullSentence = seed.toReadingSentence(),
                targetText = seed.kanji,
                readingAnswers = (listOf(seed.reading) + distractors).take(4),
                writingAnswer = seed.kanji,
            )
        }
    }

private fun KanjiSeed.toReadingSentence(): String =
    sentenceByKanji[kanji] ?: "文の中にある${kanji}を声に出して読む。"

private val sentenceByKanji: Map<String, String> = mapOf(
    "一" to "一つずつていねいに数える。",
    "右" to "道の右がわを歩く。",
    "雨" to "朝から雨がふっている。",
    "円" to "百円玉をさいふに入れる。",
    "王" to "王さまが国を守る。",
    "音" to "きれいな音が聞こえる。",
    "下" to "机の下をそうじする。",
    "火" to "火を使うときは気をつける。",
    "花" to "庭に赤い花がさいた。",
    "貝" to "海で白い貝をひろう。",
    "学" to "学校で新しいことを学ぶ。",
    "気" to "外の空気をすう。",
    "休" to "昼休みに本を読む。",
    "玉" to "赤い玉を箱に入れる。",
    "金" to "金よう日に友だちと会う。",
    "九" to "九つの石をならべる。",
    "空" to "青い空を見上げる。",
    "月" to "夜の空に月が出る。",
    "犬" to "犬と公園を走る。",
    "見" to "遠くの山を見る。",
    "五" to "五人で楽しく遊ぶ。",
    "口" to "口を大きく開けて歌う。",
    "校" to "校門の前で先生に会う。",
    "左" to "左手で紙をおさえる。",
    "三" to "三つの花をかざる。",
    "山" to "山の上にのぼる。",
    "四" to "四人で給食を食べる。",
    "子" to "子どもたちが走っている。",
    "糸" to "赤い糸でぬう。",
    "字" to "大きな字を書く。",
    "耳" to "耳をすませて音を聞く。",
    "七" to "七つの星を見つける。",
    "車" to "車に気をつけて道をわたる。",
    "手" to "手を洗ってから食べる。",
    "十" to "十まで声に出して数える。",
    "出" to "家を出て学校へ行く。",
    "女" to "女の子が本を読む。",
    "小" to "小さな声で話す。",
    "上" to "つくえの上に本を置く。",
    "森" to "森の中を歩く。",
    "人" to "たくさんの人が集まる。",
    "水" to "水をコップに入れる。",
    "正" to "正しい答えを選ぶ。",
    "生" to "生き物を大切にする。",
    "青" to "青いえんぴつを使う。",
    "石" to "丸い石をひろう。",
    "赤" to "赤いぼうしをかぶる。",
    "先" to "先生の話を聞く。",
    "千" to "千羽づるを作る。",
    "川" to "川の水がきらきら光る。",
    "早" to "早く学校へ行く。",
    "草" to "草の上にすわる。",
    "足" to "足あとが砂に残る。",
    "村" to "小さな村に住む。",
    "大" to "大きな声で返事をする。",
    "男" to "男の子が手を上げる。",
    "竹" to "竹でできたものさしを使う。",
    "中" to "箱の中をのぞく。",
    "虫" to "虫の声を聞く。",
    "町" to "町の図書館へ行く。",
    "天" to "天気のよい日に外で遊ぶ。",
    "田" to "田んぼに水が入る。",
    "土" to "土をほって種をまく。",
    "二" to "二つのりんごを分ける。",
    "日" to "日よう日に公園へ行く。",
    "入" to "教室に入る。",
    "年" to "新しい年が始まる。",
    "白" to "白い紙に絵をかく。",
    "八" to "八つのカードをならべる。",
    "百" to "百まで数を数える。",
    "文" to "短い文をノートに書く。",
    "本" to "本を声に出して読む。",
    "名" to "自分の名まえを書く。",
    "木" to "大きな木の下で休む。",
    "目" to "目を大切にする。",
    "夕" to "夕方に家へ帰る。",
    "立" to "いすから立ち上がる。",
    "力" to "力を合わせて運ぶ。",
    "林" to "林の道を歩く。",
    "六" to "六人でならんで歩く。",
    "新" to "新しい本を読む。",
    "海" to "海の近くに行く。",
    "読" to "図書室で本を読んだ。",
    "考" to "答えをよく考える。",
    "星" to "夜に星を見る。",
    "漢" to "漢字をていねいに書く。",
    "橋" to "橋をわたって学校へ行く。",
    "港" to "港に船がとまっている。",
    "湖" to "湖の水が光っている。",
    "駅" to "駅で友だちを待つ。",
    "愛" to "家族を愛する気持ちを持つ。",
    "旗" to "運動場に旗が立っている。",
    "冷" to "冷たい水を飲む。",
    "輪" to "自転車の輪を調べる。",
    "菜" to "畑で菜の花を見る。",
    "桜" to "春に桜がさく。",
    "銅" to "銅でできた道具を観察する。",
    "貿" to "外国と貿易をする。",
    "輸" to "荷物を輸送する。",
    "複" to "複雑な形をよく見る。",
    "宙" to "宇宙について調べる。",
    "難" to "難しい問題に挑戦する。",
    "窓" to "窓を開けて空気を入れる。",
    "尊" to "相手を尊重して話す。",
    "糖" to "料理に砂糖を入れる。",
)

private val gradeKanjiSeeds: Map<Int, List<KanjiSeed>> = mapOf(
    1 to listOf(
        KanjiSeed("一", "ひと"), KanjiSeed("右", "みぎ"), KanjiSeed("雨", "あめ"), KanjiSeed("円", "まるい"),
        KanjiSeed("王", "おう"), KanjiSeed("音", "おと"), KanjiSeed("下", "した"), KanjiSeed("火", "ひ"),
        KanjiSeed("花", "はな"), KanjiSeed("貝", "かい"), KanjiSeed("学", "まなぶ"), KanjiSeed("気", "いき"),
        KanjiSeed("休", "やすむ"), KanjiSeed("玉", "たま"), KanjiSeed("金", "かね"), KanjiSeed("九", "ここの"),
        KanjiSeed("空", "そら"), KanjiSeed("月", "つき"), KanjiSeed("犬", "いぬ"), KanjiSeed("見", "みる"),
        KanjiSeed("五", "いつ"), KanjiSeed("口", "くち"), KanjiSeed("校", "こう"), KanjiSeed("左", "ひだり"),
        KanjiSeed("三", "み"), KanjiSeed("山", "やま"), KanjiSeed("四", "よ"), KanjiSeed("子", "こ"),
        KanjiSeed("糸", "いと"), KanjiSeed("字", "あざ"), KanjiSeed("耳", "みみ"), KanjiSeed("七", "なな"),
        KanjiSeed("車", "くるま"), KanjiSeed("手", "て"), KanjiSeed("十", "とお"), KanjiSeed("出", "でる"),
        KanjiSeed("女", "おんな"), KanjiSeed("小", "ちいさい"), KanjiSeed("上", "うえ"), KanjiSeed("森", "もり"),
        KanjiSeed("人", "ひと"), KanjiSeed("水", "みず"), KanjiSeed("正", "ただしい"), KanjiSeed("生", "いきる"),
        KanjiSeed("青", "あお"), KanjiSeed("石", "いし"), KanjiSeed("赤", "あか"), KanjiSeed("先", "さき"),
        KanjiSeed("千", "ち"), KanjiSeed("川", "かわ"), KanjiSeed("早", "はやい"), KanjiSeed("草", "くさ"),
        KanjiSeed("足", "あし"), KanjiSeed("村", "むら"), KanjiSeed("大", "おお"), KanjiSeed("男", "おとこ"),
        KanjiSeed("竹", "たけ"), KanjiSeed("中", "なか"), KanjiSeed("虫", "むし"), KanjiSeed("町", "まち"),
        KanjiSeed("天", "あまつ"), KanjiSeed("田", "た"), KanjiSeed("土", "つち"), KanjiSeed("二", "ふた"),
        KanjiSeed("日", "ひ"), KanjiSeed("入", "いる"), KanjiSeed("年", "とし"), KanjiSeed("白", "しろ"),
        KanjiSeed("八", "や"), KanjiSeed("百", "もも"), KanjiSeed("文", "ふみ"), KanjiSeed("本", "もと"),
        KanjiSeed("名", "な"), KanjiSeed("木", "き"), KanjiSeed("目", "め"), KanjiSeed("夕", "ゆう"),
        KanjiSeed("立", "たつ"), KanjiSeed("力", "ちから"), KanjiSeed("林", "はやし"), KanjiSeed("六", "む"),
    ),
    2 to listOf(
        KanjiSeed("引", "ひく"), KanjiSeed("羽", "は"), KanjiSeed("雲", "くも"), KanjiSeed("園", "その"),
        KanjiSeed("遠", "とおい"), KanjiSeed("黄", "き"), KanjiSeed("何", "なに"), KanjiSeed("夏", "なつ"),
        KanjiSeed("家", "いえ"), KanjiSeed("科", "か"), KanjiSeed("歌", "うた"), KanjiSeed("画", "えがく"),
        KanjiSeed("会", "あう"), KanjiSeed("回", "まわる"), KanjiSeed("海", "うみ"), KanjiSeed("絵", "かい"),
        KanjiSeed("外", "そと"), KanjiSeed("角", "かど"), KanjiSeed("楽", "たのしい"), KanjiSeed("活", "いきる"),
        KanjiSeed("間", "あいだ"), KanjiSeed("丸", "まる"), KanjiSeed("岩", "いわ"), KanjiSeed("顔", "かお"),
        KanjiSeed("帰", "かえる"), KanjiSeed("汽", "き"), KanjiSeed("記", "しるす"), KanjiSeed("弓", "ゆみ"),
        KanjiSeed("牛", "うし"), KanjiSeed("魚", "うお"), KanjiSeed("京", "みやこ"), KanjiSeed("強", "つよい"),
        KanjiSeed("教", "おしえる"), KanjiSeed("近", "ちかい"), KanjiSeed("兄", "あに"), KanjiSeed("形", "かた"),
        KanjiSeed("計", "はかる"), KanjiSeed("元", "もと"), KanjiSeed("原", "はら"), KanjiSeed("言", "いう"),
        KanjiSeed("古", "ふるい"), KanjiSeed("戸", "と"), KanjiSeed("午", "うま"), KanjiSeed("後", "のち"),
        KanjiSeed("語", "かたる"), KanjiSeed("交", "まじわる"), KanjiSeed("光", "ひかる"), KanjiSeed("公", "おおやけ"),
        KanjiSeed("工", "こう"), KanjiSeed("広", "ひろい"), KanjiSeed("考", "かんがえる"), KanjiSeed("行", "いく"),
        KanjiSeed("高", "たかい"), KanjiSeed("合", "あう"), KanjiSeed("国", "くに"), KanjiSeed("黒", "くろ"),
        KanjiSeed("今", "いま"), KanjiSeed("才", "さい"), KanjiSeed("細", "ほそい"), KanjiSeed("作", "つくる"),
        KanjiSeed("算", "そろ"), KanjiSeed("姉", "あね"), KanjiSeed("市", "いち"), KanjiSeed("思", "おもう"),
        KanjiSeed("止", "とまる"), KanjiSeed("紙", "かみ"), KanjiSeed("寺", "てら"), KanjiSeed("時", "とき"),
        KanjiSeed("自", "みずから"), KanjiSeed("室", "むろ"), KanjiSeed("社", "やしろ"), KanjiSeed("弱", "よわい"),
        KanjiSeed("首", "くび"), KanjiSeed("秋", "あき"), KanjiSeed("週", "しゅう"), KanjiSeed("春", "はる"),
        KanjiSeed("書", "かく"), KanjiSeed("少", "すくない"), KanjiSeed("場", "ば"), KanjiSeed("色", "いろ"),
        KanjiSeed("食", "くう"), KanjiSeed("心", "こころ"), KanjiSeed("新", "あたらしい"), KanjiSeed("親", "おや"),
        KanjiSeed("図", "え"), KanjiSeed("数", "かず"), KanjiSeed("星", "ほし"), KanjiSeed("晴", "はれる"),
        KanjiSeed("声", "こえ"), KanjiSeed("西", "にし"), KanjiSeed("切", "きる"), KanjiSeed("雪", "ゆき"),
        KanjiSeed("線", "すじ"), KanjiSeed("船", "ふね"), KanjiSeed("前", "まえ"), KanjiSeed("組", "くむ"),
        KanjiSeed("走", "はしる"), KanjiSeed("多", "おおい"), KanjiSeed("太", "ふとい"), KanjiSeed("体", "からだ"),
        KanjiSeed("台", "うてな"), KanjiSeed("谷", "たに"), KanjiSeed("知", "しる"), KanjiSeed("地", "ち"),
        KanjiSeed("池", "いけ"), KanjiSeed("茶", "ちゃ"), KanjiSeed("昼", "ひる"), KanjiSeed("朝", "あさ"),
        KanjiSeed("長", "ながい"), KanjiSeed("鳥", "とり"), KanjiSeed("直", "ただちに"), KanjiSeed("通", "とおる"),
        KanjiSeed("弟", "おとうと"), KanjiSeed("店", "みせ"), KanjiSeed("点", "つける"), KanjiSeed("電", "でん"),
        KanjiSeed("冬", "ふゆ"), KanjiSeed("刀", "かたな"), KanjiSeed("東", "ひがし"), KanjiSeed("当", "あたる"),
        KanjiSeed("答", "こたえる"), KanjiSeed("頭", "あたま"), KanjiSeed("同", "おなじ"), KanjiSeed("道", "みち"),
        KanjiSeed("読", "よむ"), KanjiSeed("内", "うち"), KanjiSeed("南", "みなみ"), KanjiSeed("肉", "しし"),
        KanjiSeed("馬", "うま"), KanjiSeed("買", "かう"), KanjiSeed("売", "うる"), KanjiSeed("麦", "むぎ"),
        KanjiSeed("半", "なかば"), KanjiSeed("番", "つがい"), KanjiSeed("父", "ちち"), KanjiSeed("風", "かぜ"),
        KanjiSeed("分", "わける"), KanjiSeed("聞", "きく"), KanjiSeed("米", "こめ"), KanjiSeed("歩", "あるく"),
        KanjiSeed("母", "はは"), KanjiSeed("方", "かた"), KanjiSeed("北", "きた"), KanjiSeed("妹", "いもうと"),
        KanjiSeed("毎", "ごと"), KanjiSeed("万", "よろず"), KanjiSeed("明", "あかり"), KanjiSeed("鳴", "なく"),
        KanjiSeed("毛", "け"), KanjiSeed("門", "かど"), KanjiSeed("夜", "よ"), KanjiSeed("野", "の"),
        KanjiSeed("矢", "や"), KanjiSeed("友", "とも"), KanjiSeed("曜", "よう"), KanjiSeed("用", "もちいる"),
        KanjiSeed("来", "くる"), KanjiSeed("理", "ことわり"), KanjiSeed("里", "さと"), KanjiSeed("話", "はなす"),
    ),
    3 to listOf(
        KanjiSeed("悪", "わるい"), KanjiSeed("安", "やすい"), KanjiSeed("暗", "くらい"), KanjiSeed("委", "ゆだねる"),
        KanjiSeed("意", "い"), KanjiSeed("医", "いやす"), KanjiSeed("育", "そだつ"), KanjiSeed("員", "いん"),
        KanjiSeed("飲", "のむ"), KanjiSeed("院", "いん"), KanjiSeed("運", "はこぶ"), KanjiSeed("泳", "およぐ"),
        KanjiSeed("駅", "えき"), KanjiSeed("央", "おう"), KanjiSeed("横", "よこ"), KanjiSeed("屋", "や"),
        KanjiSeed("温", "あたたか"), KanjiSeed("化", "ばける"), KanjiSeed("荷", "に"), KanjiSeed("界", "かい"),
        KanjiSeed("開", "ひらく"), KanjiSeed("階", "きざはし"), KanjiSeed("寒", "さむい"), KanjiSeed("感", "かん"),
        KanjiSeed("漢", "かん"), KanjiSeed("館", "やかた"), KanjiSeed("岸", "きし"), KanjiSeed("期", "き"),
        KanjiSeed("起", "おきる"), KanjiSeed("客", "きゃく"), KanjiSeed("宮", "みや"), KanjiSeed("急", "いそぐ"),
        KanjiSeed("球", "たま"), KanjiSeed("究", "きわめる"), KanjiSeed("級", "きゅう"), KanjiSeed("去", "さる"),
        KanjiSeed("橋", "はし"), KanjiSeed("業", "わざ"), KanjiSeed("局", "つぼね"), KanjiSeed("曲", "まがる"),
        KanjiSeed("銀", "しろがね"), KanjiSeed("区", "く"), KanjiSeed("苦", "くるしい"), KanjiSeed("具", "そなえる"),
        KanjiSeed("君", "きみ"), KanjiSeed("係", "かかる"), KanjiSeed("軽", "かるい"), KanjiSeed("決", "きめる"),
        KanjiSeed("血", "ち"), KanjiSeed("研", "とぐ"), KanjiSeed("県", "かける"), KanjiSeed("庫", "くら"),
        KanjiSeed("湖", "みずうみ"), KanjiSeed("向", "むく"), KanjiSeed("幸", "さいわい"), KanjiSeed("港", "みなと"),
        KanjiSeed("号", "さけぶ"), KanjiSeed("根", "ね"), KanjiSeed("祭", "まつる"), KanjiSeed("坂", "さか"),
        KanjiSeed("皿", "さら"), KanjiSeed("仕", "つかえる"), KanjiSeed("使", "つかう"), KanjiSeed("始", "はじめる"),
        KanjiSeed("指", "ゆび"), KanjiSeed("死", "しぬ"), KanjiSeed("詩", "うた"), KanjiSeed("歯", "よわい"),
        KanjiSeed("事", "こと"), KanjiSeed("持", "もつ"), KanjiSeed("次", "つぐ"), KanjiSeed("式", "しき"),
        KanjiSeed("実", "み"), KanjiSeed("写", "うつす"), KanjiSeed("者", "もの"), KanjiSeed("主", "ぬし"),
        KanjiSeed("取", "とる"), KanjiSeed("守", "まもる"), KanjiSeed("酒", "さけ"), KanjiSeed("受", "うける"),
        KanjiSeed("州", "す"), KanjiSeed("拾", "ひろう"), KanjiSeed("終", "おわる"), KanjiSeed("習", "ならう"),
        KanjiSeed("集", "あつまる"), KanjiSeed("住", "すむ"), KanjiSeed("重", "え"), KanjiSeed("宿", "やど"),
        KanjiSeed("所", "ところ"), KanjiSeed("暑", "あつい"), KanjiSeed("助", "たすける"), KanjiSeed("勝", "かつ"),
        KanjiSeed("商", "あきなう"), KanjiSeed("昭", "しょう"), KanjiSeed("消", "きえる"), KanjiSeed("章", "しょう"),
        KanjiSeed("乗", "のる"), KanjiSeed("植", "うえる"), KanjiSeed("深", "ふかい"), KanjiSeed("申", "もうす"),
        KanjiSeed("真", "ま"), KanjiSeed("神", "かみ"), KanjiSeed("身", "み"), KanjiSeed("進", "すすむ"),
        KanjiSeed("世", "よ"), KanjiSeed("整", "ととのえる"), KanjiSeed("昔", "むかし"), KanjiSeed("全", "まったく"),
        KanjiSeed("想", "おもう"), KanjiSeed("相", "あい"), KanjiSeed("送", "おくる"), KanjiSeed("息", "いき"),
        KanjiSeed("速", "はやい"), KanjiSeed("族", "ぞく"), KanjiSeed("他", "ほか"), KanjiSeed("打", "うつ"),
        KanjiSeed("対", "あいて"), KanjiSeed("待", "まつ"), KanjiSeed("代", "かわる"), KanjiSeed("第", "だい"),
        KanjiSeed("題", "だい"), KanjiSeed("炭", "すみ"), KanjiSeed("短", "みじかい"), KanjiSeed("談", "だん"),
        KanjiSeed("着", "きる"), KanjiSeed("柱", "はしら"), KanjiSeed("注", "そそぐ"), KanjiSeed("丁", "ひのと"),
        KanjiSeed("帳", "とばり"), KanjiSeed("調", "しらべる"), KanjiSeed("追", "おう"), KanjiSeed("定", "さだめる"),
        KanjiSeed("庭", "にわ"), KanjiSeed("笛", "ふえ"), KanjiSeed("鉄", "くろがね"), KanjiSeed("転", "ころがる"),
        KanjiSeed("登", "のぼる"), KanjiSeed("都", "みやこ"), KanjiSeed("度", "たび"), KanjiSeed("島", "しま"),
        KanjiSeed("投", "なげる"), KanjiSeed("湯", "ゆ"), KanjiSeed("等", "ひとしい"), KanjiSeed("豆", "まめ"),
        KanjiSeed("動", "うごく"), KanjiSeed("童", "わらべ"), KanjiSeed("農", "のう"), KanjiSeed("波", "なみ"),
        KanjiSeed("配", "くばる"), KanjiSeed("倍", "ばい"), KanjiSeed("箱", "はこ"), KanjiSeed("畑", "はた"),
        KanjiSeed("発", "たつ"), KanjiSeed("反", "そる"), KanjiSeed("板", "いた"), KanjiSeed("悲", "かなしい"),
        KanjiSeed("皮", "かわ"), KanjiSeed("美", "うつくしい"), KanjiSeed("鼻", "はな"), KanjiSeed("筆", "ふで"),
        KanjiSeed("氷", "こおり"), KanjiSeed("表", "おもて"), KanjiSeed("病", "やむ"), KanjiSeed("秒", "びょう"),
        KanjiSeed("品", "しな"), KanjiSeed("負", "まける"), KanjiSeed("部", "べ"), KanjiSeed("服", "ふく"),
        KanjiSeed("福", "ふく"), KanjiSeed("物", "もの"), KanjiSeed("平", "たいら"), KanjiSeed("返", "かえす"),
        KanjiSeed("勉", "つとめる"), KanjiSeed("放", "はなす"), KanjiSeed("味", "あじ"), KanjiSeed("命", "いのち"),
        KanjiSeed("面", "おも"), KanjiSeed("問", "とう"), KanjiSeed("役", "やく"), KanjiSeed("薬", "くすり"),
        KanjiSeed("油", "あぶら"), KanjiSeed("有", "ある"), KanjiSeed("由", "よし"), KanjiSeed("遊", "あそぶ"),
        KanjiSeed("予", "あらかじめ"), KanjiSeed("様", "さま"), KanjiSeed("洋", "よう"), KanjiSeed("羊", "ひつじ"),
        KanjiSeed("葉", "は"), KanjiSeed("陽", "ひ"), KanjiSeed("落", "おちる"), KanjiSeed("流", "ながれる"),
        KanjiSeed("旅", "たび"), KanjiSeed("両", "てる"), KanjiSeed("緑", "みどり"), KanjiSeed("礼", "れい"),
        KanjiSeed("列", "れつ"), KanjiSeed("練", "ねる"), KanjiSeed("路", "じ"), KanjiSeed("和", "やわらぐ"),
    ),
    4 to listOf(
        KanjiSeed("愛", "いとしい"), KanjiSeed("案", "つくえ"), KanjiSeed("以", "もって"), KanjiSeed("位", "くらい"),
        KanjiSeed("衣", "ころも"), KanjiSeed("井", "い"), KanjiSeed("茨", "いばら"), KanjiSeed("印", "しるし"),
        KanjiSeed("栄", "さかえる"), KanjiSeed("英", "はなぶさ"), KanjiSeed("塩", "しお"), KanjiSeed("岡", "おか"),
        KanjiSeed("沖", "おき"), KanjiSeed("億", "おく"), KanjiSeed("加", "くわえる"), KanjiSeed("果", "はたす"),
        KanjiSeed("課", "か"), KanjiSeed("貨", "たから"), KanjiSeed("芽", "め"), KanjiSeed("賀", "が"),
        KanjiSeed("改", "あらためる"), KanjiSeed("械", "かせ"), KanjiSeed("害", "がい"), KanjiSeed("街", "まち"),
        KanjiSeed("各", "おのおの"), KanjiSeed("覚", "おぼえる"), KanjiSeed("潟", "かた"), KanjiSeed("完", "かん"),
        KanjiSeed("官", "かん"), KanjiSeed("管", "くだ"), KanjiSeed("観", "みる"), KanjiSeed("関", "せき"),
        KanjiSeed("願", "ねがう"), KanjiSeed("器", "うつわ"), KanjiSeed("岐", "き"), KanjiSeed("希", "まれ"),
        KanjiSeed("旗", "はた"), KanjiSeed("機", "はた"), KanjiSeed("季", "き"), KanjiSeed("議", "ぎ"),
        KanjiSeed("求", "もとめる"), KanjiSeed("泣", "なく"), KanjiSeed("給", "たまう"), KanjiSeed("挙", "あげる"),
        KanjiSeed("漁", "あさる"), KanjiSeed("競", "きそう"), KanjiSeed("共", "とも"), KanjiSeed("協", "きょう"),
        KanjiSeed("鏡", "かがみ"), KanjiSeed("極", "きわめる"), KanjiSeed("熊", "くま"), KanjiSeed("訓", "おしえる"),
        KanjiSeed("群", "むれる"), KanjiSeed("軍", "いくさ"), KanjiSeed("郡", "こおり"), KanjiSeed("径", "みち"),
        KanjiSeed("景", "けい"), KanjiSeed("芸", "うえる"), KanjiSeed("欠", "かける"), KanjiSeed("結", "むすぶ"),
        KanjiSeed("健", "すこやか"), KanjiSeed("建", "たてる"), KanjiSeed("験", "あかし"), KanjiSeed("固", "かためる"),
        KanjiSeed("候", "そうろう"), KanjiSeed("功", "いさお"), KanjiSeed("好", "このむ"), KanjiSeed("康", "こう"),
        KanjiSeed("香", "か"), KanjiSeed("佐", "さ"), KanjiSeed("差", "さす"), KanjiSeed("最", "もっとも"),
        KanjiSeed("菜", "な"), KanjiSeed("材", "ざい"), KanjiSeed("阪", "さか"), KanjiSeed("崎", "さき"),
        KanjiSeed("埼", "さき"), KanjiSeed("昨", "さく"), KanjiSeed("刷", "する"), KanjiSeed("察", "さつ"),
        KanjiSeed("札", "ふだ"), KanjiSeed("参", "まいる"), KanjiSeed("散", "ちる"), KanjiSeed("産", "うむ"),
        KanjiSeed("残", "のこる"), KanjiSeed("司", "つかさどる"), KanjiSeed("氏", "うじ"), KanjiSeed("試", "こころみる"),
        KanjiSeed("児", "こ"), KanjiSeed("滋", "じ"), KanjiSeed("治", "おさめる"), KanjiSeed("辞", "やめる"),
        KanjiSeed("鹿", "しか"), KanjiSeed("失", "うしなう"), KanjiSeed("借", "かりる"), KanjiSeed("種", "たね"),
        KanjiSeed("周", "まわり"), KanjiSeed("祝", "いわう"), KanjiSeed("順", "じゅん"), KanjiSeed("初", "はじめ"),
        KanjiSeed("唱", "となえる"), KanjiSeed("松", "まつ"), KanjiSeed("焼", "やく"), KanjiSeed("照", "てる"),
        KanjiSeed("省", "かえりみる"), KanjiSeed("笑", "わらう"), KanjiSeed("城", "しろ"), KanjiSeed("信", "しん"),
        KanjiSeed("臣", "しん"), KanjiSeed("成", "なる"), KanjiSeed("清", "きよい"), KanjiSeed("静", "しず"),
        KanjiSeed("席", "むしろ"), KanjiSeed("積", "つむ"), KanjiSeed("折", "おる"), KanjiSeed("節", "ふし"),
        KanjiSeed("説", "とく"), KanjiSeed("戦", "いくさ"), KanjiSeed("浅", "あさい"), KanjiSeed("選", "えらぶ"),
        KanjiSeed("然", "しか"), KanjiSeed("倉", "くら"), KanjiSeed("巣", "す"), KanjiSeed("争", "あらそう"),
        KanjiSeed("側", "かわ"), KanjiSeed("束", "たば"), KanjiSeed("続", "つづく"), KanjiSeed("卒", "そっする"),
        KanjiSeed("孫", "まご"), KanjiSeed("帯", "おびる"), KanjiSeed("隊", "たい"), KanjiSeed("達", "たち"),
        KanjiSeed("単", "ひとえ"), KanjiSeed("置", "おく"), KanjiSeed("仲", "なか"), KanjiSeed("兆", "きざす"),
        KanjiSeed("低", "ひくい"), KanjiSeed("底", "そこ"), KanjiSeed("的", "まと"), KanjiSeed("典", "ふみ"),
        KanjiSeed("伝", "つたわる"), KanjiSeed("徒", "いたずら"), KanjiSeed("努", "つとめる"), KanjiSeed("灯", "ひ"),
        KanjiSeed("働", "はたらく"), KanjiSeed("徳", "とく"), KanjiSeed("特", "とく"), KanjiSeed("栃", "とち"),
        KanjiSeed("奈", "いかん"), KanjiSeed("縄", "なわ"), KanjiSeed("熱", "あつい"), KanjiSeed("念", "ねん"),
        KanjiSeed("敗", "やぶれる"), KanjiSeed("梅", "うめ"), KanjiSeed("博", "はく"), KanjiSeed("飯", "めし"),
        KanjiSeed("飛", "とぶ"), KanjiSeed("必", "かならず"), KanjiSeed("媛", "ひめ"), KanjiSeed("標", "しるべ"),
        KanjiSeed("票", "ひょう"), KanjiSeed("不", "ふ"), KanjiSeed("付", "つける"), KanjiSeed("夫", "おっと"),
        KanjiSeed("富", "とむ"), KanjiSeed("府", "ふ"), KanjiSeed("阜", "ふ"), KanjiSeed("副", "ふく"),
        KanjiSeed("兵", "つわもの"), KanjiSeed("別", "わかれる"), KanjiSeed("変", "かわる"), KanjiSeed("辺", "あたり"),
        KanjiSeed("便", "たより"), KanjiSeed("包", "つつむ"), KanjiSeed("法", "のり"), KanjiSeed("望", "のぞむ"),
        KanjiSeed("牧", "まき"), KanjiSeed("末", "すえ"), KanjiSeed("満", "みちる"), KanjiSeed("未", "いまだ"),
        KanjiSeed("民", "たみ"), KanjiSeed("無", "ない"), KanjiSeed("約", "つづまる"), KanjiSeed("勇", "いさむ"),
        KanjiSeed("要", "いる"), KanjiSeed("養", "やしなう"), KanjiSeed("浴", "あびる"), KanjiSeed("利", "きく"),
        KanjiSeed("梨", "なし"), KanjiSeed("陸", "おか"), KanjiSeed("料", "りょう"), KanjiSeed("良", "よい"),
        KanjiSeed("量", "はかる"), KanjiSeed("輪", "わ"), KanjiSeed("類", "たぐい"), KanjiSeed("令", "れい"),
        KanjiSeed("例", "たとえる"), KanjiSeed("冷", "つめたい"), KanjiSeed("連", "つらなる"), KanjiSeed("労", "ろうする"),
        KanjiSeed("老", "おいる"), KanjiSeed("録", "しるす"),
    ),
    5 to listOf(
        KanjiSeed("圧", "おす"), KanjiSeed("囲", "かこむ"), KanjiSeed("易", "やさしい"), KanjiSeed("移", "うつる"),
        KanjiSeed("因", "よる"), KanjiSeed("営", "いとなむ"), KanjiSeed("永", "ながい"), KanjiSeed("衛", "えい"),
        KanjiSeed("液", "えき"), KanjiSeed("益", "ます"), KanjiSeed("演", "えん"), KanjiSeed("往", "いく"),
        KanjiSeed("応", "あたる"), KanjiSeed("仮", "かり"), KanjiSeed("価", "あたい"), KanjiSeed("可", "べき"),
        KanjiSeed("河", "かわ"), KanjiSeed("過", "すぎる"), KanjiSeed("解", "とく"), KanjiSeed("快", "こころよい"),
        KanjiSeed("格", "かく"), KanjiSeed("確", "たしか"), KanjiSeed("額", "ひたい"), KanjiSeed("刊", "かん"),
        KanjiSeed("幹", "みき"), KanjiSeed("慣", "なれる"), KanjiSeed("眼", "まなこ"), KanjiSeed("喜", "よろこぶ"),
        KanjiSeed("基", "もと"), KanjiSeed("寄", "よる"), KanjiSeed("紀", "き"), KanjiSeed("規", "き"),
        KanjiSeed("技", "わざ"), KanjiSeed("義", "ぎ"), KanjiSeed("逆", "さか"), KanjiSeed("久", "ひさしい"),
        KanjiSeed("救", "すくう"), KanjiSeed("旧", "ふるい"), KanjiSeed("居", "いる"), KanjiSeed("許", "ゆるす"),
        KanjiSeed("境", "さかい"), KanjiSeed("興", "おこる"), KanjiSeed("均", "ならす"), KanjiSeed("禁", "きん"),
        KanjiSeed("句", "く"), KanjiSeed("型", "かた"), KanjiSeed("経", "へる"), KanjiSeed("潔", "いさぎよい"),
        KanjiSeed("件", "くだん"), KanjiSeed("検", "しらべる"), KanjiSeed("険", "けわしい"), KanjiSeed("減", "へる"),
        KanjiSeed("現", "あらわれる"), KanjiSeed("限", "かぎる"), KanjiSeed("個", "こ"), KanjiSeed("故", "ゆえ"),
        KanjiSeed("護", "まもる"), KanjiSeed("効", "きく"), KanjiSeed("厚", "あつい"), KanjiSeed("構", "かまえる"),
        KanjiSeed("耕", "たがやす"), KanjiSeed("航", "こう"), KanjiSeed("講", "こう"), KanjiSeed("鉱", "あらがね"),
        KanjiSeed("告", "つげる"), KanjiSeed("混", "まじる"), KanjiSeed("査", "さ"), KanjiSeed("再", "ふたたび"),
        KanjiSeed("妻", "つま"), KanjiSeed("採", "とる"), KanjiSeed("災", "わざわい"), KanjiSeed("際", "きわ"),
        KanjiSeed("在", "ある"), KanjiSeed("罪", "つみ"), KanjiSeed("財", "たから"), KanjiSeed("桜", "さくら"),
        KanjiSeed("殺", "ころす"), KanjiSeed("雑", "まじえる"), KanjiSeed("賛", "たすける"), KanjiSeed("酸", "すい"),
        KanjiSeed("史", "し"), KanjiSeed("士", "さむらい"), KanjiSeed("師", "いくさ"), KanjiSeed("志", "しりんぐ"),
        KanjiSeed("支", "ささえる"), KanjiSeed("枝", "えだ"), KanjiSeed("資", "し"), KanjiSeed("飼", "かう"),
        KanjiSeed("似", "にる"), KanjiSeed("示", "しめす"), KanjiSeed("識", "しる"), KanjiSeed("質", "たち"),
        KanjiSeed("舎", "やどる"), KanjiSeed("謝", "あやまる"), KanjiSeed("授", "さずける"), KanjiSeed("修", "おさめる"),
        KanjiSeed("術", "すべ"), KanjiSeed("述", "のべる"), KanjiSeed("準", "じゅんじる"), KanjiSeed("序", "ついで"),
        KanjiSeed("招", "まねく"), KanjiSeed("証", "あかし"), KanjiSeed("象", "かたどる"), KanjiSeed("賞", "ほめる"),
        KanjiSeed("常", "つね"), KanjiSeed("情", "なさけ"), KanjiSeed("条", "えだ"), KanjiSeed("状", "じょう"),
        KanjiSeed("織", "おる"), KanjiSeed("職", "しょく"), KanjiSeed("制", "せい"), KanjiSeed("勢", "いきおい"),
        KanjiSeed("性", "さが"), KanjiSeed("政", "まつりごと"), KanjiSeed("精", "しらげる"), KanjiSeed("製", "せい"),
        KanjiSeed("税", "ぜい"), KanjiSeed("績", "せき"), KanjiSeed("責", "せめる"), KanjiSeed("接", "つぐ"),
        KanjiSeed("設", "もうける"), KanjiSeed("絶", "たえる"), KanjiSeed("祖", "そ"), KanjiSeed("素", "もと"),
        KanjiSeed("総", "すべて"), KanjiSeed("像", "ぞう"), KanjiSeed("増", "ます"), KanjiSeed("造", "つくる"),
        KanjiSeed("則", "のっとる"), KanjiSeed("測", "はかる"), KanjiSeed("属", "さかん"), KanjiSeed("損", "そこなう"),
        KanjiSeed("態", "わざと"), KanjiSeed("貸", "かす"), KanjiSeed("団", "かたまり"), KanjiSeed("断", "たつ"),
        KanjiSeed("築", "きずく"), KanjiSeed("貯", "ためる"), KanjiSeed("張", "はる"), KanjiSeed("停", "とめる"),
        KanjiSeed("提", "さげる"), KanjiSeed("程", "ほど"), KanjiSeed("適", "かなう"), KanjiSeed("統", "すべる"),
        KanjiSeed("堂", "どう"), KanjiSeed("導", "みちびく"), KanjiSeed("銅", "あかがね"), KanjiSeed("得", "える"),
        KanjiSeed("毒", "どく"), KanjiSeed("独", "ひとり"), KanjiSeed("任", "まかせる"), KanjiSeed("燃", "もえる"),
        KanjiSeed("能", "よく"), KanjiSeed("破", "やぶる"), KanjiSeed("判", "わかる"), KanjiSeed("版", "はん"),
        KanjiSeed("犯", "おかす"), KanjiSeed("比", "くらべる"), KanjiSeed("肥", "こえる"), KanjiSeed("費", "ついやす"),
        KanjiSeed("非", "あらず"), KanjiSeed("備", "そなえる"), KanjiSeed("評", "ひょう"), KanjiSeed("貧", "まずしい"),
        KanjiSeed("婦", "よめ"), KanjiSeed("布", "ぬの"), KanjiSeed("武", "たけ"), KanjiSeed("復", "また"),
        KanjiSeed("複", "ふく"), KanjiSeed("仏", "ほとけ"), KanjiSeed("粉", "でしめーとる"), KanjiSeed("編", "あむ"),
        KanjiSeed("弁", "かんむり"), KanjiSeed("保", "たもつ"), KanjiSeed("墓", "はか"), KanjiSeed("報", "むくいる"),
        KanjiSeed("豊", "ゆたか"), KanjiSeed("暴", "あばく"), KanjiSeed("貿", "ぼう"), KanjiSeed("防", "ふせぐ"),
        KanjiSeed("脈", "すじ"), KanjiSeed("務", "つとめる"), KanjiSeed("夢", "ゆめ"), KanjiSeed("迷", "まよう"),
        KanjiSeed("綿", "わた"), KanjiSeed("輸", "ゆ"), KanjiSeed("余", "あまる"), KanjiSeed("容", "いれる"),
        KanjiSeed("率", "ひきいる"), KanjiSeed("略", "ほぼ"), KanjiSeed("留", "とめる"), KanjiSeed("領", "えり"),
        KanjiSeed("歴", "れき"),
    ),
    6 to listOf(
        KanjiSeed("異", "こと"), KanjiSeed("胃", "い"), KanjiSeed("遺", "のこす"), KanjiSeed("域", "いき"),
        KanjiSeed("宇", "う"), KanjiSeed("映", "うつる"), KanjiSeed("延", "のびる"), KanjiSeed("沿", "そう"),
        KanjiSeed("恩", "おん"), KanjiSeed("我", "われ"), KanjiSeed("灰", "はい"), KanjiSeed("拡", "ひろがる"),
        KanjiSeed("閣", "かく"), KanjiSeed("革", "かわ"), KanjiSeed("割", "わる"), KanjiSeed("株", "かぶ"),
        KanjiSeed("巻", "まく"), KanjiSeed("干", "ほす"), KanjiSeed("看", "みる"), KanjiSeed("簡", "えらぶ"),
        KanjiSeed("危", "あぶない"), KanjiSeed("揮", "ふるう"), KanjiSeed("机", "つくえ"), KanjiSeed("貴", "たっとい"),
        KanjiSeed("疑", "うたがう"), KanjiSeed("吸", "すう"), KanjiSeed("供", "そなえる"), KanjiSeed("胸", "むね"),
        KanjiSeed("郷", "さと"), KanjiSeed("勤", "つとめる"), KanjiSeed("筋", "すじ"), KanjiSeed("敬", "うやまう"),
        KanjiSeed("系", "けい"), KanjiSeed("警", "いましめる"), KanjiSeed("劇", "げき"), KanjiSeed("激", "はげしい"),
        KanjiSeed("穴", "あな"), KanjiSeed("券", "けん"), KanjiSeed("憲", "けん"), KanjiSeed("権", "おもり"),
        KanjiSeed("絹", "きぬ"), KanjiSeed("厳", "おごそか"), KanjiSeed("源", "みなもと"), KanjiSeed("呼", "よぶ"),
        KanjiSeed("己", "おのれ"), KanjiSeed("誤", "あやまる"), KanjiSeed("后", "きさき"), KanjiSeed("孝", "こう"),
        KanjiSeed("皇", "こう"), KanjiSeed("紅", "べに"), KanjiSeed("鋼", "はがね"), KanjiSeed("降", "おりる"),
        KanjiSeed("刻", "きざむ"), KanjiSeed("穀", "こく"), KanjiSeed("骨", "ほね"), KanjiSeed("困", "こまる"),
        KanjiSeed("砂", "すな"), KanjiSeed("座", "すわる"), KanjiSeed("済", "すむ"), KanjiSeed("裁", "たつ"),
        KanjiSeed("策", "さく"), KanjiSeed("冊", "ふみ"), KanjiSeed("蚕", "かいこ"), KanjiSeed("姿", "すがた"),
        KanjiSeed("私", "わたくし"), KanjiSeed("至", "いたる"), KanjiSeed("視", "みる"), KanjiSeed("詞", "ことば"),
        KanjiSeed("誌", "し"), KanjiSeed("磁", "じ"), KanjiSeed("射", "いる"), KanjiSeed("捨", "すてる"),
        KanjiSeed("尺", "さし"), KanjiSeed("若", "わかい"), KanjiSeed("樹", "き"), KanjiSeed("収", "おさめる"),
        KanjiSeed("宗", "むね"), KanjiSeed("就", "つく"), KanjiSeed("衆", "おおい"), KanjiSeed("従", "したがう"),
        KanjiSeed("縦", "たて"), KanjiSeed("縮", "ちぢむ"), KanjiSeed("熟", "うれる"), KanjiSeed("純", "じゅん"),
        KanjiSeed("処", "ところ"), KanjiSeed("署", "しょ"), KanjiSeed("諸", "もろ"), KanjiSeed("除", "のぞく"),
        KanjiSeed("傷", "きず"), KanjiSeed("将", "まさに"), KanjiSeed("承", "うけたまわる"), KanjiSeed("障", "さわる"),
        KanjiSeed("蒸", "むす"), KanjiSeed("針", "はり"), KanjiSeed("仁", "じん"), KanjiSeed("垂", "たれる"),
        KanjiSeed("推", "おす"), KanjiSeed("寸", "すん"), KanjiSeed("盛", "もる"), KanjiSeed("聖", "ひじり"),
        KanjiSeed("誠", "まこと"), KanjiSeed("舌", "した"), KanjiSeed("宣", "のたまう"), KanjiSeed("専", "もっぱら"),
        KanjiSeed("泉", "いずみ"), KanjiSeed("洗", "あらう"), KanjiSeed("染", "そめる"), KanjiSeed("銭", "ぜに"),
        KanjiSeed("善", "よい"), KanjiSeed("創", "つくる"), KanjiSeed("奏", "かなでる"), KanjiSeed("層", "そう"),
        KanjiSeed("操", "みさお"), KanjiSeed("窓", "まど"), KanjiSeed("装", "よそおう"), KanjiSeed("臓", "はらわた"),
        KanjiSeed("蔵", "くら"), KanjiSeed("存", "ながらえる"), KanjiSeed("尊", "たっとい"), KanjiSeed("退", "しりぞく"),
        KanjiSeed("宅", "たく"), KanjiSeed("担", "かつぐ"), KanjiSeed("探", "さぐる"), KanjiSeed("誕", "たん"),
        KanjiSeed("暖", "あたたか"), KanjiSeed("段", "だん"), KanjiSeed("値", "ね"), KanjiSeed("宙", "ちゅう"),
        KanjiSeed("忠", "ちゅう"), KanjiSeed("著", "あらわす"), KanjiSeed("庁", "やくしょ"), KanjiSeed("潮", "しお"),
        KanjiSeed("腸", "はらわた"), KanjiSeed("頂", "いただく"), KanjiSeed("賃", "ちん"), KanjiSeed("痛", "いたい"),
        KanjiSeed("敵", "かたき"), KanjiSeed("展", "てん"), KanjiSeed("党", "なかま"), KanjiSeed("糖", "とう"),
        KanjiSeed("討", "うつ"), KanjiSeed("届", "とどける"), KanjiSeed("難", "かたい"), KanjiSeed("乳", "ちち"),
        KanjiSeed("認", "みとめる"), KanjiSeed("納", "おさめる"), KanjiSeed("脳", "のうずる"), KanjiSeed("派", "は"),
        KanjiSeed("俳", "はい"), KanjiSeed("拝", "おがむ"), KanjiSeed("背", "せ"), KanjiSeed("肺", "はい"),
        KanjiSeed("班", "はん"), KanjiSeed("晩", "ばん"), KanjiSeed("否", "いな"), KanjiSeed("批", "ひ"),
        KanjiSeed("秘", "ひめる"), KanjiSeed("俵", "たわら"), KanjiSeed("腹", "はら"), KanjiSeed("奮", "ふるう"),
        KanjiSeed("並", "なみ"), KanjiSeed("閉", "とじる"), KanjiSeed("陛", "へい"), KanjiSeed("片", "かた"),
        KanjiSeed("補", "おぎなう"), KanjiSeed("暮", "くれる"), KanjiSeed("宝", "たから"), KanjiSeed("訪", "おとずれる"),
        KanjiSeed("亡", "ない"), KanjiSeed("忘", "わすれる"), KanjiSeed("棒", "ぼう"), KanjiSeed("枚", "まい"),
        KanjiSeed("幕", "とばり"), KanjiSeed("密", "ひそか"), KanjiSeed("盟", "めい"), KanjiSeed("模", "も"),
        KanjiSeed("訳", "わけ"), KanjiSeed("優", "やさしい"), KanjiSeed("郵", "ゆう"), KanjiSeed("預", "あずける"),
        KanjiSeed("幼", "おさない"), KanjiSeed("欲", "ほっする"), KanjiSeed("翌", "よく"), KanjiSeed("乱", "みだれる"),
        KanjiSeed("卵", "たまご"), KanjiSeed("覧", "みる"), KanjiSeed("裏", "うら"), KanjiSeed("律", "りつ"),
        KanjiSeed("臨", "のぞむ"), KanjiSeed("朗", "ほがらか"), KanjiSeed("論", "あげつらう"),
    ),
)
