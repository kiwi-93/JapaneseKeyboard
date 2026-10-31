"""Merge upstream source without building or importing upstream workflow changes."""

import argparse
import hashlib
import json
import os
import subprocess
import sys

REPOSITORY = "kiwi-93/JapaneseKeyboard"
UPSTREAM = "https://github.com/KazumaProject/JapaneseKeyboard.git"
UPSTREAM_REF = "refs/remotes/sumire-upstream/dev"
NOTICE_MARKER = "<!-- sumire-upstream-sync-notifications -->"


def command(*args, cwd=None, check=True, stdin=None):
    result = subprocess.run(
        args, cwd=cwd, input=stdin, text=True, encoding="utf-8",
        errors="replace", capture_output=True, timeout=240,
    )
    if check and result.returncode:
        raise RuntimeError(f"{args[0]} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result


def git(repo, *args, check=True):
    return command("git", *args, cwd=repo, check=check)


def merge_source(repo, upstream_ref):
    """Return status, original HEAD, new HEAD and conflicts; never force a merge."""
    before = git(repo, "rev-parse", "HEAD").stdout.strip()
    if git(repo, "status", "--porcelain").stdout.strip():
        raise RuntimeError("The checkout must be clean before syncing.")
    ancestor = git(repo, "merge-base", "--is-ancestor", upstream_ref, "HEAD", check=False)
    if ancestor.returncode == 0:
        return "unchanged", before, before, []
    if ancestor.returncode != 1:
        raise RuntimeError(ancestor.stderr.strip())

    merging = git(repo, "merge", "--no-ff", "--no-commit", upstream_ref, check=False)
    try:
        # Preserve the complete workflow directory, including removing new upstream
        # workflows. Source merges must not reintroduce CI or change this schedule.
        git(repo, "restore", f"--source={before}", "--staged", "--worktree",
            "--", ".github/workflows")
        conflicts = git(repo, "diff", "--name-only", "--diff-filter=U", "-z").stdout
        conflicts = [name for name in conflicts.split("\0") if name]
        if conflicts:
            git(repo, "merge", "--abort")
            return "conflict", before, before, conflicts
        if merging.returncode not in (0, 1):
            raise RuntimeError(merging.stderr.strip() or merging.stdout.strip())
        # Even a workflows-only upstream change gets a merge commit, recording its
        # ancestry without changing any workflow file in the fork.
        upstream_sha = git(repo, "rev-parse", upstream_ref).stdout.strip()
        git(repo, "commit", "-m", f"Merge upstream dev at {upstream_sha[:12]} (source only)")
        after = git(repo, "rev-parse", "HEAD").stdout.strip()
        return "merged", before, after, []
    except Exception:
        git(repo, "merge", "--abort", check=False)
        raise


def api(path, method="GET", data=None):
    args = ["gh", "api", path, "--method", method]
    if data is not None:
        args += ["--input", "-"]
    output = command(*args, stdin=json.dumps(data) if data is not None else None).stdout
    return json.loads(output) if output.strip() else None


def pages(path):
    separator = "&" if "?" in path else "?"
    page = 1
    while True:
        entries = api(f"{path}{separator}per_page=100&page={page}")
        yield from entries
        if len(entries) < 100:
            return
        page += 1


def disable_other_workflows():
    workflows = api(f"repos/{REPOSITORY}/actions/workflows?per_page=100")["workflows"]
    for workflow in workflows:
        if (workflow["path"] != ".github/workflows/sync-upstream.yml"
                and workflow["state"] == "active"):
            api(f"repos/{REPOSITORY}/actions/workflows/{workflow['id']}/disable", "PUT")


def notify(status, upstream_sha, details):
    # Repeated conflicts against the same upstream commit produce one notification.
    key = upstream_sha if status != "failed" else hashlib.sha256(details.encode()).hexdigest()
    marker = f"<!-- sumire-sync:{status}:{key} -->"
    issue = next((item for item in pages(f"repos/{REPOSITORY}/issues?state=all")
                  if item.get("user", {}).get("login") == "github-actions[bot]"
                  and NOTICE_MARKER in (item.get("body") or "")), None)
    if issue:
        bodies = [issue.get("body") or ""]
        bodies += [item.get("body") or "" for item in pages(
            f"repos/{REPOSITORY}/issues/{issue['number']}/comments")]
        if any(marker in body for body in bodies):
            print("This result has already been notified.")
            return

    title = {"merged": "本家の更新を取り込みました", "conflict": "本家更新の取り込みが競合しました",
             "failed": "本家更新の確認・取り込みに失敗しました"}[status]
    run = f"https://github.com/{REPOSITORY}/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}"
    body = f"{marker}\n### {title}\n\n@kiwi-93\n\n{details.replace('@', '＠')}\n\n実行ログ: {run}\n\n"
    body += "APKのビルド・テスト・公開は実行していません。\n"
    if issue:
        api(f"repos/{REPOSITORY}/issues/{issue['number']}/comments", "POST", {"body": body})
    else:
        api(f"repos/{REPOSITORY}/issues", "POST", {
            "title": "本家更新の通知", "assignees": ["kiwi-93"],
            "body": NOTICE_MARKER + "\n本家更新の結果をこのIssueに記録します。更新なしの日は通知しません。\n\n" + body,
        })


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check-only", action="store_true",
                        help="Check in a disposable checkout; do not push or notify.")
    args = parser.parse_args()
    if not args.check_only and os.environ.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise RuntimeError("Publishing sync results is restricted to the configured fork workflow.")
    repo = os.getcwd()
    upstream_sha = "unknown"
    try:
        if not args.check_only:
            disable_other_workflows()
            git(repo, "config", "user.name", "github-actions[bot]")
            git(repo, "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
        git(repo, "fetch", "--filter=blob:none", "--no-tags", UPSTREAM,
            f"dev:{UPSTREAM_REF}")
        upstream_sha = git(repo, "rev-parse", UPSTREAM_REF).stdout.strip()
        status, before, after, conflicts = merge_source(repo, UPSTREAM_REF)
        if status == "unchanged":
            print("No upstream updates. No notification sent.")
            return 0
        if status == "conflict":
            details = f"本家 dev: `{upstream_sha}`\n\n競合のため custom は変更していません。\n\n競合ファイル:\n"
            details += "\n".join("- " + name.replace("`", "") for name in conflicts)
        else:
            if not args.check_only:
                # A concurrent edit rejects this push; never overwrite it with --force.
                git(repo, "push", "origin", "HEAD:refs/heads/custom")
            details = (f"本家 dev: `{upstream_sha}`\n\n取り込み後: `{after}`\n\n"
                       f"変更一覧: https://github.com/{REPOSITORY}/compare/{before}...{after}")
        print(status + "\n" + details)
        if not args.check_only:
            notify(status, upstream_sha, details)
        return 1 if status == "conflict" else 0
    except Exception as error:
        if not args.check_only:
            try:
                notify("failed", upstream_sha, str(error)[:2000])
            except Exception as notification_error:
                print(f"Notification failed: {notification_error}", file=sys.stderr)
        raise


if __name__ == "__main__":
    sys.exit(main())
