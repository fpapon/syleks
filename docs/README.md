# Syleks user guide

Syleks is a terminal assistant for working on code. Chat with a model, ask it to inspect your project or run tools, and return to saved conversations later. You can connect to Ollama or an OpenAI-compatible model server.

**New to Syleks?** Follow [Getting started](getting-started.md), then learn the [chat controls](chat.md).

| I want to… | Read |
| --- | --- |
| Install, connect a model, and send my first message | [Getting started](getting-started.md) |
| Create or update settings without leaving chat | [Configuration](configuration.md) |
| Select Ollama, ChatGPT or OpenAI API models | [Providers](providers.md) |
| Sign in, switch accounts and protect credentials | [Authentication](authentication.md) |
| Write multiline messages, paste code, and use keyboard shortcuts | [Chat and terminal controls](chat.md) |
| Inspect token consumption, context and generation performance | [Token usage](token-usage.md) |
| Find, resume, rename, or fork a conversation | [Sessions](sessions.md) |
| Switch between Build and Plan, or create a specialist | [Agents and project instructions](agents.md) |
| Let the assistant read files, make edits, run commands, or search the web | [Tools and approvals](tools-and-permissions.md) |
| Look up a CLI option or slash command | [Command reference](commands.md) |
| Locate, back up, or protect my data | [Storage and privacy](storage-and-privacy.md) |
| Fix connection, configuration, terminal, or session problems | [Troubleshooting](troubleshooting.md) |

## A typical workflow

1. Open a terminal in your project and run `syleks`.
2. Use `/config` to connect your model server if needed. Save, then choose **Restart chat**.
3. Ask a question, for example: “Explain the entry point of this application.” File access depends on the active agent's permissions.
4. Review any approval request before allowing a tool to run.
5. Rename the conversation with `/rename Understand the application`.
6. Quit with `/quit`. Next time, run `syleks -c` from the same project to continue.

Conversations are saved automatically on your machine. Your configured model server receives the context needed to answer, including permitted tool results. Web tools also contact external sites when enabled. See [Storage and privacy](storage-and-privacy.md) before using confidential code.

The examples use `syleks` on your `PATH`. [Getting started](getting-started.md) explains how to set that up and how to launch the JAR directly. Replace example model names, addresses, paths, and session IDs with your own.
