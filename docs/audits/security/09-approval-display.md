# SEC-09: Preserve approval display integrity

Status: implemented 26 October 2026. Priority: hardening. Dependencies: coordinated with SEC-05.

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

## Implementation record — 26 October 2026

Implemented presentation and regression coverage in this checkout; no commit/PR reference is available.

`SecurityText.present` is a shared, presentation-only function. Approval descriptions and details pass through it before wrapping; the canvas also applies it as a final defense for other terminal fields. It never rewrites `PermissionRequest`, policy targets, tool arguments, scripts or filenames. Exact request IDs and SEC-05 action/context revalidation remain the authorization binding. Background generation still updates session state; the terminal owner renders the panel.

Character policy (inclusive ranges, Unicode code points):

- Label C0/C1 (`Character.isISOControl`), U+00AD, U+034F, U+061C, U+115F–1160, U+17B4–17B5, U+180E, U+200B–200F, U+2028–202E, U+2060–206F, U+2800, U+3164, unpaired surrogates U+D800–DFFF, U+FEFF, U+FFA0, U+FFF9–FFFB and U+E0000–E007F as `[U+XXXX]` (uppercase hexadecimal, at least four digits).
- LF is labelled `[U+000A]` and retained as a layout break; CR, tab, ESC, BEL, OSC introducers and other control characters become visible labels, never terminal markup.
- Preserve all other code points, including ordinary Arabic/Hebrew, combining accents, Mongolian variation selectors, emoji and variation selectors. Explicit joiners/non-joiners and invisible emoji tags are labelled, so joined emoji may render as separate components. This deliberately exposes those otherwise invisible components without classifying whole scripts or every Unicode format character as dangerous.

The panel uses lossless cell wrapping rather than prose wrapping, preserving spaces and long suffixes. All sizes that support the panel show a detail-position indicator when content overflows. Ctrl+F expands; arrows and page keys reach the complete description, details and selected scope after resize. The previous arbitrary one-million-line scroll cap is removed. Selected scope remains in the fixed summary and is also available unabridged in the scrollable details. The immutable request ID is included in details.

Tool prompts identify the workspace, target, applicable policy sources/ceilings and exact grant scope/lifetime. HTTP(S) URL targets show an explicit destination origin including port. Bash prompts identify `/bin/bash --noprofile --norc -c`, workspace, timeout, environment names, sandbox description and unchanged script. Non-tool prompts show their workspace, requirements, policy sources and one-time lifetime. No configuration/schema/default migration is needed. User-visible behavior is documented in [Tools and approvals](../../tools-and-permissions.md).

Validation environment: Linux 6.12.113+deb13-amd64, Zulu OpenJDK 25+36-LTS, Maven 3.9.12. Commands used `JAVA_HOME=/home/fpa/.sdkman/candidates/java/25-zulu` and Maven `/home/fpa/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn`:

- `mvn -o -Dtest=CanvasTest,PermissionPromptTest,TerminalPainterTest,ScopedApprovalTest,PermissionConfigTest,CommandToolTest test`: 78 tests, zero failures/errors/skips. A first, smaller sandboxed run failed only because the local HTTP fixture could not open a socket; rerunning with the normal approval mechanism passed.
- `mvn -o spotless:apply`: passed. The first full suite ran 613 tests with zero failures/errors and 11 skips, then failed the formatting check because a final assertion had been added during that run. It was formatted before final verification.
- A subsequent full run caught `scrollsLongDetailsAndSupportsNarrowTerminals`: request metadata after the details obscured the final action line at the bottom scroll position. Moving that metadata before the details fixed the regression. `mvn -o spotless:apply -Dtest=CanvasTest,PermissionPromptTest,TerminalPainterTest test` then passed all 22 UI tests with zero failures/errors/skips.
- `python3 /tmp/sec09-pty.py`: passed 18 frames in a synthetic PTY probe against the final compiled classes. The saved, sanitized output is linked below.
- Final clean verification and diff results are recorded below.

Negative evidence: `CanvasTest` exercises bidi overrides/isolates, invisible characters, controls and ordinary Unicode. `PermissionPromptTest` scrolls mixed-direction filenames, multiline commands and long suffixes at multiple widths and confirms that the original request is unchanged. It feeds bracketed-paste Enter/Tab/approval text through `TerminalApp.handle`: no confirmation or navigation occurs; explicit Enter submits the displayed ID. `TerminalPainterTest` proves raw bidi/OSC/BEL never reach the output writer across frame-size changes. `ScopedApprovalTest` creates only the original logical mixed-direction filename with its original payload after explicit approval; no file exists before approval, and no display-labelled alternate filename is created. Existing stale-ID, changed-payload/context and DENY regressions remain intact. The local web fixture receives no request merely from displaying its origin, and a refused changed origin receives none.

[PTY evidence](evidence/sec09-pty.txt) records a synthetic renderer/painter plus `TerminalApp.handle` harness on a Linux PTY: expansion, selection, pasted Enter, page scrolling, resize from 100×30 to 35×15 to 80×24, explicit confirmation and visible control labels/suffixes. Only synthetic paths/content were used; ANSI sequences were removed from the saved evidence. This checks emitted text and input behavior, not font-specific visual glyph positioning. No live model/provider or real secret was used.

Limitations: this is an explicit display-control policy, not Unicode confusable detection or a guarantee about every terminal's bidi/font/combining-width behavior. Tiny terminals retain the existing resize-required screen; confirmation remains tied to the last displayed request. Installed extensions remain responsible for supplying truthful, sufficiently complete approval descriptions; overlong extension details are rejected by the existing limit. The 11 opt-in `LinuxCommandBackendTest` tests were skipped because `SYLEKS_SANDBOX_TESTS=required` was not set; they are not claimed as passes for SEC-09. No SEC-09 enforcement is deliberately deferred.

Final validation: `mvn -o -B --no-transfer-progress clean verify` passed (2 min 22 sec): 613 tests, zero failures/errors, 11 opt-in Linux backend tests skipped; packaging, license and Spotless checks passed. `git diff --check` passed. Audit links and sanitized PTY evidence checks passed. The final PTY capture contains 18 frames with explicit confirmation resolving only request 901 and pasted confirmation resolving none.
