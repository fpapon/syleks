# SEC-05: Narrow remembered approvals

Status: not started. Priority: medium–high. Dependencies: SEC-01; coordinate with SEC-09.

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
