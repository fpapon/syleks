# Agents and project instructions

[User guide](README.md) · [Configuration](configuration.md) · [Tools and approvals](tools-and-permissions.md)

An agent combines instructions, a model selection, generation settings, and permissions. Switching agents lets you change how the assistant approaches a task.

## Choose an agent

- `/agents` lists available agents and their modes. `/agent` also lists them.
- `/agent plan` or `/agent build` selects a primary agent.
- **Tab** cycles primary agents while chat is idle.
- `@reviewer Review this code: …` or `/subagent reviewer Review this code: …` runs a specialist task.

Build and Plan are built in. Setup also creates Architect and Reviewer Markdown definitions; these are available after setup and restart. Names and descriptions appear in `/agents`.

The selected agent's configured permissions determine what it may actually do. Plan is not an OS-enforced read-only environment: the starter Plan policy asks before shell commands, and a shell command can modify files. Inspect `/permissions` before delegating work.

## Primary agents and subagents

A primary agent handles the main conversation. A subagent handles an explicitly supplied task, then returns its labeled answer to the main conversation. That answer becomes available to subsequent follow-ups, and the original primary agent is restored.

**Subagents receive their own instructions and your supplied task, not the parent's entire transcript.** Include the context or code they need, or allow tools for them to inspect it. A subagent call must pass the caller's `task` permission and the subagent's `chat` permission; tools use the subagent's own rules.

Only one invocation runs at a time. Automatic model-initiated delegation, parallel subagents, and recursive delegation are not supported.

## Create a custom agent

Create a UTF-8 Markdown file in either location:

| Location | Scope |
| --- | --- |
| `~/.config/syleks/agents/NAME.md` | Available globally |
| `.syleks/agents/NAME.md` in the directory where you launch Syleks | Overrides for that launch directory |

The filename is the agent name. Use letters, digits, hyphens, or underscores, up to 64 characters. Example `reviewer.md`:

```markdown
---
description: Review supplied Java code for bugs and missing tests
mode: subagent
temperature: 0.1
max_rounds: 10
permission:
  chat: allow
  read: allow
  edit: deny
  write: deny
  bash: deny
---
Review the supplied code. Explain concrete findings and suggest fixes.
Ask for any missing context. Only claim actions supported by tool results.
```

Restart with `/restart` after saving it. Then try `@reviewer Review this method: …`. If delegation is denied, inspect the calling agent's `task` rules.

The text after frontmatter is the agent prompt. Supported fields are:

| Field | Values |
| --- | --- |
| `description` | Display description; defaults to the agent name |
| `mode` | `primary`, `subagent`, or `all`; new agents default to `all` |
| `model` | Optional declared `provider/model` or Ollama model ID |
| `temperature` | Optional number from `0` to `2` |
| `max_rounds` | Optional `-1` for unlimited rounds, or integer from `1` to `1000` |
| `permission` | Operation rules; see [Tools and approvals](tools-and-permissions.md) |

Frontmatter supports simple plain/quoted values, comments, literal/folded multiline descriptions, and nested permission maps. It is a limited format, not a complete YAML implementation: other nested objects, aliases, tags, or tool declarations are unsupported.

Agent settings can also be declared under `agent` in `agent-config.json`. Precedence is built-in defaults → JSON agent settings → global Markdown → launch-directory Markdown. Later definitions replace supplied fields and the prompt body; omitted fields retain earlier values. The effective agent prompt is combined with shared system instructions. Setup preserves customized Markdown files.

Copyable examples are in [architect.md](../examples/agents/architect.md) and [reviewer.md](../examples/agents/reviewer.md).

## Model and startup selection

An agent's `model` overrides the default when selected. Without an override, primary agents use the configured default model, while subagents inherit the current primary model. `/model` changes the active selection until another primary agent is selected.

To start with another primary agent, add `"default_agent": "architect"` to your existing configuration and restart. The name must exist and have mode `primary` or `all`.

## Give the project shared instructions

Put an `AGENTS.md` file at your project's Git root, or at the launch directory if it is not a Git project. For example:

```markdown
# Project instructions

Use Java 25. Follow the existing formatting style.
Run mvn test before reporting a code change as verified.
Do not include credentials in examples or logs.
```

Syleks reads this file as project instructions when recording the first turn of a session. The limit is 128 KiB. Existing sessions keep their recorded instructions; start `/new` to use changed project instructions.

`AGENTS.md` is distinct from custom agent definitions: it supplies shared project guidance, while `.syleks/agents/*.md` defines selectable agents. The latter location is relative to the **launch directory**, so launch from your project root if you keep definitions there.
