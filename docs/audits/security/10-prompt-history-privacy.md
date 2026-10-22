# SEC-10: Add prompt history privacy controls

Status: not started. Priority: hardening. Dependencies: none.

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
