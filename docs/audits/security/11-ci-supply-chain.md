# SEC-11: Add CI supply-chain checks

Status: implementation ready 26 October 2026. Local scanner, inventory and failure-policy validation complete; successful hosted CI evidence pending. Priority: hardening. Dependencies: none.

## Finding and relevant code

The existing Java CI builds/tests/packages the application and uploads validation artifacts. GitHub Actions use version tags. This review did not check current dependency advisories or identify a specific vulnerable dependency.

Read [.github/workflows/ci.yml](../../../.github/workflows/ci.yml), [pom.xml](../../../pom.xml), and [.gitignore](../../../.gitignore). Preserve Java 25, Maven verification, packaged SQLite/CLI checks, and report generation.

## Implementation contract

- Pin external Actions to verified full commit SHAs, with readable version comments and an update mechanism. Resolve SHAs from authoritative upstream sources at implementation time; do not invent hashes or copy stale examples.
- Set least-privilege workflow token permissions. Prevent fork/PR workflows from receiving secrets or using write privileges to execute untrusted checkout content. Treat `pull_request_target`, shell interpolation, cache poisoning, and artifact access as explicit review points.
- Add repository secret scanning and dependency vulnerability scanning with a documented failure policy. Choose maintained tooling using current official documentation; account for rate limits, advisory feeds, authentication, and transient outages.
- Check runtime and relevant build/plugin dependencies and the shaded artifact composition; generate an SBOM or equivalent dependency inventory. Distinguish reachability assessment from the presence of an advisory.
- Use narrow, reviewed, expiring suppressions with rationale, owner, and tracking reference. Do not ignore all findings or silently succeed after scanner errors. Define initial baseline triage so known findings are visible rather than hidden.
- Keep scanner credentials out of logs/artifacts and avoid uploading sensitive chat exports, config, homes, or raw process environments. Preserve useful test reports with synthetic-only fixtures.

## Acceptance and evidence

Validate workflow syntax, immutable action references, token permissions, event conditions, scanner exit behavior, and artifact paths. Exercise synthetic secret fixtures safely outside tracked production content and a controlled dependency finding where feasible. Verify that no real secret is needed for PR checks and report advisory-feed outages distinctly.

Provide official tool/action references with the implementation date, generated dependency inventory location, failure/suppression policy, initial triage results, and successful CI evidence. Local workflow inspection alone is not a completed hosted CI run. Follow [validation.md](validation.md).

## Implementation record — 26 October 2026

The [workflow](../../../.github/workflows/ci.yml) now pins every external Action to a full upstream commit SHA, grants only `contents: read`, disables persisted checkout credentials and shared caches, and runs secret/dependency checks without repository secrets. Java 25, `clean verify`, required Linux sandbox checks, packaged CLI/SQLite checks and synthetic test/performance reports remain configured. Manual and weekly scheduled runs check for newly published advisories. [Dependabot](../../../.github/dependabot.yml) proposes weekly Action/Maven updates; scanner version changes require manual review.

The [scanner policy and operating instructions](../../../.github/security/README.md) document tooling, official references, scopes, artifacts, failure handling and suppression metadata. Trivy 0.76.1 scans current tracked files for secrets and uses `rootfs` mode for resolved Maven JARs and the actual shaded JAR. Native/release plugin dependencies are resolved without executing release/native build goals. It emits separate CycloneDX inventories and unfiltered vulnerability reports. Raw secret matches, excerpts and scanner logs are never uploaded. No application configuration/schema migration is involved.

Policy: every advisory severity and secret finding blocks the job (exit 42). Operational errors block with exit 2; status distinguishes advisory-database preparation errors from scanner errors. Suppressions start empty and must specify an exact advisory/package/version/scope plus owner, rationale, tracking reference and an expiry within 90 days. Expired/malformed exceptions block the job. No known baseline is automatically accepted.

### Validation evidence

Environment: Linux, Zulu JDK 25 build 25+36-LTS, Maven 3.9.16, Python 3.13, Trivy 0.76.1, actionlint 1.7.12. Trivy and actionlint release archives were checked against their official release SHA-256 manifests. Action hashes were resolved using `git ls-remote` against the official `actions/checkout`, `actions/setup-java`, `actions/upload-artifact` and `aquasecurity/setup-trivy` repositories. The pinned setup action's nested Action and installer-script references were also inspected.

- `actionlint .github/workflows/ci.yml`: passed. All five external `uses` references satisfy full 40-character SHA checks. Source review confirmed read-only permissions, no `pull_request_target`, repository secrets, untrusted expression interpolation, shared caches or privileged artifact consumers. Artifact inputs are explicit report paths, with seven-day retention.
- `python3 -m unittest discover -s .github/security -p 'test_*.py' -v`: six tests passed with Trivy installed. A real synthetic token was detected outside tracked production content; safe summaries omitted the token and source excerpts. Gate tests returned 0 for clean results, 42 for findings, 2 for scanner errors and 2 with a distinct state for feed errors. Empty inventories, malformed reports, expired/wildcard/unowned exceptions were rejected; changed versions/scopes could not reuse a suppression.
- `mvn -B --no-transfer-progress clean verify`: passed outside the execution sandbox, **618 tests, zero failures/errors, 11 skipped**; Spotless checked all 246 Java files. The initial sandboxed run failed because local socket fixtures were forbidden; that run is not counted as a pass. The skipped Linux/backend cases are not additional SEC-03 isolation evidence; CI retains `SYLEKS_SANDBOX_TESTS: required`.
- Packaged `java --enable-native-access=ALL-UNNAMED -jar target/syleks-0.1.0-SNAPSHOT.jar --help` and `session list` with `SYLEKS_DATA_DIR=/tmp/sec11-packaged-data`: passed; the synthetic session store was empty.
- `mvn -B --no-transfer-progress -Dmaven.repo.local=/tmp/sec11-maven -Pnative,release org.apache.maven.plugins:maven-dependency-plugin:3.11.0:go-offline`: passed, populating an isolated local dependency repository for initial triage. CI instead populates its repository through verification followed by `resolve-plugins -DexcludeTransitive=false`.
- `python3 .github/security/scan.py secrets --output /tmp/sec11-security`: exit 0; no findings in currently tracked files. New implementation files are scanned once tracked in CI.
- The dependency scan initially returned exit 2 / `advisory-feed-error` because extracting the downloaded database exhausted the small `/tmp` volume. A retry using `TMPDIR` on the workspace volume downloaded both databases. The initial `fs` mode probe returned no JAR inventory and failed closed; switching packaged-artifact analysis to `rootfs` made the metadata-only Log4j fixture detect **CVE-2021-44228**. This fixture never executes vulnerable code and is excluded from real inventories.
- With those freshly downloaded databases, `scan.dependencies(...)` ran against `/tmp/sec11-maven` and the shaded JAR, using a temporary work directory and the diagnostic cache on the workspace volume. It generated `target/sec11-security/{maven,shaded}-inventory.cdx.json` and corresponding `*-vulnerabilities.json`, then the gate returned **42**. Maven: 457 detected package entries, 127 advisory occurrences, 83 distinct advisory IDs. Shaded artifact: 29 packages, 21 advisory occurrences/IDs. No finding was suppressed. This integration used the scanner helper directly to reuse the freshly downloaded diagnostic databases; CLI exit-state behavior was tested separately.
- `git diff --check`: passed. No Java source changes required formatting edits.

### Initial triage and follow-up

The [initial advisory snapshot](11-initial-triage.md) records every affected package/version, advisory ID and scope observed locally. These are presence findings, not established exploitability. The new dependency gate deliberately remains red while these findings are open; scanner execution and inventory generation succeeded, but the dependency tree did not pass the policy.

#### SEC-11-T1: Runtime advisories

Owner: repository maintainers, pending individual assignment. Open: assess and remediate the 21 shaded-artifact findings in Jackson core/databind 2.17.1 and JLine builtins/reader/remote-telnet 3.30.3. Trace application reachability, choose compatible patched dependencies or remove unnecessary bundled modules, and rerun the packaged tests and inventory. No reachability exemption has been granted.

#### SEC-11-T2: Build advisories

Owner: repository maintainers, pending individual assignment. Open: trace remaining Maven-inventory findings to their owning plugins/extensions, including native/release profiles. Prefer maintained plugin upgrades; any temporary exception needs the metadata and expiry enforced by the scanner. Do not treat old Maven-core libraries inside plugin dependencies as proof that the Maven executable itself is affected.

#### SEC-11-T3: Hosted CI evidence

Open: run the changed workflow in GitHub, inspect the required Linux sandbox checks and both artifact sets, and link the run/PR here. No code was pushed and no hosted CI run was triggered in this environment. Do not mark SEC-11 implemented until hosted execution evidence is attached. Branch protection/required checks must be enabled in repository settings; this checkout cannot establish those settings.

Limitations: source secret scanning covers the checked-out tracked revision, not Git history. Native distributions and executables, Maven itself, the runner OS and hosted infrastructure are outside the Java inventory. JAR identification depends on metadata/Java-index coverage; inventories do not prove reachability. Linux backend skips remain explicitly unvalidated here. No UI behavior changed, so terminal screenshots are not applicable.
