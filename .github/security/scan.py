#!/usr/bin/env python3
"""CI gates: 0 clean, 42 findings, 2 scanner/feed/policy failure. No credentials needed."""

import argparse
import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile


class FeedError(RuntimeError):
    pass


def read_report(path):
    report = json.loads(path.read_text())
    if report.get("SchemaVersion") != 2 or not isinstance(report.get("Results", []), list):
        raise ValueError("unexpected scanner report schema")
    return report


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def load_suppressions(path):
    entries = json.loads(path.read_text())
    if not isinstance(entries, list):
        raise ValueError("suppressions must be an array")
    today = datetime.date.today()
    for entry in entries:
        for key in ("id", "package", "version", "scope", "owner", "reason", "tracking", "expires"):
            if not isinstance(entry.get(key), str) or not entry[key].strip():
                raise ValueError("suppression is missing required metadata")
        if entry["scope"] not in ("maven", "shaded") or any(
            char in entry[key] for key in ("id", "package", "version") for char in "*?"
        ):
            raise ValueError("suppression must name an exact advisory, package, version and scope")
        expiry = datetime.date.fromisoformat(entry["expires"])
        if not today < expiry <= today + datetime.timedelta(days=90):
            raise ValueError("suppression is expired or exceeds 90 days")
    return entries


def assess(report, scope, suppressions):
    findings = []
    suppressed = []
    packages = sum(len(result.get("Packages", [])) for result in report.get("Results", []))
    if not packages:
        raise ValueError("dependency inventory is empty")
    for result in report.get("Results", []):
        for finding in result.get("Vulnerabilities", []):
            match = any(
                entry["scope"] == scope
                and entry["id"] == finding["VulnerabilityID"]
                and entry["package"] == finding["PkgName"]
                and entry["version"] == finding["InstalledVersion"]
                for entry in suppressions
            )
            (suppressed if match else findings).append(finding["VulnerabilityID"])
    return {"packages": packages, "findings": findings, "suppressed": suppressed}


class Scanner:
    def __init__(self, temporary):
        self.stage = "initialization"
        self.temporary = Path(temporary)
        # Explicit empty policies defeat implicit project-local Trivy config/ignore files.
        self.config = self.temporary / "config.json"
        self.config.write_text("{}")
        self.secret_config = self.temporary / "secret-config.json"
        self.secret_config.write_text("{}")
        self.environment = {key: value for key, value in os.environ.items() if not key.startswith("TRIVY_")}
        self.environment["TRIVY_CACHE_DIR"] = str(self.temporary / "cache")

    def prepare_feeds(self):
        self.stage = "advisory-feed-download"
        try:
            for flag in ("--download-db-only", "--download-java-db-only"):
                self.run("fs", flag, "--timeout", "15m")
        except (RuntimeError, subprocess.SubprocessError) as error:
            raise FeedError("advisory feed unavailable") from error

    def run(self, *arguments):
        command = ["trivy", *arguments, "--config", str(self.config)]
        # Never echo raw scanner output: secret matches/code snippets may be present.
        result = subprocess.run(command, env=self.environment, capture_output=True, timeout=1200)
        if result.returncode:
            raise RuntimeError("Trivy failed; scanner installation, input or advisory feed unavailable")

    def scan(self, source, kind, output):
        self.run(
            "rootfs" if kind == "vuln" else "fs",
            "--scanners", kind, "--format", "json", "--output", str(output),
            "--exit-code", "0", "--ignorefile", os.devnull,
            "--secret-config", str(self.secret_config), "--list-all-pkgs",
            "--offline-scan", "--skip-version-check", "--timeout", "15m", str(source),
        )
        return read_report(output)


def secrets(scanner, temporary, output):
    staging = temporary / "tracked"
    staging.mkdir()
    # Worktree contents of every tracked file, including dotfiles; no user home,
    # ignored local config, build output, symlink targets, or raw Git objects.
    paths = subprocess.check_output(["git", "ls-files", "-z"]).split(b"\0")
    for raw in filter(None, paths):
        path = Path(os.fsdecode(raw))
        if path.is_absolute() or ".." in path.parts:
            raise ValueError("unsafe tracked path")
        if path.is_symlink() or not path.exists():
            continue
        destination = staging / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
    report = scanner.scan(staging, "secret", temporary / "raw-secrets.json")
    safe = [
        {"file": result["Target"], "rule": secret["RuleID"], "line": secret["StartLine"]}
        for result in report.get("Results", [])
        for secret in result.get("Secrets", [])
    ]
    write_json(output / "secrets-summary.json", safe)
    return bool(safe)


def dependencies(scanner, temporary, output, repository, jar, suppressions):
    if not repository.is_dir() or not jar.is_file():
        raise ValueError("Maven repository or packaged JAR missing")
    staging = temporary / "artifact"
    staging.mkdir()
    shutil.copyfile(jar, staging / jar.name)
    scanner.prepare_feeds()
    scanner.stage = "controlled-advisory-probe"
    # Synthetic metadata only: exercise the live advisory feed without executing
    # or downloading vulnerable code, and never include this fixture in inventories.
    probe = temporary / "probe"
    probe.mkdir()
    with zipfile.ZipFile(probe / "log4j-core-2.14.1.jar", "w") as fixture:
        fixture.writestr(
            "META-INF/maven/org.apache.logging.log4j/log4j-core/pom.properties",
            "groupId=org.apache.logging.log4j\nartifactId=log4j-core\nversion=2.14.1\n",
        )
    probe_report = scanner.scan(probe, "vuln", temporary / "probe.json")
    if "CVE-2021-44228" not in assess(probe_report, "shaded", [])["findings"]:
        raise ValueError("live advisory negative probe did not detect Log4Shell")
    summaries = {}
    for scope, source in (("maven", repository), ("shaded", staging)):
        scanner.stage = scope + "-scan"
        report_path = output / (scope + "-vulnerabilities.json")
        report = scanner.scan(source, "vuln", report_path)
        summaries[scope] = assess(report, scope, suppressions)
        scanner.stage = scope + "-inventory"
        scanner.run(
            "convert", "--format", "cyclonedx", "--output",
            str(output / (scope + "-inventory.cdx.json")), str(report_path),
        )
        inventory = json.loads((output / (scope + "-inventory.cdx.json")).read_text())
        if inventory.get("bomFormat") != "CycloneDX" or not inventory.get("components"):
            raise ValueError("invalid or empty CycloneDX inventory")
    return summaries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("secrets", "dependencies"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--maven-repo", type=Path)
    parser.add_argument("--jar", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    status_path = args.output / "status.json"
    status = json.loads(status_path.read_text()) if status_path.exists() else {}
    scanner = None
    try:
        with tempfile.TemporaryDirectory(prefix="syleks-security-") as directory:
            temporary = Path(directory)
            scanner = Scanner(temporary)
            if args.mode == "secrets":
                scanner.stage = "tracked-secret-scan"
                found = secrets(scanner, temporary, args.output)
                details = {}
            else:
                suppressions = load_suppressions(Path(__file__).with_name("suppressions.json"))
                details = dependencies(
                    scanner, temporary, args.output, args.maven_repo, args.jar, suppressions
                )
                found = any(value["findings"] for value in details.values())
        status[args.mode] = {"state": "findings" if found else "clean", "details": details}
        result = 42 if found else 0
    except FeedError:
        status[args.mode] = {"state": "advisory-feed-error", "details": {"stage": scanner.stage}}
        result = 2
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, TypeError, KeyError):
        status[args.mode] = {"state": "scanner-error", "details": {"stage": scanner.stage if scanner else "initialization"}}
        result = 2
    write_json(status_path, status)
    print(f"{args.mode}: {status[args.mode]['state']} (exit {result}); see safe reports in {args.output}")
    return result


if __name__ == "__main__":
    sys.exit(main())
