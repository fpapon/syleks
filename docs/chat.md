# Chat and terminal controls

[User guide](README.md) · [All commands](commands.md)

## Write and read messages

Type in the composer at the bottom of the screen. **Enter** sends the message; **Ctrl+O** inserts a newline. Shift+Enter also inserts a newline in terminals that send the supported extended keyboard sequence. Ctrl+O is the portable choice.

Use Left/Right to move the caret, Backspace to remove the preceding character, and Delete to remove the next character. Bracketed paste lets you paste multiline text or code without sending each line as a separate message.

**Up/Down scroll the conversation**, rather than moving through input history. Page Up/Page Down scroll farther. The layout adapts when you resize the terminal; enlarge it if you see a minimum-size notice.

The header identifies the project, session, and model. The composer shows the selected agent, and an animated status indicator appears during generation or tool execution. It pauses while an approval is waiting.

## Keyboard reference

| Key | In chat |
| --- | --- |
| Enter | Send the message or slash command |
| Ctrl+O | Insert a newline |
| Left / Right | Move the input caret |
| Backspace / Delete | Delete before / after the caret |
| Up / Down | Scroll the conversation by two lines |
| Page Up / Page Down | Scroll by eight lines |
| Tab | Cycle primary agents while idle |
| Ctrl+P | Show or close the command reference overlay |
| Ctrl+G | Open configuration, keeping the draft |
| Ctrl+R | Open the session picker, keeping the draft |
| Esc | Cancel active work or dismiss an overlay |
| Ctrl+C | Cancel generation or a pending approval; exit when idle |
| Ctrl+D | Exit Syleks |

The command overlay is a reference, not a searchable command launcher. A normal key dismisses it. The configuration form, session picker, and approval panel have their own controls: see [Configuration](configuration.md), [Sessions](sessions.md), and [Tools and approvals](tools-and-permissions.md).

## Stop work or leave

Press **Esc** or **Ctrl+C** during generation to stop the current turn. Saved response fragments remain visible. Cancelling a shell command also terminates its running process and descendants during cleanup.

Use `/quit`, `/exit`, or Ctrl+D to close the application. A normal shutdown attempts to finish saving partial output. An unexpected process stop can lose fragments since the last periodic save; [Sessions](sessions.md) explains recovery. Unsent drafts are kept when switching sessions within the running app, but are not saved across quitting.

Wait for completion, or cancel work and resolve approvals, before switching sessions, selecting another agent/model, configuring, or restarting.

## Read formatted responses

Syleks displays headings, lists, quotes, emphasis, inline code, links, and fenced code blocks. Tool activity appears with its result or status. A successful file read shows a short **Read path** entry; the full read result still reaches the model.

Ask for a language label on code blocks to get syntax colors. Supported languages include Java, JavaScript/TypeScript, Markdown, AsciiDoc, XML/HTML, JSON, YAML, TOML, CSS, Python, Ruby, Kotlin, Go, Rust, C/C++, C#, SQL, Bash, and diffs. Common aliases such as `js`, `py`, and `sh` work too; unknown languages display as plain text. For example, ask: “Show the fix in a fenced Java code block, with the filename after the language.”

Use `/demo` to preview code cards, tool activity, and a Java diff. The preview uses simulated content, does not read or change your files, and is excluded from model context. Rendering uses a lightweight Markdown implementation; complex tables and some Unicode character combinations may display imperfectly.

## Save a readable copy

```text
/save notes.md
```

This exports the displayed conversation as Markdown and asks for approval if your `save` policy requires it. Relative paths start in the launch directory. Existing files are never overwritten. Read activity remains a short summary in this export.

For a complete structured export, use [session JSON export](sessions.md). Both formats can contain confidential information.
