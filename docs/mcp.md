# MCP servers

[User guide](README.md) · [Tools and approvals](tools-and-permissions.md)

Syleks connects to Model Context Protocol servers over **stdio** and **Streamable HTTP**. Discovered tools use the same agent permissions, approval prompts, cancellation, tool cards and persistent history as built-in tools. No Ollama or cloud account is needed to check an MCP connection.

## Configure servers

Add `mcp` to `~/.config/syleks/agent-config.json`, then restart Syleks:

```json
{
  "mcp": {
    "local": {
      "type": "stdio",
      "command": ["/absolute/path/to/mcp-server", "--workspace", "/path/to/project"],
      "environment": { "SERVER_MODE": "readonly" },
      "timeout_seconds": 30
    },
    "docs": {
      "type": "http",
      "url": "https://mcp.example.com/mcp",
      "bearer_token_env": "DOCS_MCP_TOKEN",
      "resources": true,
      "prompts": true
    }
  },
  "permission": {
    "mcp_*": "ask",
    "mcp_docs__t_search": "allow"
  }
}
```

The command is an argument array executed directly in the current workspace. Syleks does not invoke a shell, install packages or download a server. Only configure programs you trust: a stdio server runs with your OS permissions, independently of which of its tools you approve. Its stderr is discarded and stdout is reserved for JSON-RPC. The subprocess inherits only `PATH`, `HOME`, `USER`, `LANG`, `LC_ALL`, `TMPDIR` and `SystemRoot` when present, plus explicitly configured `environment` values. Pass server secrets explicitly in `environment` if required; provider credentials are not automatically forwarded. Values are literal strings, without shell or environment substitution.

HTTP authentication supports literal `headers`, an environment variable named by `bearer_token_env`, or OAuth. These authentication methods are independent of LLM provider accounts. Do not place secrets in the URL. Redirects are rejected. Use HTTPS for remote servers; HTTP is useful for local fixtures. Authorization headers are never copied to discovery endpoints or another server. Syleks does not log response error bodies or transport headers. Server tool results themselves are ordinary model-visible content and can be persisted/exported.

Server IDs use 1–16 lowercase ASCII letters, digits or single interior underscores, starting with a letter. Set `enabled: false` to prevent any connection or process launch. The default is enabled. `timeout_seconds` is an integer from 1 to 300, default 30. Changes take effect on `/restart` or a new CLI invocation.

## Project configuration and trust

A trusted workspace can override server definitions by ID in `.syleks/agent-config.json`:

```json
{ "mcp": { "local": { "type": "stdio", "command": ["/path/to/project-server"] } } }
```

First authorize that exact absolute workspace path in the **global** configuration:

```json
{ "mcp_trusted_projects": ["/home/me/projects/example"] }
```

Without that entry, the project file is ignored. Trust authorizes process launch and network connections from this file, including future edits, so review changes before restarting. Only its `mcp` section is read; global permissions remain the authority. Overrides replace the entire server definition. Config files resolving outside the workspace are rejected. The global and merged configurations permit at most 16 servers.

## Inspect connections and authorize tools

```sh
syleks mcp list
syleks mcp tools
```

Both commands connect and discover tools, then close connections before exiting. `list` reports each server's connection, disabled or failure status. `tools` prints MCP names available to the configured default agent. Missing credentials and invalid catalogues produce a failed server status while other servers remain usable. In chat, discovery happens when constructing the first model request. No tool call is automatically replayed after a failure. A later turn can reconnect a failed call's transport; restart to retry a failed initial discovery.

Remote tools are named `mcp_SERVER__t_NAME`. Long, non-ASCII or otherwise incompatible remote names use a stable `h_` digest suffix. Duplicate remote names, digest collisions and built-in/extension collisions fail closed. Names are suitable for the existing provider tool formats. The model sees only permitted names; execution checks permissions again even for a fabricated or stale call.

Permissions accept exact names or MCP patterns such as `mcp_docs__t_*` and `mcp_*`. Later rules override earlier rules. Permission targets are `SERVER/REMOTE_TOOL`, for example `docs/search`. ASK uses the existing approval panel. Explicit DENY cannot be relaxed by a remembered approval or a subagent. Tool schemas validate arguments before any remote call. MCP calls are serialized with other exclusive tools because server annotations are not proof that an operation is read-only.

## OAuth

For an OAuth-protected HTTP server, configure:

```json
{
  "mcp": {
    "docs": {
      "type": "http",
      "url": "https://mcp.example.com/mcp",
      "oauth": true,
      "scope": "read"
    }
  }
}
```

Run `syleks mcp auth docs`, open the printed authorization URL in your browser, and complete sign-in within three minutes. The callback listens on a randomly allocated loopback port and validates state. The client uses PKCE S256 and sends the MCP resource URI in authorization and token requests. Discovery uses protected-resource metadata advertised by `WWW-Authenticate`, with the well-known resource metadata URI as fallback, followed by authorization-server metadata. The first advertised authorization server is selected. HTTPS is required except for loopback development servers.

If dynamic client registration is unavailable, supply `client_id` from a registered **public** OAuth client that accepts loopback callbacks. Confidential client secrets, alternative grants and provider-login reuse are not supported. `scope` and `client_id` require `oauth: true`; OAuth cannot be combined with a bearer token or an Authorization header.

Access/refresh tokens live in owner-only files under `~/.config/syleks/mcp-auth/`, separately from sessions and LLM accounts. They are bound to the resource URL, client ID and scope. Refresh uses process/file locks and saves rotated tokens atomically. A refresh interrupted after it may have reached the server requires sign-in again. Platform user ACLs protect files on systems without POSIX permissions. `syleks mcp logout docs` removes the local credentials; it does not revoke tokens at the authorization server. Restart an existing chat after signing in or out in another process.

## Resources, prompts and limits

Resources and prompts are opt-in per server and require advertised server capabilities. They appear as separate permission-controlled tools:

| Suffix after `mcp_SERVER__` | MCP operation |
| --- | --- |
| `r_list` | `resources/list` (optional cursor) |
| `r_templates` | `resources/templates/list` (optional cursor) |
| `r_read` | `resources/read` (URI) |
| `p_list` | `prompts/list` (optional cursor) |
| `p_get` | `prompts/get` (name and optional string arguments) |

Their permission targets are `SERVER/METHOD`, for example `docs/resources/read`. Results are untrusted tool content; they are not automatically loaded as system instructions. Binary image/audio data and resource blobs are omitted from model context, retaining metadata. Text and structured results share the existing 32,000-character output limit.

Messages and HTTP response streams are bounded to 1 MiB. Each server may discover at most 100 tools across at most 16 pages. The shared registry permits 128 total tools, descriptions up to 4,000 characters, schemas up to 32,000 characters, and a 131,072-character schema/description budget. An invalid or excessive server catalogue is removed atomically; valid servers and native tools remain usable. Input schemas support local JSON references only. Tool-schema size also counts against the model's context budget.

The client negotiates protocol version `2025-06-18` with compatibility for `2025-03-26` and `2024-11-05` stdio peers. Streamable HTTP supports both JSON and SSE POST responses, session/version headers, cancellation notifications and session DELETE. Stdio list-change notifications are read while idle; HTTP list-change notifications are handled in POST response streams. A change refreshes the catalogue on the next request. Legacy HTTP+SSE endpoints, standalone GET notification streams, stream resumption, resource subscriptions, roots, sampling and elicitation are not implemented. HTTP 404/transport failures invalidate the session; the next discovery establishes a new session without replaying an effect.

The transport follows the MCP [lifecycle](https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle), [transport](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports) and [authorization](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization) specifications. Protocol support is tested against deterministic local fixtures, rather than certified against every external server.
