# 開発ガイド

この Fork は独自のアプリ ID `dev.ctrlcv.markdownhelperkeyboard` を使用します。

## ビルド

Gradle wrapper、JBR 25、Android API 37、NDK 29.0.14206865 を使用します。

```sh
git submodule update --init --recursive
./gradlew :app:assembleFullStandardDebug
```

APK は `app/build/outputs/apk/fullStandard/debug/` に生成されます。
署名を指定する場合は `KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` を環境変数または管理対象外の `local.properties` に設定してください。継続して更新をインストールするには同じ署名鍵を使用します。

## テスト

```sh
./gradlew :app:testFullStandardDebugUnitTest :custom_keyboard:testDebugUnitTest
```

## 開発用 Workflow

- [上流同期](UPSTREAM_SYNC.md)
- [配布 Workflow](PRIVATE_DISTRIBUTION.md)
