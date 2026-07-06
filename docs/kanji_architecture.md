# Kanji Learning App Architecture

## Scope

The core learning feature is implemented as a small UDF loop:

- `KanjiScreen` renders `KanjiUiState` and sends user events to the ViewModel.
- `KanjiViewModel` owns quiz progress, reading timers, writing strokes, and recognition state.
- `KanjiCanvasView` is a controlled drawing surface. It receives strokes from state and emits a new stroke list when the learner finishes a stroke.
- `DigitalInkRecognizerClient` hides ML Kit behind a suspend API, so tests can provide a fake recognizer.

## State Ownership

`KanjiViewModel` exposes only `StateFlow<KanjiUiState>`.

Reading mode keeps:

- Current question index
- Shuffled choices
- Correct count
- Per-question remaining seconds
- A coroutine `Job` for the active timer

Writing mode keeps:

- Current question index
- Current character index
- Captured `DrawnStroke` values
- ML Kit model state
- Recognition state: `Idle`, `Loading`, `Success`, `Error`

## Lifecycle

The reading timer runs in `viewModelScope` and is canceled when:

- The screen moves below `STARTED`
- The mode changes away from reading
- The quiz finishes
- The ViewModel is cleared

Writing mode has no timer.

## ML Kit Setup

Use:

```kotlin
implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
```

The Japanese recognizer uses the BCP-47 tag `ja-JP`, checks `RemoteModelManager.isModelDownloaded`, and downloads the model before recognition when needed.

The module also needs a normal Compose Material 3 setup and Android `minSdk` 23 or later for ML Kit Digital Ink Recognition.

## Grade Scope

The grade selector follows the current elementary school course-of-study grade allocation table:

- 1st grade: 80 kanji
- 2nd grade: 160 kanji
- 3rd grade: 200 kanji
- 4th grade: 202 kanji
- 5th grade: 193 kanji
- 6th grade: 191 kanji

The in-app seed list follows the grade allocation scope from the MEXT table and uses KANJIDIC2 readings to generate reading choices. Reading questions present the target kanji inside a sentence, following elementary-kanji-list guidance that kanji should be practiced in context rather than only as isolated characters.

References:

- MEXT grade allocation PDF: https://www.mext.go.jp/a_menu/shotou/new-cs/youryou/syo/koku/__icsFiles/afieldfile/2016/10/27/1234920.pdf
- Elementary kanji list/reference: https://ieben.net/syou-kanji/
- Context sentence learning guidance: https://surala.jp/smart-print/kanji/article/learning/18374/
- KANJIDIC2: https://www.edrdg.org/kanjidic/kanjidic2.xml.gz
