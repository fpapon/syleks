# SEC-06: Control network destinations

Status: not started. Priority: medium, conditional on network permissions/deployment. Dependencies: SEC-01; coordinate with SEC-03 and SEC-04.

## Finding and relevant code

`AgentTools` validates web URLs and checks each redirect, but permits private/local destinations when URL permissions allow them. A broad web grant exposes internal services to model-directed requests. Private services can also be legitimate user-selected endpoints; support explicit grants rather than categorically removing local access.

Read [AgentTools.java](../../../src/main/java/fr/openobject/ai/syleks/ai/AgentTools.java), [ToolExecutor.java](../../../src/main/java/fr/openobject/ai/syleks/ai/ToolExecutor.java), [McpConnection.java](../../../src/main/java/fr/openobject/ai/syleks/mcp/McpConnection.java), and [AppConfig.java](../../../src/main/java/fr/openobject/ai/syleks/app/AppConfig.java). Existing tests: `AgentToolsTest`, `McpTransportTest`, `ProviderConfigTest`.

## Implementation contract

- Separate public web access from explicit access to private, loopback, link-local, unspecified, multicast, and other non-public address classes. Provide user-controlled destination grants for internal services and cloud metadata protection.
- Classify IPv4, IPv6, mapped addresses, literal IPs, normalized hostnames, ports, and all DNS answers. Repeat authorization for every redirect. User-visible errors must not leak response bodies from rejected endpoints.
- A DNS lookup followed by an independently resolving `HttpClient` call is not sufficient against rebinding. Enforce policy against the actual connection destination through an appropriate transport or network proxy. If enforcement cannot be guaranteed, fail closed for the protected operation and document the limitation.
- Preserve hostname verification/SNI for HTTPS. Define proxy behavior explicitly: a proxy may perform resolution, so resolve/check/connect assumptions must remain valid with proxy settings.
- Keep tool network grants separate from configured model-provider origins, MCP origins, OAuth discovery, and command network capabilities. Enumerate those paths and state which policy each uses; do not silently break intentional local Ollama/MCP configurations.
- Preserve response limits, deadlines, redirect limits, cancellation, and per-destination permission checks. Fetched content remains untrusted regardless of network authorization.

## Acceptance and evidence

Use deterministic injected DNS/transport fixtures for public-to-private redirects, mixed DNS answers, rebinding between resolution and connect, localhost aliases, IPv6/mapped forms, link-local metadata destinations, port changes, proxy resolution, and private-service explicit grants. Include a real local integration check and verify denied requests never reach its listener. Avoid real metadata endpoints.

Provide the destination policy matrix, connection-level enforcement design, proxy limitations, scope inventory, migration examples, and regression results. Follow [validation.md](validation.md).
