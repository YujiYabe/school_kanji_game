# School Kanji Game

小学生が漢字の読みと書きを練習できる Android アプリです。文章の中で漢字の使われ方を確認しながら、4択の読み問題と手書き問題に取り組めます。

保護者向けの管理画面では、出題する学年の制限や学習状況の確認、学習結果に応じた YouTube 視聴時間の設定ができます。

## 主な機能

### 学習機能

- 小学1〜6年生の漢字に対応
- 文章中の漢字の読みを答える4択クイズ
- ML Kit Digital Ink Recognitionを利用した手書き練習
- 学年、1問あたりの制限時間、出題数の設定
- 間違えた問題の復習
- 得点、回答内容、日時を含む学習履歴
- 英語・スペイン語の例文訳を表示

### 保護者向け機能

- 数字のパスワードで保護された管理画面
- 子どもが選択できる学年の制限
- 学年ごとの累計回答数と達成率の表示
- 100問正解あたりの YouTube 視聴時間を設定
- 利用可能な視聴時間の調整
- YouTube視聴時に使うWi-Fiネットワークの登録

学年ごとの累計回答数は、個別の学習履歴を削除しても保持されます。

## 動作環境

- Android 6.0（API 23）以上
- JDK 17
- Android SDK 35
- Android Studio（最新版を推奨）

書き取り機能を初めて使うときは、日本語手書き認識モデルをダウンロードするためインターネット接続が必要です。

## セットアップ

このプロジェクトは、共通の管理画面・YouTube・Wi-Fi機能を提供する `android_admin_common` を複合ビルドとして参照します。次のように、両プロジェクトを同じ親ディレクトリへ配置してください。

```text
workspace/
├── android_admin_common/
└── school_kanji_game/
```

その後、`school_kanji_game` を Android Studio で開き、Gradle Syncを実行して `app` 構成を起動します。

コマンドラインからビルドする場合は、次のコマンドを使用できます。

```bash
./gradlew assembleDebug
```

生成されるAPKは `app/build/outputs/apk/debug/app-debug.apk` です。Makeを利用できる環境では、次のコマンドも使えます。

```bash
make apk      # dist/school-kanji-game.apk を作成
make install  # 接続中の端末へデバッグAPKをインストール
```

## 使い方

1. 起動画面で学年、タイマー、問題数を選びます。
2. 「スタート」を押して読み問題に回答します。
3. 読み問題の後、画面に漢字を書いて書き取り問題に回答します。
4. 結果画面で得点と間違えた問題を確認します。
5. 過去の結果は起動画面の「履歴」から確認できます。

### 管理画面の初期設定

初回は管理パスワードが未設定です。「管理画面」を開き、パスワード欄を空のまま認証した後、4桁以上の数字のパスワードを設定してください。

管理画面では次の項目を変更できます。

- 学習画面で選択可能にする学年
- 100問正解あたりの YouTube 視聴時間
- 現在の YouTube 残り時間
- アプリ内YouTube視聴の有効・無効
- YouTube視聴用Wi-FiのSSIDとパスワード
- 管理画面のパスワード

パスワードを忘れた場合にアプリ内から復元する機能はありません。

## 問題データ

問題はルートの [`kanji_yomi_questions.csv`](kanji_yomi_questions.csv) で管理しています。ビルド時に `app/src/main/assets/` へ自動コピーされます。

CSVの形式は次のとおりです。

```csv
学年,対象の漢字,読み,文章,ぶんしょう,英語,スペイン語
```

編集後は、次のコマンドで形式と内容を検証できます。

```bash
./gradlew validateKanjiCsv
```

## 技術構成

- Kotlin
- Jetpack Compose / Material 3
- ViewModel / StateFlow
- ML Kit Digital Ink Recognition 19.0.0
- Gradle Kotlin DSL

状態管理などの詳細は [`docs/kanji_architecture.md`](docs/kanji_architecture.md) を参照してください。

## 権限とデータの取り扱い

YouTube視聴用Wi-Fiを切り替えるため、ネットワーク、位置情報、付近のデバイスに関するAndroid権限を使用します。権限の許可方法やWi-Fi操作の可否はAndroidのバージョンと端末の仕様によって異なります。

学習履歴、設定、管理パスワードのハッシュ、登録したWi-Fi情報は端末内に保存されます。Wi-Fiパスワードは暗号化されていないため、本番配布時には保存方式の見直しを推奨します。

## ライセンス

ライセンスは [`LICENSE`](LICENSE) を参照してください。

## 参考資料

- [ちびむすドリル「小学国語 漢字の読み方」](https://happylilac.net/nao231228-kanji-yomi-01-ms.html#down1)
- [文部科学省 学年別漢字配当表（PDF）](https://www.mext.go.jp/a_menu/shotou/new-cs/youryou/syo/koku/__icsFiles/afieldfile/2016/10/27/1234920.pdf)
- [KANJIDIC2](https://www.edrdg.org/kanjidic/kanjidic2.xml.gz)
