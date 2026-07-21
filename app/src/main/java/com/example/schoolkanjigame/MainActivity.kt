package com.example.schoolkanjigame

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.schoolkanjigame.kanji.FileBackedKanjiSettingsStore
import com.example.schoolkanjigame.kanji.KanjiScreen
import com.example.schoolkanjigame.kanji.KanjiViewModel
import com.example.schoolkanjigame.kanji.SharedPreferencesKanjiHistoryStore
import com.example.schoolkanjigame.kanji.SharedPreferencesKanjiSettingsStore
import com.example.schoolkanjigame.kanji.SharedPreferencesQuestionAttemptStore
import com.example.schoolkanjigame.kanji.loadKanjiQuestionsFromCsv
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SchoolKanjiGameApp()
        }
    }
}

@Composable
private fun SchoolKanjiGameApp() {
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            val context = LocalContext.current
            val questionBank = remember(context) {
                loadKanjiQuestionsFromCsv(context.applicationContext)
            }
            val questionAttemptStore = remember(context) {
                SharedPreferencesQuestionAttemptStore(
                    context.applicationContext.getSharedPreferences(
                        "kanji_question_attempts",
                        Context.MODE_PRIVATE,
                    ),
                )
            }
            val settingsStore = remember(context) {
                FileBackedKanjiSettingsStore(
                    delegate = SharedPreferencesKanjiSettingsStore(
                        context.applicationContext.getSharedPreferences(
                            "kanji_settings",
                            Context.MODE_PRIVATE,
                        ),
                    ),
                    settingsFile = File(context.applicationContext.filesDir, "kanji_settings.json"),
                )
            }
            val historyStore = remember(context) {
                SharedPreferencesKanjiHistoryStore(
                    context.applicationContext.getSharedPreferences(
                        "kanji_history",
                        Context.MODE_PRIVATE,
                    ),
                )
            }
            val viewModel: KanjiViewModel = viewModel(
                factory = KanjiViewModel.factory(
                    questionBank,
                    questionAttemptStore,
                    settingsStore,
                    historyStore,
                ),
            )
            KanjiScreen(
                viewModel = viewModel,
                modifier = Modifier.safeDrawingPadding(),
            )
        }
    }
}
