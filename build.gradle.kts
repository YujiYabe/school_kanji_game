plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.register<Exec>("validateKanjiCsv") {
    commandLine("python3", "tools/validate_kanji_csv.py", "kanji_yomi_questions.csv")
}
