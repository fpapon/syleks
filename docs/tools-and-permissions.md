# Tools and approvals

[User guide](README.md) · [Agents](agents.md) · [Configuration](configuration.md) · [Extensions, patch, skills and todos](tools.md)

Tools let the assistant act on your project or retrieve information. They require a model/server with structured function-call support. Text that merely looks like a shell command in a reply is never executed automatically.

## Enable tools deliberately

Merge these entries into the existing `permission` object in `~/.config/syleks/agent-config.json`, then `/restart`:

```json
{
  "permission": {
    "read": "allow",
    "list": "allow",
    "glob": "allow",
    "grep": "allow",
    "edit": "ask",
    "write": "ask",
    "bash": "ask",
    "webfetch": "ask",
    "websearch": "ask"
  }
}
```

This is a **configuration fragment**, not a replacement for your whole file. An agent's own rules can override these global values; check `/permissions` or `/permissions reviewer` to see its effective policy. You can place the same rules inside a JSON agent's `permission` object or Markdown frontmatter.

Now ask a concrete question, for example: “Find the Java entry point, read the relevant method, and explain it.” For changes, try: “Propose a fix, then run the relevant tests.” Review tool approvals as they appear.

### Know which defaults you are using

Without a configuration file, chat, delegation (`task`), and Markdown saving are allowed; shell commands ask; the other tools are denied.

The built-in Explore subagent has its own read/search policy, but delegated tools remain capped by the parent's permissions. Grant the parent the necessary read/search permissions to use it. The `task` tool creates persistent children and supports bounded batches; see [Subagents](subagents.md). Each child's effective decision intersects its own policy with every ancestor: DENY dominates ASK, which requires approval. Remembered approvals never relax an ancestor's DENY.

The **generated starter configuration** changes these rules:

| Operation | Global | Plan override | Build override |
| --- | --- | --- | --- |
| `chat` | Allow | Inherit | Inherit |
| `task` | Deny except ask for `reviewer` | Deny | Allow |
| `save` | Ask | Deny | Ask |
| `read` | Allow | Allow | Allow |
| `edit` | Deny | Deny | Deny |
| `bash` | Deny | Ask | Ask |

Unlisted tools (`write`, `list`, `glob`, `grep`, `webfetch`, `websearch`, `apply_patch`, `skill`, `todoread`, `todowrite`, `question`) remain denied unless enabled. Build's `task: allow` overrides the global delegation rule, including for Reviewer. These are starter settings; customized agent files or configuration may change them.

## Respond to an approval request

An inline panel shows the requested action and details. For changes it includes the new content or replacement; for shell commands it includes the exact command, directory, and timeout.

| Control | Action |
| --- | --- |
| Left / Right or Tab / Shift+Tab | Choose Allow once, Always allow, or Reject |
| Enter | Confirm the selected choice |
| Up / Down, Page Up / Page Down | Scroll action details |
| Ctrl+F | Expand or collapse details |
| Esc / Ctrl+C | Cancel pending work |

Each request starts on **Allow once**. Pasted text cannot confirm a request. Your existing draft is preserved while the panel has focus.

- **Allow once** permits only the pending action.
- **Always allow** remembers approval for that tool and agent, across targets and turns while the current session stays open. It only relaxes `ask`; an explicit `deny` still blocks the action. It is not offered for chat, delegation, or Markdown saving.
- **Reject** refuses the action.

Remembered approvals do not modify your files. They are cleared on restart and session actions, including new/open/resume/fork/rename. `/permissions` lists remembered approvals. The command interface also supports `/approve` (once), `/approve once`, `/approve always`, and `/deny`.

## Available tools

You normally describe what you want in ordinary language; the model chooses tool arguments.

| Tool | What it does | Useful details |
| --- | --- | --- |
| `read` | Read a UTF-8 file with line numbers | `path`; optional one-based `offset` and `limit` (default 200, max 2000 lines) |
| `list` | List one directory level | Optional `path`, result `limit` |
| `glob` | Find paths by a filesystem glob | `pattern`, optional `path` and `limit`; `*.java` matches the root, `**/*.java` nested files |
| `grep` | Search file content with a Java regular expression | `pattern`, optional `path` and `limit`; also requires `read` permission |
| `apply_patch` | Apply exact UTF-8 patches | Workspace-confined; requires `apply_patch` plus `edit`/`write`; [format and limits](tools.md#patches) |
| `skill` | Discover/load skill references | Project `.syleks/skills` and user configuration; `skill` and `read` permissions |
| `todoread` / `todowrite` | Read/replace session todos | Persistent independent parent/child lists; [entry format](tools.md#todos-and-questions) |
| `question` | Ask the user and wait | `/answer text`, option number or ordinary text; Escape cancels |
| `write` | Create a text file, or replace it explicitly | `path`, `content`; existing files require `overwrite: true`; parent directory must exist |
| `edit` | Replace exact text in a file | `path`, `old_text`, `new_text`; match must be unique unless `replace_all: true` |
| `bash` | Run a local command and return its output/status | `command`, optional `timeout_seconds` (default 60, range 1–300) |
| `webfetch` | Retrieve an HTTP(S) page | `url`; HTML is converted to text |
| `websearch` | Search DuckDuckGo for titles and URLs | `query`, optional `limit` (default 10, max 50) |

Successful reads show a short activity entry instead of filling the transcript with source, but the full result reaches the model. Shell results include combined output, exit code, timeout, and truncation status. Search results do not automatically fetch the linked pages.

Tool execution records distinguish success, permission denial, failure and interruption using structured metadata. A command with a nonzero exit code or a timeout is a failed tool; its diagnostic still returns to the model, which can recover. A denied tool also returns its refusal without executing the action. Cancellation stops further model/tool rounds. Status is never inferred from words such as `Tool error:` appearing in legitimate output.

The shared `ToolExecutor` enforces the active agent's policy at execution, including calls the model makes without an advertised schema. Approval callbacks cannot override a `deny`. In persisted tool executions, denial is represented by `CANCELLED` with authorization `DENIED`, interruption by `INTERRUPTED`, and execution failure by `FAILED`. Completed result parts additionally record the typed status, exit code, timeout and truncation metadata.

### File and search boundaries

Relative paths start in the directory where Syleks was launched. Dedicated file tools reject paths outside that workspace and symlink paths. `edit` checks `edit` permission, including the read needed for replacement; `write` checks `write`, even when overwriting.

Directory/search tools check the root and each inspected or returned entry. `grep` also checks `read` for each inspected file. Denied entries are omitted, so an empty result can mean access was denied. Broad allowed roots with narrower exclusions can reduce repeated prompts.

Scans stop after 10,000 entries or 32 levels, and return up to 100 results by default (maximum 500). Text files and HTTP responses are limited to 1 MB; tool output is capped at 32,000 characters. Binary or malformed UTF-8 files are rejected or skipped. File writes are normal filesystem operations; avoid another editor or process changing the same file during a tool action.

### Shell and web access

Shell commands run through `/bin/bash --noprofile --norc -c` in the launch directory with closed standard input. Ordinary environment variables are inherited, but `BASH_ENV`, `ENV`, `SHELLOPTS`, `BASHOPTS`, and imported `BASH_FUNC_*` functions are removed so those shell hooks cannot run before the approved command. Use foreground, non-interactive commands. Cancelling stops the command and its descendants during cleanup.

**Shell execution uses your normal OS permissions.** Allowing a program can allow file changes or network access regardless of separate file/web rules. Command permissions govern shell invocations, not every action an allowed executable, script, interpreter, or child process can perform. Prefer an allowlist of trusted programs when using granular rules.

With granular `bash` patterns, Syleks checks **every literal command and redirection before starting any part of the script**. This includes command lists separated by `;`, newlines, `&&`, `||`, `&`, and pipelines. A single `deny` blocks the entire script, even a branch that might not run. `ask` requires approval when no action is denied. Remembered `always` approvals never override a denial or unsupported syntax.

Commands are matched with literal quotes/backslashes decoded and words separated by single spaces: `t'o'uch marker` is checked as `touch marker`. Redirections are additional targets including their operator and optional descriptor, such as `> output.txt`, `>> output.txt`, `< input.txt`, or `2> errors.txt`. Consequently, allowing `printf *` alone does not authorize `printf ok > output.txt` under a deny-by-default policy: also allow or ask for `> output.txt`. Executable names and redirection filenames are literal strings; their paths are not canonicalized. An explicit deny/ask rule matching the original whole script remains an additional restriction; a whole-script allow never skips individual checks.

```json
{
  "permission": {
    "bash": {
      "*": "deny",
      "printf *": "allow",
      "> output.txt": "ask",
      "touch *": "deny"
    }
  }
}
```

Here `printf ok; touch marker` is refused before either command runs. `printf ok > output.txt` requires approval. The complete original script, working directory and timeout appear in the approval prompt.

Granular policies accept a conservative literal subset of Bash. Variable/arithmetic/command/process substitutions, unquoted glob/brace/tilde expansion, comments, assignments, groups, control structures, here documents/strings, indirect shell commands, shell-state builtins and `printf` variable assignments are refused when they cannot be checked safely. This restriction also applies after `always` approval. For full shell scripts, use an unrestricted `"bash": "ask"` policy and review each request (or consciously grant `"bash": "allow"`). These uniform policies retain whole-shell authority. A later unconditional `*` rule overrides earlier granular rules, following the usual precedence.

Web fetch follows at most five redirects, checking permission for each destination. Permitted URLs can include private/local servers. Search sends your query to DuckDuckGo's HTML search service, requires no API key, and may fail if that service blocks requests or changes its page. Search redirects require `webfetch` permission for the destination. Web content is untrusted material supplied to the model.

## Write targeted rules

Each operation accepts `allow`, `ask`, or `deny`, or an ordered object of target patterns:

```json
{
  "permission": {
    "read": {
      "*": "allow",
      "*/.env": "deny",
      "*/secrets/*": "deny"
    },
    "bash": "ask",
    "task": {
      "*": "deny",
      "reviewer": "ask"
    }
  }
}
```

Patterns are case-sensitive: `*` matches any text and `?` one character. They are simple patterns, not filesystem glob syntax. Targets are model IDs for `chat`, agent names for `task`, normalized absolute file paths for file operations/`save`, normalized literal commands and redirections for granular `bash` rules (as described above), URLs for `webfetch`, and query text for `websearch`.

The **last matching rule wins**. Put broad rules first and narrower exceptions afterward. Rules combine in this order: defaults → global permissions → agent JSON → global agent Markdown → project-root agent Markdown → agent Markdown down to the launch directory. Agent rules can grant an action denied globally, so inspect them too. The example protects dedicated reads of those paths; it does not prevent an allowed shell command from reading them.

`"permission": "deny"` denies every operation at that level; an operation key `"*"` also addresses all operations. Application loading of configuration, agent definitions, and shared project instructions is separate from model tool permissions.

## Complete policies to adapt

The [full JSON reference](../examples/agent-config.json) lists all native operations plus MCP and custom-tool examples. The [Build definition](../examples/agents/build.md) permits inspection and asks before edits, patches and commands; [Plan](../examples/agents/plan.md), [Explore](../examples/agents/explore.md) and [Reviewer](../examples/agents/reviewer.md) deny those effects. These policies are examples and differ from setup defaults. See the [installation guide](../examples/README.md) to reconcile existing Markdown overrides.

For an approval-gated patch, permit `apply_patch` **and** `edit` for updates, or `write` for additions/deletions. For a delegated read/search task, the parent and child must both permit the necessary operations. A custom permission name only authorizes an already registered Java tool; it does not create one.
