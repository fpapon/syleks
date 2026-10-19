# Delegate work to subagents

[Agents](agents.md) · [Sessions](sessions.md) · [Tools and approvals](tools-and-permissions.md)

Subagents run in their own persisted sessions. They use the same execution loop and tools as primary agents, with their own prompt, model, permissions and bounded execution. The parent receives their results and child session IDs.

## Start a task

```text
@explore Find the implementations of UserRepository
/subagent reviewer Review the transaction handling
```

Explore is built in as a subagent for reading and searching code. Its default policy forbids writes, edits, commands and further delegation. Setup also installs an editable Explore Markdown definition. The parent must permit the necessary read/search operations too. Define other specialists using [agent files](agents.md).

Manual invocation creates a structured `task` call in the parent history, then a real child session. Its labeled answer returns to the parent without an extra parent-model request. An enabled agent with mode `subagent` or `all` can be invoked; hidden agents remain accessible by their explicit name. Disabled and primary-only agents cannot be used as children.

## Model-initiated delegation

Agents allowed to delegate receive a `task` function declaration. Its description includes eligible visible agent names and descriptions. A model can call:

```json
{"agent":"explore","prompt":"Find all implementations of UserRepository","timeout_seconds":60}
```

For independent work in parallel, the model can submit one batch:

```json
{
  "tasks": [
    {"agent":"explore","prompt":"Inspect the persistence code"},
    {"agent":"reviewer","prompt":"Review the supplied API contract"}
  ],
  "timeout_seconds":90
}
```

Use either `agent` plus `prompt`, or `tasks`, with at most eight items. The timeout belongs to the enclosing call. Unknown fields, invalid agents, oversized prompts and malformed batches are rejected before starting any child. Each prompt must contain at most 32,000 UTF-8 bytes.

The parent waits for every child in the batch. Results preserve request order, with each child's agent, `session_id`, status, answer, diagnostic and truncation flag. A failed child does not discard successful siblings. Ordinary tool calls, including separate task calls in one model response, still run sequentially; parallel delegation uses the batch form. This is structured concurrency rather than detached background jobs.

## Context and permissions

A child receives its selected agent's prompt, configured global/project instructions and the explicitly supplied task. It does not automatically receive the parent's transcript, prior tool results, internal reasoning or credentials. The delegating model can include information in its task prompt, so task prompts and results remain part of the selected provider's data boundary.

An agent's configured model overrides the parent's model; otherwise it inherits the parent's selection. Provider accounts are resolved for the child's selected provider, with the existing cloud policy, authentication and model checks. Credentials are used by the adapter and are not inserted into model context.

Every invocation requires the calling scope's `task` permission for the target name and effective `chat` permission for the child's model. Each native child tool is checked against **the child and every ancestor agent**: any DENY blocks the effect, and any ASK requires approval. An ALLOW in a child cannot relax an ancestor's DENY. Shell checks retain the phase 1 granular policy analysis.

Example permission fragment for a parent that permits Explore but asks before Reviewer:

```json
{
  "permission": {
    "task":{"*":"deny","explore":"allow","reviewer":"ask"},
    "read":"allow",
    "glob":"allow",
    "grep":"allow",
    "list":"allow",
    "write":"deny",
    "edit":"deny",
    "bash":"deny"
  }
}
```

Merge this into the existing configuration. Agent-specific rules still override global rules within each agent's effective policy; inspect `/permissions NAME`. Delegation then intersects those effective policies across the ancestry. Approval prompts are serialized in the existing TUI panel. Cancelling or timing out a child dismisses its own pending prompt without approving another child.

Read/search tools can run concurrently. File mutations and shell commands are serialized against other native tool effects in the same application instance using a workspace lock. This prevents concurrent subagent edits from interleaving individual tool operations. It does not make an entire multi-step plan transactional, merge conflicting intentions, or coordinate external editors and separate CLI processes.

## Limits and cancellation

Optional configuration, applied after `/restart`:

```json
{
  "subagents": {
    "max_depth":3,
    "max_parallel":4,
    "max_tasks":32,
    "max_rounds":16,
    "max_session_rounds":128,
    "timeout_seconds":120
  }
}
```

| Setting | Meaning | Allowed range |
|---|---|---|
| `max_depth` | Delegation edges below the root | 1–8 |
| `max_parallel` | Active child model turns/native tool handlers per application | 1–8 |
| `max_tasks` | Total child sessions created under a root session | 1–128 |
| `max_rounds` | Maximum model rounds in one child | 1–128 |
| `max_session_rounds` | Total child model-round attempts under a root | 1–2048 |
| `timeout_seconds` | Default and maximum per-call child deadline | 1–600 |

The example shows defaults. A child's agent `max_rounds` can lower the common child ceiling; `-1` does not disable that ceiling. Task and round counters are reserved atomically in SQLite, include nested work and survive restart. Deleting child history does not refund these counters. A new root session or root fork starts a new budget. These are execution limits, not an exact token or monetary cap; model output/context limits and reported usage remain provider-dependent.

The deadline starts when the task is submitted and includes approval, queuing and execution. A nested task cannot outlive its parent's deadline. Reusing an agent name already in the current ancestry is rejected as a cycle; independent siblings may use the same agent.

Esc or Ctrl+C cancels the parent and all descendants. The parent waits for children to stop and persist their state before releasing its session lease. A timeout interrupts the affected child and returns a failed task result that the parent can handle. Transport failures preserve partial text and diagnostics; a failed native tool returns its error to the child model for possible recovery.

Cancellation is cooperative. A transport or external process that ignores interruption can delay shutdown; Syleks keeps the generation lease while waiting. Authorized external effects are not rolled back. File and shell permissions do not constitute an OS sandbox.

## Inspect results and consumption

- `/children` opens the picker for direct children of the current session.
- `/session ID` opens a particular child; `/sessions` also includes child histories.
- `/parent` returns one level up; repeat it to reach the root.
- `syleks --session ID` opens the same saved history after a restart.

Child histories show their task status and diagnostics. They are available for inspection and export, with their messages and tool calls/results. They cannot generate, change model or fork: return to the parent to delegate another task. `/new` explicitly starts an independent root conversation. Ordinary `-c` and `/resume` choose a root/fork session rather than accidentally resuming a child.

The root's `/usage` includes its own requests and descendants' requests. A child includes its own subtree. Each request is stored once under its executing session; tree totals aggregate those records without copying them. A root fork copies history but does not copy usage or the child execution tree. Deleting histories removes their stored usage; consumed execution quotas remain charged to the surviving root.

Task answers returned to the parent are capped at 32,000 UTF-8 bytes for the aggregate JSON, with per-child summaries capped at 16,000 code points. Truncation is explicit and preserves correlation fields and valid JSON; the complete received answer remains in the child history. A process crash marks abandoned tasks interrupted when their leases are recovered. Pending effects are never replayed automatically.

## Complete specialist definitions and a delegated workflow

Install [explore.md](../examples/agents/explore.md) and [reviewer.md](../examples/agents/reviewer.md) in the same global/project `agents/` directory as primary definitions. Pair them with [build.md](../examples/agents/build.md) and the [full configuration](../examples/agent-config.json) or [single-server variant](../examples/agent-config-local.json). See the [installation instructions](../examples/README.md) before copying: Markdown overrides JSON, including model choice and permissions.

A useful task contains the objective, scope, required context and expected result:

```text
/agent build
@explore Inspect src/main/java/fr/openobject/ai/syleks/app and the matching tests. Explain configuration precedence, with file and symbol references. Do not modify files.
@reviewer Review src/main/java/fr/openobject/ai/syleks/app/AppConfig.java and its tests for missing validation. Prioritize actionable bugs and propose regression tests. Do not change files or execute commands.
/children
```

Open a result in the picker, inspect its tools and answer, then use `/parent` to return. For another investigation, submit a fresh task from the parent. Manual tasks do not share previous sibling results unless you include them explicitly. Model-initiated delegation can include relevant parent results in the prompt.

To request a batch in natural language, ask Build: “Use Explore to inspect configuration loading and Reviewer to check its validation in parallel. Give each the relevant source paths, then summarize their findings.” The model must emit the structured `tasks` form above; prose alone does not start children or guarantee a batch. Reviewer approval may be required. An ancestor denial, exhausted durable budget, cycle or expired deadline blocks work even if the specialist's own configuration allows it.
