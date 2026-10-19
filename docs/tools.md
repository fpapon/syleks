# Extensible tools

[Tools and approvals](tools-and-permissions.md) · [Subagents](subagents.md)

All providers advertise the same registry. Schemas count toward the model context budget; enable only the tools you need, and configure `SYLEKS_CONTEXT_WINDOW` / `SYLEKS_RESPONSE_TOKENS` for your model. The engine refuses an oversized request before contacting the provider. Every structured call is validated against its JSON Schema before execution. Permissions still apply when a model calls a tool omitted from its advertised catalogue. Unlisted operations default to deny; enable the tools you need in `permission`, then `/restart`.

```json
{
  "permission": {
    "apply_patch": "ask",
    "skill": "ask",
    "todoread": "allow",
    "todowrite": "allow",
    "question": "allow"
  }
}
```

This fragment supplements existing settings. Patch also requires `edit` for updates and `write` for additions/deletions. A parent agent's denial remains effective in a child. Skills additionally require `read` on their absolute file path. Todos and questions do not execute shell commands.

## Bounded workspace searches

`list` scans one level; `glob` and `grep` scan to depth 32. Specify `path` to focus a large project and `limit` to bound matches (default 100, maximum 500). Searches stop after 10,000 visited entries, 8 million inspected text characters, 8 million regex character accesses, or five seconds of cooperative work. Approval waits do not consume that time. Files are at most 1 MB; one extra file can be read before detecting the remaining text budget. Results include counts and an explicit truncation reason; denied/unreadable entries are omitted. Output stays within 32,000 characters, including its search footer.

Cancellation is checked between entries/lines and during regex backtracking. A regex that exhausts its work budget or stack returns an incomplete result. Filesystem calls and the Java glob matcher are not forcibly interrupted mid-call. No build/vendor directories are silently excluded; narrow `path` when a broad scan is incomplete.

## Patches

`apply_patch` takes a `patch` string:

```diff
*** Begin Patch
*** Update File: src/Example.java
@@
-    return 1;
+    return 2;
*** Add File: notes.txt
+Validated change
*** End Patch
```

Use exact context lines prefixed with a space and changes prefixed with `-`/`+`. Separate update hunks with `@@`; an optional heading after `@@ ` is informational. Use `*** End of File` for a final hunk, including files without a trailing newline. `*** Delete File: path` has no body. Moves, binary patches and fuzzy matching are unsupported. Parent directories must exist. Each path occurs once; at most 64 files and 1 MB of patch text/result per file. UTF-8, LF and consistent CRLF are supported; mixed/CR-only endings are rejected.

Every path, permission, old context and resulting size is checked before the first change. Outside-workspace paths, symlinks, binary data, ambiguous hunks and changed snapshots are refused. Workspace writes from Syleks are serialized, including custom tools declaring exclusive effects. There is no filesystem transaction across files: an I/O failure or cancellation during commit can leave a completed prefix. External processes can still race the final path/content check. Native mutations are journaled for `/changes`, `/diff`, `/undo` and `/redo`; restoration checks for external divergence. This remains confinement for native tools, without an OS sandbox. See [Sessions](sessions.md) for recovery and restoration limits.

## Skills

Place a skill at `.syleks/skills/NAME/SKILL.md` in the launch workspace, or `~/.config/syleks/skills/NAME/SKILL.md`. Names use lowercase letters/digits, `_` or `-` (1–64 characters). Project definitions override user definitions with the same name. `skill {}` discovers permitted names (listing also requires `skill` permission on target `*`); `skill {"name":"NAME"}` reads one reference. Discovery scans at most 256 directory entries; each file is UTF-8 text of at most 30,000 bytes. Symlinks are rejected. Files are read on invocation, so edits are immediately visible.

The returned document is explicitly untrusted reference data. Its instructions cannot grant permissions. Syleks does not execute scripts, install dependencies or automatically load referenced resources from a skill. Skill frontmatter is returned as text rather than parsed as executable configuration.

## Todos and questions

`todowrite` replaces the invoking session's list with `todos: [{"id":"1","content":"Run tests","status":"pending","priority":"high"}]`. IDs must be unique; status is `pending`, `in_progress`, `completed` or `cancelled`; priority is `low`, `medium` or `high`. A list has at most 100 items and 30,000 encoded bytes. `todoread {}` returns it. SQLite preserves the list on restart and deletes it with its session. Children have independent lists; todos are not copied on fork. Todo tool calls/results also remain in conversation history.

`question` accepts a question and optionally up to eight suggested `options`. The conversation displays the question; reply with `/answer text`, `/answer 2`, or ordinary text. Free text is always accepted. Escape/Ctrl+C cancels generation, including children waiting for an answer. Simultaneous child questions are queued. Questions are not permission approvals: an answer never grants tool authority. The answer is recorded as the tool result in persistent history. An interrupted question is not replayed automatically after restart.

## Java extensions

A trusted Java module on the application classpath can contribute a Fusion `@DefaultScoped` bean implementing `CustomTool`. Fusion discovers all such beans; there is no JavaScript loader or in-app installation of executable code. Use a unique name `custom_[a-z][a-z0-9_]{0,55}`. Native names are reserved and collisions fail startup. `ToolRegistry.register`/`unregister` also support runtime custom registrations; removal affects future calls/declarations. Native tools cannot be unregistered.

```java
@DefaultScoped
public class GreetingTool implements CustomTool {
    public ToolDefinition definition() {
        return new ToolDefinition("custom_greeting", "Return a greeting",
            Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")),
                   "required", List.of("name"), "additionalProperties", false), true);
    }
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        return ToolResult.success("Hello " + arguments.get("name"));
    }
}
```

Enable `custom_greeting: allow` (or `ask`) in permissions. Each call passes schema validation, own-name permission evaluation and the common effect coordinator before the handler runs. `target(arguments)` may extract a pure permission target; default `*`. ASK displays `approvalDetails(arguments)` (at most 8,000 characters); its default shows the arguments. Override this pure method to redact sensitive values while keeping the proposed action reviewable. `exclusive=true` is required for mutations or uncertain effects; read-only handlers may use false. Handlers must check interruption, use `context.require` for additional resources, bound their allocations, avoid secrets in exceptions/results, and never access the terminal. Output is truncated after at most 32,000 UTF-16 code units without splitting a surrogate pair, followed by a truncation marker. Exceptions become typed failures; cancellation propagates.

Schemas use JSON Schema 2020-12, with local `#/...` references only; remote/file references and schema identifier overrides are rejected. Descriptions are limited to 4,000 characters, schemas to 32,000 characters, and the registry to 128 entries and 131,072 schema/description characters in total. Custom Java executes with the process's OS authority; these extension checks do not sandbox malicious plugin code. Add only reviewed modules. For external processes/services, use the built-in [MCP client](mcp.md), with separate server trust, authentication and tool permissions.

## Configuration and definition examples

The [full configuration](../examples/agent-config.json) demonstrates `apply_patch`, `skill`, todos, questions, MCP patterns and a custom-tool permission. The [complete Build agent](../examples/agents/build.md) enables an approval-gated workflow; specialist definitions constrain tools further. Follow the [example installation instructions](../examples/README.md) to apply them. Skills live in their own `skills/NAME/SKILL.md` paths; agent frontmatter does not support a `tools` or `skills` list.
