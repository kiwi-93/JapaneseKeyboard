# 配布 Workflow

`sync-upstream.yml` は未署名 APK を age で暗号化して署名ジョブへ渡します。署名・配布ジョブは固定したコミットの配布ツールを使い、アプリ ID・証明書・バージョン・チェックサムを検証します。

## 設定

- `SUMIRE_AGE_RECIPIENT`: age 公開鍵（Variable または Secret）
- `SUMIRE_AGE_IDENTITY`: 対応する秘密鍵（Secret）
- `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`: APK 署名用 Secrets
- `CF_ACCOUNT_ID`、`R2_ACCESS_KEY_ID`、`R2_SECRET_ACCESS_KEY`: 配布先の Secrets

ビルドジョブへ渡す鍵は公開鍵だけです。配布先と検証する証明書は `tools/private-distribution/r2_protocol.cjs` に定義しています。別の環境で利用する場合は変更し、配布 Workflow が参照するツールのコミット SHA も更新してください。

## 初回確認

1. `verify_age_only` を有効にして手動実行し、鍵ペアの整合性を確認します。このモードはビルド・署名・配布を行いません。
2. ビルドだけを確認する場合は `SUMIRE_AUTO_PUBLISH` を未設定にして `force_build` を実行します。
3. 配布を開始する場合に限り `SUMIRE_AUTO_PUBLISH` Variable を `true` にします。以後の正常なビルドは実際に配布されます。

`r2-upload.yml` は既存 APK の手動配布用です。上記 Variable による停止対象には含まれません。

## 更新の検証

APK・チェックサム・メタデータをアップロードして読み戻し、Access 保護を確認した後に `latest.json` を更新します。自動・手動配布は共通の同時実行制御を使い、古い版や同一番号の別 APK による上書きを拒否します。

最終書き込み中の通信断や通知失敗では、配布済みでも Workflow が失敗表示になる場合があります。再実行前に `latest.json` を確認してください。
