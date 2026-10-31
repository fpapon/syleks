# Chat and terminal controls

[User guide](README.md) · [All commands](commands.md)

## Write and read messages

Type in the composer at the bottom of the screen. **Enter** sends the message; **Ctrl+O** inserts a newline. Shift+Enter also inserts a newline in terminals that send the supported extended keyboard sequence. Ctrl+O is the portable choice.

Use Left/Right to move the caret, Backspace to remove the preceding character, and Delete to remove the next character. Bracketed paste lets you paste multiline text or code without sending each line as a separate message.

**Up/Down recall older/newer accepted input**, and Down past the newest entry restores your draft and cursor. Page Up/Page Down scroll the conversation. The layout adapts when you resize the terminal; enlarge it if you see a minimum-size notice.

The header identifies the project, session, and model. The composer shows the selected agent, and an animated status indicator appears during generation or tool execution. It pauses while an approval is waiting.

## Keyboard reference

| Key | In chat |
| --- | --- |
| Enter | Send the message or slash command |
| Ctrl+O | Insert a newline |
| Left / Right | Move the input caret |
| Backspace / Delete | Delete before / after the caret |
| Up / Down | Recall older/newer input; restore the draft |
| Page Up / Page Down | Scroll by eight lines |
| Mouse wheel | Scroll by three lines; scroll the focused popup when open |
| Shift+Up / Shift+Down or Ctrl+Up / Ctrl+Down | Scroll the conversation by one line |
| Ctrl+Home / Ctrl+End | Jump to the start / latest output |
| Tab | Cycle primary agents while idle |
| Ctrl+P | Open or close the help popup, keeping the draft |
| Ctrl+X | Clear input recall, preserving visible text and cursor |
| Ctrl+G | Open configuration, keeping the draft |
| Ctrl+R | Open the session picker, keeping the draft |
| Esc | Cancel active work or dismiss an overlay |
| Ctrl+C | Cancel generation or a pending approval; exit when idle |
| Ctrl+D | Exit Syleks |

Type `/help` or press **Ctrl+P** to open the help popup. Commands are grouped by task, followed by keyboard shortcuts and approval controls. Use **Up/Down** to scroll, **Page Up/Page Down** to move a page, and **Home/End** to jump to the start or end. Press **Esc**, **Enter**, or **Ctrl+P** to close it. Help stays out of your conversation, and Ctrl+P keeps your draft. The configuration form, session picker, and approval panel have their own controls: see [Configuration](configuration.md), [Sessions](sessions.md), and [Tools and approvals](tools-and-permissions.md).

## Stop work or leave

Press **Esc** or **Ctrl+C** during generation to stop the current turn. Saved response fragments remain visible. Cancelling a shell command also terminates its running process and descendants during cleanup.

Use `/quit`, `/exit`, or Ctrl+D to close the application. A normal shutdown attempts to finish saving partial output. An unexpected process stop can lose fragments since the last periodic save; [Sessions](sessions.md) explains recovery. Unsent drafts are kept when switching sessions within the running app, but are not saved across quitting.

Wait for completion, or cancel work and resolve approvals, before switching sessions, selecting another agent/model, configuring, or restarting.

## Read formatted responses

Syleks displays headings, lists, quotes, emphasis, inline code, links, and fenced code blocks. Tool activity appears with its result or status. A successful file read shows a short **Read path** entry; the full read result still reaches the model.

Ask for a language label on code blocks to get syntax colors. Supported languages include Java, JavaScript/TypeScript, Markdown, AsciiDoc, XML/HTML, JSON, YAML, TOML, CSS, Python, Ruby, Kotlin, Go, Rust, C/C++, C#, SQL, Bash, and diffs. Common aliases such as `js`, `py`, and `sh` work too; unknown languages display as plain text. For example, ask: “Show the fix in a fenced Java code block, with the filename after the language.”

Use `/demo` to preview code cards, tool activity, and a Java diff. The preview uses simulated content, does not read or change your files, and is excluded from model context. Rendering uses a lightweight Markdown implementation; pipe tables support column alignment (`:---`, `:---:`, `---:`), inline styles, escaped pipes (`\|`), and pipes inside code spans. Cells wrap to the available width; narrow screens stack labelled values. Tables inside fenced code and user messages remain literal. Some Unicode character combinations may display imperfectly.

## Inspect recorded changes

Run `/diff` to open the read-only snapshot viewer. Filenames select source-language colors, with separate old/new line numbers and colored addition/removal markers and backgrounds. Use `n`/`p` for next/previous change block, `N`/`P` for next/previous file, arrows to scroll or pan, PgUp/PgDn to page, and Home/End for the edges. Esc or `q` closes the viewer. It shows whole before/after snapshots (including child tasks), not a minimal patch, and does not modify files or add chat messages. `/changes` still lists recorded edits; `/undo` and `/redo` restore them subject to permissions.

## Save a readable copy

```text
/save notes.md
```

This exports the displayed conversation as Markdown and asks for approval if your `save` policy requires it. Relative paths start in the launch directory. Existing files are never overwritten. Tool activity is hidden by default. With `debug_llm: true`, read activity appears as a short summary in this export. See [LLM output settings](configuration.md#llm-output).

For a complete structured export, use [session JSON export](sessions.md). Both formats can contain confidential information.

## Try a specialist workflow

With the [example configuration and agent definitions](../examples/README.md) installed, select `/agent build`, then send `@explore Find the configuration loader and explain its inputs, with file references`. A child runs with its explicit task and returns an answer. Use `/children` to open its history and `/parent` to return. `/permissions reviewer` shows another specialist's policy before invocation. Hidden agents such as the example Triage require their explicit name.

## Input recall privacy

Recall holds at most 500 accepted prompts and commands in memory, suppressing consecutive duplicates. It survives session switches and `/restart`, and is never saved to disk. Provider/configuration dialog input and rejected prompt submissions do not enter recall.

Start a submission with an ASCII space to exclude that entry from recall, including commands and multiline input. The exclusion is checked before submission whitespace is stripped. This is deliberate exclusion, without secret detection.

Use `/history clear` or **Ctrl+X** in the composer to forget recalled entries and navigation snapshots. Ctrl+X preserves the currently visible text and cursor, including a recalled entry you are editing; erase that text separately if needed. `/history off` clears existing recall and saves a preference to disable further collection; `/history on` enables collection again without restoring old entries. `/history` reports the setting. Privacy commands never enter recall themselves. Clear and disable also prevent old recall entries from transferring through a later restart.

These controls affect input recall only. Submitted prompts can still be sent to the provider and stored in the SQLite conversation, and conversation exports remain available through `/save`. Unsent drafts still survive session switches and restart. Clearing recall does not delete conversations, drafts or provider data, and Java immutable strings do not permit a memory-zeroization guarantee. See [configuration](configuration.md#input-recall) for the saved preference.

## Live work and timers

During a turn, the output panel describes the current activity: waiting for the model, reasoning, writing an answer, reading/searching files, using a tool, or delegating work. These short explanations remain visible with `debug_llm: false`; raw command output and tool dumps still require debug mode.

Delegation shows the subagent name, the assigning agent, and a bounded explanation from the assigned task prompt. Parallel and nested tasks have independent activity rows and elapsed timers. When the provider supplies subagent reasoning, a short excerpt appears under that task. Completion notices show the outcome and elapsed time, including failures, denied tasks, and timeouts.

The footer shows the current turn's elapsed time as `minutes:seconds`, updating without keyboard input and freezing at completion or cancellation. The live activity row also shows time in the current phase and accumulated reasoning/model-wait and task-execution time. Waiting for your permission or an answer counts toward total elapsed time but not reasoning or task totals. Parallel task time in the main row is wall-clock waiting time, not the sum of child execution times. These timers describe observed application activity, not provider-internal compute time. They reset for a new turn or a different session; live progress is not added to model context.
