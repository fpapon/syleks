# CI security gates

The Java CI workflow runs on pushes, pull requests, manual dispatch and weekly on the default branch. It uses Java 25, verifies Maven, exercises the packaged CLI/SQLite runtime and preserves synthetic performance/test reports. `security-reports` contains only the explicitly selected scanner outputs, retained for seven days.

## Policy

- All detected secret rules and dependency advisory severities (including unknown severity and unfixed advisories) block the job. There is no grandfathered baseline. Initial findings remain visible in the reports until fixed or individually reviewed.
- `scan.py` returns **0** for a clean scan, **42** for findings and **2** for operational/policy errors. `status.json` distinguishes `advisory-feed-error` from `scanner-error`. Missing inputs, empty dependency inventories, malformed reports, failed downloads and expired suppressions fail closed. No `continue-on-error`, stale-feed fallback or scanner ignore files are used.
- Trivy 0.76.1 downloads its vulnerability and Java-index databases into a fresh private temporary directory. Downloads use the public upstream OCI feeds; no NVD key, paid service or PR secret is required. Rate limits and transient feed outages fail the check; retry the job after service recovery. Do not disable updates to turn an outage green.
- A synthetic GitHub-token fixture tests real secret detection and verifies that the safe report omits the token. After downloading feeds, a metadata-only Log4j 2.14.1 JAR must trigger CVE-2021-44228 before real dependency scanning proceeds. The fixture contains no vulnerable executable code and is excluded from inventories.

## Scope and artifacts

The secret scan copies the current contents of all Git-tracked regular files, including dotfiles, into a private directory. It skips symlinks, deleted files and untracked/ignored local data. This checks the checked-out revision, **not historical commits**. A clean result does not certify that earlier commits never contained credentials. Rotate any exposed credential and review history separately when investigating an incident.

Raw secret matches and source excerpts are captured only in private temporary files and removed on exit. `secrets-summary.json` exports only file, line and rule ID. Scanner stdout/stderr are captured and are never printed or uploaded. Homes, Maven settings, local application configuration, chat exports and environment dumps are not artifact inputs. Existing test/performance reports continue to use synthetic fixtures.

The build uses an isolated Maven repository under `$RUNNER_TEMP/syleks-maven`, without restoring/saving cross-run caches. After `clean verify`, Maven Dependency Plugin 3.11.0 resolves plugin dependencies for both `native` and `release` profiles without executing their build goals. Trivy's `rootfs` mode scans this repository (runtime, provided, test, annotation processor, build extension, plugin and plugin-transitive JARs) separately from a directory containing only the final shaded application JAR. `rootfs` enables packaged JAR analysis; `fs` mode scans repository source files for secrets. This deliberately includes build-time and test-time risk rather than filtering it away.

`$RUNNER_TEMP/security/` contains:

| File | Meaning |
| --- | --- |
| `maven-inventory.cdx.json` | CycloneDX inventory of resolved Maven artifacts, including build tools |
| `shaded-inventory.cdx.json` | CycloneDX inventory detected from the actual shaded JAR |
| `*-vulnerabilities.json` | Full dependency reports with all findings, including suppressed ones |
| `status.json` | Gate state, package counts, active and suppressed advisory IDs |
| `secrets-summary.json` | Secret locations and rule IDs without match values |

JAR identification relies on embedded Maven metadata and Trivy's Java index. Shading, relocation or missing metadata can limit identification; an SBOM is an inventory, not a reachability or exploitability proof. Maven itself, the runner OS, native GraalVM downloads and GitHub's hosted infrastructure are outside this Java dependency inventory. The workflow scans resolved native/release plugins, not the native distribution or a built native executable.

## Suppressions and initial triage

`.github/security/suppressions.json` starts empty. An exception must name one exact advisory ID, Maven `group:artifact`, installed version and scan scope (`maven` or `shaded`), plus a nonempty `reason`, `owner`, `tracking` reference and ISO `expires` date. Wildcards are rejected; expiry must be in the future and at most 90 days away. Expired or malformed entries fail the check rather than extending an exception. Secret findings have no suppression mechanism: remove the secret or replace the synthetic fixture. Do not add scanner-wide ignores or turn down severity filtering.

For each initial finding, the repository maintainers must track the affected scope/version, proposed upgrade or mitigation, owner and a tracking issue/PR. Runtime findings need assessment against the packaged inventory; build-only findings still need review because Maven executes plugins. Reachability decisions require separate evidence. A suppression requires a reviewed PR with that evidence and an expiry; its advisory remains in the uploaded raw dependency report and the status summary. An empty suppression list does not assert an advisory-free dependency tree.

## Workflow trust and maintenance

All external Actions are pinned to upstream-verified full commit SHAs with release comments. The pinned Trivy setup action also pins its nested Actions and installation-script checkout. It installs an explicit scanner version with cache disabled. Weekly Dependabot PRs update Actions and Maven coordinates; review scanner version updates in the workflow manually against official releases, rerun the probes, and review Action and nested-action changes before merging. Never replace a full hash with a moving tag.

The workflow grants only `contents: read`; all unspecified token scopes are disabled. Checkout disables persisted credentials. Only `push`/`pull_request` execute checked-out code; there is no `pull_request_target` or privileged `workflow_run` consumer, write token, deployment, secret interpolation, shared cache, or downloaded artifact execution. `workflow_dispatch` and scheduled runs use the same read-only policy. Artifact upload uses GitHub's job-scoped artifact service, not a write-scoped repository token. Treat reports from PR code as untrusted evidence; do not execute their content or feed it into a privileged workflow. Repository rules should require the `build` job before merging; branch protection is an external repository setting.

## Local checks

With Python 3.11+, Trivy 0.76.1, JDK 25 and Maven 3.9+ on `PATH`:

Allow several GB of temporary disk space for database download/extraction. On a small `/tmp` partition, set `TMPDIR` to a private directory on a larger volume before running the dependency scan.

```sh
python3 -m unittest discover -s .github/security -p 'test_*.py' -v
python3 .github/security/scan.py secrets --output /tmp/syleks-security
mvn -B --no-transfer-progress -Dmaven.repo.local=/tmp/syleks-maven clean verify
mvn -B --no-transfer-progress -Dmaven.repo.local=/tmp/syleks-maven -Pnative,release org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve-plugins -DexcludeTransitive=false
python3 .github/security/scan.py dependencies --output /tmp/syleks-security --maven-repo /tmp/syleks-maven --jar target/syleks-0.1.0-SNAPSHOT.jar
actionlint .github/workflows/ci.yml
git diff --check
```

The synthetic secret probe is skipped locally if Trivy is absent; CI installs Trivy first, so it must run there. Java tests needing local sockets or Linux isolation also need the corresponding host permissions/backend. A local pass does not establish successful hosted CI execution.

Official references reviewed on 26 October 2026: [Trivy filesystem scanning](https://trivy.dev/docs/latest/target/filesystem/), [Java coverage](https://trivy.dev/docs/latest/coverage/language/java/), [secret scanning](https://trivy.dev/docs/latest/scanner/secret/), [Trivy releases](https://github.com/aquasecurity/trivy/releases/tag/v0.76.1), [pinned setup action](https://github.com/aquasecurity/setup-trivy/blob/81e514348e19b6112ce2a7e3ecbafe19c1e1f567/action.yaml), [Maven plugin resolution](https://maven.apache.org/plugins/maven-dependency-plugin/resolve-plugins-mojo.html), [Dependabot options](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-options-reference), [GitHub token permissions](https://docs.github.com/en/actions/security-for-github-actions/security-guides/automatic-token-authentication).
