# SEC-11: Add CI supply-chain checks

Status: not started. Priority: hardening. Dependencies: none.

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
