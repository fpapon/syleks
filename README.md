# Syleks TUI — Java 25 + Yupiik Fusion

A dark, OpenCode/Codex-inspired full-screen agent TUI for **Ollama, ChatGPT plan usage, OpenAI API and OpenAI-compatible servers**, with permission-checked local commands.

> **Status: working-source MVP.** Java 25 and Maven 3.9+ are required. The test suite covers configuration, rendering, streaming protocols, permissions, and local command execution. Verify your chosen model server and terminal in your own environment.

Start with the [end-user guide](docs/README.md) for setup, configuration, chat controls, sessions, agents, tools, approvals, and troubleshooting. The [command reference](docs/commands.md) lists all CLI options and interactive commands.

## Providers and authentication

Ollama works locally without cloud authentication. For an eligible ChatGPT plan through the official open-source OAuth flow:

```sh
bin/syleks auth login chatgpt
bin/syleks model list
bin/syleks model use chatgpt/<slug-from-list>
bin/syleks --new
```

Use `/connect`, `/providers`, `/models`, `/accounts` and `/logout` in the TUI. Accounts persist across launches with token rotation and dedicated protected storage. Identity alone does not authorize plan usage. `auth login openai-api` is a separate, explicit API billing path.

Cloud requests send necessary conversation context, instructions and approved tool results outside your environment. `{"allow_cloud":false}` in `.syleks/project.json` forbids cloud providers. See [Providers](docs/providers.md) and [Authentication](docs/authentication.md) for commands, architecture, XDG/Secret Service storage, restrictions, SSH and manual checks. OAuth/inference tests use simulated servers; no real subscription request was performed here.

## Persistent sessions

Conversations are saved locally in SQLite, grouped by canonical project root (including Git worktrees). Start with `bin/syleks --new`, resume with `bin/syleks -c`, or select a conversation with `/sessions` / Ctrl+R. The legacy `java --enable-native-access=ALL-UNNAMED -jar target/syleks-0.1.0-SNAPSHOT.jar chat` launch remains supported.

```sh
mvn clean verify
bin/syleks --help
bin/syleks session list
bin/syleks --continue
```

The default database is `~/.local/share/syleks/syleks.db`; `XDG_DATA_HOME` and `SYLEKS_DATA_DIR` override it. Sessions retain messages, streamed fragments and structured tool history. `/new`, `/resume`, `/rename TITLE` and `/fork` manage conversations without deleting previous history. Explicit JSON export and deletion are available through `session export ID` and `session delete ID`.

See [Sessions](docs/sessions.md) for resume, forks, context budgets, concurrency, and recovery, and [Storage and privacy](docs/storage-and-privacy.md) for database locations, migrations, and backups. JSON exports may include confidential prompts, reasoning and tool results. `/clear` now starts a new persistent session.

## Stack

- **Yupiik Fusion 1.2.0** (`fusion-api`, `fusion-cli`): annotation-generated dependency injection and `@Command` launcher.
- **Fusion JSON** (`fusion-json`): request serialization and streamed JSON response parsing.
- **Nimbus JOSE+JWT 10.10**: signed OIDC ID-token validation and JWKS caching.
- **Java 25**, virtual thread for LLM generation, Java `HttpClient` streaming.
- **JLine 3.30.3**: raw terminal, unicode input, alternate screen and resize.
- **Progress indicator**: an indeterminate bar in the footer sweeps in the active agent’s color during generation and tool execution, with an Esc interrupt hint. It pauses for permission prompts and disappears when idle or cancelled.
- **Dedicated ANSI renderer**: cell buffer, responsive layout, dark palette, row-level diff painting (no TamboUI SNAPSHOT dependency).
- **Local commands**: a `bash` tool with permission checks, captured output, timeouts, and cancellation.
- **Model endpoints**: Ollama NDJSON `POST /api/chat`, public Responses SSE `POST /v1/responses`, or configured OpenAI-compatible `POST /chat/completions`.

## Quick start

Prerequisites: Java 25, Maven >= 3.9, xterm-256color or truecolor-compatible terminal; locally running Ollama or an Ollama endpoint accessible from your environment.

```bash
ollama pull qwen3:8b
ollama serve   # only if Ollama is not already running

mvn clean package
java --enable-native-access=ALL-UNNAMED -jar target/syleks-0.1.0-SNAPSHOT.jar chat
```

To compile and launch directly with Maven in an interactive terminal:

```bash
mvn -q compile exec:exec
```

The plugin launches a separate Java process, selects the `chat` command automatically, and uses the same environment variables as the JAR launch. Exit with Ctrl+D or `/quit`.

## Source formatting

Spotless formats production and test Java sources with Google Java Format's four-space AOSP style. Generated files under `target/` are excluded.

```sh
mvn spotless:apply   # format source files
mvn spotless:check   # check without changing files
mvn clean verify     # build, test, package, and check formatting (also used by CI)
```

Plugin and formatter versions are pinned in `pom.xml`. Normal builds check formatting during `verify`; they do not rewrite sources automatically. See the [Spotless Maven documentation](https://github.com/diffplug/spotless/blob/main/plugin-maven/README.md) for plugin usage.

## Native executable with Apache Arthur

The optional `native` Maven profile uses [Apache Geronimo Arthur](https://geronimo.apache.org/arthur/maven.html) 1.0.9 to build `target/syleks` during `package`:

```bash
# Run Maven with Java 25; this profile downloads its configured GraalVM distribution.
mvn -Pnative clean package
./target/syleks chat
```

A regular `mvn clean package` continues to build the executable JAR. The native build requires a platform C/C++ toolchain (for example GCC and development headers on Linux), zlib development headers, and enough memory for Native Image. Build on the OS/architecture where the executable will run. The configured binary uses dynamic system libraries and requires no JVM at runtime. Existing configuration, model endpoints, terminal controls, and `/bin/bash` requirements still apply.

The checked-in profile configures a GraalVM download for Linux x64. Maven must run on Java 25 or newer to compile the source. For another platform or an installed Native Image executable, adapt the Arthur configuration in `pom.xml`; the `nativeImage` configuration entry is currently commented out.

The profile uses the shaded application JAR and bundled Fusion/JLine reachability metadata, enables HTTP/HTTPS and native access, and keeps JLine initialization at runtime. Application metadata includes service descriptors and the JDK HTML parser's DTD for web tools. Arthur annotation scanning is disabled because the app uses generated metadata; unsupported native-image features fail the build rather than producing a JVM fallback.

The repository `.sdkmanrc` selects Zulu Java 25 for JVM development. The native build is optional and has not been verified in the current development environment. Validate the resulting executable in an interactive terminal, including `/demo`, permissions, local commands, and your HTTP/HTTPS model connection.

## Configuration

Generate a starter configuration and default agent Markdown files:

```sh
bin/syleks config init
# Or: java -jar target/syleks-0.1.0-SNAPSHOT.jar config init
```

In an interactive terminal this opens a dark ANSI configuration form using the same renderer as chat. It works for both **new and existing** configuration files. Inside a chat, use **`/config`** (or `/config init`, `/config edit`) or **Ctrl+G** to open the same form. Ctrl+G keeps the current chat draft intact.

The form edits provider ID/name, OpenAI-compatible base URL, optional API key, model ID/name, context/output token limits, global round limit, and Plan/Build temperatures and round limits. Existing values are prefilled; API keys are masked. Type to replace a selected value, use left/right to edit it, Enter/Tab to advance, and Shift+Tab/up to return. Backspace clears a selected value, including an existing API key. Esc cancels before saving.

After the last field, review the settings and choose **Save**, **Back** or **Cancel**. Saving creates or atomically updates `~/.config/syleks/agent-config.json`, and fills in any missing schema and default agent Markdown files. Other providers, models, custom agent definitions and existing permissions are preserved when updating. A configuration changed by another process since the form opened is rejected instead of overwritten. Malformed existing JSON can be repaired using the form, with an explicit warning before saving.

After saving, the form offers **Restart chat** or **Later**. Restart rebuilds the Fusion container, loads the new configuration and configured default model, and reopens the current persistent session with its history and unsent drafts. It never sends a chat request automatically. Later keeps the current running settings; `/restart` applies the saved configuration when ready. Configuration changes are available while chat is idle; finish or cancel generation and resolve pending permissions before opening setup.

New configurations use `model: provider/model`, `provider` with `npm: @ai-sdk/openai-compatible`, model `limit.context`/`limit.output`, global `max_rounds`, and per-agent temperature, round limits and permissions. The starter policy allows chat/read, asks for saves and reviewer delegation, and denies other delegation, edit and bash globally. Plan denies task/save/edit and asks for bash. Build allows task/read, asks for save/bash, and denies edit. Per-agent rules override global rules. Existing policies are retained when editing; generated agent files never replace customized files.

For unattended initialization, skip the form explicitly:

```sh
bin/syleks config init --defaults
```

`--defaults` creates only missing files and preserves existing ones. Its bundled settings use provider `local` (`Local`), base URL `http://192.168.52.32:8000/v1`, model `qwen3.8-27b` (`Qwen3.8 27B UD-Q4_K_M`), context/output limits `196608`/`32768`, global round limit `20`, and Plan/Build round limits `30` with temperatures `0.2`/`0.1`. No API key is written by `--defaults`; adjust the provider address and model to your server before chatting.

Redirected stdin retains the line-based setup for scripts creating a new configuration; EOF cancels it without creating files. Existing files are preserved in this mode. Use an interactive terminal or `/config` to update an existing file.

The command uses the same home directory as the configuration loader (`HOME`, falling back to Java's `user.home`). Setup itself requires neither a running model server nor a sessions database. New files have private permissions on POSIX filesystems; entered API keys are stored in the configuration file. It also creates a local `agent-config.schema.json`, plus missing `agents/build.md`, `agents/plan.md`, `agents/architect.md`, and `agents/reviewer.md` files.

If upgrading from Fusion Agent, move `$HOME/.config/fusion-agent` to `$HOME/.config/syleks` and any project `.fusion-agent` directory to `.syleks`. Configuration filenames and contents stay the same.

Create `$HOME/.config/syleks/agent-config.json` using this supported subset of [OpenCode provider configuration](https://docs.opencode.ai/docs/providers/). Define several providers as separate entries under the singular `provider` key. Each provider has its own base URL, optional API key, and model catalog. See [examples/agent-config.json](examples/agent-config.json):

```json
{
  "$schema": "../agent-config.schema.json",
  "model": "local/qwen3:8b",
  "provider": {
    "local": {
      "name": "Local server",
      "options": {
        "baseURL": "http://127.0.0.1:8000/v1"
      },
      "models": {
        "qwen3:8b": {
          "name": "Qwen 3 8B",
          "limit": {
            "context": 32768,
            "output": 8192
          }
        }
      }
    },
    "remote": {
      "name": "Remote server",
      "npm": "@ai-sdk/openai-compatible",
      "options": {
        "baseURL": "https://models.example.com/v1",
        "apiKey": "replace-with-your-api-key"
      },
      "models": {
        "qwen3:8b": {
          "name": "Remote Qwen 3 8B",
          "limit": {
            "context": 65536,
            "output": 16384
          }
        },
        "team/reasoner": {
          "name": "Reasoning model"
        }
      }
    }
  },
  "agent": {
    "build": {
      "model": "local/qwen3:8b",
      "temperature": 0.1
    },
    "plan": {
      "model": "remote/team/reasoner",
      "temperature": 0.2
    }
  }
}
```

The same model ID can exist under different providers. Select one with `/model local/qwen3:8b` or `/model remote/qwen3:8b`. An agent's `model` setting selects its provider when that agent becomes active; in this example `/agent plan` uses `remote/team/reasoner`.

[agent-config.schema.json](agent-config.schema.json) is a JSON Schema Draft 2020-12 schema for the complete configuration, including providers, agents, permissions, round limits, and legacy Ollama settings. JSON Schema-aware editors can use the `$schema` property for validation and completion. The example's relative path works inside this repository; after copying it to your home configuration directory, change `$schema` to the schema's absolute file URI, for example `file:///absolute/path/to/syleks/agent-config.schema.json`. You can also supply the schema and configuration file to any validator supporting Draft 2020-12.

The schema rejects unknown fields and invalid types, identifiers, URLs, permissions, temperatures, and numeric limits. It does not load other files or resolve configured names. Startup validation additionally checks provider/model references, the default agent (including Markdown definitions), and that output limits do not exceed context limits. The application reads `$schema` as editor metadata; it does not run a JSON Schema validator at startup. `mvn -Dtest=ConfigSchemaTest test` validates the checked-in examples and schema regression cases.

- `model` selects `provider-id/model-id`. The provider and model must be declared under `provider`; only the raw model ID is sent to the server.
- `options.baseURL` receives `/chat/completions`; `options.apiKey`, when provided, is sent as a Bearer token.
- `name` fields supply display labels. `npm` identifies the supported OpenAI-compatible protocol; this Java app does not install or execute npm packages. Other SDK protocols are unsupported.
- The active agent starts as `build`, or the primary agent named by `default_agent`. Use `/agent NAME` to select an agent. Omitted temperatures use the server default; configured values must be 0–2.
- `limit.output` is sent as `max_tokens`. `limit.context` sets the context budget; Syleks estimates size conservatively and selects recent complete turns that fit, reserving room for output and tools. Omitted older context is reported without deleting saved history. Automatic summary generation is not implemented. Limits may be omitted; see [Configuration](docs/configuration.md).
- Restart after editing the file. Unreadable files, invalid JSON, or invalid settings stop startup with an error.

Existing Ollama settings remain supported:

```json
{
  "ollama_url": "http://127.0.0.1:11434",
  "ollama_model": "qwen3:8b"
}
```

A missing file uses these Ollama defaults. `OLLAMA_MODEL` overrides the selected model; a bare model ID selects Ollama. `OLLAMA_URL` overrides the legacy Ollama URL and does not change provider base URLs. `AGENT_SYSTEM_PROMPT` remains an environment variable. Configured build/plan temperatures also apply to Ollama through its `options.temperature` field.

To connect to your **on-premise Ollama** server:

```bash
export OLLAMA_URL=http://YOUR-PRIVATE-OLLAMA-SERVER:11434
export OLLAMA_MODEL=qwen3:8b   # replace with the exact name from `ollama list`
java --enable-native-access=ALL-UNNAMED -jar target/syleks-0.1.0-SNAPSHOT.jar chat
```

The app appends `/api/chat` to `OLLAMA_URL`. No SaaS API or telemetry is configured. **Prefer HTTPS with auth or a private VPN** for connections to a separate server: plain HTTP on a network is not confidential.

### Agent round limits

Set `max_rounds` in `$HOME/.config/syleks/agent-config.json` to limit model/tool rounds per user turn or subagent invocation:

```json
{
  "max_rounds": 20,
  "agent": {
    "build": { "max_rounds": 30 },
    "plan": { "max_rounds": 5 },
    "reviewer": { "mode": "subagent", "max_rounds": 10 }
  }
}
```

Merge these settings into your existing configuration, then restart. Values must be `-1` for unlimited rounds or integers from 1 to 1000; the default is 12. For example, set `"max_rounds": -1` globally or on an individual agent to disable its round limit. Agents without an override inherit the global value. Agent Markdown frontmatter also accepts `max_rounds: -1`; global and project Markdown definitions override JSON using the usual agent precedence.

A round is one model response, including execution of any tools requested in that response. Multiple tool calls in one response count as one round; permission approvals do not count as rounds. An answer without tool calls finishes the turn immediately, including on the last permitted round. If that last response requests tools, their results are recorded and generation stops with the configured limit in the status; send a new message to continue. The counter resets for each new user turn or subagent invocation. This setting does not limit elapsed time or total conversation length.

## Markdown agents and subagents

Declare agents in UTF-8 Markdown files. The filename becomes the agent name (`reviewer.md` → `reviewer`). Files are loaded at startup from:

1. `$HOME/.config/syleks/agents/*.md` for global definitions.
2. `.syleks/agents/*.md` in the launch directory for project definitions.

Precedence is built-in defaults → JSON `agent` settings → global Markdown → project Markdown. Later definitions override supplied fields and replace the prompt body; unspecified fields retain earlier values. Restart after editing definitions.

For example, create `reviewer.md`:

```markdown
---
description: Review Java code for bugs and missing tests
mode: subagent
temperature: 0.1
---
You are a focused Java 25 code reviewer.
Review the code supplied with your task. Explain each finding and suggest a concrete fix.
Ask for missing source code; never claim to have read files or executed tests.
```

Supported frontmatter fields:

| Field | Behavior |
| --- | --- |
| `description` | Explains the agent in `/agents`; defaults to its name. |
| `mode` | `primary`, `subagent`, or `all`. New agents default to `all`; built-in Build/Plan remain primary. |
| `model` | Optional configured `provider/model` or Ollama model ID. |
| `temperature` | Optional number from 0 to 2. |
| `permission` | Agent permission overrides; see below. |

The Markdown body supplies the agent prompt, appended to the shared system prompt. Frontmatter supports flat plain/quoted string fields, numeric temperatures and round limits, comments, and literal/folded multiline descriptions (`|`/`>`). Nested maps are supported for `permission` rules only. Other nested YAML, tool declarations, aliases, and executable tags are unsupported and produce a configuration error. Agent names may contain letters, digits, hyphens, and underscores (maximum 64 characters).

- `/agents` lists every definition and its mode.
- `/agent architect` selects a primary agent. Tab cycles through primary and `all` agents.
- `@reviewer Review this pasted code: …` or `/subagent reviewer Review this pasted code: …` invokes a subagent.
- A subagent receives its own prompt and the supplied task, with no parent transcript. Its labeled answer is added to the main conversation for subsequent follow-up. The selected primary agent is restored after completion or cancellation. Calls run one at a time; model-initiated delegation and recursive subagents are not implemented.
- A declared agent model overrides the global model. Without one, primary agents use the global model and subagents inherit the current primary model. `/model` overrides the active primary model until another agent is selected.
- To start with a custom primary agent, add `"default_agent": "architect"` to `agent-config.json`.

Ready-to-copy definitions are in [examples/agents/architect.md](examples/agents/architect.md) and [examples/agents/reviewer.md](examples/agents/reviewer.md). These agents use the configured chat endpoint and can use shell, file, search, and web tools when their permissions allow them.

## Permissions

Set global permissions in `$HOME/.config/syleks/agent-config.json` and overrides in JSON `agent` entries:

```json
{
  "permission": {
    "chat": "allow",
    "task": { "*": "deny", "reviewer": "ask" },
    "save": "ask",
    "read": "allow",
    "edit": "deny",
    "bash": "deny"
  },
  "agent": {
    "plan": { "permission": { "task": "deny", "save": "deny" } },
    "reviewer": { "permission": { "chat": "allow", "read": "allow" } }
  }
}
```

Agent Markdown frontmatter supports the same rules:

```markdown
---
description: Review supplied Java code
mode: subagent
permission:
  chat: allow
  read: allow
  edit: deny
  bash: deny
  task:
    "*": deny
---
Review the supplied code and explain your findings.
```

- Actions are `allow`, `ask`, or `deny`. A scalar such as `"permission": "deny"` applies to every operation. `"*"` can also be used as an operation key.
- Effective rules combine defaults → global `permission` → agent JSON → global agent Markdown → project agent Markdown. The last matching rule wins, including target patterns. Agent overrides can explicitly grant an action denied globally.
- Target patterns support `*` (any text) and `?` (one character), with case-sensitive matching. Targets are model IDs for `chat`, agent names for `task`, normalized absolute paths for `save`, and the exact shell command for `bash`. List broad patterns before narrower exceptions; declaration order matters.
- `ask` pauses the action until approval or rejection. `/approve` is shorthand for `/approve once`. Once permits only the pending action. For agent tools, always remembers approval for that agent and tool across targets and turns while the current session stays open; it only relaxes `ask`, so explicit `deny` rules still apply. It does not change the configuration file or grant access to other agents/tools. Restart and session actions (including new/open/resume/fork/rename) clear remembered approvals. Chat, delegation, and Markdown exports support once only. `/permissions [AGENT]` also lists remembered tool approvals. Esc/Ctrl+C cancel pending work. Session/agent/model changes are blocked while an approval is pending. See [Tools and approvals](docs/tools-and-permissions.md) for panel controls.
- Subagent calls check the caller's `task` permission for the target agent and the callee's `chat` permission for its model. An explicit manual invocation does not bypass either check.
- `/permissions` shows the selected primary agent's effective policy; `/permissions reviewer` inspects another agent.

The app enforces `chat` (an agent turn including model continuations), `task` (subagent calls), `save` (explicit session exports), and `bash` (local commands). Defaults allow chat/task/save and ask before every command. Explicit existing `bash: deny` settings remain effective. `read`, `edit`, `write`, `webfetch`, `websearch`, `glob`, `grep`, and `list` are implemented tools and default to `deny`; enable them explicitly as shown below. Reading application configuration and agent definitions at startup is separate from tool permissions.

Copy or merge [examples/permissions.json](examples/permissions.json) into your configuration, then restart the app.

### Permission prompt

When an action needs approval, an inline panel replaces the composer with **Allow once**, **Always allow**, and **Reject**. Use **Left/Right** or **Tab/Shift+Tab** to select, then **Enter** to confirm. Every new request starts on Allow once. The selected choice explains its scope above the action details. Always allow applies to that tool and agent for this app session; explicit deny rules still apply. Requests for chat, delegation, and conversation exports offer Allow once and Reject only.

Use **Up/Down** or **Page Up/Page Down** to scroll the action details and **Ctrl+F** to expand/collapse the panel. **Esc** or **Ctrl+C** cancels the pending action. The draft is preserved while the prompt has keyboard focus; pasted text cannot confirm a request. The `/approve [once|always]` and `/deny` commands remain available as the session command interface, while the focused prompt uses the selection controls directly.

## File and web tools

Enable tools globally in `$HOME/.config/syleks/agent-config.json`, or use the same `permission` entries per agent (JSON or Markdown frontmatter):

```json
{
  "permission": {
    "read": "allow", "list": "allow", "glob": "allow", "grep": "allow",
    "edit": "ask", "write": "ask",
    "webfetch": "ask", "websearch": "ask"
  }
}
```

Merge these entries into your existing configuration and restart. Both Ollama and OpenAI-compatible connections advertise permitted tools; execution always checks the actual acting agent's policy, including subagents. The model/server must support structured function calls.

| Tool | Arguments | Behavior |
| --- | --- | --- |
| `read` | `path`, optional `offset`, `limit` | UTF-8 text with numbered lines; offset starts at 1, limit defaults to 200 (max 2000). |
| `write` | `path`, `content`, optional `overwrite` | Creates a file in an existing directory; replacing an existing file requires `overwrite: true`. |
| `edit` | `path`, `old_text`, `new_text`, optional `replace_all` | Exact text replacement. Match must be unique unless `replace_all: true`. |
| `list` | optional `path`, `limit` | Lists one directory level. |
| `glob` | `pattern`, optional `path`, `limit` | Java filesystem glob relative to path, e.g. `*.java` or `**/*.java` (the latter matches nested files). |
| `grep` | `pattern`, optional `path`, `limit` | Java regex search; returns file paths, line numbers, and matching lines. |
| `webfetch` | `url` | HTTP(S) GET; HTML becomes plain text, JSON/text remains text. |
| `websearch` | `query`, optional `limit` | DuckDuckGo HTML search, returning titles and URLs; default 10, max 50 results. |

File paths are normalized absolute paths for permission matching; relative paths start at the launch directory. Dedicated file tools reject workspace escapes and symlink paths. `edit` includes reading its target to perform the replacement; it checks `edit` permission. `write` checks `write`, including when overwriting. Approval displays the exact replacement or new content. These tools use normal filesystem writes, not transactions or an OS sandbox; avoid concurrent external writers.

`list`, `glob`, and `grep` check permission on the search root and each returned/inspected entry. `grep` additionally requires `read` for every inspected file. Denied entries are omitted; `ask` may request approval for individual entries. Use broad allow rules followed by sensitive-path deny patterns to avoid repeated prompts. Scans do not follow symlinks, visit at most 10,000 entries and 32 levels, and return at most 100 results by default (max 500). Text files are limited to 1 MB; tool output is capped at 32,000 characters. Binary and malformed UTF-8 files are rejected or skipped.

`webfetch` permission targets are full URLs, checked again at each redirect (max five). `websearch` permission targets are query strings; its initial request goes to `https://html.duckduckgo.com/html/`. Search redirects additionally require `webfetch` permission for the destination. Search sends your query to DuckDuckGo, requires no API key, and can fail if the service changes its HTML or presents a challenge. No result pages are fetched automatically. HTTP responses are limited to 1 MB and decoded as UTF-8. Fetches can access private/local HTTP endpoints when permitted, so use URL-specific rules where needed. Web content is untrusted input to the model.

## Local command execution

Ask the agent to run a command, for example: `Run pwd, then run mvn test and summarize the results.` A tool-capable model/server must return structured `bash` calls; plain text commands in model replies are never executed. Both Ollama and OpenAI-compatible tool calls are supported.

Configure the global policy, or the `permission` block of an individual agent:

```json
{
  "permission": {
    "bash": "ask"
  }
}
```

For `ask`, the TUI shows the exact command, working directory, and timeout. `/approve once` executes that command once; `/approve always` approves this and future `bash` requests for the same agent during this app session, except commands matched by deny rules. `/deny` returns a refusal to the model. `allow` runs matching commands without prompting, while `deny` blocks them. Subagent commands use the executing subagent's policy, and permission changes require a restart.

The tool accepts `command` and optional `timeout_seconds` (default 60, range 1–300). Commands run through `/bin/bash -c` in the launch directory with inherited environment variables and closed stdin. Combined stdout/stderr, exit code, timeout state, and truncation state are returned to the model and shown in the conversation. Captured output is limited to 32,000 characters. Esc/Ctrl+C cancels the running command and model turn; command processes and their descendants are terminated during cleanup. Tool/model conversations stop after the configured `max_rounds` limit (default 12). This is intended for foreground, non-interactive commands, rather than persistent background services.

Shell execution uses your normal OS permissions and is not a filesystem sandbox. An allowed shell command can read/write files or access the network regardless of separate `read`/`edit`/web declarations. Command patterns match the full command string; they do not parse shell syntax, so a broad allow pattern also covers matching pipelines and compound commands. Use `ask` to review those commands before execution. Command output is sent back to your configured model endpoint.

## Markdown and source highlighting

Successful `read` calls show a short **Read path** activity entry instead of dumping the source file into the terminal. Full read results still reach the model and remain available to later turns. `/save` exports the displayed transcript, including read summaries. The default assistant prompt requests concise Markdown responses and relevant snippets rather than full-file echoes.

Responses render headings, lists, block quotes, horizontal rules, **bold**, *emphasis*, inline code, and links. Tool results use Markdown headings and code cards; web search results are linked lists. Language-tagged fenced code blocks receive lexical syntax coloring for Java, JavaScript/TypeScript, Markdown, AsciiDoc, XML/HTML, JSON, YAML, TOML, CSS, Python, Ruby, Kotlin, Go, Rust, C/C++, C#, SQL, Bash, and diffs. Common aliases such as `js`, `ts`, `md`, `adoc`, `py`, `sh`, and filenames such as `pom.xml` are recognized. Unknown languages use plain text.

Use a language label when asking for snippets, for example:

````markdown
```java UserService.java
public User findById(long id) {
    return repository.findById(id).orElseThrow();
}
```
````

Highlighting preserves source whitespace and carries multiline comment/string state within each block, including wrapped lines. It is a lightweight lexer rather than a compiler or complete CommonMark parser.

## Controls

| Shortcut | Effect |
| --- | --- |
| Enter | Send message |
| Ctrl+O | Insert a newline (works in standard terminals) |
| Ctrl+P | Show/close command palette |
| Tab | Cycle through primary agents while idle |
| Ctrl+C or Esc | Cancel in-flight generation (Ctrl+C again exits) |
| Ctrl+D | Exit |
| Left / Right | Edit the text |
| Up / Down, PageUp / PageDown | Scroll conversation |
| Bracketed paste | Paste code without sending it |
| `/help` | Show commands |
| `/clear` | Open a new persistent session; keep the previous conversation |
| `/model MODEL` | Switch to a configured `provider/model` or a bare Ollama model |
| `/agents` | List available agents and subagents |
| `/permissions [AGENT]` | Inspect effective permissions |
| Permission prompt: Left/Right, Tab, Enter | Select Allow once / Always allow / Reject, then confirm |
| Permission prompt: Ctrl+F, Up/Down, PgUp/PgDn | Expand or scroll action details |
| `/approve [once\|always]` / `/deny` | Approve once (default), remember a tool approval for this agent and session, or refuse |
| `/agent NAME` | Select a primary agent |
| `@NAME TASK` / `/subagent NAME TASK` | Run an isolated subagent task |
| `/demo` | Show sample tool cards and Java diff (fake; no files read) |
| `/save session.md` | Explicitly export Markdown (refuses to overwrite) |
| `/quit` | Exit |

The layout uses a workspace header, blue user-message rails, shaded code cards with file/language labels, and a footer status indicator. The composer border, prompt marker, and agent label change color with the active agent: blue for Build, purple for Plan, and a stable color derived from the name for custom agents and subagents. The composer keeps the caret visible when editing multiline input. Use `/demo` to preview the styling with clearly labeled simulated content; it does not read or modify project files. Truecolor terminals with a Unicode monospace font give the best result.

## Architecture

```text
Main (CLI routing) -> Fusion container
                               |
                        TerminalApp (JLine event loop)
                         |                 |
                  ChatRenderer         ChatSession (Fusion bean)
                  TerminalCanvas        |
                  TerminalPainter    OllamaClient (Fusion bean)
                                      |
                                 Ollama /api/chat or provider /chat/completions
```

- TerminalApp is the only owner of terminal output. Generated content is synchronized into ChatSession via a virtual thread.
- ChatRenderer builds a fresh cell buffer; TerminalPainter redraws only rows whose cells changed.
- Session messages and structured tool history are saved locally in SQLite and restored on resume. Unsent drafts remain in memory only.
- ANSI escape bytes from model output are replaced by spaces, not interpreted by the terminal.

## Current feature boundaries

The app supports streaming conversations, declared agents/subagents, and permission-checked shell, file, search, and web tools. Unknown tool names are reported to the model without execution. The `/demo` entries remain synthetic and do not run tools. Markdown styling is deliberately lightweight (highlighted fenced code, headings, emphasis, inline code, links, and line prefixes) and is not yet a complete CommonMark implementation. Unicode display-width handling is approximate, particularly for combining grapheme clusters.

## Security notes

- The model endpoint defaults to loopback (`127.0.0.1`).
- Avoid using `OLLAMA_URL=http://...` across untrusted networks; secure remote deployments with TLS and authentication.
- Do not enter client code if the chosen Ollama server is operated by an untrusted party.
- Conversations are persisted automatically in the local SQLite database. The app reads configuration and declared agent Markdown files at startup; permitted shell commands can access project files and their output is included in model requests. See [Storage and privacy](docs/storage-and-privacy.md).
- `/save` may export sensitive text in plaintext at the location you choose.
- Model identifiers must match `[A-Za-z0-9._:/-]{1,240}`. Provider models must be declared in the configuration and available on the selected server.

## Suggested next increments

1. Encryption at rest and automatic context summaries.
2. Syntax-aware diff navigation and richer Markdown tables.
3. Richer file browsing and attachment workflows.
4. OS-sandboxed execution and modifications with diff approval.
5. File picker, fuzzy search, configurable keymap, terminal themes.

Licensed Apache-2.0 (see LICENSE).
