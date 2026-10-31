# 個人用カスタム版

このリポジトリの `custom` ブランチは、KazumaProject/JapaneseKeyboard を元にした非公式の個人用Forkです。表示名・アイコンは本家のものを使用しています。

## 基点と変更内容

上流の `58b6bd9b418a11606990caa8c6b746d0bc617d17` を最初の基点とし、現在は `dev` の `6e929009dcb33a271dbf610163d442ad38c5696a` までの更新を取り込んでいます。`.github/workflows/` はFork側の設定を維持しています。

- 通常表示のキーボードを幅・位置・下余白で調整した際も、親領域をテーマの背景色で不透明にします。実際のフローティングモードは変更していません。
- ライブ変換結果を表示中に「、」「。」「！」「？」「…」を入力すると、変換結果と入力した記号を確定します。各記号を1文字入力したときに適用します。
- 通常の文節変換中のEnterは、現在の文節までを確定し、残りの文節の変換を続けます。
- 通常の文節変換中の左右キーで、対象文節の読みの長さを1文字ずつ調整します。画面上のキーと物理キーボードに対応します。

カスタム配列・設定バックアップなどの個人データは、このリポジトリには含めません。

## アプリの識別情報

| 項目 | 値 |
| --- | --- |
| フル版・標準配布のアプリID | `dev.ctrlcv.markdownhelperkeyboard` |
| ソースのnamespace | `com.kazumaproject.markdownhelperkeyboard`（本家と同じ） |
| 表示名 | `スミレ 日本語キーボード` / `Sumire Japanese Keyboard`（本家と同じ） |
| バージョン | `1.7.139-fork.2` / versionCode `835` |
| 署名証明書のSHA-256 | `2179af3733bbfbe959a5d53196210880c6fb4a666bef5b3113e0f6cee1cf1c4e` |

このFork独自のアプリIDです。Google Play版、および旧カスタム版 `com.kazumaproject.markdownhelperkeyboard.dev` とは別のアプリとして共存します。署名鍵は旧カスタム版と同じですが、アプリIDが異なるため旧カスタム版への上書き更新にはなりません。設定・カスタム配列・辞書は旧版からエクスポートし、新版へインポートしてください。

Lite/F-Droid版は上流と同じ仕組みで、それぞれ `.lite` / `.fdroid` が追加されます。`com.kiwi-93...` はハイフンを含むためAndroidのアプリIDには使用できません。[AndroidのアプリID規則](https://developer.android.com/build/configure-app-module)を参照してください。

## 署名鍵の扱い

既存APKの署名に使用した鍵を継続使用します。開発中に作成されたAndroid Debug証明書です。今後このForkのアプリIDでインストールした版へ更新できるよう、署名鍵を固定します。

秘密鍵・パスワード・Base64にした鍵はコミットしません。署名情報は環境変数、またはGitの管理対象外の `local.properties` から読み込みます。環境変数を優先し、指定する場合は次の4項目すべてを設定します。

```properties
KEYSTORE_FILE=/absolute/path/to/existing-fork-key.keystore
KEYSTORE_PASSWORD=<store password>
KEY_ALIAS=<key alias>
KEY_PASSWORD=<key password>
```

パスは絶対パス、またはリポジトリのルートからの相対パスです。Windowsの `local.properties` では `/` 区切りを使ってください。

この指定がある場合、DebugとReleaseの両方に同じ鍵を使用します。指定がないローカルDebugビルドはAndroid標準のdebug.keystoreを使用するため、新しいPCで生成された鍵では既存版に更新できません。既存版へ更新するAPKは、上記のSHA-256と一致することを必ず確認してください。

このForkの非公開Repository Secretsには以下を保存しています。現在のActionsは更新取り込み専用なので、これらの署名用Secretsは使用しません。ローカルビルドには、別途保管してある鍵ファイルを使用します。

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 既存鍵ファイルをBase64にした値 |
| `KEYSTORE_PASSWORD` | キーストアのパスワード |
| `KEY_ALIAS` | 署名する鍵の別名 |
| `KEY_PASSWORD` | 鍵のパスワード |

Secretsはバックアップとしてダウンロードできません。元の鍵ファイルを安全な場所にも保管してください。詳細は [GitHubのSecrets設定](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets) と [Androidの署名鍵管理](https://developer.android.com/studio/publish/app-signing) を参照してください。

本家の更新取り込みとGitHub標準のメール通知は [UPSTREAM_SYNC.md](UPSTREAM_SYNC.md) を参照してください。ビルド方法は現在のまま継続し、配布先はCloudflare R2とAccessによる本人専用の `apk.ctrlcv.dev` とする方針です。[配布手順](PRIVATE_DISTRIBUTION.md)に準備・検証方法を記載しています。

## ビルドと確認

上流と同じビルド環境（Gradle wrapper / JBR 25 / Android API 37 / NDK 29.0.14206865）を使用します。

```sh
./gradlew :app:assembleFullStandardDebug -PzenzDebugNativeAbis=arm64-v8a,x86_64
```

出力は `app/build/outputs/apk/fullStandard/debug/app-full-standard-debug.apk` です。アプリIDはGradleに設定済みです。追加の `.dev` suffixや表示名を上書きするマニフェストは不要です。

追加機能と関連する回帰テストは、以下で実行できます。

```sh
./gradlew :app:testFullStandardDebugUnitTest \
  --tests '*BunsetsuBoundaryAdjustmentTest' \
  --tests '*BunsetsuConversionProjectionTest' \
  --tests '*BunsetsuEnterCommitTest' \
  --tests '*CustomToggleComposingTest' \
  --tests '*ComposingTextArbiterTest' \
  --tests '*LiveConversionPunctuationTest' \
  --tests '*PhysicalKeyboardShortcutRepositoryTest'
```

SDKの `aapt dump badging` でアプリIDとversionCode、`apksigner verify --print-certs` で署名証明書のSHA-256を確認できます。

2026-10-31の本家更新取り込み時に、上記7クラス・67件に加え、本家の縦書き文節編集、カーソル、変換エンジン、キーボード表示関連のテストも実行し、計21クラス・183件の成功（失敗・エラー・スキップなし）を確認しました。フル版Debug APKのビルドと、アプリID・versionCode・表示名・既存版との署名証明書の一致も確認しています。このバージョンの実機インストールは未確認です。

競合していたEnter処理は、本家の縦書き文節編集を先に処理し、通常の文節変換ではForkの部分確定を継続する形に統合しました。入力の再処理でも、縦書き編集の状態更新と、部分確定後の残りの変換状態の維持を両立しています。

## ライセンス

本家の著作権表示・MITライセンスを維持します。辞書・ライブラリ・モデルについては、本家の `THIRD-PARTY-NOTICES.md` と `docs/licenses/` の条件を引き継ぎます。
