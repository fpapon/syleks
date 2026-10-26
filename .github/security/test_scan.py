import contextlib
import datetime
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import scan


class PolicyTest(unittest.TestCase):
    def test_exact_suppression_keeps_other_versions_and_scopes_visible(self):
        report = {"Results": [{"Packages": [{}], "Vulnerabilities": [{
            "VulnerabilityID": "CVE-2099-0001", "PkgName": "example:library",
            "InstalledVersion": "1.0", "Severity": "LOW",
        }]}]}
        entry = {"id": "CVE-2099-0001", "package": "example:library", "version": "1.0", "scope": "maven"}
        self.assertEqual(["CVE-2099-0001"], scan.assess(report, "shaded", [entry])["findings"])
        self.assertEqual(["CVE-2099-0001"], scan.assess(report, "maven", [entry])["suppressed"])
        entry["version"] = "2.0"
        self.assertEqual(["CVE-2099-0001"], scan.assess(report, "maven", [entry])["findings"])

    def test_empty_dependency_inventory_is_error(self):
        with self.assertRaises(ValueError):
            scan.assess({"Results": []}, "maven", [])

    def test_expired_broad_and_unowned_suppressions_are_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "suppressions.json"
            entry = dict(id="CVE-2099-0001", package="example:library", version="1.0", scope="maven",
                         owner="security-maintainer", reason="Synthetic regression fixture",
                         tracking="https://example.invalid/issues/1",
                         expires=str(datetime.date.today() + datetime.timedelta(days=30)))
            path.write_text(json.dumps([entry]))
            self.assertEqual(1, len(scan.load_suppressions(path)))
            for changes in ({"expires": str(datetime.date.today())}, {"package": "*"},
                            {"owner": ""}, {"expires": str(datetime.date.today() + datetime.timedelta(days=91))}):
                path.write_text(json.dumps([{**entry, **changes}]))
                with self.assertRaises(ValueError):
                    scan.load_suppressions(path)

    def test_cli_distinguishes_clean_finding_and_scanner_failure(self):
        for value, expected, state in ((False, 0, "clean"), (True, 42, "findings"),
                                       (RuntimeError("synthetic outage"), 2, "scanner-error"),
                                       (scan.FeedError("synthetic feed outage"), 2, "advisory-feed-error")):
            with self.subTest(state=state), tempfile.TemporaryDirectory() as directory:
                result = dict(side_effect=value) if isinstance(value, Exception) else dict(return_value=value)
                with patch.object(sys, "argv", ["scan.py", "secrets", "--output", directory]), \
                     patch.object(scan, "secrets", **result), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(expected, scan.main())
                self.assertEqual(state, json.loads((Path(directory) / "status.json").read_text())["secrets"]["state"])

    def test_scanner_errors_and_malformed_reports_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            scanner = scan.Scanner(directory)
            with patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 1)):
                with self.assertRaises(RuntimeError):
                    scanner.scan(directory, "vuln", Path(directory) / "report.json")
            path = Path(directory) / "bad.json"
            for data in ("{", '{}', '{"SchemaVersion":2,"Results":{}}'):
                path.write_text(data)
                with self.assertRaises(ValueError):
                    scan.read_report(path)


@unittest.skipUnless(shutil.which("trivy"), "Trivy is required for the real synthetic secret probe")
class SecretProbeTest(unittest.TestCase):
    def test_real_secret_fixture_blocks_without_exporting_match(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "fixture"
            fixture.mkdir()
            # Construct a nonfunctional token only in temporary test data.
            sentinel = "ghp_" + "Ab9cD8eF7gH6iJ5kL4mN3oP2qR1sT0uVwXyZ"
            (fixture / "credential.txt").write_text("github_token=" + sentinel + "\n")
            scanner = scan.Scanner(root)
            report = scanner.scan(fixture, "secret", root / "raw.json")
            self.assertTrue(any(result.get("Secrets") for result in report.get("Results", [])))
            output = root / "output"
            output.mkdir()
            with patch.object(scan.subprocess, "check_output", return_value=b"credential.txt\0"), \
                 contextlib.chdir(fixture):
                self.assertTrue(scan.secrets(scanner, root, output))
            safe = (output / "secrets-summary.json").read_text()
            self.assertNotIn(sentinel, safe)
            self.assertNotIn("Match", safe)
            self.assertNotIn("Code", safe)


if __name__ == "__main__":
    unittest.main()
