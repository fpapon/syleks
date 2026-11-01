# Agents and project instructions

[User guide](README.md) · [Configuration](configuration.md) · [Tools and approvals](tools-and-permissions.md)

An agent combines instructions, a model selection, generation settings, and permissions. Switching agents lets you change how the assistant approaches a task.

## Choose an agent

- `/agents` lists enabled, visible agents and their modes. `/agent` also lists them.
- `/agent plan` or `/agent build` selects a primary agent.
- **Tab** cycles enabled, visible primary agents while chat is idle.
- `@reviewer Review this code: …` or `/subagent reviewer Review this code: …` runs a specialist task.

Build, Plan and the Explore subagent are built in. Setup also creates editable Architect, Reviewer and Explore Markdown definitions; these are available after setup and restart. Names and descriptions appear in `/agents`.

The selected agent's configured permissions determine what it may actually do. Plan is not an OS-enforced read-only environment: the starter Plan policy asks before shell commands, and a shell command can modify files. Inspect `/permissions` before delegating work.

## Primary agents and subagents

A primary agent handles the main conversation. A subagent handles an explicitly supplied task, then returns its labeled answer to the main conversation. That answer becomes available to subsequent follow-ups, and the original primary agent is restored.

**Subagents receive their own instructions and your supplied task, not the parent's entire transcript.** Include the context or code they need, or allow tools for them to inspect it. Delegation requires `task` and effective `chat` permission. Child tools are constrained by the child's permissions and every ancestor's permissions; a child cannot override an ancestor's DENY.

The model can delegate through the structured `task` tool, including batches of independent subagents with bounded parallelism. Each child has a persisted session, deadline, finite round budget and correlation to its parent call. Recursion is bounded and cycles are rejected. See [Subagents](subagents.md) for schemas, limits, cancellation, navigation and usage accounting.

## Create a custom agent

Create a UTF-8 Markdown file in either location:

| Location | Scope |
| --- | --- |
| `~/.config/syleks/agents/NAME.md` | Available globally |
| `.syleks/agents/NAME.md` at the Git root and each directory down to the launch directory | Project overrides, from root to the most specific directory |

Project model overrides and permission expansions require [explicit content-bound trust](tools-and-permissions.md#project-trust-and-permission-ceilings); global permission ceilings always apply. Outside a Git project, only the launch directory supplies project definitions. Parent directories outside the project and sibling directories are not scanned. Project agent files/directories resolving outside the project through symlinks are rejected.

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
| `disable` | Boolean `true` blocks selection and invocation; default `false` |
| `hidden` | Boolean `true` removes the agent from `/agents` and Tab; default `false` |
| `model` | Optional declared `provider/model` or Ollama model ID |
| `temperature` | Optional number from `0` to `2` |
| `max_rounds` | Optional `-1` for unlimited rounds, or integer from `1` to `1000` |
| `permission` | Operation rules; see [Tools and approvals](tools-and-permissions.md) |

Frontmatter supports simple plain/quoted values, comments, literal/folded multiline descriptions, and nested permission maps. It is a limited format, not a complete YAML implementation: other nested objects, aliases, tags, or tool declarations are unsupported.

Agent settings can also be declared under `agent` in `agent-config.json`. Precedence is built-in defaults → JSON agent settings → global Markdown → project-root Markdown → Markdown at each directory down to the launch directory. Later definitions replace supplied fields and the prompt body; omitted fields retain earlier values, including `disable` and `hidden`. Permission rules are overlaid in the same order. Setup preserves customized Markdown files.

A hidden agent remains available by its explicit name (`/agent NAME` for primary/all, `@NAME task` for subagent/all), subject to the same permissions. Hiding does not grant or revoke authority. A disabled agent is unavailable through every selection and invocation path, including direct tool execution. To disable an inherited definition in Markdown, a body is optional:

```markdown
---
disable: true
---
```

Set `disable: false` in a later definition to re-enable it. Use actual booleans: `"true"`, `yes`, numbers and null are rejected. If every primary agent is hidden, Tab leaves the current selection unchanged. The default agent must exist, be enabled and have mode `primary` or `all`; a hidden default is permitted.

Complete, copyable definitions are in the [example catalog](https://github.com/fpapon/syleks/blob/main/examples/README.md#install-agent-and-subagent-definitions): [Build](https://github.com/fpapon/syleks/blob/main/examples/agents/build.md), [Plan](https://github.com/fpapon/syleks/blob/main/examples/agents/plan.md), [Architect](https://github.com/fpapon/syleks/blob/main/examples/agents/architect.md), [Explore](https://github.com/fpapon/syleks/blob/main/examples/agents/explore.md), [Reviewer](https://github.com/fpapon/syleks/blob/main/examples/agents/reviewer.md), [Triage](https://github.com/fpapon/syleks/blob/main/examples/agents/triage.md) and [Retired](https://github.com/fpapon/syleks/blob/main/examples/agents/retired.md). Both primary and subagent definitions belong in `agents/`; mode controls how they run.

These examples include explicit model selection, temperature, round limits, flags, tool policy and a full prompt. Install the matching [JSON configuration](https://github.com/fpapon/syleks/blob/main/examples/agent-config.json), or change/omit their `model` fields to match your server. For Ollama, use a bare model ID such as `qwen3:8b`. Copy only definitions you want and review existing overrides first.

For a working local workflow, install Build, Explore and Reviewer, restart, then use:

```text
/agent build
/permissions build
/permissions reviewer
@explore Find where configuration is loaded. Include file paths and relevant symbols.
@reviewer Review src/main/java/fr/openobject/ai/syleks/app/AppConfig.java for validation bugs. Return findings without changing files.
```

Build's example permits native reads/searches and asks before edits, patches and commands. It allows Explore delegation and asks before Reviewer. The specialists deny mutation, commands and nested delegation. Plan's example denies delegation as well. To support nested delegation, both the delegating child and every ancestor must permit `task` and the necessary descendant tools; set finite shared limits too.

You can use a literal description with `description: |` or a folded description with `description: >`, followed by indented text. Permission keys containing wildcards must be quoted (`"*": deny`). Do not use YAML anchors, lists, inline JSON objects, arbitrary nested metadata, or multiline prompt scalars in frontmatter. Put the full prompt after the closing `---`.

## Model and startup selection

An agent's `model` overrides the default when selected and can select another provider using `provider/model`. Without an override, primary agents use the configured default model, while subagents inherit the current primary model. `/model` changes the active selection until another primary agent is selected. Temperature is sent as Ollama `options.temperature` or Chat Completions `temperature`; the Responses adapters keep their existing payload and omit this sampling field. Account/model support remains provider-specific.

To start with another primary agent, add `"default_agent": "architect"` to your existing configuration and restart. Agent definitions, global instructions and environment settings are immutable during a running application instance: `/restart` rebuilds the services and applies the configured default agent/model while reopening the conversation. A disabled/missing default, invalid mode/model reference, invalid boolean or malformed definition fails loading; the application does not silently substitute another agent. Even disabled definitions are validated.

## Give the project shared instructions

Put global instructions in `~/.config/syleks/AGENTS.md`. Put project instructions in `AGENTS.md` at your project's Git root, with additional files in directories down to where you launch Syleks. Outside Git, only the launch directory supplies project instructions. For example:

```markdown
# Project instructions

Use Java 25. Follow the existing formatting style.
Run mvn test before reporting a code change as verified.
Do not include credentials in examples or logs.
```

The effective instructions are concatenated from general to specific: shared system prompt (`AGENT_SYSTEM_PROMPT` when set) → global instructions → selected agent prompt → project-root instructions → nested instructions down to the launch directory. Project sections identify their relative source paths. Siblings, parents above the project root, and instructions for files outside this launch ancestry are not loaded automatically. This is prompt guidance; execution permissions remain enforced separately.

Syleks snapshots the project sections when recording the first turn of a session. Existing sessions keep that snapshot, including when resumed from another directory in the same Git project; start `/new` to use changed project instructions or another launch scope. Global instructions and the current agent prompt refresh after `/restart`. Broken links, non-regular files, invalid UTF-8 and project instruction links escaping the repository fail loading rather than being ignored.

Changing the primary agent keeps the conversation and its project-instruction snapshot. The next model request uses the selected agent's current system prompt and permissions, including when switching Build → Plan → Build. Reopening a session also combines the selected agent's current prompt with the saved project instructions; it does not restore an obsolete agent prompt. Agent configuration changes still require `/restart`.

`AGENTS.md` supplies shared guidance; `.syleks/agents/*.md` defines agents. Instructions below the launch directory are not dynamically loaded when a tool reads a file there.

## Size limits

Each Markdown agent file, inline JSON agent prompt, global instruction file and project instruction file is limited to **128 KiB of UTF-8**. At most **128 Markdown files** are read across all inherited locations, totalling **1 MiB**, and the final catalog contains at most **128 agents including Build, Plan and Explore**. The JSON configuration itself is limited to **1 MiB**.

Combined effective instructions, including source labels, are limited to **256 KiB** and must also fit the selected model's context budget. Oversized instructions fail explicitly without being silently truncated. These byte limits do not imply a particular model can accept that much context. The editor schema checks booleans and basic structure; the application also validates byte limits, references and effective catalog size.
