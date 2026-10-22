# Troubleshooting

[User guide](README.md) · [Configuration](configuration.md) · [Sessions](sessions.md)

## Syleks will not start

| Symptom | What to check |
| --- | --- |
| `syleks: command not found` | Add the built source directory's absolute `bin` path to `PATH`, or use the full JAR launch command in [Getting started](getting-started.md). |
| “Build Syleks first” | Run `mvn clean package` from the Syleks source directory with Java 25 and Maven 3.9+. |
| Unsupported Java class version | Check `java -version` and `mvn -version`; both must use Java 25 or newer compatible with the build. |
| Invalid configuration or agent definition | Check the named file, valid JSON/frontmatter, declared model/provider names, temperatures, and numeric limits. Use the local JSON schema in your editor. |
| Setup does not show the styled form | Run `syleks config init` in an interactive terminal without redirected input/output. `--defaults` deliberately skips prompts. Inside chat use `/config` or Ctrl+G. |
| Existing configuration is skipped by setup | Redirected setup and `--defaults` preserve files. Use the interactive form to update them. |

If chat cannot start because of malformed configuration, standalone `syleks config init` can repair the JSON through the form. Review its warning and keep a private copy of the original if necessary. Agent-definition errors may still require correcting the named Markdown file directly.

## The model does not answer

| Symptom | What to try |
| --- | --- |
| Connection refused / timeout | Start the model server and verify its address is reachable from this machine. For Ollama, check `OLLAMA_URL`; for a provider, check `options.baseURL`. |
| Authentication failure | Check the provider's API key and server requirements. Save changes and restart. Do not paste keys into a support conversation. |
| Model not found | Use the exact model ID listed by your server. For Ollama, check `ollama list` and install the chosen model if needed. Syleks does not pull models automatically. |
| Saved model unavailable | The old provider/model may have been removed. History remains readable; select a replacement with `/model provider/model` or restore its configuration. |
| A different model keeps appearing | Check `OLLAMA_MODEL`, agent model overrides, and the saved session model. Selecting an agent reapplies its model selection. |
| Settings have not changed after saving | Choose Restart chat after setup, or run `/restart`. Later leaves the current runtime unchanged. |
| Configuration changed elsewhere | Reopen the form and review the newest file before saving again. Syleks rejects stale updates. |
| Round limit reached | Tool results are saved. Send a follow-up to continue, or adjust global/per-agent `max_rounds` and restart. |
| Context budget exceeded | Increase the model's supported context limit, shorten the message/instructions, select a suitable model, or start `/new`. |

An “older context omitted” notice means the model request could not include every saved turn. Your stored history remains intact. Use `/compact` to generate a bounded summary for future requests, or reintroduce relevant details explicitly. Automatic compaction is enabled for primary chat, but omissions can still occur when manual mode is configured, summarization fails, or the retained context cannot fit. Chat reports compaction progress and failures. An oversized latest turn is kept intact and requires a larger-context model or `/new`.

## Tools are not running

Run `/permissions` for the active agent, or `/permissions NAME` for a subagent. Rules can differ between agents and can be overridden by Markdown definitions. After editing rules, restart.

- A denied action cannot be approved from the prompt; change its effective configuration deliberately if you want to permit it.
- A waiting approval pauses the action. Select a choice and press Enter; pasted text will not confirm it.
- Plain command text in a response is not executable tool output. The model and server must support structured function calls.
- A subagent needs the caller's `task` permission as well as its own `chat` permission. It does not receive the parent's transcript automatically.
- `grep` needs both search and read permission. Denied or unreadable entries may be omitted.
- File tools reject paths outside the launch directory's workspace and paths through symlinks. Start from the intended project root.
- Text files over 1 MB, binary files, and invalid UTF-8 are unsupported by the dedicated text tools. Search scans and output are bounded.
- Exact edits fail if the old text is missing or ambiguous; supply more surrounding text or request an intentional replacement of all matches.
- Shell commands require `/bin/bash` and cannot accept interactive standard input. Long commands may hit the configured timeout.
- Web search can fail when DuckDuckGo changes its HTML or blocks automated requests. Try a known URL with permitted `webfetch` instead.

Separate file/web permissions do not sandbox an approved shell command. See [Tools and approvals](tools-and-permissions.md).

## Sessions are missing, busy, or interrupted

| Symptom | What to try |
| --- | --- |
| Empty session list | Check the launch directory, canonical Git root, current worktree, OS user, `SYLEKS_DATA_DIR`, and `XDG_DATA_HOME`. Different worktrees and project paths have separate lists. |
| ID rejected | Copy the full ID from `syleks session list` in the same project. Cross-project IDs are not accepted. |
| Session busy / in use | Finish or cancel work in the other terminal and retry. Process death releases its lock. Never delete lock files to bypass it. |
| Database locked | Another write exceeded the five-second wait. Retry and check for other database users. Use local storage with supported locking rather than a network share. |
| Data directory refused | Choose a writable local directory outside Git working trees; do not use a symlink as the data directory. |
| Database schema too new | Upgrade Syleks. Do not edit the schema version manually. |
| Partial or interrupted reply | Reopen the session. Saved fragments remain available; text since the last periodic save may be lost after a hard stop. No tool is replayed automatically. |
| New project instructions ignored | Existing sessions retain saved instructions. Start `/new` to record changed `AGENTS.md` instructions. |
| Export refuses to overwrite | `/save` deliberately requires a new filename. JSON shell redirection follows your shell's overwrite rules instead. |

See [Storage and privacy](storage-and-privacy.md) before restoring, moving, or manually inspecting a database.

## Terminal and keyboard problems

- Enlarge the terminal to at least 35 columns by 15 rows.
- Use Ctrl+O for a newline if Shift+Enter sends the message instead.
- Up/Down scroll the transcript; Left/Right edit the composer.
- Finish or cancel current generation/approval before opening configuration or switching sessions/models/agents.
- Use a truecolor-capable terminal and a Unicode monospace font for the intended appearance. Some combining characters and complex Markdown layouts have limited rendering support.
- If a forced stop leaves your shell in an unusual terminal mode, run `reset` in that shell.

For a visual check without invoking tools, use `/demo`. Its content is simulated. When reporting a problem, include your Syleks build, Java version, terminal, the command you ran, and a redacted error message. Avoid sharing API keys, confidential prompts, raw session exports, or private tool output.

## Configuration example and agent-definition problems

| Symptom | What to check |
| --- | --- |
| Copied example still uses an old policy or model | Global/project Markdown overrides JSON. Check the same-named files in every loaded directory, then `/restart`. |
| Editor cannot find the schema after copying | Change the example's `$schema` to `./agent-config.schema.json` when using the setup-created local schema. |
| Example proxy fails | Remove `options.proxy` unless you have configured the placeholder proxy for a real service. |
| Provider receives `${KEY}` as its key | JSON values are literal; use a real private `apiKey` or the built-in credential flow. MCP alone supports `bearer_token_env`. |
| Project JSON provider/agent settings have no effect | Only trusted project MCP settings load from `.syleks/agent-config.json`. Use global JSON and project `.syleks/agents/NAME.md`. |
| Hidden agent is missing from the list | Use its explicit name. Disabled agents cannot run at all. |
| Frontmatter fails to parse | Use supported scalar fields and nested permission maps; quote `"*"` keys, use actual booleans, and put the prompt in the body. Full YAML is unsupported. |
| Child has read permission but cannot inspect files | Every ancestor must also permit read/search. `grep` additionally checks `read`. |
| Unlimited agent still hits a child limit | Shared child round/deadline/depth limits and durable root quotas still apply. Start a new root for a fresh durable budget. |
| MCP example does not connect | Example entries are disabled. Configure a real endpoint/executable and credentials, then enable deliberately and restart. |

Compare your installation with the [example catalog](../examples/README.md), [full field reference](configuration.md#complete-configuration-reference), and [complete Markdown definitions](agents.md#create-a-custom-agent).
