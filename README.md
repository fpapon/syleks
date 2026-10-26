# Syleks TUI — Java 25 + Yupiik Fusion

A dark, OpenCode/Codex-inspired full-screen agent TUI for **Ollama, ChatGPT plan usage, OpenAI API, SpaceXSI, OpenRouter, OpenCode Console, Gemini and OpenAI-compatible servers**, with permission-checked local commands.

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

Use `/connect`, `/providers`, `/models`, `/accounts` and `/logout` in the TUI. Accounts persist across launches with token rotation and dedicated protected storage. Identity alone does not authorize plan usage. `auth login openai-api`, `auth login spacexsi`, `auth login openrouter`, `auth login opencode` and `auth login google` are separate, explicit API billing paths. Noninteractive login reads `OPENAI_API_KEY`, `XAI_API_KEY`, `OPENROUTER_API_KEY`, `OPENCODE_API_KEY` or `GEMINI_API_KEY` only for that command.

Cloud requests send necessary conversation context, instructions and approved tool results outside your environment. `{"allow_cloud":false}` in `.syleks/project.json` forbids cloud providers. See [Providers](docs/providers.md) and [Authentication](docs/authentication.md) for commands, architecture, XDG/Secret Service storage, restrictions, SSH and manual checks. OAuth/inference tests use simulated servers; no real subscription request was performed here.

## Persistent sessions

Conversations are saved locally in SQLite, grouped by canonical project root (including Git worktrees). Start with `bin/syleks --new`, resume with `bin/syleks -c`, or select a conversation with `/sessions` / Ctrl+R. The legacy `java --enable-native-access=ALL-UNNAMED -jar target/syleks-0.1.0-SNAPSHOT.jar chat` launch remains supported.

```sh
mvn clean verify
bin/syleks --help
bin/syleks session list
bin/syleks --continue
```

The default database is `~/.local/share/syleks/syleks.db`; `XDG_DATA_HOME` and `SYLEKS_DATA_DIR` override it. Sessions retain messages, streamed fragments and structured tool history. `/new`, `/resume`, `/rename TITLE` and `/fork` manage conversations without deleting previous history. Primary chat automatically summarizes older context near its input limit; set `compaction.mode` to `manual` to disable additional summary requests. Use `/compact` for explicit context summaries, `/fork MESSAGE-ID` for prefix forks, and `/changes`, `/diff`, `/undo`, `/redo` for native file history. Validated JSON export/import and deletion are available through `session export ID`, `session import PATH` and `session delete ID`.

See [Sessions](docs/sessions.md) for resume, forks, context budgets, concurrency, and recovery, and [Storage and privacy](docs/storage-and-privacy.md) for database locations, migrations, and backups. JSON exports may include confidential prompts, reasoning and tool results. `/clear` now starts a new persistent session.

## Token metrics

A responsive right sidebar shows persisted session token totals, the last LLM request, context budget and generation performance. Use `/sidebar [auto|show|hide]` to control visibility and `/usage` for a scrollable detail view, including on narrow terminals. Unknown metrics remain `N/A`; partial totals are marked `*`. Cumulative session consumption is distinct from the last context sent to the model. See [Token usage](docs/token-usage.md) for provider differences, configuration, SQLite storage and precision limits.

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

Install the built executable for your user without sudo:

```bash
./bin/install-native.sh
syleks --help
```

The installer copies `target/syleks` to `~/.local/bin/syleks`, replacing an existing installation. If that directory is missing from `PATH`, it prints the line to add to your shell startup file. You can select another destination or a previously built executable with `./bin/install-native.sh --bin-dir "$HOME/bin" /path/to/syleks`. The installer works from any working directory and does not modify your shell configuration.

A regular `mvn clean package` continues to build the executable JAR. The native build requires a platform C/C++ toolchain (for example GCC and development headers on Linux), zlib development headers, and enough memory for Native Image. Build on the OS/architecture where the executable will run. The configured binary uses dynamic system libraries and requires no JVM at runtime. Existing configuration, model endpoints, terminal controls, and `/bin/bash` requirements still apply.

The checked-in profile configures a GraalVM download for Linux x64. Maven must run on Java 25 or newer to compile the source. For another platform or an installed Native Image executable, adapt the Arthur configuration in `pom.xml`; the `nativeImage` configuration entry is currently commented out.

The profile uses the shaded application JAR and bundled Fusion/JLine reachability metadata, enables HTTP/HTTPS and native access, and keeps JLine initialization at runtime. Application metadata includes service descriptors and the JDK HTML parser's DTD for web tools. Arthur annotation scanning is disabled because the app uses generated metadata; unsupported native-image features fail the build rather than producing a JVM fallback.

The repository `.sdkmanrc` selects Zulu Java 25 for JVM development. The native build is optional and has not been verified in the current development environment. Validate the resulting executable in an interactive terminal, including `/demo`, permissions, local commands, and your HTTP/HTTPS model connection.

## Configuration

Run `bin/syleks config init` to create or edit settings in a terminal form; use `/config` or Ctrl+G inside chat. Review and save, then choose **Restart chat** or run `/restart` later. For unattended creation of missing files, use `bin/syleks config init --defaults`. The bundled server/model are deployment examples: replace them with your own.

Global settings live in `~/.config/syleks/agent-config.json`. The [configuration guide](docs/configuration.md) documents every supported field, defaults, environment overrides, provider/model selection, permissions, child budgets and MCP options. The [JSON schema](agent-config.schema.json) supports editor validation; startup also validates references and runtime constraints.

| Example | Contents |
| --- | --- |
| [Full agent-config.json](examples/agent-config.json) | Multiple providers/models, proxies, every agent option, all native permissions, child limits and disabled MCP stdio/HTTP/OAuth examples |
| [One compatible server](examples/agent-config-local.json) | A local endpoint with Build/Plan and read-only specialists |
| [Ollama](examples/agent-config-ollama.json) | Ollama model/URL with tools and bounded delegation |
| [Example catalog and installation](examples/README.md) | Copying, schema paths, placeholders, Markdown overrides and usage |

The full example contains optional proxy and remote-provider placeholders. Remove or replace them before use. MCP examples are disabled until configured. JSON values are literal strings; provider API keys do not support environment interpolation.

## Markdown agents and subagents

Primary agents, delegated specialists, hidden agents and disabled definitions use UTF-8 Markdown in `~/.config/syleks/agents/` or project `.syleks/agents/`. The filename defines the name; frontmatter defines metadata and permissions; the body supplies the prompt.

Complete examples include [Build](examples/agents/build.md), [Plan](examples/agents/plan.md), [Architect](examples/agents/architect.md), [Explore](examples/agents/explore.md), [Reviewer](examples/agents/reviewer.md), [Triage](examples/agents/triage.md) and [Retired](examples/agents/retired.md). Their model selections require matching JSON providers, or editing/removing the `model` line. Both agent types go in the same `agents/` directory.

Precedence is built-ins → JSON → global Markdown → project Markdown from Git root to launch directory. Later supplied fields and prompt bodies override earlier definitions, subject to the global permission ceiling and project trust. Existing Markdown can therefore override freshly edited JSON. Restart to apply changes; inspect `/agents` and `/permissions NAME`.

```text
/agent build
@explore Find the entry point and configuration loader; return file references.
@reviewer Review src/main/java/fr/openobject/ai/syleks/app/AppConfig.java for validation bugs.
/children
```

Children run in persisted sessions with explicit task context, bounded parallelism/depth/rounds/deadlines, and permissions capped by every ancestor. See [Agents and project instructions](docs/agents.md) for the full format and inheritance rules, and [Subagents](docs/subagents.md) for tasks, batches, context, budgets and navigation.

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
- Explicit global `permission` rules form a ceiling. Within each layer the last matching rule wins; independent layers combine using DENY > ASK > ALLOW. Agents and approvals cannot relax a global DENY or bypass a global ASK. Project expansions and model overrides require [content-bound local trust](docs/tools-and-permissions.md#project-trust-and-permission-ceilings).
- Target patterns support `*` (any text) and `?` (one character), with case-sensitive matching. Targets are model IDs for `chat`, agent names for `task`, normalized absolute paths for `save`, and normalized literal commands/redirections for granular `bash` rules. List broad patterns before narrower exceptions; declaration order matters.
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

Merge these entries into your existing configuration and restart. All provider adapters advertise permitted tools; execution checks the acting agent and all ancestors for child calls. The model/server must support structured function calls.

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

For `ask`, the TUI shows the exact command, working directory, and timeout. `/approve once` executes that command once; `/approve always` approves this and future `bash` requests for the same agent during this app session, except commands matched by deny rules. `/deny` returns a refusal to the model. `allow` runs matching commands without prompting, while `deny` blocks them. Subagent commands are checked against the child's policy and every ancestor's policy. Permission changes require a restart.

The tool accepts `command` and optional `timeout_seconds` (default 60, range 1–300). Commands run through `/bin/bash --noprofile --norc -c` in the launch directory with a minimal environment and closed stdin (see [environment policy and opt-ins](docs/configuration.md#subprocess-environments)). Combined stdout/stderr, exit code, timeout state, and truncation state are returned to the model and shown in the conversation. Captured output is limited to 32,000 characters. Esc/Ctrl+C cancels the running command and model turn; command processes and their descendants are terminated during cleanup. Tool/model conversations stop after the configured `max_rounds` limit (default 12). This is intended for foreground, non-interactive commands, rather than persistent background services.

Shell execution uses your normal OS permissions and is not a filesystem sandbox. An allowed shell command can read/write files or access the network regardless of separate `read`/`edit`/web declarations. Granular command policies analyze every literal command and redirection before execution; any denial blocks the entire script. Unsupported shell syntax is refused under granular policies. A uniform `bash: ask` policy permits full shell syntax after approval. See [shell rules](docs/tools-and-permissions.md#shell-and-web-access) for the supported subset and examples. Command output is sent back to your configured model endpoint.

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
| Ctrl+P | Show/close scrollable help popup; keep the draft |
| Tab | Cycle through primary agents while idle |
| Ctrl+C or Esc | Cancel in-flight generation (Ctrl+C again exits) |
| Ctrl+D | Exit |
| Left / Right | Edit the text |
| Up / Down | Recall older/newer prompts and commands; Down past the newest restores the draft |
| PageUp / PageDown | Scroll conversation |
| Bracketed paste | Paste code without sending it |
| `/help` | Open command and keyboard help popup |
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
                       TerminalApp (JLine)
                        |          |
                   ChatRenderer  ChatSession -> AgentRuntime
                   TerminalCanvas     |           |
                   TerminalPainter SQLite     Provider adapters
                                                  |
                                        Ollama / Responses /
                                        Chat Completions

AgentRuntime -> ToolExecutor -> native tools / MCP / Java extensions
             -> SubagentExecutor -> persisted child sessions
```

- TerminalApp is the only owner of terminal output. Generated content is synchronized into ChatSession via a virtual thread.
- ChatRenderer builds a fresh cell buffer; TerminalPainter redraws only rows whose cells changed.
- Session messages and structured tool history are saved locally in SQLite and restored on resume. Unsent drafts remain in memory only.
- ANSI escape bytes from model output are replaced by spaces, not interpreted by the terminal.

## Current feature boundaries

The app supports streaming conversations, persisted root/child sessions, declared agents, bounded parallel delegation, permission-checked native tools, MCP servers and Java tool extensions. Unknown tool names are reported to the model without execution. The `/demo` entries remain synthetic and do not run tools. Markdown styling is deliberately lightweight (highlighted fenced code, headings, emphasis, inline code, links, and line prefixes) and is not yet a complete CommonMark implementation. Unicode display-width handling is approximate, particularly for combining grapheme clusters.

## Security notes

- The model endpoint defaults to loopback (`127.0.0.1`). Remote provider and MCP endpoints require HTTPS by default; remote HTTP requires an exact global `insecure_http_origins` grant and displays a warning. See the [transport policy and migration guide](docs/providers.md#transport-security).
- Avoid using plain HTTP Ollama connections across untrusted networks; secure remote deployments with TLS and authentication.
- Do not enter client code if the chosen Ollama server is operated by an untrusted party.
- Conversations are persisted automatically in the local SQLite database. The app reads configuration and declared agent Markdown files at startup; permitted shell commands can access project files and their output is included in model requests. See [Storage and privacy](docs/storage-and-privacy.md).
- `/save` may export sensitive text in plaintext at the location you choose.
- Model identifiers must match `[A-Za-z0-9._:/-]{1,240}`. Provider models must be declared in the configuration and available on the selected server.

## Suggested next increments

1. Encryption at rest.
2. Syntax-aware diff navigation and richer Markdown tables.
3. Richer file browsing and attachment workflows.
4. OS-sandboxed execution and richer change review.
5. File picker, fuzzy search, configurable keymap, terminal themes.

Licensed Apache-2.0 (see LICENSE).
