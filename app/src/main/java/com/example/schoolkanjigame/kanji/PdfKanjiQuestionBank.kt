package com.example.schoolkanjigame.kanji

import android.content.Context

private data class CsvKanjiSeed(
    val grade: Int,
    val target: String,
    val reading: String,
    val sentence: String,
    val markedSentence: String,
    val sentenceReading: String,
    val markedSentenceReading: String,
    val englishSentence: String,
    val spanishSentence: String,
)

fun loadKanjiQuestionsFromCsv(
    context: Context,
    assetName: String = "kanji_yomi_questions.csv",
): List<KanjiQuestion> {
    val seeds = context.assets.open(assetName).bufferedReader().useLines { lines ->
        lines.drop(1)
            .mapNotNull(::parseCsvKanjiSeed)
            .toList()
    }

    return seeds.mapIndexed { index, seed ->
        val distractors = seeds
            .asSequence()
            .filter { it.grade == seed.grade && it.reading != seed.reading }
            .map { it.reading }
            .distinct()
            .take(3)
            .toList()
        KanjiQuestion(
            id = "csv_${seed.grade}_${index}_${seed.target}",
            grade = seed.grade,
            fullSentence = seed.sentence,
            sentenceReading = seed.sentenceReading,
            markedSentence = seed.markedSentence,
            markedSentenceReading = seed.markedSentenceReading,
            englishSentence = seed.englishSentence,
            spanishSentence = seed.spanishSentence,
            targetText = seed.target,
            readingAnswers = listOf(seed.reading) + distractors,
            writingAnswer = seed.target,
        )
    }
}

private fun parseCsvKanjiSeed(line: String): CsvKanjiSeed? {
    if (line.isBlank()) return null

    val columns = parseCsvLine(line)
    if (columns.size < 4) return null

    val grade = columns[0].toIntOrNull() ?: return null
    val target = columns[1]
    val reading = columns[2]
    val markedSentence = columns[3]
    val markedSentenceReading = columns.getOrNull(4).orEmpty()
    val englishSentence = columns.getOrNull(5).orEmpty()
    val spanishSentence = columns.getOrNull(6).orEmpty()
    val sentence = markedSentence.stripRubyMarkers()
    val sentenceReading = markedSentenceReading.stripRubyMarkers()

    return CsvKanjiSeed(
        grade = grade,
        target = target,
        reading = reading,
        sentence = sentence,
        markedSentence = markedSentence,
        sentenceReading = sentenceReading,
        markedSentenceReading = markedSentenceReading,
        englishSentence = englishSentence,
        spanishSentence = spanishSentence,
    )
}

private fun String.stripRubyMarkers(): String =
    filterNot { it == '[' || it == ']' || it == '{' || it == '}' }

private fun parseCsvLine(line: String): List<String> {
    val columns = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var index = 0

    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && inQuotes && line.getOrNull(index + 1) == '"' -> {
                current.append('"')
                index++
            }
            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> {
                columns.add(current.toString())
                current.clear()
            }
            else -> current.append(char)
        }
        index++
    }
    columns.add(current.toString())
    return columns
}
