#!/usr/bin/env python3
import csv
import sys
from dataclasses import dataclass
from pathlib import Path


KANJI_START = ord("\u4e00")
KANJI_END = ord("\u9fff")
KATAKANA_START = ord("\u30a1")
KATAKANA_END = ord("\u30f6")


@dataclass
class Issue:
    line_number: int
    severity: str
    code: str
    message: str


@dataclass
class RubyToken:
    text: str
    ruby: str
    is_target: bool = False
    from_target_run: bool = False


def is_kanji(char: str) -> bool:
    return KANJI_START <= ord(char) <= KANJI_END


def to_hiragana(char: str) -> str:
    code = ord(char)
    if KATAKANA_START <= code <= KATAKANA_END:
        return chr(code - 0x60)
    return char


def matches_reading_anchor(reading_char: str, sentence_char: str) -> bool:
    return reading_char == sentence_char or to_hiragana(reading_char) == to_hiragana(sentence_char)


def index_of_reading_anchor(reading: str, anchor: str, start_index: int) -> int:
    for index in range(start_index, len(reading)):
        if matches_reading_anchor(reading[index], anchor):
            return index
    return -1


def marked_target_start(marked_sentence: str, target: str) -> int:
    marker = f"[{target}]"
    marker_index = marked_sentence.find(marker)
    if marker_index < 0:
        return -1
    return len(marked_sentence[:marker_index].replace("[", "").replace("]", ""))


def build_ruby_tokens(marked_sentence: str, sentence_reading: str, target: str, target_reading: str) -> list[RubyToken]:
    sentence = marked_sentence.replace(f"[{target}]", target)
    target_start = marked_target_start(marked_sentence, target)
    tokens: list[RubyToken] = []
    sentence_index = 0
    reading_index = 0

    while sentence_index < len(sentence):
        char = sentence[sentence_index]
        if not is_kanji(char):
            if reading_index < len(sentence_reading) and matches_reading_anchor(sentence_reading[reading_index], char):
                reading_index += 1
            sentence_index += 1
            continue

        run_start = sentence_index
        while sentence_index < len(sentence) and is_kanji(sentence[sentence_index]):
            sentence_index += 1
        run_end = sentence_index
        run_text = sentence[run_start:run_end]

        next_anchor = next((c for c in sentence[sentence_index:] if not is_kanji(c)), None)
        if next_anchor is None:
            ruby_end = len(sentence_reading)
        else:
            ruby_end = index_of_reading_anchor(sentence_reading, next_anchor, reading_index)
            if ruby_end < reading_index:
                ruby_end = reading_index
        run_reading = sentence_reading[reading_index:ruby_end]
        reading_index = ruby_end

        if target_start >= 0 and run_start <= target_start < run_end:
            target_offset = target_start - run_start
            prefix_text = run_text[:target_offset]
            suffix_text = run_text[target_offset + len(target):]
            target_reading_start = run_reading.find(target_reading)

            if target_reading_start < 0:
                if prefix_text:
                    tokens.append(RubyToken(prefix_text, run_reading, from_target_run=True))
                tokens.append(RubyToken(target, "", is_target=True, from_target_run=True))
                if suffix_text:
                    tokens.append(RubyToken(suffix_text, "", from_target_run=True))
                continue

            target_reading_end = target_reading_start + len(target_reading)
            if prefix_text:
                tokens.append(RubyToken(prefix_text, run_reading[:target_reading_start], from_target_run=True))
            tokens.append(RubyToken(target, "", is_target=True, from_target_run=True))
            if suffix_text:
                tokens.append(RubyToken(suffix_text, run_reading[target_reading_end:], from_target_run=True))
        else:
            tokens.append(RubyToken(run_text, run_reading))

    return tokens


def strip_markers(text: str) -> str:
    return "".join(char for char in text if char not in "[]{}")


def marker_sequence(text: str) -> list[str] | None:
    sequence: list[str] = []
    index = 0
    while index < len(text):
        char = text[index]
        if char not in "[{]}":
            index += 1
            continue
        if char in "]}":
            return None
        close_marker = "]" if char == "[" else "}"
        close_index = text.find(close_marker, index + 1)
        if close_index < 0:
            return None
        sequence.append(char)
        index = close_index + 1
    return sequence


def has_markers(text: str) -> bool:
    return any(char in "[{}]" for char in text)


def validate_row(line_number: int, row: dict[str, str], include_warnings: bool) -> list[Issue]:
    issues: list[Issue] = []
    grade = row.get("学年", "")
    target = row.get("対象の漢字", "")
    answer = row.get("読み", "")
    sentence = row.get("文章", "")
    sentence_reading = row.get("ぶんしょう", "")
    marker = f"[{target}]"
    plain_sentence_reading = strip_markers(sentence_reading)

    if not grade.isdigit():
        issues.append(Issue(line_number, "error", "invalid_grade", f"学年が数値ではありません: {grade}"))
    if sentence.count(marker) != 1:
        issues.append(Issue(line_number, "error", "target_marker_count", f"対象マーカー {marker} は1つだけ必要です: {sentence}"))
    if answer and answer not in plain_sentence_reading:
        issues.append(
            Issue(
                line_number,
                "error",
                "answer_not_in_sentence_reading",
                f"読み `{answer}` が全文読み `{plain_sentence_reading}` に含まれていません",
            ),
        )
    uses_explicit_markers = "{" in sentence or "{" in sentence_reading or "[" in sentence_reading
    if uses_explicit_markers:
        sentence_markers = marker_sequence(sentence)
        reading_markers = marker_sequence(sentence_reading)
        if sentence_markers is None:
            issues.append(Issue(line_number, "error", "invalid_sentence_markers", f"文章のマーカーが壊れています: {sentence}"))
        if reading_markers is None:
            issues.append(
                Issue(line_number, "error", "invalid_reading_markers", f"ぶんしょうのマーカーが壊れています: {sentence_reading}"),
            )
        if sentence_markers is not None and reading_markers is not None and sentence_markers != reading_markers:
            issues.append(
                Issue(
                    line_number,
                    "error",
                    "marker_sequence_mismatch",
                    f"文章のマーカー順 {sentence_markers} とぶんしょうのマーカー順 {reading_markers} が一致しません",
                ),
            )
        if sentence.count("[") != 1 or sentence.count("]") != 1:
            issues.append(Issue(line_number, "error", "target_marker_pair_count", "文章の [] は対象漢字1箇所だけにしてください"))
        if sentence_reading.count("[") != 1 or sentence_reading.count("]") != 1:
            issues.append(Issue(line_number, "error", "target_reading_marker_pair_count", "ぶんしょうの [] は対象読み1箇所だけにしてください"))

    if not include_warnings or not target or not answer or not sentence_reading or sentence.count(marker) != 1:
        return issues

    tokens = build_ruby_tokens(sentence, plain_sentence_reading, target, answer)
    for token in tokens:
        if token.is_target:
            continue
        if token.from_target_run and len(answer) >= 2 and answer in token.ruby:
            issues.append(
                Issue(
                    line_number,
                    "warning",
                    "answer_leaked_to_ruby",
                    f"対象漢字以外の `{token.text}` のルビ `{token.ruby}` に答え `{answer}` が含まれています",
                ),
            )

    for token in tokens:
        if token.is_target or not token.from_target_run or not token.text or not token.ruby:
            continue
        if len(token.text) == 1 and len(token.ruby) >= 3:
            issues.append(
                Issue(
                    line_number,
                    "warning",
                    "suspicious_target_neighbor_ruby",
                    f"対象漢字に隣接する `{token.text}` のルビ `{token.ruby}` が長すぎる可能性があります",
                ),
            )

    return issues


def main() -> int:
    include_warnings = "--warnings" in sys.argv
    args = [arg for arg in sys.argv[1:] if arg != "--warnings"]
    csv_path = Path(args[0]) if args else Path("kanji_yomi_questions.csv")
    issues: list[Issue] = []
    with csv_path.open(encoding="utf-8-sig", newline="") as csv_file:
        reader = csv.DictReader(csv_file)
        for line_number, row in enumerate(reader, start=2):
            issues.extend(validate_row(line_number, row, include_warnings))

    for issue in issues:
        print(f"{csv_path}:{issue.line_number}: {issue.severity}: {issue.code}: {issue.message}")

    return 1 if any(issue.severity == "error" for issue in issues) else 0


if __name__ == "__main__":
    raise SystemExit(main())
