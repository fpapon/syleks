# Command reference

[User guide](README.md)

## Commands in your shell

Run these from the project whose sessions you want to access. `ID` means a full session identifier from `syleks session list`; do not type the literal word `ID`.

| Command | Result |
| --- | --- |
| `syleks` | Open chat and offer the project's existing sessions |
| `syleks chat` | Historical equivalent of the default launch |
| `syleks --new` | Create and open a new session |
| `syleks -c` or `syleks --continue` | Resume the latest session; create one if there are none |
| `syleks -s ID` or `syleks --session ID` | Open a specific session |
| `syleks --fork ID` | Copy an entire session and open the copy |
| `syleks config init` | Open the configuration form to create or update settings |
| `syleks config init --defaults` | Create missing starter files without prompting or overwriting |
| `syleks session list` | List this project's sessions with IDs, titles, update times, models, message counts, and statuses |
| `syleks session export ID` | Write the full session as JSON to standard output |
| `syleks session import PATH` | Validate and import an archive into this project, returning a new UUID |
| `syleks session fork ID [MESSAGE-ID]` | Create a whole-session or inclusive-prefix fork |
| `syleks session messages ID` | List message UUIDs, roles and statuses |
| `syleks session changes ID` / `diff ID` | Inspect recorded native file changes |
| `syleks session undo ID` / `redo ID` | Restore recorded file contents after checking divergence |
| `syleks session delete ID` | Permanently delete a session, without a confirmation prompt |
| `syleks mcp list` | Connect and report configured MCP server status |
| `syleks mcp tools` | List MCP tools available to the default agent |
| `syleks mcp auth SERVER` | Authorize an MCP server with OAuth PKCE |
| `syleks mcp logout SERVER` | Remove locally stored MCP OAuth credentials |
| `syleks -h` or `syleks --help` | Show usage |

Choose one interactive selection option at a time: `--new`, `--continue`, `--session`, or `--fork`. These also work after the historical `chat` prefix. IDs from another project are rejected; change into that project to access them.

`session list` uses tab-separated columns and includes a header. Export writes JSON; redirect it explicitly to save a file. Exit codes are `0` for success, `2` for argument/identifier errors, and `1` for runtime failures such as inaccessible storage or a busy session. Error messages go to standard error.

## Commands inside chat

Type a command into the composer and press Enter. Internal commands are handled by Syleks instead of being sent as chat messages to the model. Unknown slash commands show an error.

| Command | Result |
| --- | --- |
| `/sidebar [auto\|show\|hide]` | Toggle or select persisted token sidebar visibility (safe terminal constraints always apply) |
| `/usage` | Open token/performance details and configured context/response budgets |
| `/help` | Open the scrollable command and keyboard reference popup |
| `/config`, `/config init`, `/config edit` | Open the same configuration form |
| `/restart` | Reload configuration and reopen this session, preserving history and drafts |
| `/new` | Open a new persistent session |
| `/clear` | Same as `/new`; the previous session is retained |
| `/sessions` | Filter sessions by title, ID or model; navigate with arrows, pages and Home/End |
| `/resume` | Open the latest session |
| `/rename TITLE` | Change the current session's title |
| `/fork [MESSAGE-ID]` | Fork the whole conversation or an inclusive message prefix |
| `/messages` | Show message UUIDs for selecting a fork boundary |
| `/compact` | Generate a summary for future requests, keeping the full transcript |
| `/import PATH` | Import a validated session archive and open its root |
| `/changes`, `/diff` | Inspect native file snapshots |
| `/undo`, `/redo` | Restore recorded file contents through the active agent's write policy |
| `/model MODEL` | Select a configured `provider/model` or a bare Ollama model ID |
| `/agents` | List agents and their modes |
| `/agent` | List agents |
| `/agent NAME` | Select a primary agent |
| `/subagent NAME TASK` | Run a task with a subagent |
| `/children` | Browse direct child task sessions |
| `/parent` | Return to the parent session |
| `/session ID` | Open a saved session, including a child history |
| `@NAME TASK` | Alternative way to invoke a subagent |
| `/permissions` | Show the active agent's effective permissions |
| `/permissions NAME` | Show another agent's effective permissions |
| `/approve`, `/approve once` | Allow the pending action once |
| `/approve always` | Remember a tool approval for this agent while this session stays open |
| `/deny` | Reject a pending action |
| `/answer TEXT` | Answer a tool question; a suggested option number or free text is accepted |
| `/save PATH` | Export displayed messages as Markdown; never overwrite a file |
| `/demo` | Preview simulated tool cards and highlighted code |
| `/quit`, `/exit` | Quit |

When the approval panel has focus, use its keyboard controls to approve or reject; the commands above also describe the session's approval actions. See [Tools and approvals](tools-and-permissions.md) for approval scope and [Chat controls](chat.md) for shortcuts.

Configuration and agent files are loaded at startup. After saving changes, choose **Restart chat** or use `/restart`. Restart applies the configured default model; ordinary session resume uses the session's saved model. Restarting does not send a message automatically.

## Providers and accounts

CLI commands work without opening the TUI:

```sh
bin/syleks auth login chatgpt [account-id]
bin/syleks auth login openai-api
bin/syleks auth login spacexsi
bin/syleks auth login openrouter
bin/syleks auth login opencode
bin/syleks auth login google
bin/syleks auth logout chatgpt [account-id]
bin/syleks auth status
bin/syleks auth accounts
bin/syleks auth use <account-id>
bin/syleks provider list
bin/syleks provider use <provider-id>
bin/syleks model list
bin/syleks model use <provider/model-or-model-id>
```

TUI: `/connect` opens login choices, `/providers` selects a provider, `/models` filters/selects account models, `/accounts` manages individual registrations, `/logout [account-id]` signs out. Existing `/model provider/model` still works. Cloud use and API billing require explicit choices; see [Providers](providers.md) and [Authentication](authentication.md).

See [MCP servers](mcp.md) for server configuration, project trust, authentication and permissions.

## Configuration example workflow

```sh
syleks config init --defaults
# Review and merge a configuration from examples/ into ~/.config/syleks/agent-config.json.
# Copy chosen example Markdown definitions into the global or project agents/ directory.
syleks --new
```

Then use `/restart` after edits, `/agents` to inspect definitions, `/permissions NAME` to inspect policy, and `/agent NAME` or `@NAME TASK` to use them. Exact paths, schema adjustments and model dependencies are in the [example installation guide](../examples/README.md); the [configuration reference](configuration.md#complete-configuration-reference) lists every supported field.
