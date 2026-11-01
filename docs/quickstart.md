# Five-minute quickstart

Syleks is a terminal AI coding assistant. It streams answers, keeps local project conversations, and can use tools subject to your permissions. This walkthrough takes about five minutes after [installation](installation.md), with Java 25 and a working Ollama server already available.

## 1. Prepare Ollama

Download the example model if you do not already have it:

```sh
ollama pull qwen3:8b
ollama list
```

Start `ollama serve` in another terminal only if Ollama is not already running. The model download can take longer than this walkthrough.

Without a configuration file, Syleks uses `http://127.0.0.1:11434` and `qwen3:8b`. To configure these explicitly, merge the following into `~/.config/syleks/agent-config.json`:

```json
{
  "model": "ollama/qwen3:8b",
  "provider": {
    "ollama": {
      "options": { "baseURL": "http://127.0.0.1:11434" }
    }
  }
}
```

Preserve existing settings. You can also use `syleks config init` or `/config` to edit settings in a terminal form. The bundled setup defaults describe a particular deployment: replace them with your provider and model, then save and restart chat. See [provider configuration](providers.md) for remote and cloud options.

## 2. Open a conversation

From your project directory:

```sh
cd /path/to/your/project
syleks --new
```

The full-screen interface opens a new project session. Type this and press **Enter**:

```text
Help me plan a small change to this project. Ask what context you need first.
```

The reply should stream into the conversation. This first exchange does not require file or shell tools. To let the assistant inspect your project, configure [tools and permissions](tools-and-permissions.md); a chat request does not override a denied action.

::: tip Connection problems
Check that `ollama list` includes the exact selected model and that the configured server is reachable. Read [troubleshooting](troubleshooting.md) for provider errors.
:::

## 3. Follow up and resume

Ask a follow-up, then name the conversation and exit:

```text
/rename My first conversation
/quit
```

Back in the same project directory:

```sh
syleks -c
```

Syleks loads the latest session. Continue with a question referring to your previous exchange. Sessions are stored automatically in local SQLite; `/save` is an explicit export, not a prerequisite for resuming. Exports can include private conversation and tool data.

## Essential controls

| Control | Action |
| --- | --- |
| Enter | Send the message |
| Ctrl+O | Insert a newline |
| Esc | Stop generation |
| Page Up / Page Down | Scroll the conversation |
| Ctrl+R or `/sessions` | Choose a saved session |
| `/quit` or Ctrl+D | Exit |

## Continue learning

- [Setup and your first conversation](getting-started.md) covers configuration alternatives.
- [Chat controls](chat.md) covers editing, input recall and shortcuts.
- [Sessions](sessions.md), [agents](agents.md) and [subagents](subagents.md) explain ongoing work.
- [MCP servers](mcp.md) explains external tools, resources and prompts.
- [Command reference](commands.md) lists CLI and interactive commands.
