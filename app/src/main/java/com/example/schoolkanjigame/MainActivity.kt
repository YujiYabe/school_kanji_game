package com.example.schoolkanjigame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.schoolkanjigame.kanji.KanjiScreen
import com.example.schoolkanjigame.kanji.KanjiViewModel

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
            val viewModel: KanjiViewModel = viewModel()
            KanjiScreen(
                viewModel = viewModel,
                modifier = Modifier.safeDrawingPadding(),
            )
        }
    }
}
