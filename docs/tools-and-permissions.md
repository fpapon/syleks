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

This is a **configuration fragment**, not a replacement for your whole file. Explicit global values cap every agent. Agent rules may restrict them; check `/permissions` or `/permissions reviewer` for effective decisions, sources, ceilings and trust state. You can place the same rules inside a JSON agent's `permission` object or Markdown frontmatter.

Now ask a concrete question, for example: “Find the Java entry point, read the relevant method, and explain it.” For changes, try: “Propose a fix, then run the relevant tests.” Review tool approvals as they appear.

### Know which defaults you are using

Without a configuration file, chat, delegation (`task`), and Markdown saving are allowed; shell commands ask; the other tools are denied.

The built-in Explore subagent has its own read/search policy, but delegated tools remain capped by the parent's permissions. Grant the parent the necessary read/search permissions to use it. The `task` tool creates persistent children and supports bounded batches; see [Subagents](subagents.md). Each child's effective decision intersects its own policy with every ancestor: DENY dominates ASK, which requires approval. Remembered approvals never relax an ancestor's DENY.

The **generated starter configuration** changes these rules:

| Operation | Global | Plan override | Build override |
| --- | --- | --- | --- |
| `chat` | Allow | Inherit | Inherit |
| `task` | Deny except ask for `reviewer` | Deny | Deny except ask for `reviewer` |
| `save` | Ask | Deny | Ask |
| `read` | Allow | Allow | Allow |
| `edit` | Deny | Deny | Deny |
| `bash` | Deny | Deny | Deny |

Unlisted tools (`write`, `list`, `glob`, `grep`, `webfetch`, `websearch`, `apply_patch`, `skill`, `todoread`, `todowrite`, `question`) remain denied unless enabled. Build's `task: allow` remains capped by the global delegation rule. Global `bash: deny` also caps both agents' `bash: ask` preferences. These are starter settings; customized agent files or configuration may change them.

## Respond to an approval request

An inline panel shows the requested action and details. For changes it includes the new content or replacement; for shell commands it includes the exact command, directory, and timeout.

| Control | Action |
| --- | --- |
| Left / Right or Tab / Shift+Tab | Choose Allow once, Remember exact, or Reject |
| Enter | Confirm the selected choice |
| Up / Down, Page Up / Page Down | Scroll action details |
| Ctrl+F | Expand or collapse details |
| Esc / Ctrl+C | Cancel pending work |

Each request starts on **Allow once**. Pasted text cannot confirm a request. Your existing draft is preserved while the panel has focus.

- **Allow once** permits only the pending action.
- **Remember exact** remembers only the reviewed action and arguments for 15 minutes, bound to canonical target, agent, workspace, session/task, policy and account/execution context. It only relaxes a matching `ask`; DENY still wins. Chat, delegation, exports, questions, deletion patches and custom/MCP effects remain one-time.
- **Reject** refuses the action.

Remembered approvals do not modify configuration or survive restart. `/permissions` and `/approvals` list exact grants; `/approvals revoke ID` and `/approvals revoke all` revoke them. Cancellation, clear, session/model/agent and account changes invalidate memory. Policy or project trust changes require a restart before tools execute. Use `/approve remember` for exact memory; legacy `/approve always` has the same narrow meaning. See [grant identity and lifecycle](approvals.md).

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

Shell commands run through `/bin/bash --noprofile --norc -c` in the launch directory with closed standard input. The inherited environment is cleared and replaced by a minimal allowlist; startup hooks, exported functions, unknown secrets, tokens and agent sockets are absent by default. The global-only `command_environment` setting supports explicit development opt-ins and trusted PATH directories. See the [environment policy matrix](configuration.md#subprocess-environments). Use foreground, non-interactive commands. Restricted-mode cancellation stops the entire contained process group, including detached descendants.

**Shell execution defaults to restricted Linux isolation.** The global-only `command_sandbox` policy controls OS capabilities, independently of shell approvals. Unsupported enforcement refuses execution; explicit unrestricted mode grants host authority. See [command isolation](command-sandbox.md) for the backend, setup and limitations. Separate file/web rules do not constrain code inside an allowed program. Command permissions govern shell invocations, not every action an allowed executable, script, interpreter, or child process can perform. Prefer an allowlist of trusted programs when using granular rules.

With granular `bash` patterns, Syleks checks **every literal command and redirection before starting any part of the script**. This includes command lists separated by `;`, newlines, `&&`, `||`, `&`, and pipelines. A single `deny` blocks the entire script, even a branch that might not run. `ask` requires approval when no action is denied. Remembered exact approvals never override a denial or unsupported syntax.

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

Granular policies accept a conservative literal subset of Bash. Variable/arithmetic/command/process substitutions, unquoted glob/brace/tilde expansion, comments, assignments, groups, control structures, here documents/strings, indirect shell commands, shell-state builtins and `printf` variable assignments are refused when they cannot be checked safely. This restriction also applies after exact remembered approval. For full shell scripts, use a uniform `"bash": "ask"` permission policy and review each request (or consciously grant `"bash": "allow"`). These uniform policies retain whole-shell syntax within the selected OS sandbox mode. A later unconditional `*` rule overrides earlier granular rules, following the usual precedence.

Web fetch follows at most five redirects, checking permission for each destination. Public web destinations are enabled subject to URL permissions. Private/local services also require an exact global `web_private_origins` grant; metadata and other reserved destinations remain denied. See the [destination policy and migration guide](#web-destination-policy). Search sends your query to DuckDuckGo's HTML search service, requires no API key, and may fail if that service blocks requests or changes its page. Search redirects require `webfetch` permission for the destination. Web content is untrusted material supplied to the model.

## Web destination policy

`webfetch` and `websearch` apply the global network destination policy in addition to tool permissions. An approval, remembered grant, project setting, or child agent cannot bypass it. `/permissions` shows the active policy. Edit `~/.config/syleks/agent-config.json` yourself and restart to apply or revoke grants:

```json
{
  "web_private_origins": ["https://intranet.example:8443", "http://127.0.0.1:8080"],
  "permission": {"webfetch": {"https://intranet.example:8443/*": "ask", "http://127.0.0.1:8080/*": "ask"}}
}
```

Previously a broad URL allow also reached internal services. The default now has no internal grants. Add only the exact services you intend to expose to model-directed GET requests. Grants include scheme, normalized host, and effective port; changing any requires another grant. Granting one origin covers all its paths and ordinary private/loopback DNS answers, so retain narrow URL permissions. Hostnames are lowercased and a final DNS dot is removed; IPv6 literals (including mapped IPv4) are normalized. Use ASCII/punycode hostnames. Userinfo, fragments, scoped IPv6, invalid ports, wildcard grants, and grants with paths or queries are refused. HTTP and HTTPS remain supported for credential-free web retrieval; prefer HTTPS for sensitive data. The transport sends only fixed GET headers, with no cookies or authorization forwarding.

| Address class | Default | With an exact service grant |
| --- | --- | --- |
| Public IPv4 and ordinary IPv6 global unicast | Allowed subject to URL permission | Same |
| RFC1918 IPv4, IPv6 unique-local, IPv4/IPv6 loopback | Denied | Allowed subject to URL permission |
| Link-local (including `169.254.169.254`), unspecified, multicast, broadcast | Denied | Denied |
| Shared address space, documentation, benchmarking, reserved IPv4 and conservative IPv6 special-purpose/translation/transition exclusions | Denied | Denied |
| Known metadata/platform virtual addresses `fd00:ec2::254` and `168.63.129.16` | Denied | Denied |

The classifier conservatively excludes IPv6 special-purpose allocations, including some globally reachable special-purpose services. Ordinary globally reachable IPv4 special-purpose allocations are treated as public. The reference ranges are recorded in the [IANA IPv4](https://www.iana.org/assignments/iana-ipv4-special-registry/) and [IPv6](https://www.iana.org/assignments/iana-ipv6-special-registry/) registries. The extra platform exclusions follow the documented [AWS metadata endpoint](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/retrieve-iid.html) and [Azure virtual platform address](https://learn.microsoft.com/en-us/azure/virtual-network/what-is-ip-address-168-63-129-16). This static classifier requires maintenance as allocations change; it cannot discover arbitrary public-address services that an operator routes internally.

Every DNS answer must pass the policy before a connection is opened. Each hop resolves once; the socket connects to the authorized numeric address, and checks its connected peer. There is no independent hostname resolution at connect time. The original hostname remains the HTTP Host and HTTPS certificate/SNI identity. Every redirect repeats normalization, URL permission, resolution, and destination checks, including port changes. Rejected endpoints receive no HTTP request; their response bodies cannot enter diagnostics. Fetched content remains untrusted.

Web tools use **direct connections only**. JVM/environment proxies and the default `ProxySelector` are intentionally ignored. There is no tool proxy configuration or fallback to a proxy that resolves destinations remotely. Deployments requiring a proxy must provide a reviewed connection-enforcing transport before enabling that route. Host routing, transparent proxies, custom NAT64 prefixes, and a same-user host attacker remain outside this application boundary; use the OS/network boundary to enforce such deployment constraints.

The direct transport uses HTTP/1.1, normal JVM TLS trust, a 15-second connect limit, a 25-second deadline per hop including DNS/TLS/body, at most five redirects, 1 MB response data, 64 KiB combined headers/chunk framing, and interruptible socket cancellation. It supports fixed-length, chunked, and connection-close bodies. Unsupported content encoding/framing fails closed; there is no retry using a weaker transport. It selects the first authorized DNS answer, without address fallback or HTTP/2. A stalled platform resolver may outlive caller cancellation, but its result cannot initiate a connection after cancellation.

| Network path | Governing policy |
| --- | --- |
| Native `webfetch`/`websearch`, including children | This destination policy plus effective tool/ancestor permissions |
| Model providers and provider discovery | Configured provider origins, provider proxy settings, SEC-04 transport policy; local Ollama remains supported |
| MCP HTTP and MCP OAuth discovery/registration/token exchange | Configured MCP origin, SEC-04 transport/OAuth rules and tool permissions; intentional local MCP remains supported |
| Provider OAuth and account discovery | Authentication transport rules; no web service grants |
| Shell commands | `bash` permission and separate `command_sandbox.network` capability; this is not an HTTP destination filter |
| Stdio MCP/custom installed extensions | Trusted installed executable/extension authority and configured command isolation where applicable; arbitrary extension networking is not filtered by native web policy |

`insecure_http_origins` permits reviewed provider/MCP plaintext transport and does not grant web tool access. Conversely, `web_private_origins` cannot authorize a model provider, MCP origin, OAuth HTTP exception, or command network access.

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

## Project trust and permission ceilings

Explicit user global `permission` entries in `~/.config/syleks/agent-config.json` are immutable constraints during an application instance. Missing global entries are neutral constraints, so built-in fallback denials do not block intentional user agent capabilities such as Explore's read/search permissions. Built-in fallback behavior still applies when no agent grants a capability. An explicit global `"*": "deny"` is a ceiling for all operations unless a later matching global rule provides an exception.

Ordered matching happens separately within each layer. Independent constraints combine as follows:

| Preference / ceiling | ALLOW | ASK | DENY |
| --- | --- | --- | --- |
| ALLOW | ALLOW | ASK | DENY |
| ASK | ASK | ASK | DENY |
| DENY | DENY | DENY | DENY |

To enable commands for Build while denying them for Plan, deliberately grant authority globally and restrict Plan:

```json
{
  "permission": { "bash": "ask" },
  "agent": {
    "build": { "permission": { "bash": "allow" } },
    "plan": { "permission": { "bash": "deny" } }
  }
}
```

Build still asks. Use global `bash: allow` only if you intend to permit commands without that ceiling approval. Existing global denials are preserved; there is no automatic migration to a wider grant. The full examples retain their global edit/write/command denials: change those ceiling entries explicitly before expecting agent-level ASK preferences to enable those actions.

Untrusted project agents can provide instructions and restrictions but cannot expand their inherited permissions or change the inherited model/provider. Trusted project agents may enable capabilities missing from defaults, while remaining capped by explicit global rules and inherited agent restrictions. Nested definitions cannot relax earlier agent constraints. `AGENTS.md` remains attributed repository guidance and never grants trust or execution authority.

From the exact launch workspace, review the complete inherited agent definitions and project MCP configuration:

```sh
syleks trust inspect
syleks trust grant <reviewed-sha256>
syleks trust inspect
syleks trust revoke
```

`inspect` prints the canonical workspace, trust state, SHA-256 digest and configuration contents without starting MCP or loading application services. Copy the digest only after reviewing all displayed definitions. Grant checks that digest against the current snapshot. Records live outside project authority in `~/.config/syleks/project-trust/`, keyed by canonical launch workspace. Symlink aliases share the same identity; different nested launch workspaces need their own review. The manifest covers file names and full contents of `.syleks/agent-config.json` and `.syleks/agents/*.md` at every inherited directory, including newly added files. Any content or file-set change invalidates the pin. Links escaping the project are rejected. Loaded definitions use the captured snapshot.

Trust authorizes configured executable integrations and potential model/provider changes, so review commands and destinations carefully. Only the project JSON `mcp` section is supported; arbitrary project JSON provider/global permission settings remain ignored. Legacy `mcp_trusted_projects` entries are accepted for compatibility but grant no authority. Review and pin existing projects explicitly; previous launches never create trust.

Configuration, trust and revocation take effect on `/restart` or a new process. Restart active chats after changing or revoking trust. `/permissions [AGENT]` shows decisions and their rule sources, ceiling constraints and trust snapshot. CLI, primary and child agents, custom and MCP tool calls share execution checks; ancestor constraints and DENY still apply to remembered approvals.

Native mutation tools, patch operations and `/save` cannot modify user files under `~/.config/syleks/`, including global policy and trust records, even when the home directory is the workspace. Instrumented custom tool mutations receive the same protection. Installed executable extensions, MCP servers and unrestricted shell commands retain their OS process authority; this boundary does not isolate arbitrary code or defend against a same-user host attacker. SEC-03 tracks OS command isolation.
