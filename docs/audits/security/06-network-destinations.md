# SEC-06: Control network destinations

Status: implemented 26 October 2026. Priority: medium, conditional on network permissions/deployment. Dependencies: SEC-01; coordinate with SEC-03 and SEC-04.

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

## Implementation record — 26 October 2026

Implementation is in the working tree; no commit/PR was created. The [destination matrix, connection design, proxy limitations, scope inventory and migration examples](../../tools-and-permissions.md#web-destination-policy) document the final contract. Configuration/schema, bundled defaults, the full example, CLI help, `/permissions`, and web tool descriptions have been updated together.

`WebDestinationPolicy` reads `web_private_origins` from global configuration only. The default `[]` separates public web grants from access to private/loopback services. Exact scheme/normalized host/effective-port grants can authorize RFC1918, unique-local and loopback destinations. Link-local, unspecified, multicast, shared/reserved ranges, conservative IPv6 special-purpose/transition ranges and known metadata/platform virtual addresses remain denied even with a service grant. This deliberately stricter grant boundary prevents internal-service exceptions from enabling metadata access. Project JSON, project Markdown agents, environment values and approval callbacks cannot expand the global policy. Network policy refusals return structured DENIED results. Changing the global file invalidates existing executors through the existing approval-policy revision check; restart loads the new policy.

`PinnedWebTransport` is a bounded direct HTTP/1.1 GET transport. It checks all DNS answers before opening a socket, connects using an authorized numeric `InetAddress`, and checks the connected peer. The chosen DNS answer is not resolved again at connect time. TLS wraps that socket using the original hostname, standard JVM trust, HTTPS endpoint identification and DNS SNI. Each redirect passes through the URL gate and destination policy again, including changed ports. Requests never forward cookies or credentials; rejected endpoint bodies are not read or included in policy errors. There is no weaker transport fallback.

Proxy resolution cannot be authorized by a local DNS check. Native web tools therefore intentionally ignore the default ProxySelector, JVM SOCKS/HTTP settings and environment proxies, and offer no remote-resolving proxy route. This is a documented departure from the previous implicit JVM proxy behavior. Provider/MCP configured endpoints and their proxies, OAuth transport, command network capabilities and installed extension authority remain separate. Intentional local Ollama/MCP settings continue to work under their existing SEC-04 policy.

Confirmed regression evidence:

- Deterministic injected answers cover public/private mixtures in either order, empty answers, localhost aliases, IPv4/IPv6/mapped forms, metadata/link-local and reserved addresses, exact origin ports and service grants. Metadata tests use only synthetic address classification; no real metadata endpoint is contacted.
- A local HTTP listener receives zero requests for denied/mixed answers. An explicit service grant delivers the intended response, preserving the DNS hostname in Host. A resolver that changes its answer on a second lookup is called exactly once; connection delivery is to the first authorized address. This tests pinning without timing-dependent DNS races.
- A simulated public redirect into the real protected local listener is rejected despite an approving URL callback; the listener receives zero requests. Real local redirects to a different ungranted port are refused; with both origins granted, URL denial still prevents the target request and explicit authorization permits it.
- A ProxySelector that fails if used is bypassed for an authorized real local request. A separate denied request does not consult the selector.
- A local trusted TLS fixture confirms hostname verification and the original DNS SNI. A wrong DNS hostname resolving to the same listener fails its handshake before an HTTP request.
- The shared ToolExecutor rejects both primary and child actors when only trusted project configuration/environment suggests a private grant. A global grant permits both; a global edit invalidates the old executor and a reload revokes access without additional listener requests. This is executor-level child coverage; no live provider-driven delegation was used.
- Socket cancellation closes an in-flight local connection. Raw local response fixtures verify fixed-length, chunked and close-delimited success, bounds on data and framing, and rejection of oversized, ambiguous, encoded or truncated responses. Existing HTML conversion, search query handling, URL permissions, redirect handling, provider and MCP regressions also pass. The SEC-05 remembered-approval fixture now explicitly grants its two loopback service origins, preserving its original URL/path approval assertions under the new default; the first full run identified that necessary fixture migration.

Validation environment: Linux amd64 kernel `6.12.113+deb13-amd64`; Zulu OpenJDK `25+36-LTS` (`25.28+85-CA`); Maven `3.9.16`. Commands used Java from `/home/fpa/.sdkman/candidates/java/25-zulu` and Maven from `/home/fpa/.sdkman/candidates/maven/3.9.16/bin`. Local relative documentation link targets were also checked successfully. The final test runs used PATH consisting of those bin directories plus `/usr/bin:/bin`. Local socket tests initially failed with `Socket: Operation not permitted` inside the execution sandbox; the following successful runs used normal approval to run outside it.

```sh
mvn spotless:apply
mvn -B --no-transfer-progress -Dtest=WebDestinationPolicyTest,PinnedWebTransportTest,WebDestinationConfigTest,TransportTlsTest,AgentToolsTest,ProviderConfigTest,McpTransportTest,ConfigSchemaTest,ScopedApprovalTest test
# PASS: 61 tests, 0 failures, 0 errors, 0 skipped.
mvn -B --no-transfer-progress clean verify
# PASS: 582 tests, 0 failures, 0 errors, 11 opt-in OS sandbox checks skipped.
# Shaded JAR packaged; Spotless verification passed.
git diff --check
# PASS: no whitespace errors.
/home/fpa/.sdkman/candidates/java/25-zulu/bin/java -jar target/syleks-0.1.0-SNAPSHOT.jar --help
# PASS in a PTY: new web grant guidance displayed; exit 0.
```

Limitations: public HTTP is still plaintext; internal grants expose the reviewed service to model-selected paths subject to URL permissions. Web networking supports direct HTTP/1.1 only, selects the first DNS answer, rejects unsupported framing/content encodings, and uses a static conservative address classifier. DNS cancellation can leave a platform resolver running until it returns, but cannot initiate a socket afterward. Transparent proxies, arbitrary host routing/custom NAT64 and same-user OS compromise require a deployment network/OS boundary. This task does not filter arbitrary installed extension code, shell networking, provider/MCP/OAuth connections, or change SEC-03's platform guarantees. No public internet integration, production credential, real metadata request, live model session, native-image build or interactive rendering/resizing test was used; the change adds policy/help text without changing terminal layout. The 11 opt-in `LinuxCommandBackendTest` checks were skipped because `SYLEKS_SANDBOX_TESTS=required` was not selected; this is not OS backend validation. Hosted CI remains unexecuted here.
