# SEC-10: Add prompt history privacy controls

Status: implemented 26 October 2026. Priority: hardening. Dependencies: none.

## Finding and relevant code

Input history currently stores up to 500 accepted prompts/commands in memory, suppresses consecutive duplicates, survives session switches and `/restart`, and clears on process exit. Credentials entered in separate provider/configuration modals are not ordinary composer submissions. User prompts and command arguments can nevertheless contain secrets.

Read [Editor.java](../../../src/main/java/fr/openobject/ai/syleks/tui/Editor.java), [TerminalApp.java](../../../src/main/java/fr/openobject/ai/syleks/tui/TerminalApp.java), [SessionViewState.java](../../../src/main/java/fr/openobject/ai/syleks/tui/SessionViewState.java), and [HelpModal.java](../../../src/main/java/fr/openobject/ai/syleks/tui/HelpModal.java). Existing tests: `EditorTest`, `PromptHistoryTest`, `ChatRestartTerminalTest`.

## Implementation contract

- Add an explicit clear-input-history action and a user setting to disable collection. Expose any configurable size limit with bounded validation.
- Support deliberate per-entry exclusion, such as a documented leading-space convention or one-shot private-input mode. Test exclusion before submission whitespace is stripped. Do not rely on heuristic secret detection as the privacy guarantee.
- Distinguish input recall from persisted conversation history and provider requests. Excluding an entry from recall does not prevent a submitted prompt being stored or sent. State this clearly in help and settings.
- Do not introduce disk persistence as part of this task. Clear recalled entries, navigation snapshots, and restart-transfer copies when privacy controls require it; a later restart must not resurrect cleared entries.
- A clear-history command must not immediately reinsert itself into cleared history. Disabling collection must have an explicit, tested policy for existing entries; prefer clearing them.
- Preserve draft text/cursor, multiline Unicode handling, Up/Down behavior, PageUp/PageDown scrolling, duplicate suppression, failed-submission behavior, and modal credential exclusion. Java immutable strings cannot guarantee memory zeroization; do not claim it.

## Acceptance and evidence

Test disabled collection, clear while browsing history, empty history, clear then restart, excluded entries with leading spaces, multiline and Unicode input, rejected submissions, commands, dialog secrets, session switches, and repeated privacy toggles. Assert excluded input is absent from recall/restart snapshots while documenting its independent conversation persistence.

Update keyboard/help/configuration/privacy docs and schema/defaults as needed. Provide targeted regression results and a PTY smoke check. Follow [validation.md](validation.md).

## Implementation record — 26 October 2026

Implemented in this checkout; no commit/PR reference is available.

`Editor` owns the collection policy and clears recalled entries, the navigation index and the saved navigation draft/cursor together. Clearing preserves the visible composer text and cursor, including a recalled entry currently being edited. That visible text remains an unsent draft; session-switch drafts retain their existing behavior. Restart transfers only the current recall list, and restoration filters entries through the current collection policy. Disabled collection rejects even a stale restart list. No input-recall persistence was introduced.

User controls:

- `/history clear` or Ctrl+X in the composer clears recall. Privacy commands never enter recall, including invalid variants, so clearing cannot immediately reinsert itself.
- `/history off` clears existing entries and disables collection immediately after saving the preference. `/history on` permits new collection without restoring old entries. `/history` reports the setting. A save failure reports an error and leaves the previous collection policy in effect.
- An initial ASCII space deliberately excludes one accepted submission, including commands and multiline Unicode prompts. Both submission paths pass the original editor text to the exclusion check before whitespace stripping; rejected prompts remain drafts.
- The limit remains fixed at 500 with consecutive duplicate suppression. No configurable size or new range was added.

The saved setting is `"input_history": false` (disabled) or `true` (enabled) in the existing global provider-selection `config.json`: absolute `$XDG_CONFIG_HOME/syleks`, otherwise `${user.home}/.config/syleks`. Missing defaults to `true`; non-booleans, including explicit null, fail startup validation. The protected store's configuration lock preserves other preferences/provider selection. Only the boolean is saved, never recalled text. Project configuration cannot supply this setting. This file is separate from `agent-config.json`, its schema, bundled agent defaults and the agent setup form; no schema/default migration is required. [Configuration](../../configuration.md#input-recall), [chat/privacy](../../chat.md#input-recall-privacy), commands, getting-started instructions, README and terminal help document the controls and boundaries.

Validation environment: Debian 13, Linux 6.12.113+deb13-amd64, Zulu OpenJDK 25+36-LTS, Maven 3.9.16. Maven commands used `JAVA_HOME=/home/fpa/.sdkman/candidates/java/25-zulu` and `PATH=/home/fpa/.sdkman/candidates/java/25-zulu/bin:/home/fpa/.sdkman/candidates/maven/3.9.16/bin:$PATH`.

- `mvn -B --no-transfer-progress -Dtest=EditorTest,PromptHistoryTest,PromptHistoryPreferencesTest,ChatRestartTerminalTest,ConfigurationModalTest test`: 16 tests, zero failures/errors/skips. This includes empty history, browsing clear with visible Unicode cursor preservation, disabled collection, leading-space commands and multiline prompts, rejected submissions, duplicate/cap behavior, session switches, repeated privacy toggles, modal exclusion, stale restart snapshots and actual provider/conversation behavior through a synthetic in-process transport.
- `mvn -B --no-transfer-progress spotless:apply`: passed; formatting diffs were inspected.
- Final `mvn -B --no-transfer-progress clean verify`: passed in 2 min 31 sec, 618 tests, zero failures/errors, 11 skips (607 executed). Compilation, packaging, license and formatting checks passed. The skipped `LinuxCommandBackendTest` tests require `SYLEKS_SANDBOX_TESTS=required`; they are outside SEC-10 and are not claimed as passes.
- `JAVA_HOME=/home/fpa/.sdkman/candidates/java/25-zulu python3 scripts/sec10-history-smoke.py`: passed against the packaged application in a Linux PTY, with temporary home/workspace/data and synthetic inputs. It verifies effects in SQLite, persisted disabled settings, clear/disable across fresh runtime restarts, reenable without resurrection, Unicode recall, masked configuration-key input, modal focus, help and conversation paging, resizing 120×36 → 40×14 → 120×36, no unintended submitted messages, and clean exit. [Saved evidence](evidence/sec10-pty.txt); the reproducible script keeps raw synthetic terminal output under ignored `target/sec10/`.
- `git diff --check`: passed. Python smoke-script syntax and documentation links were checked.

Validation iterations: sandboxed full verification could not open local HTTP fixture sockets and was rerun through the normal approval mechanism. An early new regression fixture sent input before its asynchronous configuration modal loaded; it now waits for the terminal completion. The first PTY harness also sent input during an asynchronous rename, causing partial text to become an unintended provider prompt and hit the socket restriction. The final harness waits for each rename's SQLite effect and terminal completion, and asserts that no extra conversation messages were submitted. Final full verification and PTY runs used approved execution permissions; no live model or real credentials were used.

Negative evidence: excluded raw input never appears in recall/restart lists; disabled restoration refuses a synthetic stale list; clearing navigation prevents Down from restoring the old navigation draft. Toggling collection repeatedly never restores old entries. The PTY checks distinct rename effects to prove excluded/disabled commands cannot be recalled, and checks that clearing survives restart. Dialog input neither creates recalled commands nor adds conversation messages, and its key field emits masking bullets without the synthetic plaintext key. A separate real `ChatSession` regression proves an excluded prompt **does** reach the synthetic provider and persist in conversation history, independently of its empty recall list.

Limitations: these controls govern recall only. Submitted prompts may still be stored in SQLite and sent to providers; `/save` remains explicit and non-overwriting. Unsent drafts, including visible recalled text, remain separate from recall and survive existing session/restart flows. Clearing does not delete provider data or conversation records, and Java immutable strings cannot guarantee memory zeroization. PTY evidence covers emitted text and behavior on Linux, not every terminal/font implementation. No SEC-10 enforcement is deliberately deferred.
