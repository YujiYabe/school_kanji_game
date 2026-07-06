# school_kanji_game

小学生向けの Android 漢字学習アプリです。Kotlin / Jetpack Compose / Material 3 で、読みの4択クイズと、ML Kit Digital Ink Recognition を使う書き取り練習を実装しています。

## 開発環境

- Android Studio
- JDK 17
- Android SDK 35
- minSdk 23

## 主な依存関係

- Jetpack Compose
- Material 3
- ViewModel / StateFlow
- `com.google.mlkit:digital-ink-recognition:19.0.0`

## 起動

Android Studio でこのディレクトリを開き、`app` を実行してください。初回の書きモードでは日本語手書き認識モデルのダウンロードが必要です。


## 参考ページ
https://happylilac.net/nao231228-kanji-yomi-01-ms.html#down1
