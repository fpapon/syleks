# Getting started

[User guide](README.md) · Next: [Chat controls](chat.md)

## What you need

- Java 25 and Maven 3.9 or newer to build from source. Java 25 is also required to run the JAR.
- An interactive terminal with at least 35 columns and 15 rows. A truecolor terminal and a Unicode monospace font give the best display.
- A running Ollama or OpenAI-compatible server with the model you want to use. Tool use additionally requires a model and server that support structured function calls.
- `/bin/bash` if you enable local shell commands.

Setup can create configuration files before a model server is running.

## Build and make the command available

From the Syleks source directory:

```sh
mvn clean package
export PATH="$PWD/bin:$PATH"
syleks --help
```

The build runs the tests and creates `target/syleks-0.1.0-SNAPSHOT.jar`. The launcher in `bin/` finds that JAR even when you run it from a different project. Keep the built source directory in place. To make the command available in future terminals, add its **absolute** `bin` path to your shell's `PATH` configuration.

You can also launch the JAR directly:

```sh
java --enable-native-access=ALL-UNNAMED -jar /path/to/syleks/target/syleks-0.1.0-SNAPSHOT.jar
```

Append any [CLI option](commands.md) to that command. The historical `chat` argument still works. From the source directory, `mvn compile exec:exec` also launches chat in an interactive terminal.

If you have a native Syleks executable, invoke it directly, for example `./target/syleks --help`, or run `./bin/install-native.sh` to install it as `~/.local/bin/syleks`. The installer prints PATH setup instructions when needed. Its chat commands and configuration are the same. Building that optional executable is covered in the repository [README](../README.md#native-executable-with-apache-arthur).

## Option A: use Ollama

With Ollama installed, download the model and start the server if it is not already running:

```sh
ollama pull qwen3:8b
ollama serve
```

In another terminal, go to your project and start Syleks:

```sh
cd /path/to/your/project
syleks
```

Without a configuration file, Syleks uses `http://127.0.0.1:11434` and `qwen3:8b`. For another Ollama server:

Save this in `~/.config/syleks/agent-config.json`:

```json
{
  "model": "ollama/qwen3:8b",
  "provider": {
    "ollama": { "options": { "baseURL": "http://YOUR-SERVER:11434" } }
  }
}
```

`provider.ollama.options.baseURL` is the server address; Syleks adds `/api/chat`. Use a trusted connection for remote servers.

## Option B: use an OpenAI-compatible server

Run:

```sh
syleks config init
```

The terminal form asks for the provider, server base URL, optional API key, model name, context/output limits, and agent settings. Enter the exact model ID accepted by your server. A base URL commonly ends in `/v1`; Syleks adds `/chat/completions`.

Review the values, choose **Save**, then **Restart chat** to open chat with those settings. The same form is available inside chat through `/config` or **Ctrl+G**. See [Configuration](configuration.md) for editing controls and multiple providers.

The bundled starter address and model are examples for a particular local deployment. Replace them with your own server and model; setup does not install a model or start a server.

## Send your first message

Type a question and press **Enter**. Responses appear as they arrive. Use **Ctrl+O** to insert a newline, and paste code directly into the composer. Press **Esc** to stop a response. Use **Up/Down** to recall and edit earlier prompts or commands, and **Page Up/Page Down** to scroll the conversation. Down past the newest entry restores your unsent draft and cursor. The last 500 submissions are kept in memory across chat session switches and `/restart`; closing Syleks clears this input history. Consecutive duplicates are stored once. Use `/history clear` or Ctrl+X to clear recall, `/history off` to disable and clear it, or a leading space to exclude one submission. These controls do not prevent conversation storage or provider requests; see [input recall privacy](chat.md#input-recall-privacy).

Try these steps:

1. Send “Help me plan a small change to this project.”
2. Send a follow-up question referring to the answer.
3. Use `/rename My first conversation`.
4. Exit with `/quit`, then run `syleks -c` from the same project.
5. Ask a follow-up that refers to the earlier exchange.

The session history is loaded into the conversation and the next model context, within the configured context budget. You do not need to save manually. For multiple conversations, see [Sessions](sessions.md).

If the assistant needs file access or another tool, use [Tools and approvals](tools-and-permissions.md) to configure it. A request written in chat does not bypass permission rules.

## Upgrading from Fusion Agent

Move `$HOME/.config/fusion-agent` to `$HOME/.config/syleks`, and project `.fusion-agent` directories to `.syleks`, keeping your existing files. Do not overwrite an existing Syleks configuration. Restart Syleks after moving the files.

## Start from a complete example

If you prefer editing files, choose the [Ollama example](../examples/agent-config-ollama.json) or [single compatible server example](../examples/agent-config-local.json). Follow the [copying instructions](../examples/README.md#install-a-json-example), including the local schema path and existing Markdown overrides. The [full reference](../examples/agent-config.json) also demonstrates multiple providers, proxies and MCP.

For specialists, install the [complete agent definitions](../examples/README.md#install-agent-and-subagent-definitions), restart, select `/agent build`, and try `@explore Find the application entry point and cite the file`. Check `/permissions` if file access is denied. Examples are independent of the more restrictive setup defaults.
