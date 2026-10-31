# 個人用APKのR2配布

ビルド方法は変更しません。現在の手順で作成した署名付きAPKを、Cloudflare R2の `sumire-apk-private` に手動でアップロードし、`apk.ctrlcv.dev` をCloudflare Accessで保護します。GitHub Releasesへの公開や、M720qへのビルド環境構築は行いません。

## 認証と公開範囲

- 既存のCloudflare AccessのGitHubログイン連携を再利用します。新しいGitHub OAuthアプリやメールのワンタイムコード認証は作成しません。
- AccessアプリはGitHub連携だけをログイン方法として使い、ポリシーでは本人のGitHub登録メールとそのGitHub連携の両方を必須にします。メールは許可判定に使い、ログイン操作はGitHubで行います。
- Accessアプリと本人限定ポリシーを確認した後でR2のカスタムドメインを接続します。
- `r2.dev` の公開URLを無効にします。他の有効なカスタムドメインがある場合も、保護を迂回できるためアップロードを中止します。
- 未ログインのアクセスがAccessのログイン画面へ転送されることを確認するまで、APKはアップロードしません。

既存のAccessアプリ・ポリシーが上記の条件と異なる場合は、自動では上書きせず停止します。初回のCloudflare API認証と、実環境での動作確認が必要です。

参考: [R2をAccessで保護する公式手順](https://developers.cloudflare.com/r2/tutorials/cloudflare-access/)、[GitHubログイン連携](https://developers.cloudflare.com/cloudflare-one/integrations/identity-providers/github/)

## Cloudflare APIの認証

`CLOUDFLARE_API_TOKEN` 環境変数で認証します。トークンの値はチャット、リポジトリ、コマンド引数へ書き込まないでください。既存のDNS専用トークンは、そのまま維持します。

対象アカウントと `ctrlcv.dev` ゾーンに対し、以下を扱えるトークンが必要です。

| リソース | 権限 |
| --- | --- |
| Account | Workers R2 Storage: Edit |
| Account | Access: Apps and Policies: Edit |
| Account | Access: Organizations, Identity Providers, and Groups: Read |
| Zone (`ctrlcv.dev`) | Zone: Read |

R2の有効化と、Zero Trust組織・GitHub連携の設定は完了している必要があります。トークン権限やR2の契約状態に不足がある場合はエラーで停止します。

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

## R2へ配置する

既存のGitHub連携が1つであることを確認して再利用します。本人のGitHub登録メールはプライベートな設定として指定してください。

```sh
python3 tools/private-distribution/publish_r2.py \
  --bundle /private/path/to/upload-bundle \
  --email <本人のGitHub登録メール>
```

アップロードにはNode.js/npmとWrangler `4.156.0` を使用します。保存先はAPKのversionCode・ソースコミット・SHA-256を含む固定パスです。バージョン別の3ファイルが揃った後にだけ `latest.json` を更新します。

```text
https://apk.ctrlcv.dev/builds/<versionCode>-<commit>-<sha256>/sumire.apk
https://apk.ctrlcv.dev/builds/<versionCode>-<commit>-<sha256>/sumire.apk.sha256
https://apk.ctrlcv.dev/builds/<versionCode>-<commit>-<sha256>/build.json
https://apk.ctrlcv.dev/latest.json
```

これらのURLはGitHubログインと本人限定ポリシーで保護します。R2のドメイン直下はファイル一覧を返さないため、APKの完全なURLを使ってください。アップロード成功だけでは本人のログイン・ダウンロード確認までは完了しないため、最後にブラウザーで実際のAPKをダウンロードして確認します。

## 配布処理のテスト

```sh
python3 -m unittest discover -s tools/private-distribution -p 'test_*.py' -v
```

本人以外を許可するポリシー、GitHub以外のログイン、`r2.dev` などの迂回経路、準備後に書き換えられたAPKを拒否することを検証します。
