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

Forking at a selected message is not available. Deleting the parent does not delete its forks.

## Recover from interruption

Responses appear immediately and are saved in batches about every **600 ms**, plus at completion, cancellation, and errors. Press Esc or Ctrl+C to cancel active work. On a network error, the response is marked as an error and saved fragments remain accessible.

After an unexpected process stop, run `syleks -c` or open the session from the picker. Syleks detects abandoned generations and marks them interrupted, retaining the fragments already saved. A hard stop can lose text received since the last batch save.

An interrupted tool call is never replayed automatically. Inspect the project and tool history before asking to try an action again: an external action may have taken effect before the process stopped.

## Continue with the right context and model

Sessions remember their selected model. Ordinary resume keeps that model even if your configured default has changed. Use `/model provider/model` or `/model qwen3:8b` to choose a replacement explicitly. Restarting through `/restart` intentionally applies the configured default model to the reopened session.

If a provider was removed, the history remains readable; Syleks asks you to select a replacement. If a model is missing on the server, Syleks reports the server error rather than silently substituting or downloading another model.

Saved system and project instructions are restored with the conversation. To apply changed project instructions, start `/new`. Valid tool-call/result pairs are retained in request order. Internal reasoning and visual-only parts are not automatically sent back as model messages.

A model cannot accept unlimited history. Syleks estimates size, reserves output space, and includes the most recent complete turns that fit. A notice identifies omitted older context. **The complete saved conversation remains intact**, but the model may no longer see its older details. There is no automatic summary or compaction into a generated summary.

If the latest turn or instructions alone exceed the budget, increase the supported context limit, select a suitable model, or start a new session with a shorter message. See [Configuration](configuration.md).

## Export or delete

For a readable Markdown copy, use `/save conversation.md` in chat. It obeys `save` permissions and refuses to overwrite a file.

For the full structured record:

```sh
syleks session list
syleks session export ID > conversation.json
```

JSON includes session metadata, messages, structured parts, and tool executions. It can include confidential code, prompts, reasoning, parameters, results, and secrets appearing in those contents. Store it privately and keep it out of version control. Shell redirection can overwrite a file; choose a new filename or use `set -C` in a compatible shell first. There is no session import command in this version.

To delete a session:

```sh
syleks session delete ID
```

**Deletion is immediate, with no confirmation prompt, and cannot be undone in Syleks.** Check the ID first. Related messages and tool records are deleted too; forks survive. Deletion is not secure erasure from disk or backups.

## Several terminals

You can use different sessions in multiple Syleks instances. Another instance may read the same conversation, but only one instance can generate or perform a protected change on that session at a time.

If a session is busy, finish or cancel work in the other terminal, then retry. A terminated process releases its lock automatically. Do not delete lock files to bypass a busy session. See [Troubleshooting](troubleshooting.md) for storage errors.
