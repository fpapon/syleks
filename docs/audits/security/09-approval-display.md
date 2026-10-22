# SEC-09: Preserve approval display integrity

Status: not started. Priority: hardening. Dependencies: coordinate with SEC-05.

## Finding and relevant code

Terminal rendering filters ISO control characters and escapes. Unicode bidirectional formatting and some invisible characters can still make a command or filename appear different from its logical content. This is a display-spoofing risk; the review did not demonstrate terminal code execution.

Read [TerminalCanvas.java](../../../src/main/java/fr/openobject/ai/syleks/tui/TerminalCanvas.java), [PermissionPrompt.java](../../../src/main/java/fr/openobject/ai/syleks/tui/PermissionPrompt.java), [ChatRenderer.java](../../../src/main/java/fr/openobject/ai/syleks/tui/ChatRenderer.java), [TerminalApp.java](../../../src/main/java/fr/openobject/ai/syleks/tui/TerminalApp.java), and [PermissionRequest.java](../../../src/main/java/fr/openobject/ai/syleks/model/PermissionRequest.java).

## Implementation contract

- Render security-sensitive fields through a shared presentation function that visibly represents bidirectional formatting controls and dangerous invisible/control characters using stable labels such as `U+202E`.
- Define the exact character policy. Preserve ordinary right-to-left text, combining marks, Unicode filenames, and emoji where possible; do not classify entire languages or all formatting characters as hostile.
- Display executable/script, workspace, target, policy source, grant scope/lifetime, and destination origin clearly. Keep full action details reachable; truncation must be visible and cannot hide security-relevant suffixes.
- Rendering must not change the command/path actually authorized or executed. Bind the displayed approval to its immutable request/action identity and reject stale confirmations.
- Keep ESC/OSC/C0/C1 filtering, bracketed-paste protection, modal focus, resize handling, and keyboard navigation intact. Approval text is untrusted content, not terminal markup.

## Acceptance and evidence

Test bidi overrides/isolates, zero-width characters, control/escape sequences, multiline commands, long suffixes, mixed-direction filenames, ordinary Arabic/Hebrew, combining characters, emoji, narrow terminals, and resizing. Confirm the displayed label is visible and the original logical target reaches the policy/execution layer unchanged. Test stale request IDs and pasted confirmation keys.

Provide the character policy, renderer tests (`CanvasTest`, `PermissionPromptTest`, `TerminalPainterTest` and relevant integration tests), plus screenshots or PTY evidence of security-sensitive examples. Follow [validation.md](validation.md).
