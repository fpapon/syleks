# Engineering module contracts — proposed v1

These contracts implement the reviewable proposal in [ADR 0001](adr/0001-engineering-contracts.md). **Public API approval is pending.** They are not a workflow engine. The executable JSON interface is packaged as [`/engineering/contracts-v1.schema.json`](../../src/main/resources/engineering/contracts-v1.schema.json). It is independent of `agent-config.schema.json` and session archive formats.

## Responsibilities and module interfaces

An interface here is a validated JSON document with named identity, inputs and invariants, rather than a new public Java service SPI. Consumers must validate structural and semantic rules before using a document. Package-private `EngineeringDocuments.parse(bytes, expectedKind)` enforces those rules; `TechnologyPackLoader.load(reference)` returns an immutable verified snapshot. No caller may treat a parsed document as a grant of execution authority.

| Contract (`kind`) | Responsibility and input/output interface | Key invariants / ownership |
| --- | --- | --- |
| `EngineeringProject` (`engineering_project`) | Bind `id`, canonical `root_path`, `provenance`, explicit `allow_cloud`, pinned `technology_packs` and a `token_budget` to an existing Syleks project. Read from user-owned enrollment; an eventual resolver maps the canonical root to `session.model.Project.id`. | A project ID is a logical Engineering identity, not a trusted filesystem path or credential. All matching ancestor enrollments constrain a launch directory. Existing Syleks project/session identifiers stay unchanged. |
| `TechnologyPack` (`technology_pack`) | Manifest `pack.json`: `id`, `version`, and entries `{path, sha256, role}`. Input reference is `{id, version, local_path, manifest_sha256}`. Reader output is `Snapshot(id, version, manifestSha256, resources)`, with immutable text keyed by relative path. | Pack path is local and absolute; manifest and every entry are hashed. Roles are FRAMEWORK, SKILL, WORKFLOW. Content is reference data and confers no permissions. WORKFLOW entries additionally validate as `workflow_definition`. No code/module installer or remote resolver. |
| `WorkflowDefinition` (`workflow_definition`) | Declarative `id`, `version`, and stages with `id`, `depends_on`, selected `agent`, and optional `approval_gate` reference. A future scheduler consumes a pinned definition and emits a run. | Unique stage IDs, known unique dependencies, acyclic graph. Agent existence, effective permissions, pack membership and gate references must be resolved before a future run can start. The parser does not create tasks or run stages. |
| `WorkflowRun` (`workflow_run`) | Persistable run identity and `project_id`, `workflow_id`, `definition_sha256`, optional `session_id`, `revision`, `status`, `context_sha256`, `budget_id` and stage/child-session correlations. | Compare-and-swap `revision` and project scoping are future repository obligations. Status is CREATED/RUNNING/WAITING_APPROVAL/COMPLETED/FAILED/CANCELLED/INTERRUPTED. Unique stage IDs. An imported/completed run is history, not permission to execute. |
| `ApprovalGate` (`approval_gate`) | Describe a review bound to run/stage, artifact hash, policy hash, action (PLAN/EFFECT/RELEASE), exact destination, status, reviewer, decision instant and expiry. | Decided APPROVED/REJECTED states require reviewer and timestamp; pending/expired records cannot fabricate a decision. Timestamp syntax is validated. An approval in arbitrary JSON is untrusted history. A trusted issuer must later check current artifact bytes, policy, destination, reviewer authority and expiry before dispatch. |
| `ContextManifest` (`context_manifest`) | Versioned inventory of selected evidence: project, revision, entries `{path, sha256, provenance, byte_count, release_id}`. A future selector consumes this plus source bytes and returns a bounded request manifest. | Paths are relative and normalized; duplicate paths are rejected. The transport must verify actual bytes/digests and include instructions, schemas, summaries, prompts and tool results in the eventual exact-payload release. `release_id` never changes PRIVATE lineage to PUBLIC. |
| `TokenBudget` (`token_budget`) | Persistable execution-budget proposal: input/output/total limits, aggregate reserved and consumed counts, and unknown-usage policy BLOCK. | Nonnegative safe JSON integers (≤ 2^53−1), input/output ceilings ≤ total, reserved + consumed ≤ total. This is distinct from `session.ContextBudget` admission estimation. Reservation, reconciliation, retry charging and atomic concurrency are specified for future storage; no ledger or new usage charging is implemented now. |
| `RepositoryAnalysis` (`repository_analysis`) | Identify a local extractor/version, working-tree fingerprint, input path/digests/provenance, and findings with rule/path/line span/evidence digest/provenance. | Unique input paths; normalized relative finding paths; positive ordered line spans. Findings must be tied to actual reviewed input hashes by the future analyzer. No AST parser, embedding service or source upload is added by P01. |

The `project_registry` envelope has `schema_version: 1`, `projects` (full EngineeringProject documents), and `local_routes` (exact provider/endpoint with `reviewed_by` and ISO-8601 `reviewed_at`). It is stored at `<AppConfig.home>/.config/syleks/engineering-projects.json`. Its schema is part of the same v1 resource. The registry is user-owned security configuration, not a template/project file. It must be outside enrolled roots. Runtime stores its startup hash and requires restart for any changed bytes.

Identity strings are bounded opaque identifiers; pack/definition/extractor versions use `major.minor.patch` with optional prerelease. Version strings identify content but do not prove it: SHA-256 pins are mandatory. Contracts contain no access tokens, API keys, OAuth clients, accounts, provider fallback rules or executable scripts. Cross-document reference resolution is deferred to a future consumer; structural parsing alone must never claim a run is executable or a release authorized.

## User enrollment example

After contract approval, create the registry yourself outside your project, protect its directory/file with private OS permissions, and replace the sample root with an existing absolute project directory. This example enrolls a private project while permitting no inference route; it is a configuration example, not an installed template:

```json
{
  "schema_version": 1,
  "kind": "project_registry",
  "projects": [{
    "schema_version": 1,
    "kind": "engineering_project",
    "id": "my-project",
    "root_path": "/absolute/path/to/project",
    "allow_cloud": false,
    "provenance": "PRIVATE",
    "technology_packs": [],
    "token_budget": {
      "schema_version": 1,
      "kind": "token_budget",
      "id": "my-budget",
      "input_limit": 80000,
      "output_limit": 20000,
      "total_limit": 100000,
      "reserved": 0,
      "consumed": 0,
      "unknown_usage": "BLOCK"
    }
  }],
  "local_routes": []
}
```

A pack reference inside `technology_packs` supplies `id`, `version`, `local_path` (the external checkout/directory containing `pack.json`) and the lowercase SHA-256 of that manifest's exact bytes. Compute pins locally; verify the separate checkout's provenance before enrolling it. A local route entry supplies `provider`, exact inference `endpoint`, `reviewed_by` and `reviewed_at`. Add one only after verifying local non-forwarding inference; a route name or timestamp alone cannot prove that. Restart Syleks after every registry change. The token budget above is a validated future-ledger contract, not a newly active aggregate spending cap; existing context/round limits remain the active budgets in P01.

## Schema evolution and storage

Every document explicitly carries integer `schema_version: 1` and its exact `kind`. The parser rejects unknown properties, wrong discriminators, duplicate JSON keys, trailing JSON, non-UTF-8 data, unknown versions and invalid graph/budget/decision/span invariants. All properties are required; only explicitly nullable references/decision fields accept null. There are no permissive defaults for security fields. Change to the wire shape requires a new version and explicit migration; pack content versioning is independent of the wire schema. Readers must never interpret an unsupported version using older defaults.

P01 stores only the enrollment file and reads external pack manifests/resources. It creates no workflow database, run files or session migration. Future persistence must reuse private `DataPaths` and transactional `SqliteDatabase`, add normalized project-scoped records with schema/version checks and optimistic revisions, and retain full provenance. Persist references to existing root/child sessions; do not copy conversations or usage into a second store. Task/round quotas remain distinct from token accounting. Preserve crash recovery and do not replay uncertain effects.

Approval/release authority belongs in user-owned state outside project paths and session imports. Historical gate records can live alongside runs but are not credentials or grants. Imported records cannot overwrite trusted authorizations. Full filesystem paths and private provenance are themselves sensitive metadata; neither packs nor manifests are automatically uploaded.

## Local template repository interface

The template repository stays separate. A user checks out or provisions a known version locally and supplies its absolute directory, ID, exact version and manifest SHA-256. Syleks does not fetch it. The directory contains `pack.json` with manifest entries referring to normalized relative files and their SHA-256 digests. Framework/skill files are bounded UTF-8 text; workflow files follow the workflow definition schema. Symlinks in every path component and references outside the pack are rejected. The loader verifies all entries before returning any snapshot, limits each file to 128 KiB and total resource bytes to 1 MiB; manifests/documents are at most 1 MiB, nesting is at most 32, and pack manifests have at most 128 entries. No file contents are logged on validation failure.

Only schemas, the generic reader and synthetic test fixtures live in Syleks. No actual engineering framework, technology instructions, production skill or template repository layout beyond this manifest interface is bundled. P01 validates selected packs during enrollment but does not inject their contents into existing chat prompts or install them under existing agent/skill directories.

## Runtime confidentiality and release

For an enrolled project, confidential input includes the whole conversation and its ancestry, file/tool results, project instructions, summaries, derived analysis and their metadata. No PRIVATE→PUBLIC transition follows from paraphrasing, summarization, local extraction or model output. `provenance` records origin; authorization to release is a separate decision bound to an exact representation and destination.

P01 enforces a conservative enclave:

- Before provider dispatch/catalog work for an inference request, and again before each transport send/continuation, check immutable enrollment and route policy. Legacy calls, child turns and summary requests use these checks too.
- Permit only exact user-reviewed direct inference routes to literal `127.0.0.1` or `[::1]`. Reject hostname guesses, alternate paths/ports, configured proxies and unreviewed loopback services. Disable implicit system proxies for enrolled projects. The reviewer must separately verify that the local service cannot forward private content; this code cannot attest to the listener's internals.
- Deny external tools before registry resolution/approval/effects, including shell, web, MCP and arbitrary custom tools. MCP discovery cannot spawn an enabled server first. Native local effects still pass existing permission and ancestry checks.
- Block Engineering cloud inference even with `allow_cloud=true`. With false, report CLOUD_DISABLED. With true, ChatGPT reports AUTHORIZED_RELEASE_REQUIRED; API-key cloud adapters report OFFICIAL_SUBSCRIPTION_REQUIRED. There is no active release executor in P01, so there is no downgrade to a generic API or credential workaround.

Future cloud release requires all of: project/environment ceilings permit it; trusted authorization covers the final outbound payload and exact destination; original private lineage is retained; the selected account has official subscription scope/model access; all ancestor tool permissions permit the action; the request fits the shared budget. The implementation must authorize tool schemas, instructions and subsequent tool outputs as well as user-visible prose, and must recheck changed policy/bytes. No automatic release based on a model's claim of sanitization is allowed.

The existing official ChatGPT integration is documented by OpenAI's [registration/sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in) and [models/inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference) contracts and was checked in P00. Eligibility, grants, model catalog and completed inference remain separate; P01 adds no new provider capability and performs no live sign-in. Existing API-key providers remain available to unenrolled legacy chats.

## Errors and cancellation

Internal validation/dispatch failures use `IOException` with a stable content-free `Engineering: CODE` diagnostic. Codes include INVALID_DOCUMENT, UNSUPPORTED_VERSION, DOCUMENT_TOO_LARGE, INVALID_DEPENDENCY, CYCLIC_DEPENDENCY, DUPLICATE_IDENTITY, INVALID_BUDGET, INVALID_DECISION, INVALID_TIMESTAMP, INVALID_SOURCE_SPAN, UNTRUSTED_PATH, HASH_MISMATCH, PACK_MISMATCH, CLOUD_DISABLED, AUTHORIZED_RELEASE_REQUIRED, OFFICIAL_SUBSCRIPTION_REQUIRED, UNVERIFIED_LOCAL_ROUTE, EXTERNAL_TOOL_BLOCKED and CONFIGURATION_CHANGED_RESTART_REQUIRED. Enrollment converts failures to `IllegalStateException("Engineering: INVALID_REGISTRY")` so startup cannot proceed with a partially trusted policy. This diagnostic intentionally hides paths and nested validation values. No schema input or credentials are echoed.

Malformed/version/trust/hash errors require correcting local data; they are not retried remotely. Policy/approval errors require a deliberate user decision and, for enrollment changes, restart. Cancellation produces `InterruptedIOException` with CANCELLED while preserving the interrupted flag; existing tool/agent layers propagate cancellation. Reads check interruption between files; validation and hashing operate on bounded inputs. P01 does not promise forced interruption during a filesystem syscall or a same-user filesystem race. A future run cancelled during an uncertain effect must persist INTERRUPTED/reconciliation-needed state; it must not auto-retry the effect.

See [compatibility](COMPATIBILITY.md), [validation evidence](P01-VALIDATION.md) and the ADR's explicit approval request before using these documents as a stable external API.
