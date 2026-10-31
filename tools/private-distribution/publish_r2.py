"""Set up Access-protected R2 distribution and upload an already verified APK."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

from prepare_bundle import APP_ID, CERTIFICATE, digest

DOMAIN = "apk.ctrlcv.dev"
ZONE = "ctrlcv.dev"
BUCKET = "sumire-apk-private"
WRANGLER = "wrangler@4.156.0"


class Cloudflare:
    def __init__(self, token):
        self.token = token

    def request(self, path, method="GET", body=None):
        request = urllib.request.Request(
            "https://api.cloudflare.com/client/v4/" + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={"Authorization": "Bearer " + self.token, "Content-Type": "application/json"},
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            # Never print credentials or complete request headers.
            raise RuntimeError(f"Cloudflare API returned HTTP {error.code} for {path}.") from None
        if not result.get("success"):
            codes = [item.get("code") for item in result.get("errors", [])]
            raise RuntimeError(f"Cloudflare API rejected {path}; error codes: {codes}")
        return result["result"]

    def all(self, path):
        results = []
        for page in range(1, 101):
            separator = "&" if "?" in path else "?"
            items = self.request(f"{path}{separator}per_page=50&page={page}")
            results.extend(items)
            if len(items) < 50:
                return results
        raise RuntimeError("Too many Cloudflare results; refusing an incomplete policy check.")


def validate_owner_policy(policies, email, github_id):
    if len(policies) != 1:
        raise ValueError("Access must have exactly one owner-only Allow policy.")
    policy = policies[0]
    if (policy.get("decision") != "allow" or policy.get("exclude")
            or policy.get("require") != [{"login_method": {"id": github_id}}]
            or policy.get("include") != [{"email": {"email": email}}]):
        raise ValueError("The existing Access policy differs from the requested owner-only policy.")


def validate_bucket_access(managed, domains):
    if managed.get("enabled") is not False:
        raise ValueError("The r2.dev public URL must be disabled.")
    if any(item.get("enabled") and item.get("domain") != DOMAIN for item in domains):
        raise ValueError("The bucket has another enabled custom domain that could bypass Access.")


def existing_github_provider(providers):
    github = [provider for provider in providers if provider.get("type") == "github"]
    if len(github) != 1:
        raise ValueError("Exactly one existing GitHub identity provider is required; no new provider was created.")
    return github[0]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


def protected_redirect(location, auth_domain):
    parsed = urllib.parse.urlsplit(location)
    return (parsed.scheme == "https" and parsed.hostname == auth_domain
            and parsed.path.startswith("/cdn-cgi/access/login/"))


def verify_unauthenticated_access(auth_domain):
    opener = urllib.request.build_opener(NoRedirect)
    last_error = None
    for attempt in range(20):
        try:
            response = opener.open(f"https://{DOMAIN}/.sumire-access-check", timeout=20)
            response.close()
            raise ValueError("Unauthenticated access was not redirected to Cloudflare Access.")
        except urllib.error.HTTPError as error:
            if error.code in (302, 303, 307) and protected_redirect(error.headers.get("Location", ""), auth_domain):
                print("Unauthenticated requests are redirected to Cloudflare Access.", flush=True)
                return
            last_error = f"HTTP {error.code}"
        except urllib.error.URLError:
            last_error = "DNS or TLS is not ready"
        print(f"Waiting for the protected domain: {last_error} ({attempt + 1}/20)", flush=True)
        time.sleep(15)
    raise RuntimeError("The protected domain is not ready. No APK was uploaded.")


def validate_bundle(directory):
    root = Path(directory)
    record = json.loads((root / "build.json").read_text(encoding="utf-8"))
    if record.get("application_id") != APP_ID or record.get("certificate_sha256") != CERTIFICATE:
        raise ValueError("Unexpected application or signing certificate in the prepared bundle.")
    if not re.fullmatch(r"[a-f0-9]{40}", record.get("source_commit", "")):
        raise ValueError("Invalid source commit.")
    if not isinstance(record.get("version_code"), int) or record["version_code"] < 1:
        raise ValueError("Invalid version code.")
    apk_hash = digest(root / "sumire.apk")
    if apk_hash != record.get("apk_sha256") or (root / "sumire.apk").stat().st_size != record.get("size_bytes"):
        raise ValueError("The prepared APK has changed; prepare the bundle again.")
    if (root / "sumire.apk.sha256").read_text().strip() != f"{apk_hash}  sumire.apk":
        raise ValueError("The SHA-256 file does not match the prepared APK.")
    return record


def configure(cf, email):
    zones = cf.all("zones?name=" + ZONE + "&status=active")
    if len(zones) != 1 or zones[0].get("name") != ZONE:
        raise RuntimeError("The active ctrlcv.dev zone is missing or the API token cannot read it.")
    zone = zones[0]
    account = zone["account"]["id"]
    account_path = "accounts/" + account
    organization = cf.request(account_path + "/access/organizations")
    auth_domain = organization.get("auth_domain", "")
    if not auth_domain.endswith(".cloudflareaccess.com"):
        raise RuntimeError("Set up the Cloudflare Zero Trust organization in the dashboard first.")

    github = existing_github_provider(cf.all(account_path + "/access/identity_providers"))
    applications = cf.all(account_path + "/access/apps")
    if any(app.get("domain", "").startswith(DOMAIN + "/") for app in applications):
        raise RuntimeError("An existing sub-path Access application could override the owner-only policy.")
    applications = [app for app in applications if app.get("domain") == DOMAIN]
    if len(applications) > 1:
        raise RuntimeError("Multiple Access applications use this hostname; inspect them first.")
    if applications:
        app = applications[0]
        if app.get("type") != "self_hosted" or app.get("allowed_idps") != [github["id"]]:
            raise RuntimeError("The existing Access application is not restricted to the existing GitHub provider.")
    else:
        app = cf.request(account_path + "/access/apps", "POST", {
            "name": "Sumire personal APK", "type": "self_hosted", "domain": DOMAIN,
            "session_duration": "24h", "allowed_idps": [github["id"]],
            "auto_redirect_to_identity": False, "app_launcher_visible": False,
        })
    policies_path = account_path + "/access/apps/" + app["id"] + "/policies"
    policies = cf.all(policies_path)
    if not policies:
        cf.request(policies_path, "POST", {
            "name": "Owner only", "decision": "allow", "precedence": 1,
            "include": [{"email": {"email": email}}], "exclude": [],
            "require": [{"login_method": {"id": github["id"]}}],
        })
    validate_owner_policy(cf.all(policies_path), email, github["id"])

    buckets_path = account_path + "/r2/buckets"
    buckets = cf.request(buckets_path)["buckets"]
    if not any(bucket["name"] == BUCKET for bucket in buckets):
        cf.request(buckets_path, "POST", {"name": BUCKET, "locationHint": "apac"})
    bucket_path = buckets_path + "/" + BUCKET
    managed_path = bucket_path + "/domains/managed"
    cf.request(managed_path, "PUT", {"enabled": False})
    domains_path = bucket_path + "/domains/custom"
    domains = cf.request(domains_path)["domains"]
    validate_bucket_access(cf.request(managed_path), domains)
    current = next((item for item in domains if item.get("domain") == DOMAIN), None)
    if current is None:
        cf.request(domains_path, "POST", {"domain": DOMAIN, "zoneId": zone["id"], "enabled": True, "minTLS": "1.2"})
    elif not current.get("enabled"):
        cf.request(domains_path + "/" + DOMAIN, "PUT", {"enabled": True, "minTLS": "1.2"})
    validate_owner_policy(cf.all(policies_path), email, github["id"])
    validate_bucket_access(cf.request(managed_path), cf.request(domains_path)["domains"])
    verify_unauthenticated_access(auth_domain)
    return account


def publish(cf, email, bundle):
    record = validate_bundle(bundle)
    account = configure(cf, email)
    prefix = f"builds/{record['version_code']}-{record['source_commit'][:12]}-{record['apk_sha256'][:12]}"
    npx = shutil.which("npx.cmd" if os.name == "nt" else "npx")
    if npx is None:
        raise RuntimeError("Node.js/npm is required to upload using Wrangler.")
    environment = dict(os.environ, CLOUDFLARE_ACCOUNT_ID=account, CLOUDFLARE_API_TOKEN=cf.token,
                       WRANGLER_SEND_METRICS="false")
    files = [("sumire.apk", "application/vnd.android.package-archive"),
             ("sumire.apk.sha256", "text/plain"), ("build.json", "application/json")]
    for name, content_type in files:
        subprocess.run([npx, "--yes", WRANGLER, "r2", "object", "put", f"{BUCKET}/{prefix}/{name}",
                        "--remote", "--file", str((Path(bundle) / name).resolve()),
                        "--content-type", content_type, "--cache-control", "private, no-store"],
                       env=environment, cwd=bundle, check=True, timeout=300)
    # Only publish the latest pointer after every versioned object was uploaded.
    latest = dict(record, apk_url=f"https://{DOMAIN}/{prefix}/sumire.apk")
    latest_file = Path(bundle) / "latest.json"
    latest_file.write_text(json.dumps(latest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    subprocess.run([npx, "--yes", WRANGLER, "r2", "object", "put", f"{BUCKET}/latest.json",
                    "--remote", "--file", str(latest_file.resolve()),
                    "--content-type", "application/json", "--cache-control", "private, no-store"],
                   env=environment, cwd=bundle, check=True, timeout=90)
    print("Private APK URL: " + latest["apk_url"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--email", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", args.email):
        parser.error("A valid owner email address is required.")
    token = os.environ.get("CLOUDFLARE_API_TOKEN", "").strip()
    if not token:
        parser.error("CLOUDFLARE_API_TOKEN is not set. Do not paste the token into chat or commit it.")
    publish(Cloudflare(token), args.email, args.bundle.resolve())
