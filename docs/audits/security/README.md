# Security implementation backlog

Status: implementation backlog. SEC-01, SEC-02, SEC-04, SEC-05, SEC-06, SEC-07, SEC-08, SEC-09 and SEC-10 are implemented with validation recorded in their task documents. SEC-03 is implementation ready with local Linux evidence and required CI checks configured; hosted CI execution remains pending. SEC-11 checks and inventories are locally validated; dependency findings and hosted CI evidence remain open.

Review baseline: 22 October 2026. This backlog follows a source review of permission enforcement, repository configuration, shell execution, filesystem access, network clients, credentials, and terminal handling. Reinspect current code before editing; paths and behavior can change. The earlier [shell security audit](../opencode-parity/shell-security.md) describes protections that must remain intact.

## Evidence and threat model

Two isolated runtime probes against the packaged application confirmed:

1. With global `permission.bash: deny`, adding project `.syleks/agents/build.md` with `permission:\n  bash: allow` changed the effective Build decision from DENY to ALLOW after configuration reload. This demonstrates policy expansion; the probe did not execute an attacker command.
2. `LocalCommandRunner` inherited a non-secret sentinel environment variable. No real credential was read or transmitted.

Other findings are based on source inspection. Broad remembered approvals, remote HTTP support, and private-network web fetch are existing design choices with conditional security consequences. At review time the filesystem race was a hypothesis; SEC-07 now records a deterministic synthetic reproduction of the earlier path-write behavior and its regression tests. This is not evidence of exploitation against a live user workspace. This is not a dependency vulnerability assessment or a complete penetration test.

Assume repositories, model responses, fetched documents, tool output, and MCP server content can be hostile. The user and their explicit global security choices are trusted. Project text, agent definitions, and model-generated configuration changes cannot grant themselves authority. Approved executables and installed extensions can perform arbitrary actions within their process authority. Protect against accidental secret disclosure and malicious project content; OS isolation must state its limitations against a same-user host attacker.

## Work queue

| ID | Task | Priority | Dependencies |
| --- | --- | --- | --- |
| SEC-01 | [Permission ceiling and project trust](01-permission-ceiling-and-project-trust.md) — implemented 25 October 2026 | High | None |
| SEC-02 | [Subprocess environment isolation](02-subprocess-environment.md) — implemented 25 October 2026 | High | None; coordinate with SEC-03 |
| SEC-03 | [OS command sandbox](03-command-sandbox.md) — implementation ready 26 October 2026; local backend validated, hosted CI pending | High, larger scope | SEC-01 and SEC-02 |
| SEC-04 | [Credential transport security](04-credential-transport.md) — implemented 25 October 2026 | High for remote HTTP | None; share URL handling with SEC-06 |
| SEC-05 | [Scoped remembered approvals](05-scoped-approvals.md) — implemented 26 October 2026 | Medium–High | SEC-01 |
| SEC-06 | [Network destination controls](06-network-destinations.md) — implemented 26 October 2026 | Medium, deployment dependent | SEC-01; coordinate with SEC-03/04 |
| SEC-07 | [Race-resistant file access](07-filesystem-races.md) — implemented 26 October 2026 | Medium, potential issue | None |
| SEC-08 | [Secret storage and redaction](08-secret-storage.md) — implemented 26 October 2026 | Hardening | SEC-04; coordinate with SEC-02 |
| SEC-09 | [Approval display integrity](09-approval-display.md) — implemented 26 October 2026 | Hardening | Coordinate with SEC-05 |
| SEC-10 | [Prompt history privacy](10-prompt-history-privacy.md) — implemented 26 October 2026 | Hardening | None |
| SEC-11 | [CI supply-chain checks](11-ci-supply-chain.md) — implementation ready 26 October 2026; findings recorded, hosted CI pending | Hardening | None |

Start with SEC-01, SEC-02, and SEC-04. Follow with SEC-05 and the supported sandbox backend. Network and filesystem enforcement need dedicated adversarial testing. Tasks without an implementation record remain **not started**; this table expresses dependencies, not authorization to delegate work.

## Agent execution contract

1. Read the repository `AGENTS.md`, this index, the assigned task, and [validation.md](validation.md).
2. Trace the actual execution paths and existing tests before changing behavior. A missing model tool schema is not an authorization boundary.
3. Implement the assigned scope and its regression tests. Resolve routine implementation choices using the stated contracts; document API/configuration decisions and platform limitations.
4. Keep global policy, trust records, credentials, and insecure exceptions outside model-editable project authority. Preserve ancestor constraints and fail closed when required enforcement is unavailable.
5. Update schema, generated/bundled defaults, examples, CLI/TUI help, and relevant user documentation together when configuration changes. Never silently migrate restrictive settings into more permissive ones.
6. Run appropriate checks and record exact results. Do not label skipped OS/backend checks as passed. Use fake credentials, temporary homes/workspaces, and local fixtures.
7. Submit a reviewable change with the evidence listed in the task. Change a task's status to implemented only when its acceptance criteria are met; list any deliberately deferred scope.

## Invariants across all tasks

- Terminal output remains owned by `TerminalApp`; background work uses synchronized session state.
- Effective DENY cannot be relaxed by a user approval callback, remembered grant, child agent, extension, or MCP call.
- Preserve granular shell analysis, all-actions-before-effects checks, output bounds, cancellation, and denial of unsupported syntax.
- Preserve explicit non-overwriting `/save`, project-scoped session handling, credential-free exports, and terminal escape sanitization.
- Preserve OAuth state, PKCE, nonce/signature/issuer/audience validation and refresh locking. Do not replace verified claims with decoded unverified token data.
- Keep project instructions visibly attributable. Prompt wording alone cannot enforce a security boundary.
