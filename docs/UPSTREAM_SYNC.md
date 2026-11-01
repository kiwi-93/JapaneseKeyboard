# 上流同期 Workflow

`sync-upstream.yml` は上流の `dev` を `custom` にマージし、競合がある場合は中止します。Fork の Workflow 定義は維持します。定期実行は毎日 07:17 JST です。

`custom` へのアプリ・ビルド設定・関連スクリプトの Push でも起動し、そのコミットをビルドします。文書や Workflow 定義だけの変更は対象外です。

上流同期ではマージ後のコミットを同じ実行内でビルドします。同期処理自身の Push は別の実行を起こさないため、二重ビルドにはなりません。定期確認で上流更新がなければビルドを省略します。

手動実行のオプション:

- `verify_age_only`: 鍵ペアの整合性だけを確認
- `force_build`: 更新がなくてもビルド
- `source_sha`: 強制ビルドするコミット（`custom` の祖先に限定）

自動配布には `SUMIRE_AUTO_PUBLISH=true` が必要です。未設定ではビルドまでで停止します。配布設定は [PRIVATE_DISTRIBUTION.md](PRIVATE_DISTRIBUTION.md) を参照してください。

同期とビルド・配布の結果は通知用 Issue に記録します。メールの受信は GitHub アカウントの通知設定に従います。
