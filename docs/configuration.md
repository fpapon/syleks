# Configuration

[User guide](README.md) · [Agents](agents.md) · [Tools and approvals](tools-and-permissions.md)

## Create or update settings

In your shell, run `syleks config init`. Inside chat, use `/config` or **Ctrl+G**. `/config init` and `/config edit` open the same form. Finish or cancel current work and resolve approvals first.

The form uses the chat's terminal styling and prefills existing values. It asks for:

| Setting | What to enter |
| --- | --- |
| Provider ID / name | A short identifier such as `local`, and a display name |
| Base URL | Your OpenAI-compatible server's API base, such as `http://127.0.0.1:8000/v1` |
| API key | Your server's key, or leave empty if it does not require one |
| Model ID / name | The exact server model ID, and a display name |
| Context / output limits | Limits supported by your model and server; output must fit within context |
| Global round limit | Maximum model/tool rounds per message |
| Plan / Build settings | Temperature and round limit for each agent |

**Editing controls:** type to replace a selected prefilled value; use Left/Right to edit within it. Enter, Tab, or Down advances, and Shift+Tab or Up returns to the previous field. Home/End move within the value; Backspace/Delete erase text or clear a selected value, including an API key. Keys are masked on screen. Esc, Ctrl+C, or Ctrl+D closes the form before saving.

After the last field, review the values and choose **Save**, **Back**, or **Cancel**. Saving updates the JSON file atomically and creates missing default agent files and the local schema. Existing custom agent files, other providers/models, and permission rules are preserved. If another process changes the configuration while the form is open, saving is rejected: reopen it to load the latest values. Repairing malformed JSON shows a warning; keep a backup if you need settings the form cannot recover.

Use Tab or arrow keys to select a review/restart choice, then Enter to confirm. Once saving has started, wait for it to complete; cancellation is disabled during the write.

After saving, choose **Restart chat** to apply the settings, or **Later** to continue with the current settings. You can run `/restart` later. Restart keeps your current session history and in-memory drafts, selects the configured default model, and does not contact the model until you send a message. Approvals remembered during the old runtime are cleared.

## Files created by setup

```text
~/.config/syleks/
  agent-config.json
  agent-config.schema.json
  agents/
    build.md
    plan.md
    architect.md
    reviewer.md
    explore.md
```

The configuration directory uses `HOME`, falling back to Java's `user.home`. `XDG_CONFIG_HOME` does not relocate it. New configuration directories/files get private permissions where supported. API keys entered in the form are stored in plaintext in `agent-config.json`; keep it private.

For unattended creation:

```sh
syleks config init --defaults
```

This creates **only missing files**, never overwrites existing ones, and does not offer to restart chat. Its bundled settings use `local/qwen3.8-27b` at `http://192.168.52.32:8000/v1`, context/output limits `196608`/`32768`, global round limit `20`, and Plan/Build limits `30` with temperatures `0.2`/`0.1`. No API key is written. These are deployment-specific examples; change the server and model before using them elsewhere.

With redirected input, setup uses line prompts for a missing configuration and preserves an existing one. End of input cancels before writing. Use an interactive terminal or `/config` to update existing values.

## Configure providers and models by hand

Use valid JSON: no comments or trailing commas. This example defines one OpenAI-compatible provider:

```json
{
  "$schema": "./agent-config.schema.json",
  "model": "local/my-model",
  "provider": {
    "local": {
      "name": "My local server",
      "npm": "@ai-sdk/openai-compatible",
      "options": {
        "baseURL": "http://127.0.0.1:8000/v1"
      },
      "models": {
        "my-model": {
          "name": "My coding model",
          "limit": { "context": 32768, "output": 8192 }
        }
      }
    }
  },
  "max_rounds": 20,
  "agent": {
    "plan": { "temperature": 0.2, "max_rounds": 30 },
    "build": { "temperature": 0.1, "max_rounds": 30 }
  },
  "permission": {
    "chat": "allow",
    "read": "allow",
    "bash": "ask"
  }
}
```

Replace `my-model` with your server's ID. Add `"apiKey": "YOUR-KEY"` inside `options` if required; Syleks sends it as a Bearer token. `npm` selects the supported OpenAI-compatible protocol: no npm package is installed or executed. Syleks appends `/chat/completions` to `baseURL`.

Add more entries under the singular `provider` object to use several servers, each with its own model catalog. The same raw model ID can exist on different providers. Select one explicitly with `/model local/my-model`. Only the model ID after the provider prefix is sent to the server. Provider/model names must refer to declared entries; Syleks does not discover or download models.

Set `options.proxy` independently on each provider to route its API calls through an HTTP proxy:

```json
{
  "provider": {
    "local": {
      "options": {
        "baseURL": "https://llm.example.com/v1",
        "proxy": "http://proxy.example.com:8080"
      },
      "models": { "my-model": {} }
    },
    "ollama": { "options": { "proxy": "http://proxy.example.com:8080" } },
    "chatgpt": { "options": { "proxy": "http://proxy.example.com:8080" } },
    "openai-api": { "options": { "proxy": "http://proxy.example.com:8080" } },
    "spacexsi": { "options": { "proxy": "http://proxy.example.com:8080" } },
    "openrouter": { "options": { "proxy": "http://proxy.example.com:8080" } },
    "opencode": { "options": { "proxy": "http://proxy.example.com:8080" } },
    "google": { "options": { "proxy": "http://proxy.example.com:8080" } }
  }
}
```

The proxy URL requires the `http` scheme, a hostname and an explicit port from `1` to `65535`. An optional trailing slash is allowed; credentials, other paths, query parameters and fragments are rejected. HTTPS API requests use the proxy's CONNECT tunnel. Proxy authentication is not supported. Omit `proxy` to keep the Java HTTP client's default proxy behavior. Built-in entries (`ollama`, `chatgpt`, `openai-api`, `spacexsi`, `openrouter`, `opencode`, `google`) accept only `options.proxy`; their endpoints and authentication remain configured through the existing settings. Proxy settings cover chat and model discovery, and do not change OAuth login or token refresh traffic. Restart after editing the file.

`$schema` enables validation/completion in compatible editors using the schema beside the configuration. The repository also contains a [full configuration example](../examples/agent-config.json) and [schema](../agent-config.schema.json). Startup separately validates references and supported settings. Restart after hand edits.

## Ollama configuration and environment variables

Ollama does not need a provider entry. Without a file, Syleks defaults to `qwen3:8b` at `http://127.0.0.1:11434`. You can save legacy Ollama settings instead of a provider-based `model`:

```json
{
  "ollama_url": "http://127.0.0.1:11434",
  "ollama_model": "qwen3:8b"
}
```

| Variable | Effect |
| --- | --- |
| `OLLAMA_URL` | Overrides `ollama_url`; `/api/chat` is appended. Does not override provider URLs. |
| `OLLAMA_MODEL` | Overrides the configured default model selection. A bare model ID selects Ollama. |
| `AGENT_SYSTEM_PROMPT` | Replaces the shared default system prompt. Saved sessions retain their recorded instructions. |
| `SYLEKS_CONTEXT_WINDOW` | Fallback context window, default `8192` |
| `SYLEKS_RESPONSE_TOKENS` | Fallback response reserve, default `2048`, capped at one quarter of the selected context window when no output limit is specified |
| `SYLEKS_COMPACTION` | `automatic` (default) or `manual`; overrides `compaction.mode` |
| `SYLEKS_DATA_DIR` | Overrides the session data directory; see [Storage](storage-and-privacy.md) |
| `XDG_DATA_HOME` | Parent of the default `syleks/` data directory when `SYLEKS_DATA_DIR` is unset |

Environment variables are read at startup. Remove an unwanted `OLLAMA_MODEL` override if your configured default model appears to be ignored. An agent's own model setting can also select a different model when that agent is activated.

## Context, temperature, and round limits

Per-model `limit.context` and `limit.output` take priority over the context environment defaults. Positive global context/reserve values are required, and the global reserve must be smaller than the global window. Limits must also be supported by the server. Ollama requests use `num_ctx`/`num_predict`; OpenAI-compatible requests use `max_tokens` for output.

Syleks estimates context size conservatively and reserves room for the response and tools. Automatic compaction is enabled for primary chat: before each request, including tool continuations, older complete turns are summarized when estimated input reaches 85% of the input allowance. Recent complete turns are retained with a target of 60% after compaction. Summaries use the selected provider/model/account and consume additional model requests under the existing chat permission.

Configure this in `~/.config/syleks/agent-config.json`:

```json
{
  "compaction": {"mode": "automatic", "threshold": 85, "target": 60}
}
```

Percentages must be integers from 1 to 99, with `target < threshold`. Set `mode` to `manual` (or `SYLEKS_COMPACTION=manual`) to disable automatic summary requests. `/compact` remains available in either mode. Automatic compaction attempts at most once per user generation; each attempt has the existing bounded summary request count. If a summary fails, Syleks keeps the previous summary, reports the failure, and falls back to including recent turns that fit. The latest turn is never split; a turn or pinned instructions that exceed the budget still require a larger context or `/new`. The target is best effort, especially with large current tool results. Saved history is always preserved. See [Sessions](sessions.md) for details.

Agent `temperature` is optional, from `0` to `2`; omitted values use the server default. Global `max_rounds` defaults to `12` without configuration and accepts `-1` for unlimited rounds or integers from `1` to `1000`. Per-agent values override it, including `-1`. Unlimited turns still finish when the model answers without tool calls and can be cancelled normally.

Agents also support boolean `disable` and `hidden`. Disabled agents cannot be selected or invoked; hidden agents are omitted from discovery but remain available by explicit name. Agent definitions inherit global and project Markdown overrides from the Git root to the launch directory. See [Agents](agents.md) for precedence, global/project instructions, size limits, provider sampling support and restart behavior.

A round is one model response and any tool calls it requests. It is not a user message count or time limit. A final response with no tool calls ends the turn immediately. If the last allowed round requests tools, their results are recorded and Syleks stops with a limit notice; send another message to continue. The count resets for every message or subagent task.

The optional `subagents` object adds finite child execution ceilings: `max_depth` (3), `max_parallel` (4), `max_tasks` (32), `max_rounds` (16), `max_session_rounds` (128), and `timeout_seconds` (120). The values in parentheses are defaults. Task/round counters are durable per root session and include nested tasks; an agent's `max_rounds: -1` cannot remove the common child ceiling. See [Subagents](subagents.md) for ranges, batch syntax and deadline behavior. The existing setup form preserves these numeric settings when saved.

## Token sidebar

`/sidebar [auto|show|hide]` saves visibility in the existing provider-selection `config.json`. Context color thresholds are configured with `context_warning_percent` and `context_critical_percent` in that file. See [Token usage](token-usage.md) for the exact location, defaults and precision limits.

## MCP servers

Configure stdio or Streamable HTTP servers in `mcp`. Project definitions require an exact workspace entry in the global `mcp_trusted_projects` list. MCP credentials and permissions are independent of model providers. See [MCP servers](mcp.md) for configuration, OAuth and limits.

## Complete configuration reference

Use the [example catalog](../examples/README.md) to choose a full reference, a single-server configuration, or an Ollama configuration. Each is a complete JSON document; `permissions.json` is a merge fragment. The examples show supported combinations, rather than settings that every installation needs. Setup's bundled defaults differ from these examples.

Only the global `~/.config/syleks/agent-config.json` configures providers, agents, global permissions and child limits. A project `.syleks/agent-config.json` supplies **only MCP definitions**, and only for an explicitly trusted launch workspace. For project agent customization, use `.syleks/agents/NAME.md`; for instructions, use `AGENTS.md`.

### Top-level keys

| Key | Type / default when omitted | Purpose |
| --- | --- | --- |
| `$schema` | String, optional | Editor schema path/URI; generated setup uses a schema beside the configuration |
| `model` | String, optional | Default `provider/model`; overrides `ollama_model`; built-in providers are also valid prefixes |
| `provider` | Object, empty | Named compatible servers and built-in proxy overrides |
| `ollama_url` | String, `http://127.0.0.1:11434` | Ollama endpoint before `/api/chat`; overridden by `OLLAMA_URL` |
| `ollama_model` | Model ID, `qwen3:8b` | Ollama fallback when `model` is absent; overridden by `OLLAMA_MODEL` |
| `default_agent` | Agent name, `build` | Enabled `primary` or `all` agent to select on startup/restart |
| `max_rounds` | Integer, `12` | `-1` or 1–1000 rounds per turn; agents may override |
| `permission` | Decision or object | Overlay on built-in permissions; not a replacement unless using a blanket decision |
| `agent` | Object, built-ins retained | Agent definitions/overrides keyed by filename-compatible name |
| `subagents` | Object, finite defaults | Shared depth, parallelism, task, round and deadline limits; [ranges](subagents.md#limits-and-cancellation) |
| `mcp` | Object, empty | At most 16 named stdio/HTTP servers; [server options](#mcp-server-fields) |
| `mcp_trusted_projects` | Array, empty | Up to 128 exact absolute launch workspace paths allowed to supply project MCP overrides |

There are no `providers`, `tools`, `system_prompt`, `instructions` or `agents` top-level settings. Use singular `provider` / `agent`, `permission`, `AGENT_SYSTEM_PROMPT`, and instruction files. Unknown settings are rejected by the schema; use it for editor checks. Startup also checks semantic constraints that JSON Schema cannot resolve, including agent/model references and incompatible MCP authentication options.

### Provider and model fields

| Path under `provider.ID` | Values and behavior |
| --- | --- |
| `name` | Optional nonblank display label; defaults to the provider ID |
| `npm` | Optional `@ai-sdk/openai-compatible`; protocol selector only |
| `options.baseURL` | Required for custom providers; HTTP(S) API base without credentials, query or fragment |
| `options.apiKey` | Optional nonblank literal Bearer token; omit it for unauthenticated servers |
| `options.proxy` | Optional `http://HOST:PORT`; no credentials; port 1–65535 |
| `models.MODEL.name` | Optional display label; defaults to the raw model ID |
| `models.MODEL.limit.context` | Optional positive integer up to 2147483647 |
| `models.MODEL.limit.output` | Optional positive integer up to 2147483647; cannot exceed context when both are present |

Custom providers require `options.baseURL`. Their `models` catalog is optional, but a selected custom model must be declared. Model IDs can include `/` (for example `remote/team/reasoner` selects provider `remote`, model `team/reasoner`). Provider IDs use letters, digits, `.`, `_` or `-`; model IDs additionally allow `:` and `/`. The schema gives exact length and character restrictions.

Reserved entries `ollama`, `chatgpt`, `openai-api`, `spacexsi`, `openrouter`, `opencode` and `google` accept only `options.proxy`. They do not accept custom URLs, API keys, names or model catalogs. Configure Ollama using its legacy keys/environment; configure built-in cloud authentication through [account commands](authentication.md). Provider JSON values are literal: `${NAME}`, `{env:NAME}`, `~` and relative file includes are not expanded by Syleks.

### JSON agent fields

Every [Markdown frontmatter field](agents.md#create-a-custom-agent) is also valid under `agent.NAME`: `description`, `mode`, `model`, `temperature`, `max_rounds`, `disable`, `hidden`, and `permission`. JSON additionally accepts `prompt`, a nonblank string containing the instructions. Markdown uses its body for the prompt and does not accept a `prompt:` frontmatter field.

New agents default to their name as description, mode `all`, enabled and visible, no model/temperature/round override, and an empty prompt if JSON omits it. Existing agents retain omitted settings. Markdown requires a nonblank body unless `disable: true`. JSON string prompts can contain `\n` for line breaks. The [full JSON example](../examples/agent-config.json) and [complete Markdown definitions](../examples/README.md#install-agent-and-subagent-definitions) cover primary, subagent, all, hidden and disabled agents.

`permission` may be `"deny"` / `"ask"` / `"allow"`, an object mapping operation names to decisions, or operation names to ordered target maps. The full reference enumerates every native operation and demonstrates `custom_greeting` and MCP rules. Custom permission entries do not install executable tools; [Java extensions](tools.md#java-extensions) must already be registered. `*` addresses all operations; broad rules must precede narrower exceptions. See [permission targets and precedence](tools-and-permissions.md#write-targeted-rules).

### MCP server fields

Fields live at `mcp.SERVER`. IDs start with a lowercase letter and contain up to 16 lowercase letters/digits or single interior underscores.

| Field | Type / default | Applies to |
| --- | --- | --- |
| `type` | Required `stdio` or `http` | Both |
| `enabled` | Boolean, `true` | Both; `false` prevents launch/connection |
| `timeout_seconds` | Integer 1–300, `30` | Both |
| `resources` / `prompts` | Booleans, `false` | Both; opt in to separate resource/prompt tools |
| `command` | Required nonempty string array, at most 128 arguments | stdio; executable and literal arguments, no implicit shell |
| `environment` | String map, at most 64 entries | stdio; added to the small inherited environment |
| `url` | Required HTTP(S) endpoint | HTTP; no credentials, query or fragment |
| `headers` | String map, at most 64 entries | HTTP; literal custom headers; transport-managed headers are forbidden |
| `bearer_token_env` | Environment variable name | HTTP; reads the token from the environment, no simultaneous Authorization header |
| `oauth` | Boolean, `false` | HTTP; cannot combine with bearer token or Authorization header |
| `client_id` | Optional public OAuth client ID | HTTP with `oauth: true`; omit to use dynamic registration |
| `scope` | Optional nonblank scope string | HTTP with `oauth: true` |

`command`/`environment` cannot be used on HTTP servers; `url`/`headers`/`bearer_token_env` cannot be used on stdio servers. OAuth is HTTP-only. Disabled definitions are still validated. See [MCP](mcp.md) for header restrictions, environment inheritance, credential handling and protocol limits.

### Files that are separate from agent-config.json

| File | Settings |
| --- | --- |
| Provider selection `config.json` | Active provider/model/account references; sidebar and context color preferences |
| `.syleks/project.json` | Project cloud policy, for example `{"allow_cloud": false}` |
| Global/project `AGENTS.md` | Shared instructions loaded separately from agent definitions |
| Global/project `agents/NAME.md` | Agent metadata and prompt overrides |
| Global/project `skills/NAME/SKILL.md` | On-demand skill reference documents |

Provider preferences and credentials honor an absolute `XDG_CONFIG_HOME`; legacy agent configuration and MCP credentials remain under `~/.config/syleks`. See [Storage and privacy](storage-and-privacy.md) for exact paths. Additional environment switches are `SYLEKS_ALLOW_CLOUD=false` ([cloud policy](providers.md#sessions-and-confidentiality)), `SYLEKS_CREDENTIAL_BACKEND=auto|secret-service|file` and `SYLEKS_AUTH_TIMEOUT_SECONDS` ([authentication](authentication.md)).
