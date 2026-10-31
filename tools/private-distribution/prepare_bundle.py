"""Verify an existing APK and prepare upload files; never builds or signs an APK."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

APP_ID = "dev.ctrlcv.markdownhelperkeyboard"
CERTIFICATE = "2179af3733bbfbe959a5d53196210880c6fb4a666bef5b3113e0f6cee1cf1c4e"


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(*arguments):
    return subprocess.run(arguments, check=True, capture_output=True, text=True,
                          encoding="utf-8", errors="replace").stdout


def prepare(apk, build_tools, java, commit, destination):
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("A full source commit SHA is required.")
    tools = Path(build_tools)
    aapt = tools / ("aapt.exe" if (tools / "aapt.exe").exists() else "aapt")
    metadata = run(str(aapt), "dump", "badging", str(apk))
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", metadata)
    if not match or match[1] != APP_ID:
        raise ValueError("The APK has an unexpected application ID.")
    certificates = run(str(java), "-jar", str(tools / "lib/apksigner.jar"),
                       "verify", "--print-certs", str(apk))
    if re.findall(r"certificate SHA-256 digest: ([a-f0-9]+)", certificates) != [CERTIFICATE]:
        raise ValueError("The APK signing certificate does not match the fork key.")
    apk_hash = digest(apk)
    record = {
        "application_id": APP_ID, "version_code": int(match[2]), "version_name": match[3],
        "certificate_sha256": CERTIFICATE, "apk_sha256": apk_hash,
        "size_bytes": Path(apk).stat().st_size, "source_commit": commit,
        "prepared_at": datetime.now(timezone.utc).isoformat(),
    }
    output = Path(destination)
    output.mkdir(parents=True, exist_ok=True)
    existing = output / "sumire.apk"
    if existing.exists() and digest(existing) != apk_hash:
        raise ValueError("The destination contains a different APK; choose a new directory.")
    shutil.copyfile(apk, existing)
    (output / "sumire.apk.sha256").write_text(f"{apk_hash}  sumire.apk\n", encoding="ascii")
    (output / "build.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {APP_ID} {record['version_name']} ({record['version_code']}).")
    print(f"APK SHA-256: {apk_hash}")
    return record


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--build-tools", required=True, type=Path)
    parser.add_argument("--java", required=True, type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    prepare(args.apk, args.build_tools, args.java, args.commit, args.output)
