import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("sync_upstream", Path(__file__).with_name("sync_upstream.py"))
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class MergeSourceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="sumire-sync-test-")
        self.repo = Path(self.temporary.name).resolve()
        assert self.repo.parent == Path(tempfile.gettempdir()).resolve()
        assert self.repo.name.startswith("sumire-sync-test-")
        self.addCleanup(self.temporary.cleanup)
        self.git("init", "-b", "custom")
        self.git("config", "user.name", "Sync Test")
        self.git("config", "user.email", "sync-test@example.invalid")
        self.write(".github/workflows/build.yml", "original workflow\n")
        self.write(".github/workflows/sync-upstream.yml", "fork sync workflow\n")
        self.write("app.txt", "base\n")
        self.commit("base")
        self.git("branch", "upstream")
        self.write("fork.txt", "custom behavior\n")
        self.commit("fork changes")
        self.before = self.git("rev-parse", "HEAD")

    def git(self, *args):
        return sync.git(self.repo, *args).stdout.strip()

    def write(self, path, text):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def commit(self, message):
        self.git("add", ".")
        self.git("commit", "-m", message)

    def upstream_change(self, path, text):
        self.git("switch", "upstream")
        self.write(path, text)
        self.commit("upstream change")
        self.git("switch", "custom")

    def test_no_update_keeps_head_and_worktree(self):
        self.assertEqual("unchanged", sync.merge_source(self.repo, "upstream")[0])
        self.assertEqual(self.before, self.git("rev-parse", "HEAD"))
        self.assertEqual("", self.git("status", "--porcelain"))

    def test_source_merge_preserves_customizations_and_has_both_parents(self):
        self.upstream_change("app.txt", "updated source\n")
        upstream = self.git("rev-parse", "upstream")
        result = sync.merge_source(self.repo, "upstream")
        self.assertEqual("merged", result[0])
        self.assertEqual("updated source\n", (self.repo / "app.txt").read_text())
        self.assertEqual("custom behavior\n", (self.repo / "fork.txt").read_text())
        self.assertEqual([self.before, upstream], self.git("show", "-s", "--format=%P").split())

    def test_conflict_aborts_without_changing_fork(self):
        self.write("app.txt", "fork source\n")
        self.commit("custom source")
        before = self.git("rev-parse", "HEAD")
        self.upstream_change("app.txt", "upstream source\n")
        result = sync.merge_source(self.repo, "upstream")
        self.assertEqual("conflict", result[0])
        self.assertEqual(["app.txt"], result[3])
        self.assertEqual(before, self.git("rev-parse", "HEAD"))
        self.assertEqual("fork source\n", (self.repo / "app.txt").read_text())
        self.assertEqual("", self.git("status", "--porcelain"))

    def test_workflow_changes_are_not_imported(self):
        self.git("switch", "upstream")
        self.write(".github/workflows/build.yml", "new upstream build\n")
        self.write(".github/workflows/added.yml", "new automatic build\n")
        self.write("app.txt", "updated source\n")
        self.commit("upstream source and workflows")
        self.git("switch", "custom")
        self.assertEqual("merged", sync.merge_source(self.repo, "upstream")[0])
        self.assertEqual("", self.git("diff", self.before, "HEAD", "--", ".github/workflows"))
        self.assertFalse((self.repo / ".github/workflows/added.yml").exists())
        self.assertEqual("updated source\n", (self.repo / "app.txt").read_text())

    def test_workflow_conflict_is_resolved_with_fork_workflow(self):
        self.write(".github/workflows/build.yml", "fork disabled build\n")
        self.commit("fork workflow")
        self.upstream_change(".github/workflows/build.yml", "upstream build\n")
        self.assertEqual("merged", sync.merge_source(self.repo, "upstream")[0])
        self.assertEqual("fork disabled build\n", (self.repo / ".github/workflows/build.yml").read_text())
        self.assertEqual("unchanged", sync.merge_source(self.repo, "upstream")[0])

    def test_dirty_checkout_is_rejected(self):
        self.write("app.txt", "uncommitted user work\n")
        with self.assertRaisesRegex(RuntimeError, "clean"):
            sync.merge_source(self.repo, "upstream")
        self.assertEqual("uncommitted user work\n", (self.repo / "app.txt").read_text())


class NotificationTest(unittest.TestCase):
    def test_manual_upload_is_kept_enabled_and_android_build_is_disabled(self):
        workflows = {"workflows": [
            {"id": 1, "path": ".github/workflows/sync-upstream.yml", "state": "active"},
            {"id": 2, "path": ".github/workflows/r2-upload.yml", "state": "active"},
            {"id": 3, "path": ".github/workflows/android.yml", "state": "active"},
        ]}
        with patch.object(sync, "api", side_effect=[workflows, None]) as api:
            sync.disable_other_workflows()
            self.assertEqual(2, api.call_count)
            self.assertEqual("PUT", api.call_args.args[1])
            self.assertTrue(api.call_args.args[0].endswith("/3/disable"))

    def test_repeated_conflict_does_not_send_a_comment(self):
        issue = {"number": 1, "user": {"login": "github-actions[bot]"},
                 "body": sync.NOTICE_MARKER + "\n<!-- sumire-sync:conflict:abc -->"}
        with patch.object(sync, "pages", side_effect=[[issue], []]), patch.object(sync, "api") as api:
            sync.notify("conflict", "abc", "same conflict")
            api.assert_not_called()

    def test_new_result_creates_one_assigned_notification_issue(self):
        with patch.object(sync, "pages", return_value=iter([])), patch.object(sync, "api") as api:
            sync.notify("merged", "abc", "updated source")
            data = api.call_args.args[2]
            self.assertEqual(["kiwi-93"], data["assignees"])
            self.assertIn(sync.NOTICE_MARKER, data["body"])
            self.assertIn("<!-- sumire-sync:merged:abc -->", data["body"])


if __name__ == "__main__":
    unittest.main()
