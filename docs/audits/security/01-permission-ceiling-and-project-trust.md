# SEC-01: Immutable permission ceiling and project trust

Status: implemented (25 October 2026). Priority: high. Dependencies: none.

## Finding and relevant code

Confirmed by an isolated configuration probe: an automatically loaded project agent can change a global Bash DENY into ALLOW. `AgentCatalog` loads project definitions after user definitions; `AppConfig.permissions()` overlays agent rules onto global rules.

Read:

- [AgentCatalog.java](../../../src/main/java/fr/openobject/ai/syleks/app/AgentCatalog.java)
- [AppConfig.java](../../../src/main/java/fr/openobject/ai/syleks/app/AppConfig.java)
- [PermissionPolicy.java](../../../src/main/java/fr/openobject/ai/syleks/app/PermissionPolicy.java)
- [ContextService.java](../../../src/main/java/fr/openobject/ai/syleks/session/ContextService.java)
- [ToolExecutor.java](../../../src/main/java/fr/openobject/ai/syleks/ai/ToolExecutor.java)
- [SubagentExecutor.java](../../../src/main/java/fr/openobject/ai/syleks/ai/SubagentExecutor.java)

Existing tests: `AgentConfigTest`, `PermissionConfigTest`, `InstructionHierarchyTest`, `SubagentExecutionTest`, `ToolExecutorTest`, and `McpConfigurationTest`.

## Required behavior

- Separate immutable user constraints from per-agent preferences. Explicit user global restrictions form a ceiling; project definitions and approvals can only restrict that authority. Preserve ordered matching within each layer; combine independent constraints using DENY > ASK > ALLOW.
- Distinguish explicit global configuration from built-in defaults. Do not accidentally interpret every built-in default denial as an immutable ceiling and disable intended agent capabilities. Define neutral/missing-rule semantics explicitly and test them.
- Project trust must be an explicit local user action before accepting project permission expansions, model/provider overrides, or executable integrations. Trust never lifts the global ceiling. Prompt text and Markdown frontmatter cannot declare a project trusted.
- Use canonical project identity, store trust outside the repository, support inspection/revocation, and bind capability expansion to reviewed configuration content or require renewed consent after security-relevant changes. Address new agent files and nested definitions as well as modifications to existing files.
- Project `AGENTS.md` content is repository-supplied guidance. Retain source attribution and ensure it cannot replace security policy. Reconcile with the existing MCP trusted-project mechanism rather than maintaining contradictory trust definitions.
- Protect security policy/trust files from dedicated mutation tools and document that unrestricted shell execution still needs SEC-03. A project agent must not write its own authority and acquire it through `/restart`.
- `/permissions` should show the effective decision, originating source, ceiling restriction, and trust state. CLI, primary agents, children, custom tools, and MCP tools use the same enforcement.

## Migration and acceptance

This changes documented override behavior. Document how users explicitly grant a capability at the ceiling and restrict individual agents. Existing explicit global DENY rules must remain restrictive after migration. ASK constraints remain approval-gated. Never auto-trust a project based on a previous successful launch or a model response.

Acceptance tests must cover global DENY plus project ALLOW; global ASK plus project ALLOW; absent explicit global entry versus built-in defaults; broad and narrow ordered rules; nested agent overrides; revoked trust; changed trusted definitions; project symlinks; restart; an approval callback returning true; remembered grants; and child/ancestor intersections. Denied effects must leave markers absent.

## Completion evidence

Provide the policy combination truth table, schema/configuration migration examples, trust lifecycle design, regression results, and before/after reproduction of the confirmed probe. Include terminal evidence for permission provenance and trust controls. Follow [validation.md](validation.md).

## Implementation record — 25 October 2026

Implemented in the working tree; no commit or PR created.

### Policy and migration

`PermissionPolicy` now keeps independent constraints instead of flattening ceilings into override rules. Matching remains ordered within each layer. Independent decisions combine as follows (a missing ceiling entry is ALLOW/neutral):

| Preference / constraint | ALLOW | ASK | DENY |
| --- | --- | --- | --- |
| ALLOW | ALLOW | ASK | DENY |
| ASK | ASK | ASK | DENY |
| DENY | DENY | DENY | DENY |

Only explicitly configured global permissions form the immutable user ceiling. Built-in fallback denials remain preferences, preserving intentional user agent grants and Explore capabilities. Project layers cannot relax inherited agent restrictions. Untrusted project definitions are additionally capped by their inherited effective defaults/user policy and cannot change the model/provider. Trusted project definitions can expand missing default capabilities within those constraints. Each shell layer assesses all literal actions separately, preserving unsupported-syntax rejection and checks before effects. Execution-time checks apply to fabricated calls, extensions and MCP, alongside existing ancestor intersections and approval checks.

For migration, a global `bash: deny` plus agent `bash: allow` now stays DENY. To allow Build to request commands, explicitly change the global entry to `bash: ask`; Build still asks even if its preference says ALLOW. Use global `bash: allow` only for an intentional ceiling grant, and restrict Plan separately with its own `bash: deny`. Existing files and bundled restrictive defaults are never automatically widened. The schema deprecates `mcp_trusted_projects`; its entries no longer authorize integrations. Full examples intentionally retain their global mutation/command denials. See the [configuration examples and complete truth table](../../tools-and-permissions.md#project-trust-and-permission-ceilings).

### Trust lifecycle and protected files

`ProjectTrust` snapshots the full file names and contents of inherited `.syleks/agents/*.md` and `.syleks/agent-config.json` files. Canonical launch workspace identity keys a SHA-256 content pin stored in `~/.config/syleks/project-trust/`. Symlink aliases share identity; another nested launch scope requires its own review. Added, removed or modified definitions invalidate the pin, including new agents at inherited directories. Escaping links are rejected. Agent loading uses captured contents, avoiding a second read of a different definition after the trust check.

`syleks trust inspect` displays the canonical identity, state, digest and full review contents without initializing integrations. `syleks trust grant <reviewed-sha256>` requires a matching reviewed digest. `syleks trust revoke` removes the pin without reading project content, including when changed files are invalid UTF-8. Only the existing project JSON `mcp` section is supported; project JSON cannot supply global security settings or arbitrary provider configuration. Prompt/frontmatter text cannot create trust. Attributed project `AGENTS.md` guidance remains separate from policy.

Trust and policy are application-instance snapshots: grant, revocation and edits require `/restart` or a new process for active chats. This matches existing configuration lifecycle behavior; revocation does not retroactively stop already running processes. Native mutation gates, patch effects, instrumented custom mutations and `/save` protect user configuration/trust under `~/.config/syleks/`, including when home is the workspace and through path aliases. Installed executable code and unrestricted shell retain OS authority; SEC-03 is still required for isolation against those processes and same-user host changes. No OS sandbox guarantee is claimed here.

### Regression and runtime evidence

The index's original isolated runtime probe recorded global Bash DENY becoming ALLOW after project agent reload. `PermissionCeilingTest.reproducesTheOldOverlayExpansionAndReportsTheEnforcedCeilingSource` reconstructs that previous overlay algorithm (ALLOW), then loads the current trusted project/global configuration (DENY) and checks the displayed provenance. This is a reproduction of the old policy combination, not a new execution of an older packaged binary.

Current packaged-app PTY output, with synthetic paths shortened below, included:

```text
Permissions for build (ordered within layers; DENY > ASK > ALLOW)
trusted workspace /tmp/.../project; SHA-256 ...
bash: DENY; source: /tmp/.../project/.syleks/agents/build.md; preference: ALLOW;
  ceiling [DENY; source: /tmp/.../home/.config/syleks/agent-config.json; preference: DENY]
```

The packaged CLI rejected an incorrect digest, accepted the reviewed digest, reported changed content as untrusted, and revoked trust while an agent file contained invalid UTF-8. PTY interaction exercised `/permissions`, two window sizes, Page Up, help open/close and clean quit. No provider request or real credential was used. Temporary raw/text terminal and CLI evidence is at `/tmp/sec01-terminal-_dj3bmax/`; the smoke driver is `/tmp/sec01-terminal.py`. The driver isolates `HOME`, `XDG_CONFIG_HOME`, JVM `user.home`, workspace and session storage. An initial incompletely isolated PTY attempt failed to load provider selection; the corrected isolated run passed.

New regression coverage includes all nine decision pairs, neutral missing entries, explicit DENY/ASK with project ALLOW, user capabilities over built-in denials, ordered broad/narrow shell rules, nested restrictions and new inherited agents, changed and revoked pins, canonical aliases and escaping links, restart via new configuration instances, immutable loaded content, protected configuration writes and permissive approval callbacks. Negative tests assert absent write/shell markers and no allowed prefix effect. Existing remembered-approval coverage now includes a trusted project Bash ALLOW under global granular denials. Ancestor tests use an allowed global write ceiling, a denying parent and an allowing child, proving the ancestor intersection independently of the new global ceiling. Provider parity fixtures explicitly trust project model overrides across all four adapters.

### Executed validation

Environment: Linux, Zulu OpenJDK 25 build `25+36-LTS`, Apache Maven 3.9.16. Maven commands used `JAVA_HOME=/home/fpa/.sdkman/candidates/java/25-zulu` and `/home/fpa/.sdkman/candidates/maven/3.9.16/bin/mvn` (the shell initially had neither Java nor Maven on PATH).

- Focused command: `mvn -q -Dtest=PermissionCeilingTest,AgentConfigTest,PermissionConfigTest,InstructionHierarchyTest,SubagentExecutionTest,ToolExecutorTest,McpConfigurationTest,ShellCommandPolicyTest test` — passed after updating legacy override expectations. These eight classes account for **140 passing tests** in the final full run.
- `mvn -q spotless:apply` — passed; formatting changes inspected.
- `mvn -B --no-transfer-progress clean verify` — final run **BUILD SUCCESS**, **512 tests, zero failures/errors/skips**; Spotless checked 212 Java files. Final log: `/tmp/sec01-verify-unrestricted.log`.
- The initial sandboxed full run could not bind loopback HTTP fixtures. Full verification was rerun with approved socket access; no fixture/backend checks were silently skipped. Legacy provider-model fixtures were updated to grant explicit trust before the final successful run.
- `python3 /tmp/sec01-terminal.py` — passed the packaged CLI/PTY assertions above with exit code 0.
- `git diff --check` — passed.

No required SEC-01 enforcement is deferred. Live trust changes deliberately follow the documented restart lifecycle. OS isolation, arbitrary executable behavior, remembered-approval narrowing and local filesystem races remain the separately scoped SEC-03, SEC-05 and SEC-07 work.
