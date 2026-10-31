import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import publish_r2 as publishing
from prepare_bundle import APP_ID, CERTIFICATE, digest


class PrivateAccessTest(unittest.TestCase):
    def policy(self):
        return {"decision": "allow", "include": [{"email": {"email": "owner@example.invalid"}}],
                "exclude": [], "require": [{"login_method": {"id": "github-idp"}}]}

    def test_owner_email_and_github_are_both_required(self):
        publishing.validate_owner_policy([self.policy()], "owner@example.invalid", "github-idp")

    def test_everyone_rule_is_rejected(self):
        policy = self.policy()
        policy["include"] = [{"everyone": {}}]
        with self.assertRaises(ValueError):
            publishing.validate_owner_policy([policy], "owner@example.invalid", "github-idp")

    def test_email_only_or_other_login_method_is_rejected(self):
        for required in ([], [{"login_method": {"id": "email-pin"}}]):
            policy = self.policy()
            policy["require"] = required
            with self.assertRaises(ValueError):
                publishing.validate_owner_policy([policy], "owner@example.invalid", "github-idp")

    def test_additional_bypass_policy_is_rejected(self):
        with self.assertRaises(ValueError):
            publishing.validate_owner_policy([self.policy(), {"decision": "bypass"}],
                                             "owner@example.invalid", "github-idp")

    def test_r2_dev_public_access_is_rejected(self):
        with self.assertRaises(ValueError):
            publishing.validate_bucket_access({"enabled": True}, [])

    def test_other_enabled_domain_is_rejected(self):
        with self.assertRaises(ValueError):
            publishing.validate_bucket_access({"enabled": False}, [{"domain": "public.example.invalid", "enabled": True}])
        publishing.validate_bucket_access({"enabled": False}, [{"domain": publishing.DOMAIN, "enabled": True}])

    def test_login_redirect_requires_the_exact_cloudflare_team(self):
        domain = "team.cloudflareaccess.com"
        self.assertTrue(publishing.protected_redirect("https://team.cloudflareaccess.com/cdn-cgi/access/login/apk.example.invalid", domain))
        self.assertFalse(publishing.protected_redirect("https://team.cloudflareaccess.com.evil.invalid/cdn-cgi/access/login/", domain))
        self.assertFalse(publishing.protected_redirect("http://team.cloudflareaccess.com/cdn-cgi/access/login/", domain))
        self.assertFalse(publishing.protected_redirect("https://team.cloudflareaccess.com/other", domain))

    def test_existing_github_is_reused_and_no_fallback_is_selected(self):
        github = {"id": "github-idp", "type": "github"}
        self.assertEqual(github, publishing.existing_github_provider([{"id": "otp", "type": "onetimepin"}, github]))
        with self.assertRaises(ValueError):
            publishing.existing_github_provider([{"id": "otp", "type": "onetimepin"}])
        with self.assertRaises(ValueError):
            publishing.existing_github_provider([github, {"id": "other", "type": "github"}])

    def test_tampered_apk_is_rejected_before_cloudflare_is_contacted(self):
        temporary = tempfile.TemporaryDirectory(prefix="sumire-r2-test-")
        root = Path(temporary.name).resolve()
        assert root.parent == Path(tempfile.gettempdir()).resolve()
        assert root.name.startswith("sumire-r2-test-")
        self.addCleanup(temporary.cleanup)
        apk = root / "sumire.apk"
        apk.write_bytes(b"prepared APK")
        checksum = digest(apk)
        record = {"application_id": APP_ID, "certificate_sha256": CERTIFICATE,
                  "source_commit": "a" * 40, "version_code": 835, "apk_sha256": checksum,
                  "size_bytes": apk.stat().st_size}
        (root / "build.json").write_text(json.dumps(record))
        (root / "sumire.apk.sha256").write_text(f"{checksum}  sumire.apk\n")
        self.assertEqual(record, publishing.validate_bundle(root))
        apk.write_bytes(b"replaced APK")
        with patch.object(publishing, "configure") as configure:
            with self.assertRaises(ValueError):
                publishing.publish(object(), "owner@example.invalid", root)
            configure.assert_not_called()


if __name__ == "__main__":
    unittest.main()
