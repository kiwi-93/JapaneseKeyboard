# 本家更新の取り込みと通知

## 役割

- GitHub Actions: 本家 `KazumaProject/JapaneseKeyboard` の `dev` から、競合しない更新をこのForkの `custom` へ自動マージする。
- ビルド・テスト: マージ成功後にGitHub Actions上で回帰テストとFull Standard ARM64 APKのビルドを行う。
- APKの配布: Cloudflare R2とAccessを使い、GitHubログインで本人だけがダウンロードできる形にする。[配布手順](PRIVATE_DISTRIBUTION.md)を参照。

スケジュールは毎日07:17（日本時間、UTCでは前日の22:17）。GitHubの混雑で実行が遅れることがあります。Actionsの **Sync upstream source → Run workflow** から手動実行もできます。

## 取り込み処理

1. 本家 `dev` を取得し、取り込み済みか確認する。
2. 更新があればGitでマージする。`.github/workflows/` はForkの内容を維持し、本家の新しいワークフローも取り込まない。
3. ソースなどに競合があればマージを中止し、`custom` はそのまま維持する。
4. 競合がなければマージコミットを作成し、`custom` へ送信する。実行中に別の更新が入っていた場合は上書きしない。
5. 結果を「本家更新の通知」Issueに記録する。同じ本家コミットに対する同じ競合は繰り返し通知しない。

Gitの競合がないことや自動テストの成功は、実機ですべての操作が動作することを保証するものではありません。

同期ジョブは結果と取り込み後の完全なSHAを後続へ渡します。更新をマージした場合だけ、そのSHAを使ってビルドします。署名・R2配布は `SUMIRE_AUTO_PUBLISH` Variable が `true` の場合に限ります。未設定が初期状態で、Push後に自動でR2へ配布しません。更新なし・競合・同期失敗では自動ビルドしません。本家のワークフローは取り込まず、同期用と既存APKの手動R2アップロード用以外のワークフローは停止します。

手動実行で `verify_age_only` を選ぶと、同期・ビルド・署名・配布を行わず、age鍵ペアだけを検査します。`force_build` を同時に指定しても鍵検査だけです。手順は[配布手順](PRIVATE_DISTRIBUTION.md)を参照してください。

手動実行で `force_build` を選ぶと、更新なしでも現在の `custom` を再ビルドできます。`source_sha` に40文字のコミットSHAを指定すると、現在の `custom` の祖先に限ってそのコミットを使用します。指定先にはCI用のバージョン設定が必要で、導入前の古いコミットでは検証に失敗します。同期でマージが生じた場合は常にマージ後のSHAを使用します。以前に配布したコミットより古いSHAや、分岐したSHAで最新版を巻き戻すことはできません。バージョン番号は実行番号・再実行回数から生成し、同じコミットでも新しい実行や同期ジョブを含む再実行では増やします。失敗ジョブだけの再実行は、同期ジョブが選んだ元の番号を引き継ぎます。 ただし定期実行の全ジョブを再実行すると、既に同期済みなら通常はビルドされません。その場合は `force_build` を指定して手動実行します。古い実行を後から再実行した場合は、既に配布済みの番号により停止する場合があります。失敗した配布ジョブだけを再実行する場合は、元の暗号化Artifact（保持期間1日）が必要です。期限切れの場合は全ジョブを再実行してください。

自動・手動の配布には、Workflowに固定したレビュー済みコミットの配布ツールを使用し、マージされたソースのスクリプトは実行しません。配布ツール更新時はツールの変更を先にコミットし、その完全なSHAに両Workflowの参照を変更してください。新しいSHAはWorkflowより先にGitHubに存在する必要があります。必要な鍵の登録とAccessの確認は[配布手順](PRIVATE_DISTRIBUTION.md)を参照してください。

## メール通知

更新の取り込み成功・競合・実行失敗とビルド・配布の成功または失敗を、通知用Issue #1に追記します。最初のIssueは `kiwi-93` に割り当て、各通知でもメンションします。メールはGitHubの登録アドレスとアカウントの通知設定に従って配信されます。SMTPや別のメールサービスは使用しません。

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

## 検証と初回運用

Linuxの独立した作業領域でFull Standardのユニットテスト2,523件のうち2,519件が成功し、4件は明示的に有効化する性能・メモリ計測のためスキップされました。辞書の古い期待値とIMEのテスト初期化を更新し、スキン切り替え後に候補アイコンの色が残る既存の不具合も修正しています。Windowsの元作業領域では改行変換による辞書・ライセンスのハッシュ不一致や、長い一時パスによるRobolectricのSQLiteエラーを確認しています。

Workflowの構文、署名、暗号化、模擬R2テストはローカルで検証しました。実際のGitHub Actionsからの署名・Access確認・R2配布は本番を操作しないため未実行です。暗号化用の鍵を登録し、設定とコードを確認した後に運用してください。Workflowは全テストの失敗を無視せず、ビルド・配布を停止します。
