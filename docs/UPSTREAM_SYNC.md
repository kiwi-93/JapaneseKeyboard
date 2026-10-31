# 本家更新の取り込みと通知

## 役割

- GitHub Actions: 本家 `KazumaProject/JapaneseKeyboard` の `dev` から、競合しない更新をこのForkの `custom` へ自動マージする。
- ビルド・テスト: 現在のローカル環境・手順を継続する。
- APKの配布: Cloudflare R2とAccessを使い、GitHubログインで本人だけがダウンロードできる形にする。[配布手順](PRIVATE_DISTRIBUTION.md)を参照。

スケジュールは毎日07:17（日本時間、UTCでは前日の22:17）。GitHubの混雑で実行が遅れることがあります。Actionsの **Sync upstream source → Run workflow** から手動実行もできます。

## 取り込み処理

1. 本家 `dev` を取得し、取り込み済みか確認する。
2. 更新があればGitでマージする。`.github/workflows/` はForkの内容を維持し、本家の新しいワークフローも取り込まない。
3. ソースなどに競合があればマージを中止し、`custom` はそのまま維持する。
4. 競合がなければマージコミットを作成し、`custom` へ送信する。実行中に別の更新が入っていた場合は上書きしない。
5. 結果を「本家更新の通知」Issueに記録する。同じ本家コミットに対する同じ競合は繰り返し通知しない。

Gitの競合がないことは、アプリの動作を保証するものではありません。更新後のビルドとテストはローカルで実施してください。特にアプリID・署名設定・バージョン・変換処理の競合は確認が必要です。

既存のビルド・公開・回帰テスト用ワークフローは無効にしています。同期処理の開始時にも、同期用と既存APKの手動R2アップロード用以外のワークフローが有効になっていれば停止します。Android SDKのインストール、Gradleの実行、署名鍵の使用、APKの生成、Releaseの公開は同期処理に含みません。R2への配置は自動同期とは別の手動処理です。

## メール通知

更新の取り込み成功・競合・実行失敗を、1つの通知用Issueに追記します。最初のIssueは `kiwi-93` に割り当て、各通知でもメンションします。メールはGitHubの登録アドレスとアカウントの通知設定に従って配信されます。SMTPや別のメールサービスは使用しません。

[GitHubの通知設定](https://github.com/settings/notifications)で、次を有効にしてください。

- **Participating / Participating, @mentions and custom** のメール通知: Issueへの割り当て・メンション・返信をメールで受け取る。
- **System → Actions → Email**: 通知Issueの作成まで到達できない実行失敗も、Actionsの通知対象にする。こちらは失敗時のみの通知にすると更新なしの日のメールを減らせます。

アカウント側の通知設定はリポジトリの設定とは別です。Issueの作成・メンションだけで、無効になっているメール通知を有効にはできません。詳細は [GitHub Actionsの通知設定](https://docs.github.com/en/subscriptions-and-notifications/how-tos/managing-github-actions-notifications) を参照してください。

公開リポジトリでは、60日間活動がないと定期ワークフローが停止することがあります。その場合はActions画面から **Enable workflow** で再開してください。[GitHubの説明](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/disable-and-enable-workflows)

## ローカルで同期処理を検証する

```sh
python3 -m unittest discover -s tools/fork-sync -p 'test_*.py' -v
```

実際の本家との差分を試す場合は、使い捨てのクリーンなチェックアウトで次を実行します。作業用のマージコミットができる場合がありますが、GitHubへの送信・Issue通知は行いません。

```sh
python3 /path/to/sync_upstream.py --check-only
```
