# SEC-04: Secure credential transport

Status: not started. Priority: high when using remote HTTP. Dependencies: none; coordinate with SEC-06 and SEC-08.

## Finding and relevant code

Provider endpoint validation permits HTTP(S), and compatible-provider requests attach configured API keys without requiring HTTPS. Remote HTTP therefore exposes credentials and prompts to observers on the transport path. MCP bearer/header configuration also needs consistent handling. OAuth transport validation already provides a stronger starting point.

Read [AppConfig.java](../../../src/main/java/fr/openobject/ai/syleks/app/AppConfig.java), [OllamaClient.java](../../../src/main/java/fr/openobject/ai/syleks/ai/OllamaClient.java), [ApiKeyChatProvider.java](../../../src/main/java/fr/openobject/ai/syleks/provider/ApiKeyChatProvider.java), [ResponsesLlmProvider.java](../../../src/main/java/fr/openobject/ai/syleks/provider/ResponsesLlmProvider.java), [McpServerConfig.java](../../../src/main/java/fr/openobject/ai/syleks/mcp/McpServerConfig.java), [McpConnection.java](../../../src/main/java/fr/openobject/ai/syleks/mcp/McpConnection.java), and [McpOAuth.java](../../../src/main/java/fr/openobject/ai/syleks/mcp/McpOAuth.java).

## Implementation contract

- Require HTTPS for non-loopback provider and MCP endpoints by default, with or without credentials. Preserve a narrowly defined loopback HTTP development path.
- Any insecure remote exception must identify the reviewed destination, be user controlled, and be visible when selected. Project files, model tool arguments, environment inheritance, and remembered approvals cannot silently enable it.
- Centralize URL classification and transport requirements across chat, discovery, authentication, reconnect, configuration setup, and CLI paths. Reject credentials in URLs. Validate ports and normalized origins consistently.
- Do not disable certificate or hostname verification. Redirects must never forward credentials to an unapproved origin or downgrade HTTPS to HTTP. Preserve OAuth's stricter rules rather than loosening them to a shared lowest common denominator.
- Define loopback treatment for IPv4, IPv6, mapped addresses, and hostnames; do not infer trust from a hostname suffix. Coordinate actual destination enforcement with SEC-06.
- Error messages must identify the blocked destination/policy without exposing authorization headers, query secrets, tokens, or configured key values.

## Acceptance and evidence

Cover remote HTTP rejection before any credential-bearing request; local fixture support; IPv6 loopback; misleading localhost names; malformed ports/userinfo; explicit exceptions and revocation; redirects across origins and downgrade attempts; discovery/chat consistency; bearer-token MCP and custom headers. Use injected HTTP transports or local TLS fixtures to observe headers safely.

Update schema, setup validation, examples, provider/MCP documentation, and migration guidance together. Provide a transport decision matrix and tests proving no credential delivery on rejected paths. Follow [validation.md](validation.md).
