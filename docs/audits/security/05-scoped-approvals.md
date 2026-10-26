# SEC-05: Narrow remembered approvals

Status: implemented 26 October 2026. Priority: medium–high. Dependencies: SEC-01; coordinate with SEC-09.

## Finding and relevant code

`ChatSession.ToolGrant` currently contains only agent and operation. An Always approval can relax subsequent ASK decisions for every target of that tool in the session. Explicit effective DENY still wins. This is documented existing behavior, not a demonstrated DENY bypass.

Read [ChatSession.java](../../../src/main/java/fr/openobject/ai/syleks/ai/ChatSession.java), [PermissionRequest.java](../../../src/main/java/fr/openobject/ai/syleks/model/PermissionRequest.java), [PermissionPrompt.java](../../../src/main/java/fr/openobject/ai/syleks/tui/PermissionPrompt.java), [TerminalApp.java](../../../src/main/java/fr/openobject/ai/syleks/tui/TerminalApp.java), and [SubagentExecutor.java](../../../src/main/java/fr/openobject/ai/syleks/ai/SubagentExecutor.java). Existing tests: `PermissionPromptTest`, `PermissionConfigTest`, `CommandToolTest`, `SubagentExecutionTest`.

## Implementation contract

- Default remembered approval to the exact reviewed action, bound to agent, operation, canonical target, workspace/session, policy revision, and expiration. Bash grants should cover the exact reviewed script and execution context, not just an executable prefix.
- Bind write/edit/patch approvals to reviewed effect parameters where required: permission to touch a path is not automatically approval of every future replacement. Never replay one-time approvals for changed arguments.
- Offer broader path/origin scopes only through an explicit user choice with a precise description. Do not turn a literal filename containing wildcard characters into a grant pattern. Do not offer an unrestricted remembered Bash grant as the default.
- Reevaluate policy ceilings and all ancestor constraints for every invocation before consulting grants. Unsupported shell syntax and explicit denials remain denied.
- Provide list/revoke controls and expiration. Invalidate grants on trust or policy changes, session identity changes, account-sensitive context changes, and restart. Preserve the existing conservative reset behavior unless a narrower documented contract replaces it.
- Keep chat, save/export, delegation, and irreversible effects one-time where currently required. Model content cannot select the approval choice or pre-create a grant.

## Acceptance and evidence

Approve one action, then attempt a different command, path, destination, agent, payload, workspace, or child task. Each must require its own approval or be denied. Test exact reuse, literal wildcard filenames, revocation, expiry, policy tightening, stale prompt IDs, interrupted waits, concurrent requests, and UI/CLI agreement. A callback returning true must not bypass DENY.

Update help and permission displays with grant scope and lifetime. Supply the grant identity schema, lifecycle table, adversarial test results, and terminal evidence. Follow [validation.md](validation.md).

## Implementation record — 26 October 2026

Implemented in the working tree; no commit or PR created. `ApprovalGrants` replaces the agent/operation-wide grant with an exact identity, a random revocation ID and a monotonic 15-minute deadline. The [identity schema and lifecycle table](../../approvals.md) specify agent/operation, canonical target, full immutable JSON arguments and effect details, workspace/session/task, policy revision, model/provider/account and execution context. Reuse does not extend expiration. Literal wildcard filenames are never interpreted as patterns. The store is memory-only and bounded to 1024 entries.

`ChatSession` defaults every prompt to Once and offers Remember exact only for supported native effects. `/approve remember` and the panel share the same implementation. Legacy `/approve always` is a compatibility alias for the new exact scope, never the previous broad behavior. `/approvals` lists scope/expiry/IDs; `/approvals revoke ID` removes one grant, and `revoke all` also rejects any pending request. `/permissions` includes the selected agent's grants. Help, renderer labels and user documentation were updated. There are no new configuration keys, persisted grant files or schema changes; restart discards all old volatile approvals.

No broader directory/origin/prefix scope is offered. This is a deliberate conservative choice within the contract. Chat, exports, delegation, questions, maintenance undo/redo, patches containing deletions, and custom/MCP extension effects are one-time. Generic extension metadata cannot establish whether an effect is reversible. Native writes, replacements and patches bind the entire payload/flags; Bash binds the exact script, timeout and frozen environment/sandbox context. JSON arguments are recursively frozen before permission waits, including nested extension/MCP values.

Effective policy and every child ancestor constraint are reevaluated before grant lookup and after an approval wait. The shell's existing full-script preflight still rejects forbidden actions or unsupported syntax before any prefix effect. Queued requests recheck authority after waking. Child grants bind the actual child session, preventing reuse in a later task of the same agent. Stale IDs and interrupted waits cannot approve their replacements. One-time approvals also recheck their reviewed action/context before continuing.

Grant memory clears on cancellation, clear, agent/model/session actions, replacement persistence/provider services and context changes. `ProviderService` maintains an approval revision for account/model changes and reconnect/logout, plus protected selection/account-record fingerprints. Credential values are never exposed by these fingerprints and the credential vault is not unlocked for this check. Loaded policy/trust fingerprints cover global configuration, global instructions/agent definitions and project definitions/trust records. Changes clear memory and refuse tools/approval until restart, rather than hot-loading authority into a reviewed invocation. Configuration loading checks the revision both before and after parsing; a deterministic proxy-mapper test changes ALLOW to DENY during loading and confirms startup fails closed.

Validation environment: Linux amd64, Zulu OpenJDK `25+36-LTS`, Maven `3.9.16`. The full suite retained the required real Linux backend checks (Bubblewrap `0.12.0`, systemd `257.13-1~deb13u1`, cgroup v2 on kernel `6.12.113+deb13-amd64`). Maven used these inline assignments:

```sh
JAVA_HOME=/home/fpa/.sdkman/candidates/java/25-zulu
PATH=/home/fpa/.sdkman/candidates/java/25-zulu/bin:/home/fpa/.sdkman/candidates/maven/3.9.16/bin:$PATH
```

Commands and executed results:

```sh
mvn -B --no-transfer-progress -Dtest=ApprovalGrantsTest,ScopedApprovalTest,CommandToolTest,PermissionConfigTest,PermissionPromptTest,CanvasTest,ToolExecutorTest,SubagentExecutionTest,ProviderServiceTest test
# PASS: 100 tests, 0 failures/errors/skips at that stage.
mvn -B --no-transfer-progress spotless:apply clean verify
# SYLEKS_SANDBOX_TESTS=required; PASS: 570 tests, no failures/errors/skips before the load-consistency regression was added.
mvn -B --no-transfer-progress -Dtest=HelpModalTest,PermissionPromptTest,CanvasTest test package spotless:check
# PASS: 20 UI tests, packaging and formatting after the final help-label update.
mvn -B --no-transfer-progress spotless:apply -Dtest=AppConfigTest test
# PASS: 11 tests, including deterministic policy change during loading.
mvn -B --no-transfer-progress clean verify
# SYLEKS_SANDBOX_TESTS=required; final BUILD SUCCESS: 571 tests, 0 failures/errors/skips; shaded JAR, license and formatting checks passed.
python3 /tmp/syleks-sec05-pty.py
# PASS against the final shaded JAR; disposable HOME/project/data and a synthetic local provider.
git diff --check
# PASS.
```

Final test log: `/tmp/syleks-sec05-final-verify.log`. Local socket, namespace and user-cgroup tests ran with approved access outside the execution sandbox; no unavailable backend check was counted as passed. The hosted CI workflow has not been executed from this session; its existing required full-suite job includes these new regression tests automatically.

Adversarial effects evidence: identical scripts/writes/GETs reuse the exact grant, while changed scripts, timeout values, payloads, paths, destination paths/ports, actors and child tasks require separate decisions. Literal `*.txt` is created as that literal filename; a different filename receives no grant. Denied web destinations receive zero fixture requests; only the approved GET is delivered (twice for exact reuse). Denied write/edit/patch payloads leave their marker contents unchanged. Policy tightening, trust revocation, account changes, grant expiry and stale review expiry prevent the pending effect. Individual revocation, revoke-all, clear, cancellation and fresh-runtime behavior require another review. Concurrent identical requests reuse only their matching identity; concurrent different targets produce separate prompts, and stale prompt IDs cannot approve the next one. Existing tests retain DENY enforcement against callbacks returning true and granular shell rejection with no prefix markers.

Terminal evidence: the packaged OpenAI-compatible fixture produces a write, an identical write and a changed-payload write. The real PTY confirms default Once, selection of Remember exact and its lifetime, bracketed-paste Enter refusal, Ctrl+F expansion, resize from 120×30 to 80×24, automatic exact reuse, a new Once prompt for the changed payload, Reject, list/revoke-all controls, configuration-modal navigation/Escape, synthetic API-key masking and Ctrl+D exit. The final file retains only the first payload. Exactly four local provider requests occur; no live model, paid endpoint, real credential or external service is used. The first PTY harness attempt reached the approval checks but looked for the key field before navigating the setup wizard; only the corrected, replayed final run is counted as passing. The temporary script is local evidence rather than a required repository artifact.

Remaining boundaries: no native-image build was performed. File/command actions approved before expiry/revocation may finish afterward; revocation is not rollback. External same-user filesystem races remain SEC-07, display-integrity hardening remains SEC-09, and arbitrary trusted Java extension process authority remains outside remembered approval enforcement. Broader remembered scopes are intentionally absent; no required enforcement relies on prompt wording or a model-provided choice.
