# 個人用APKのR2配布

ビルド方法は変更しません。現在の手順で作成した署名付きAPKを、既存のCloudflare R2バケット `apk-private-store` に手動でアップロードします。配布ドメインは `apk.ctrlcv.dev` です。既存のCloudflare Access・GitHubログイン・許可ポリシーをそのまま利用し、GitHub Releasesへの公開やM720qの環境構築は行いません。

## 認証と公開範囲

- 既存のCloudflare AccessのGitHubログイン連携を再利用します。認証設定の新規作成・変更はしません。
- 配布処理の開始時と公開直前に、APKのURLと `latest.json` が未ログインでは `ctrlcv.cloudflareaccess.com` のAccessログインへ転送されることを検証します。
- バケットの許可ユーザーや `r2.dev` の無効化は既存のCloudflare設定で管理します。今回のS3キーにはAccess管理権限がないため、これらの管理設定は書き換えません。
- APKの再署名はせず、既存のFork用署名証明書に一致するAPKだけを準備します。

既存のテスト画像で確認済みの認証を利用します。ダウンロードを本人に限定するポリシーと、別の公開URLによる迂回がないことはCloudflare管理画面で維持してください。アップロード処理は、未ログインでもAPKに到達できる状態では停止します。

参考: [R2をAccessで保護する公式手順](https://developers.cloudflare.com/r2/tutorials/cloudflare-access/)、[GitHubログイン連携](https://developers.cloudflare.com/cloudflare-one/integrations/identity-providers/github/)

## GitHub ActionsのSecrets

このForkのRepository Secretsを使用します。既存のDNS専用トークンや、追加のCloudflare管理APIトークンは使用しません。

| Secret | 内容 |
| --- | --- |
| `R2_ACCESS_KEY_ID` | 対象バケットのオブジェクト読み書き用Access Key ID |
| `R2_SECRET_ACCESS_KEY` | 対応するSecret Access Key |
| `CF_ACCOUNT_ID` | R2バケットがあるCloudflare Account ID |

S3キーは対象バケットのオブジェクト読み書き・一覧取得に使用します。署名鍵のSecretsは配布処理では使用しません。

## 既存APKを検証・準備する

Python 3.11以降（配布時はHTTPS対応が必要）、Java、Android SDKのBuild Toolsを使用します。準備処理はビルドや再署名を行いません。

```sh
python3 tools/private-distribution/prepare_bundle.py \
  --apk /path/to/sumire-dev-debug.apk \
  --build-tools /path/to/android-sdk/build-tools/36.0.0 \
  --java /path/to/java \
  --commit <APKを作成したソースの40文字のコミットSHA> \
  --output /private/path/to/upload-bundle
```

アプリID、署名証明書、APKのSHA-256を検証し、`sumire.apk`、`sumire.apk.sha256`、`build.json` を準備します。GitHubにコミットするのは配布用スクリプトと説明書だけです。

## ビルド済みAPKをR2へ配置する

Node.js 24以降と、ForkのActionsを手動実行できるGitHub CLI認証を使用します。R2の永続的なSecret Access KeyをPCにコピーする必要はありません。

```sh
node tools/private-distribution/upload_existing.cjs --bundle /private/path/to/upload-bundle
```

このコマンドがアップロード専用の手動Actionsを起動します。Actionsは3ファイルだけに有効な15分間のアップロード権限を作り、PCごとに生成した一時公開鍵で暗号化して返します。公開リポジトリのArtifactにも、平文のアップロードURLやAPKは保存しません。一時秘密鍵はPCのプロセス内だけで使用します。

PCからR2へ3ファイルを直接送信した後、ActionsがR2上の全ファイルを読み直してSHA-256を検証し、成功時だけ `latest.json` を更新します。古いversionCodeへの最新版の巻き戻しは拒否します。ビルド・テスト・Release公開は実行しません。

```text
https://apk.ctrlcv.dev/builds/<versionCode>-<commit>-<request-id>/sumire.apk
https://apk.ctrlcv.dev/builds/<versionCode>-<commit>-<request-id>/sumire.apk.sha256
https://apk.ctrlcv.dev/builds/<versionCode>-<commit>-<request-id>/build.json
https://apk.ctrlcv.dev/latest.json
```

これらのURLは既存のGitHubログインとAccessの許可ポリシーで保護します。R2のドメイン直下はファイル一覧を返さないため、APKの完全なURLを使ってください。配布結果は準備フォルダーの `upload-result.json` にも保存します。最後にブラウザーでGitHub認証後のAPKダウンロードを確認してください。

## 配布処理のテスト

```sh
node --test tools/private-distribution/r2_protocol.test.cjs
```

APK識別情報とファイル名、暗号化したアップロード権限、期限と送信先の検証、Accessで保護されていない配布パスの拒否をテストします。初回の実行では、既存バケット・Secretsを使った送信と、R2側のチェックサム検証も行います。
