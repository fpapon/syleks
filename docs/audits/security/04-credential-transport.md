# SEC-04: Secure credential transport

Status: implemented 25 October 2026 (global exact-origin HTTP exceptions; OAuth remains strict). Priority: high when using remote HTTP. Dependencies: none; coordinate with SEC-06 and SEC-08.

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

## Implementation record — 25 October 2026

Implemented in the working tree; no commit or PR created. `net.TransportSecurity` is the shared endpoint validator for provider configuration/setup, prepared chat connections, catalog discovery, Responses requests, MCP connections (including directly constructed configurations), MCP OAuth and provider OAuth HTTP requests. Unapproved remote HTTP fails before requests or credential resolution at the provider/MCP boundaries. Ports, userinfo, query and fragment are checked before use. Diagnostics omit URL secrets and configured credentials. All provider clients explicitly disable redirects; MCP/OAuth retain their existing no-redirect clients. Certificate/hostname verification is preserved.

The [transport decision matrix](../../providers.md#transport-security) defines literal IPv4, IPv6, mapped-address and hostname handling, and migration guidance. MCP OAuth keeps its narrower preexisting fixture address list and existing PKCE/state/issuer checks. The schema filters remote HTTP for provider/MCP endpoints; runtime validation additionally checks canonical address syntax and port range. Setup, bundled defaults, examples guidance, README and provider/MCP/authentication/configuration docs were updated together. New setup defaults to `http://127.0.0.1:8000/v1`; existing configurations are never rewritten.

Remote HTTP exceptions are now available in the global `insecure_http_origins` array. Each entry is an exact HTTP origin (hostname case/default port normalized), never a wildcard or path. Global policy is loaded before project MCP definitions and is applied again at provider request and MCP connection boundaries. Project files and inherited environment variables cannot add grants. Removing an origin and restarting/reloading configuration revokes it; an existing runtime retains its immutable policy until restart. CLI warnings go to stderr; setup, selected-provider chat snapshots, enabled MCP chat notices and connected MCP status make use visible. These view-only chat warnings do not enter model history or exports. OAuth never consults the exception list. The default list stays empty.

Schema note: the schema checks exact-origin declaration syntax and restricts remote HTTP by default. When a nonempty list is present, matching the declared origin to each endpoint and checking effective ports remains runtime enforcement, rather than an attempted dynamic JSON Schema comparison. No required transport enforcement is deferred.

Validation environment: Linux amd64, Zulu OpenJDK 25 (`25+36-LTS`), Maven 3.9.16. Maven used these exact environment assignments for every command below:

```sh
JAVA_HOME=/home/fpa/.sdkman/candidates/java/25-zulu
PATH=/home/fpa/.sdkman/candidates/java/25-zulu/bin:/home/fpa/.sdkman/candidates/maven/3.9.16/bin:$PATH
```

Assignments were passed inline to Maven (equivalent to exporting them in the shell). Commands and results:

```sh
mvn -B --no-transfer-progress -Dtest=TransportSecurityTest,TransportTlsTest,ProviderConfigTest,McpTransportTest,McpOAuthTest,OAuthTest,GatewayProviderTest,ResponsesProviderTest,ConfigInitWizardTest,ConfigInitializerTest test
# PASS: 68 tests, 0 failures/errors/skips.
mvn -B --no-transfer-progress spotless:apply -Dtest=ConfigSchemaTest test
# PASS: 5 schema tests after adding shared schema transport restrictions.
mvn -B --no-transfer-progress clean verify
# PASS: 526 tests, 0 failures/errors/skips; shaded JAR and formatting check passed.
mvn -B --no-transfer-progress spotless:apply
mvn -B --no-transfer-progress -Dtest=TransportTlsTest test
# PASS: 1 test after clearing the fixture keytool child's inherited environment.
git diff --check
# PASS.
```

Local socket fixtures initially failed under the execution sandbox; final fixture runs and full verification ran with approved local socket access. No skipped fixture/backend check is counted as a pass.

Negative evidence: injected prepared-chat transport receives no request for rejected HTTP connections. Catalog/Responses paths reject remote HTTP before looking up even a nonexistent account. MCP rejects remote HTTP before bearer environment resolution or custom-header attachment. Hostname suffixes, mapped/scoped IPv6, malformed ports and URL secrets are rejected. A local TLS fixture receives the synthetic bearer token/custom key at the approved source, returns cross-origin HTTPS and downgrade redirects, and observes **zero requests** at either target. The same fixture confirms hostname mismatch causes an SSL handshake failure. Production TLS settings are not changed; only the test client trusts its temporary localhost certificate.

Packaged CLI smoke: `provider list` with an isolated temporary home and synthetic API key rejects remote HTTP and omits the key from diagnostics. Manual PTY smoke: `config init` with isolated `user.home` and HOME removed displays the loopback default, rejects remote HTTP at the URL field, accepts loopback, masks a synthetic API key, and exits on Escape without creating the configuration. Manual terminal resizing was not exercised; no layout/rendering code changed, and existing terminal tests passed in full verification.

Limitations: exact `localhost` still uses the system resolver, and an explicitly configured proxy can observe loopback HTTP development traffic. This is not DNS/private-network enforcement or OS isolation; those remain SEC-06/SEC-03. Secret storage/redaction beyond transport diagnostics remains SEC-08. No live provider, production credential, paid endpoint or internet service was used.

### Follow-up: exact-origin exception setting

Configuration and migration example: merge `"insecure_http_origins": ["http://192.168.1.20:8000"]` into global `~/.config/syleks/agent-config.json`, then restart. Paths on that origin are covered; changed hosts/ports are not. See [the full example](../../../examples/agent-config-insecure-http.json) and [user guide](../../providers.md#transport-security) for scope, plaintext exposure and revocation behavior. Prior strict-only behavior described in the initial smoke record above applies when the list is empty.

The focused follow-up command, using the same JDK/Maven environment above, passed 43 tests with zero failures/errors/skips:

```sh
mvn -B --no-transfer-progress -Dtest=TransportSecurityTest,TransportTlsTest,InsecureTransportConfigTest,ConfigSchemaTest,ConfigInitWizardTest,ProviderConfigTest,McpTransportTest,McpOAuthTest test
```

`InsecureTransportConfigTest` delivers a synthetic bearer key to a reviewed remote origin through a local proxy and verifies chat and Ollama catalog/chat routing. Changing the grant's port or removing it rejects configuration reload without any extra proxy requests. A trusted project and an inherited exception-named variable cannot grant authority; the same trusted project can use an already globally authorized MCP origin. Policy tests reject wildcard/secret-bearing/malformed grants, normalize default ports/case and deny other origins. MCP tests cover direct connection construction with bearer/custom-header grants, revocation and continued OAuth rejection. Existing TLS redirect/hostname checks remain in the focused suite. Setup tests preserve global grants and show the warning, while URL answers alone cannot create a grant.

Final verification on 26 October 2026 used the same JDK/Maven environment and approved local socket access:

```sh
mvn -B --no-transfer-progress spotless:apply clean verify
# PASS: 533 tests, 0 failures/errors/skips; formatting, license checks and shaded JAR passed.
git diff --check
# PASS.
```

The final regression check also confirms that visible transport warnings stay out of the conversation history used for model requests and exports. The initial sandboxed full run failed because local fixture sockets were prohibited; only the subsequent complete run with socket access is counted as passing.
