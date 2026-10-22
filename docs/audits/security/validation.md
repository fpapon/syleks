# Shared security validation and handoff

Apply these requirements to each task. This file defines future evidence; it does not record executed implementation results.

## Test environment

Use JDK 25 and Maven 3.9+. Use temporary homes, canonical workspaces, private configuration/data directories, and synthetic credentials. Unit tests must not require a live Ollama server, paid provider, production keyring, public metadata service, or internet connection. Isolate inherited host environment values in subprocess tests.

Local HTTP/TLS fixtures may need permission to open sockets under the execution environment's sandbox. Report that constraint honestly and use the normal approval mechanism when necessary. Do not bypass execution-environment restrictions. OS sandbox tests require real kernel/backend checks in a supported disposable environment; mocked backend tests alone cannot establish isolation.

## Required evidence by boundary

| Boundary | Positive case | Negative case |
| --- | --- | --- |
| Policy/trust | Explicit user grant permits intended action | Project/child/approval cannot widen global constraints |
| Effects | Allowed action creates its temporary marker | Denied compound action creates no prefix marker |
| Environment | Explicit non-secret development setting works | Unknown secrets/startup hooks absent or ineffective |
| OS isolation | Intended workspace operation succeeds | Outside file/network/socket/process access blocked |
| Transport | Authorized TLS or narrow development fixture works | Rejected destination receives no token/request |
| Approvals | Exact reviewed action can be approved | Changed target/payload/context cannot reuse approval |
| Filesystem | Valid create/edit/journal operation works | Swapped paths cannot affect an outside marker |
| Secrets | Reference resolves only for intended destination | Diagnostics, metadata and unrelated children omit sentinel |
| Display | Full action and scope can be reviewed | Invisible controls cannot conceal logical target |
| History | Ordinary recall preserves draft/cursor | Excluded/cleared entries cannot return after restart |
| CI | Checks and inventory run successfully | Findings/scanner failure produce the specified failure state |

Use deterministic adversarial synchronization where possible, rather than a timing-dependent race that occasionally passes. Assertions should inspect effects, destinations, and structured decisions, not only error strings. Include alternate entry points: CLI, TUI, primary tools, delegated tools, MCP/custom extensions, discovery, restart, and cancellation when relevant.

## Commands

Run relevant test classes first. Then format changed Java sources and run the repository verification command:

```sh
mvn -Dtest=RelevantTest,AnotherRelevantTest test
mvn spotless:apply
mvn -B --no-transfer-progress clean verify
git diff --check
```

Replace the example test names with real classes. Inspect formatting diffs and avoid unrelated changes. Documentation-only edits need link/content checks rather than a full Java build. UI changes also require a real terminal or PTY check, including resize, modal focus, keyboard interaction, and sensitive-value masking. Preserve screenshots/recordings only when they contain synthetic data.

## Completion record

Append a concise implementation record to the completed task containing:

- Status and date; commit or PR reference when available.
- Final behavior and any departures from the proposed design, with rationale.
- Configuration/schema migration examples and user-visible effects.
- Exact commands, environment/backend versions, test totals, and outcomes.
- Negative-test evidence showing forbidden effects did not occur.
- Remaining limitations, skipped checks with reasons, and follow-up task references.

Distinguish confirmed runtime behavior, source-based inference, test-only behavior, and unsupported guarantees. Never convert a skipped or unavailable-platform test into a security claim. Do not mark a task complete while its required enforcement is missing or silently falls back to weaker behavior.
