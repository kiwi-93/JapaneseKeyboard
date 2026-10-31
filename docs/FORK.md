# 個人用カスタム版

このリポジトリの `custom` ブランチは、KazumaProject/JapaneseKeyboard を元にした非公式の個人用Forkです。表示名・アイコンは本家のものを使用しています。

## 基点と変更内容

実機で確認した版を維持するため、上流の `58b6bd9b418a11606990caa8c6b746d0bc617d17` を基点にしています。Fork作成時の `dev` に含まれる、それ以後の更新はまだ取り込んでいません。

- 通常表示のキーボードを幅・位置・下余白で調整した際も、親領域をテーマの背景色で不透明にします。実際のフローティングモードは変更していません。
- ライブ変換結果を表示中に「、」「。」を入力すると、変換結果と句読点を確定します。
- 通常の文節変換中のEnterは、現在の文節までを確定し、残りの文節の変換を続けます。
- 通常の文節変換中の左右キーで、対象文節の読みの長さを1文字ずつ調整します。画面上のキーと物理キーボードに対応します。

カスタム配列・設定バックアップなどの個人データは、このリポジトリには含めません。

## アプリの識別情報

| 項目 | 値 |
| --- | --- |
| フル版・標準配布のアプリID | `com.kazumaproject.markdownhelperkeyboard.dev` |
| ソースのnamespace | `com.kazumaproject.markdownhelperkeyboard`（本家と同じ） |
| 表示名 | `スミレ 日本語キーボード` / `Sumire Japanese Keyboard`（本家と同じ） |
| バージョン | `1.7.139-fork` / versionCode `833` |
| 署名証明書のSHA-256 | `2179af3733bbfbe959a5d53196210880c6fb4a666bef5b3113e0f6cee1cf1c4e` |

旧カスタム版（versionCode 832）と同じアプリID・署名鍵です。Google Play版とは別のアプリとして共存し、旧カスタム版には更新としてインストールできます。Lite/F-Droid版は上流と同じ仕組みで、それぞれ `.lite` / `.fdroid` が追加されます。

## 署名鍵の扱い

既存APKの署名に使用した鍵を継続使用します。開発中に作成されたAndroid Debug証明書ですが、別の鍵に交換すると既存インストールへ上書き更新できなくなるため、ここでは変更しません。

秘密鍵・パスワード・Base64にした鍵はコミットしません。署名情報は環境変数、またはGitの管理対象外の `local.properties` から読み込みます。環境変数を優先し、指定する場合は次の4項目すべてを設定します。

```properties
KEYSTORE_FILE=/absolute/path/to/existing-fork-key.keystore
KEYSTORE_PASSWORD=<store password>
KEY_ALIAS=<key alias>
KEY_PASSWORD=<key password>
```

パスは絶対パス、またはリポジトリのルートからの相対パスです。Windowsの `local.properties` では `/` 区切りを使ってください。

この指定がある場合、DebugとReleaseの両方に同じ鍵を使用します。指定がないローカルDebugビルドはAndroid標準のdebug.keystoreを使用するため、新しいPCで生成された鍵では既存版に更新できません。既存版へ更新するAPKは、上記のSHA-256と一致することを必ず確認してください。

GitHub Actions用には、このForkの非公開Repository Secretsに以下を保存します。既存の `.github/workflows/android.yml` が参照する名前と同じです。Secretsの登録だけではビルドは実行されません。

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 既存鍵ファイルをBase64にした値 |
| `KEYSTORE_PASSWORD` | キーストアのパスワード |
| `KEY_ALIAS` | 署名する鍵の別名 |
| `KEY_PASSWORD` | 鍵のパスワード |

Secretsはバックアップとしてダウンロードできません。元の鍵ファイルを安全な場所にも保管してください。詳細は [GitHubのSecrets設定](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets) と [Androidの署名鍵管理](https://developer.android.com/studio/publish/app-signing) を参照してください。

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

2026-10-31に上記7クラス・61件のテスト成功（失敗・エラー・スキップなし）、フル版Debug APKのビルド成功を確認しました。APKのアプリID・versionCode・表示名と、既存版との署名証明書の一致も確認しています。今回の識別情報変更後の実機インストールは未確認です。

## ライセンス

本家の著作権表示・MITライセンスを維持します。辞書・ライブラリ・モデルについては、本家の `THIRD-PARTY-NOTICES.md` と `docs/licenses/` の条件を引き継ぎます。
