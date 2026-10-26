# Exact remembered approvals

Every prompt starts on **Allow once**. **Remember exact** authorizes the reviewed action for up to **15 minutes**, within the current runtime and conversation. It does not authorize other targets of that tool. No directory, origin, wildcard or executable-prefix scopes are offered.

```text
/approve once
/approve remember
/approvals
/approvals revoke GRANT-ID
/approvals revoke all
```

`/approve` means once. The old `/approve always` spelling remains an alias for **exact** memory with the same expiration; it never restores the former broad tool/agent grant. `/permissions [AGENT]` includes that agent's exact grants. `/approvals` lists all active grants with IDs, canonical targets, action fingerprints, workspace/session/task scope and expiration. Revocation stops future reuse; it does not undo an effect already approved or completed. Revoke-all also rejects the currently pending request and retires its prompt ID.

The panel's Left/Right or Tab/Shift+Tab controls choose Once, Remember exact, or Reject. Enter confirms the selected choice; pasted text cannot confirm it. Up/Down and Page Up/Page Down scroll the complete details, and Ctrl+F expands them. Each replacement prompt resets to Once. The command interface and panel use the same approval implementation. Old prompt IDs cannot approve a replacement request.

## What an exact grant covers

The in-memory identity schema is:

| Field | Binding |
| --- | --- |
| Agent and operation | Exact active agent and individual permission operation |
| Canonical target | Resolved existing ancestor plus missing path suffix; URLs normalize scheme/host/default port and retain path/query; Bash preserves the complete literal script |
| Action SHA-256 | Type-tagged hash of tool name, every frozen JSON argument, and the reviewed effect details; map ordering is irrelevant, list ordering and values remain significant |
| Workspace | Canonical launch directory |
| Session | Persistent conversation ID or a fresh runtime-local conversation identifier |
| Task | Exact child session ID and ancestor/model context; manual legacy child calls use their generation identity |
| Policy revision | Fingerprint of loaded global configuration/instructions/agent definitions and current project definitions/trust record |
| Execution/account context | Selected model/provider/account state and revision, protected account-record fingerprints, sandbox policy and frozen command environment fingerprint |
| Grant ID and expiration | Random UUID, UTC expiration display, and an independent monotonic 15-minute deadline |

The store is bounded to 1024 grants, with the oldest entry evicted when full. Reuse does not renew the deadline. The UTC time is informational; changing the system wall clock cannot extend authorization. A rememberable pending review also expires after 15 minutes, before either Once or Remember can accept it.

Tool arguments are recursively copied into immutable JSON maps/lists before validation or waiting for approval. A one-time approval resumes only its original invocation. The worker rechecks its action identity and context before continuing. Changed arguments, effect details, canonical targets or account context do not inherit either a one-time response or a remembered grant.

Examples:

- Remembering `printf ok > result.txt` does not approve `printf changed > result.txt`, another redirection, or a different `timeout_seconds` value. Identical scripts remain bound to the workspace, sandbox, environment and account context; this is not a command-prefix rule.
- Remembering a write to `file.txt` with content `one` does not approve content `two`. Edit replacements and complete patch payloads are part of the identity, including overwrite/replace flags.
- A literal filename `*.txt` grants only that literal target, never a filename pattern.
- A remembered GET does not approve another path, query or host/port, including a redirect destination. The destination must pass policy and its own approval before any request is sent.
- An exact action can be reused within one child task. A later task of the same agent has a different identity and must obtain its own approval. Every ancestor's constraints are checked first on each invocation.

Chat requests, delegation, exports, questions, undo/redo, patches containing deletions, and custom/MCP extension effects offer **Once and Reject only**. Extensions have no general declaration proving that an effect is reversible, so they do not receive a remembered scope. Policy `allow` remains an explicit user configuration decision; a remembered approval only relaxes a matching `ask`. Effective `deny` and unsupported shell syntax always win, even if a callback returns true.

## Lifecycle and invalidation

| Event | Result |
| --- | --- |
| Identical action under ASK, same live context | Reuse until original expiry |
| Changed target, payload, timeout, agent, task or account context | Separate approval required; no implicit broader scope |
| Fifteen minutes elapsed | Grant removed on next lookup/list; review must be obtained again |
| Revoke ID | Remove exactly that grant |
| Revoke all | Clear memory and reject any pending prompt |
| Cancellation, clear, model/agent change | Clear all grants |
| New/open/resume/fork/rename/session identity action | Clear all grants |
| Provider/account selection, reconnect/logout, protected account record change | Invalidate prior memory and reject a pending review with changed context |
| Global policy/agent definition or project definition/trust change | Clear memory and refuse execution/approval until restart; stale authority is never hot-reloaded |
| Restart or replacement runtime | Empty store; grants are never loaded from disk |

No configuration setting or schema migration is needed. Existing volatile broad grants disappear on restart; new approvals always use this narrower contract. Remembering never writes permission configuration or creates a reusable capability in session exports. Session execution records may report `USER_EXACT` or `SESSION_EXACT` as audit metadata, without importing grant authority on resume.

Live policy validation runs before each tool invocation and again around remembered authorization and approval waits. Account fingerprints include selection/identity/renewal metadata and fallback credential revisions without exposing token values or unlocking the credential vault. Refresh/reconnect can conservatively invalidate grants even if the account ID stays the same. Global and project instruction content still follows the existing trust/permission ceiling rules.

These checks do not make filesystem access atomic against an external same-user writer. Existing file-tool and sandbox boundaries still apply; race-resistant access is SEC-07 work. Grant targets and reviewed details remain subject to the existing terminal sanitization; approval-display integrity is tracked separately by SEC-09. Trusted Java extensions retain their documented process authority, but cannot acquire remembered grants through model arguments.
