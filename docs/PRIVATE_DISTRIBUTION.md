# 個人用APKのR2配布

本家更新の自動マージ後、GitHub Actionsでビルド・署名・検証したAPKを既存のCloudflare R2バケット `apk-private-store` に配布します。従来どおりローカルで作成した署名付きAPKの手動アップロードも利用できます。配布ドメインは `apk.ctrlcv.dev` です。既存のCloudflare Access・GitHubログイン・許可ポリシーをそのまま利用し、GitHub Releasesへの公開やM720qの環境構築は行いません。

## 認証と公開範囲

- 既存のCloudflare AccessのGitHubログイン連携を再利用します。認証設定の新規作成・変更はしません。
- 配布処理の開始時と公開直前に、APK・チェックサム・build.jsonの各URLと `latest.json` が未ログインでは `ctrlcv.cloudflareaccess.com` のAccessログインへ転送されることを検証します。
- バケットの許可ユーザーや `r2.dev` の無効化は既存のCloudflare設定で管理します。今回のS3キーにはAccess管理権限がないため、これらの管理設定は書き換えません。
- 自動配布は既存のFork鍵で署名し、証明書を検証します。手動配布は既存APKを再署名せず、同じ証明書を検証します。

既存のテスト画像で確認済みの認証を利用します。ダウンロードを本人に限定するポリシーと、別の公開URLによる迂回がないことはCloudflare管理画面で維持してください。アップロード処理は、未ログインでもAPKに到達できる状態では停止します。

参考: [R2をAccessで保護する公式手順](https://developers.cloudflare.com/r2/tutorials/cloudflare-access/)、[GitHubログイン連携](https://developers.cloudflare.com/cloudflare-one/integrations/identity-providers/github/)

## GitHub ActionsのSecrets

このForkのRepository Secretsを使用します。既存のDNS専用トークンや、追加のCloudflare管理APIトークンは使用しません。

| Secret | 内容 |
| --- | --- |
| `R2_ACCESS_KEY_ID` | 対象バケットのオブジェクト読み書き用Access Key ID |
| `R2_SECRET_ACCESS_KEY` | 対応するSecret Access Key |
| `CF_ACCOUNT_ID` | R2バケットがあるCloudflare Account ID |

S3キーは対象バケットのオブジェクト読み書き・一覧取得に使用します。手動配布では署名鍵を使用しません。

### 自動ビルドに必要な追加設定

設定はリポジトリの **Settings → Secrets and variables → Actions** で、運用開始前に所有者が登録します。今回のローカル実装ではSecretsを登録・変更していません。

| 種別 | 名前 | 内容 |
| --- | --- | --- |
| Variable | `SUMIRE_AGE_RECIPIENT` | ageの公開鍵（`age1...`） |
| Secret | `SUMIRE_AGE_IDENTITY` | 対応するage秘密鍵（`AGE-SECRET-KEY-1...`） |
| Secret | `KEYSTORE_BASE64` | 既存Fork用keystoreファイルのBase64全体 |
| Secret | `KEYSTORE_PASSWORD` | keystoreのパスワード |
| Secret | `KEY_ALIAS` | 既存の署名鍵のエイリアス |
| Secret | `KEY_PASSWORD` | 署名鍵のパスワード |

[age公式リリース](https://github.com/FiloSottile/age/releases)を使い、安全なローカル環境で `age-keygen -o identity.txt` を実行し、表示される公開鍵をVariableへ、ファイル内の秘密鍵をSecretへ登録します。秘密鍵ファイルは安全に保管し、Gitへ追加しないでください。Keystoreは現在インストール済みアプリと同じものを使用します。 WindowsでKeystoreをBase64にして登録する場合は、PowerShellで次を実行し、クリップボードの内容を `KEYSTORE_BASE64` のSecret欄に貼り付けてください。パスを実際のファイルに変更し、履歴やリポジトリへ値を書き出さないでください。

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\path\to\fork.keystore')) | Set-Clipboard
```

貼り付け後はクリップボードを消去し、残りの3項目も既存Keystoreの値で登録してください。未署名APKは公開鍵で暗号化され、暗号文だけを1日保持のArtifactとして受け渡します。秘密鍵と署名鍵は署名ジョブだけで使用します。ビルド用ジョブは読み取り権限のみで、失敗通知も別のジョブから行います。

APKのversionCodeは `1000000 + 実行番号×1000 + 再実行回数×4 + 1`（ARM64）です。再実行回数250以上またはAndroidの上限を超える場合は停止します。同じコミットの再実行も別番号です。同一番号の異なるAPK、古い番号、配布済みコミットの子孫でないコミットは拒否します。更新対象の `latest.json` はETagを使う条件付き書き込みで、手動・自動の同時実行や外部からの更新による巻き戻しを防ぎます。手動・自動は共通の `private-r2-upload` グループで直列化します。自動版を配布した後は、それより小さい番号のローカルAPKは手動でも配布できません。ローカルビルドで `-PsumireVersionCode` と `-PsumireVersionSuffix` を指定する場合は、ABI分割による加算も含め、既存の最新版より大きい番号を使ってください。GitHubの待機枠は1件なので、複数の待機実行があると古い待機実行がキャンセルされる場合があります。

署名済みAPK・チェックサム・build.jsonをR2から読み戻して検証し、Accessへのリダイレクトを再確認してから `latest.json` を更新します。検証やアップロードの失敗時には途中のファイルが残る場合がありますが、最新版の更新には進みません。最後の条件付き書き込み中に通信が途切れた場合は、R2側で書き込み済みかを失敗通知だけでは断定できません。再実行前にAccess認証後の `latest.json` を確認してください。 また、最新版を更新した後に通知Issueへの投稿だけが失敗した場合も、Actionsは失敗表示になりますがAPKは配布済みです。自動実行の成否は通知Issue #1に記録されます。

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

PCからR2へ3ファイルを直接送信した後、ActionsがR2上の全ファイルを読み直してSHA-256を検証し、成功時だけ `latest.json` を更新します。古いversionCode・異なるAPKによる同一versionCode・配布済みコミットからの巻き戻しを拒否します。ビルド・テスト・Release公開は実行しません。

```text
https://apk.ctrlcv.dev/builds/<versionCode>-<commit>-<request-id>/sumire.apk
https://apk.ctrlcv.dev/builds/<versionCode>-<commit>-<request-id>/sumire.apk.sha256
https://apk.ctrlcv.dev/builds/<versionCode>-<commit>-<request-id>/build.json
https://apk.ctrlcv.dev/latest.json
```

これらのURLは既存のGitHubログインとAccessの許可ポリシーで保護します。R2のドメイン直下はファイル一覧を返さないため、APKの完全なURLを使ってください。配布結果は準備フォルダーの `upload-result.json` にも保存します。最後にブラウザーでGitHub認証後のAPKダウンロードを確認してください。

## 配布処理のテスト

```sh
node --test tools/private-distribution/*.test.cjs
```

APK識別情報とファイル名、暗号化したアップロード権限、期限と送信先の検証、Accessで保護されていない配布パスの拒否をテストします。初回の実行では、既存バケット・Secretsを使った送信と、R2側のチェックサム検証も行います。
