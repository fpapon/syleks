# Sessions: save, resume, and branch conversations

[User guide](README.md) · [Command reference](commands.md)

A session is one conversation in one project. Syleks saves messages, partial responses, and tool history automatically. Closing your terminal does not remove saved conversations. Reopening a session restores its history for display **and for the next model request**, within the context budget.

## Start or resume

From your project directory:

```sh
syleks             # offers this project's saved sessions
syleks --new       # starts a separate conversation
syleks -c          # resumes the latest session, or creates one if none exist
```

`--continue` is the long form of `-c`. To open a specific session:

```sh
syleks session list
syleks --session ID
```

Replace `ID` with a full UUID from the list. `-s ID` is equivalent. The latest session is based on last activity, including opening a session. Titles initially come from the first user message; no extra model call is needed to name a conversation.

## Find and switch conversations in chat

Press **Ctrl+R** or enter `/sessions`.

| Control | Action |
| --- | --- |
| Type text | Filter titles, ignoring case |
| Backspace | Remove a filter character |
| Up / Down | Move the selection |
| Enter | Open the selected session |
| Delete | Delete the selected session; Enter confirms, Esc cancels |
| Esc | Close the picker |
| Ctrl+C | Close the picker without exiting chat |
| Ctrl+D | Exit Syleks |

Rows show the title, last-use time, model, message count, and status. Times use UTC. A marker identifies the current session. Opening another conversation replaces the displayed history, updates the header, and resets scrolling for the newly loaded history.

An `IDLE` session is ready to continue, `ACTIVE` indicates ongoing work, and `INTERRUPTED` identifies work that did not finish cleanly.

Unsent drafts are kept separately for each session while the app is running. Use Ctrl+R to switch without replacing your draft with a command. Drafts are not stored on disk and do not survive quitting.

Other session commands:

```text
/new
/resume
/rename Fix the login bug
/fork
```

`/clear` also opens a new session: it does **not** delete the old conversation. Finish or cancel generation and resolve any pending approval before changing sessions.

## Projects stay separate

Sessions belong to the canonical Git working-tree root. Starting from a subdirectory of that working tree finds the same project. Git worktrees and separate clones have their own session lists, even if they share a remote URL. Without Git, the launch directory is the project.

The location of the Syleks executable does not determine the project. Run it from the project you want to work on. A session ID from another project is refused, including for export and deletion; there is no cross-project access option.

All projects share one local database outside your repositories. See [Storage and privacy](storage-and-privacy.md) for locations and backups.

## Fork a conversation

Use `/fork` in chat, or run:

```sh
syleks --fork ID
```

A fork copies the **entire conversation** at that moment into a new session, including its model and structured tool history. It records its parent and can then evolve independently. Sending messages in the fork does not change the original. Forking itself does not call the model or rerun tools.

To fork a prefix, use `/messages` to find a message UUID, then `/fork MESSAGE-ID`. The selected message is included. In the shell, `syleks session messages ID` lists boundaries and `syleks session fork ID MESSAGE-ID` prints the new session UUID without opening chat. `syleks session fork ID` copies the full conversation.

A boundary that splits a tool call from its result is refused; choose a message after all results for that call. Tool references are remapped into the fork. A summary is copied only when all messages it summarizes are inside the selected prefix. Usage, todos, file-restore authority and child sessions are not copied. Deleting the parent does not delete its forks.

## Subagent sessions

Delegation creates a separate child session with its own messages, model, tools and consumption. Use `/children`, `/session ID` and `/parent` to navigate; the session picker marks branches with `↳`. Child sessions remain inspectable after restart and can be exported. Their execution metadata records the parent tool call, effective agent ancestry, status, round count and diagnostics. Child histories cannot generate, change model or fork; return to the parent for another task. See [Subagents](subagents.md).

Latest-session resume skips child tasks. A root's usage includes its descendants without copying their usage records. Root forks copy historical task results but do not duplicate children or consumption. Deleting a parent preserves its child histories and removes their parent link; deleting child history does not refund the root's consumed task/round quota.

## Recover from interruption

Responses appear immediately and are saved in batches about every **600 ms**, plus at completion, cancellation, and errors. Press Esc or Ctrl+C to cancel active work. On a network error, the response is marked as an error and saved fragments remain accessible.

After an unexpected process stop, run `syleks -c` or open the session from the picker. Syleks detects abandoned generations and marks them interrupted, retaining the fragments already saved. A hard stop can lose text received since the last batch save.

An interrupted tool call is never replayed automatically. Inspect the project and tool history before asking to try an action again: an external action may have taken effect before the process stopped.

## Continue with the right context and model

Sessions remember their selected model. Ordinary resume keeps that model even if your configured default has changed. Use `/model provider/model` or `/model qwen3:8b` to choose a replacement explicitly. Restarting through `/restart` intentionally applies the configured default model to the reopened session.

If a provider was removed, the history remains readable; Syleks asks you to select a replacement. If a model is missing on the server, Syleks reports the server error rather than silently substituting or downloading another model.

The selected agent's current system prompt and startup-loaded global instructions are combined with the conversation's saved project instructions. The project snapshot includes `AGENTS.md` from the Git root down to the original launch directory. Changing Build → Plan → Build therefore changes the effective prompt and permissions without replacing the conversation. `/restart` reloads agents and global instructions. To apply changed project instructions or another launch scope, start `/new`. Valid tool-call/result pairs are retained in request order. Internal reasoning and visual-only parts are not automatically sent back as model messages.

A model cannot accept unlimited history. Syleks estimates size, reserves output space, and includes the most recent complete turns that fit. A notice identifies omitted older context. **The complete saved conversation remains intact**, but the model may no longer see its older details. Primary chat automatically summarizes older complete turns before request admission when estimated input reaches the configured threshold (85% by default), retaining recent turns toward a 60% target. This makes additional model requests under the existing chat permission. Set `compaction.mode` to `manual` or `SYLEKS_COMPACTION=manual` to disable automatic requests. `/compact` remains available explicitly.

If the latest turn or instructions alone exceed the budget, increase the supported context limit, select a suitable model, or start a new session with a shorter message. See [Configuration](configuration.md).

## Generate a context summary

Run `/compact` while the session is idle. It uses the selected provider/model/account with the current agent's `chat` permission. The summary request has no tool declarations or executor authority. The summarizer receives archived conversation data and the previous summary, rather than the active agent's instructions. Reported consumption belongs to this session and appears in `/usage`.

The latest user turn and its results stay verbatim. Earlier turns become a bounded, model-generated summary for subsequent requests. The saved summary records its inclusive message position, so covered history is replaced rather than sent twice. The model receives it as user reference data, outside system instructions. System/project instructions and the pinned summary share the normal context budget, including the output reserve and tool schemas. `/compact` does not delete or rewrite raw messages, reasoning, tool results or usage.

Esc/Ctrl+C cancels compaction. Empty, oversized, failed or interrupted summaries leave the prior summary unchanged and release the session lease. Another process cannot generate in that session while compaction runs. If there are no older turns to summarize, Syleks reports that instead of contacting the model. Repeated compaction includes the previous summary plus newly archived turns.

Summary input is bounded to 16 MiB and split into at most 64 budgeted requests. Each resulting summary is at most 16,000 UTF-8 bytes and may be smaller for the model's context window. A failure before the final commit preserves the prior summary; any requests already made remain counted. Automatic primary-chat compaction uses the selected model, including paid providers, and attempts at most once per generation. Failure preserves the prior summary and falls back to normal context limits, with a notice. Chat displays estimated input occupancy before and after summarization; these UTF-8 estimates are distinct from provider-reported token consumption. Child tasks retain normal context admission and their existing model-request limits. Summaries can omit details; the complete transcript stays available locally.

## Inspect and restore native file changes

`/changes` lists recorded changes and `/diff` shows a bounded textual before/after view. `/undo` restores the latest recorded native mutation batch, and `/redo` reapplies the earliest undone batch. These commands also include delegated descendants' file effects. The shell equivalents are:

```sh
syleks session changes ID
syleks session diff ID
syleks session undo ID
syleks session redo ID
```

Native `write`, `edit` and `apply_patch` store bounded before/after snapshots in SQLite before touching each file. Patch files share a batch. Each file is limited to 1 MB. Creating or deleting a file can be undone. A new edit branches away from redo history. Forks do not acquire authority to undo their parent's edits; imported snapshots are historical data only.

Before restoration, Syleks checks all expected file contents and all required approvals. A file changed externally, moved outside the current workspace or replaced by a symlink is refused. No file in a batch is restored when this initial check fails. In chat, restoration uses the active agent's `write` permission and existing ASK panel; explicit DENY still wins. A shell `session undo` or `redo` is a direct user operation. A workspace lock coordinates journaled effects between instances sharing the data directory.

This restores file contents, not conversations, Git commits, permissions or process/network effects. Shell, MCP and uninstrumented custom tools are not automatically snapshotted. Multi-file changes are not a filesystem transaction: an I/O failure or crash during commit can leave a completed prefix. A noncooperating external process can race the final content/path check. Recovery classifies pending snapshots from observed bytes and never writes files; a conflicting pending change requires inspection and a fresh session for further automatic restoration. File snapshots are retained until the session is deleted, so protect your database and exports.

## Export or delete

For a readable Markdown copy, use `/save conversation.md` in chat. It obeys `save` permissions and refuses to overwrite a file.

For the full structured record:

```sh
syleks session list
syleks session export ID > conversation.json
```

Version 2 JSON includes session metadata, messages, structured parts, tool executions, summaries, todos, usage, native file snapshots and delegated child histories. Fork branches are separate exports. It can include confidential code, prompts, reasoning, parameters, results, and secrets appearing in those contents. Store it privately and keep it out of version control. Shell redirection can overwrite a file; choose a new filename or use `set -C` in a compatible shell first. Use `syleks session import conversation.json` or `/import conversation.json` to import a validated archive into this project; see below.

In the session picker (**Ctrl+R** or `/sessions`), select a row and press **Delete**, then **Enter** to confirm or **Esc** to cancel. The picker stays open and keeps its filter. Deleting the current session clears its chat and unsent draft; a new session is created when you next send a message. Deleting another session preserves your current chat and draft. A session busy in another terminal cannot be deleted.

To delete a session from the shell:

```sh
syleks session delete ID
```

**The shell command deletes immediately, with no confirmation prompt. Deletion cannot be undone in Syleks.** Check the ID first. Related messages and tool records are deleted too; forks survive. Deletion is not secure erasure from disk or backups.

## Import an archive

```sh
syleks session import conversation.json
```

The command prints a new UUID. `/import PATH` opens the imported root session in chat. The current project's canonical root must match the archive's `projectRoot`; legacy version 1 records require the same local project ID. There is no cross-project override. Imports do not install provider/agent configuration or bind an archived provider account to your credentials. Select a missing provider/account/model explicitly before sending new requests.

All session, message, part, execution, usage-request and file-change identities are remapped. Existing sessions are never overwritten, including when importing the same archive twice. Tool references, parent/child task relations and usage are remapped together. Importing a tree preserves its own and descendant counters without charging them to the original tree. Pending work becomes interrupted and is never resumed automatically. Todo state and summary boundaries are preserved; raw transcript timestamps and ordering remain available.

Imported file snapshots are marked historical and cannot be undone/redone, so opening an archive never changes workspace files. No tools, commands, callbacks or URLs from an archive are executed. Provider credentials and remembered approvals are not imported. JSON is validated before the transaction writes any rows: supported versions, unique IDs, ordered messages/parts, paired tool references, execution parameters, task topology and nonnegative usage metrics are checked. Failure rolls back the entire import. Archives are limited to 32 MiB and 128 related sessions.

## Several terminals

You can use different sessions in multiple Syleks instances. Another instance may read the same conversation, but only one instance can generate or perform a protected change on that session at a time.

If a session is busy, finish or cancel work in the other terminal, then retry. A terminated process releases its lock automatically. Do not delete lock files to bypass a busy session. See [Troubleshooting](troubleshooting.md) for storage errors.

## Navigating large session lists

The picker filters titles, full or partial session IDs, and model names without regard to case. Matching lists are reused until you change the filter or session list. Arrows cycle through matches; PgUp/PgDn move one visible page and stop at the edges; Home/End jump to the first/last match. The footer shows your position and match count. Navigation stays disabled while confirming deletion.

## Configuration changes and specialist examples

The [example workflow](../examples/README.md) installs configuration and Markdown independently from saved histories. `/restart` applies edited configuration/agent definitions and the configured default agent/model while retaining this conversation; ordinary resume uses the session's saved model. Project-instruction snapshots remain attached to existing histories: use `/new` for changed project `AGENTS.md` instructions.

Explore/Reviewer tasks create children under the current root. Use `/children` to inspect their history and `/parent` to return. Changing limits or deleting a child does not refund the root's durable task/round counters; start a new root when you need a fresh delegation budget. See [Subagents](subagents.md).
