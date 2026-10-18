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

`$schema` enables validation/completion in compatible editors using the schema beside the configuration. The repository also contains a [complete provider example](../examples/agent-config.json) and [schema](../agent-config.schema.json). Startup separately validates references and supported settings. Restart after hand edits.

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
| `SYLEKS_DATA_DIR` | Overrides the session data directory; see [Storage](storage-and-privacy.md) |
| `XDG_DATA_HOME` | Parent of the default `syleks/` data directory when `SYLEKS_DATA_DIR` is unset |

Environment variables are read at startup. Remove an unwanted `OLLAMA_MODEL` override if your configured default model appears to be ignored. An agent's own model setting can also select a different model when that agent is activated.

## Context, temperature, and round limits

Per-model `limit.context` and `limit.output` take priority over the context environment defaults. Positive global context/reserve values are required, and the global reserve must be smaller than the global window. Limits must also be supported by the server. Ollama requests use `num_ctx`/`num_predict`; OpenAI-compatible requests use `max_tokens` for output.

Syleks estimates context size conservatively, reserves room for the response and tools, and keeps recent complete turns that fit. Older exchanges may be omitted from a request, with a notice in chat, but stay in your saved history. There is no automatic summarization. See [Sessions](sessions.md) for details.

Agent `temperature` is optional, from `0` to `2`; omitted values use the server default. Global `max_rounds` defaults to `12` without configuration and accepts integers from `1` to `1000`. Per-agent values override it.

A round is one model response and any tool calls it requests. It is not a user message count or time limit. A final response with no tool calls ends the turn immediately. If the last allowed round requests tools, their results are recorded and Syleks stops with a limit notice; send another message to continue. The count resets for every message or subagent task.
