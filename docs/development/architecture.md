# Architecture and source builds

Syleks is a single-module Java 25 Maven application. The documentation site is a separate static build and has no role in CLI startup.

## Application structure

| Package | Responsibility |
| --- | --- |
| `app` | CLI routing, configuration, agent definitions and policy |
| `tui` | JLine input, ANSI rendering and terminal loop |
| `provider` and `ai` | Provider adapters, streaming, generation and tool execution |
| `session` | Project conversations, context management and agent orchestration |
| `session/storage` | SQLite persistence and migrations |
| `mcp` | MCP connections, tools, resources and prompts |
| `auth` | Provider authentication and credential storage |

Browse the [source tree](https://github.com/fpapon/syleks/tree/main/src/main/java/fr/openobject/ai/syleks). Yupiik Fusion supplies annotation-generated dependency injection and CLI wiring. Terminal output belongs to `TerminalApp`; background generation updates synchronized session state.

## Build and test

Use Java 25 and Maven 3.9 or newer:

```sh
mvn clean package
mvn test
mvn -Dtest=EditorTest test
mvn spotless:apply
mvn -B --no-transfer-progress clean verify
```

Unit tests use JUnit Jupiter and do not need a live Ollama instance. Some OS sandbox integration tests require supported Linux isolation infrastructure; see [command isolation](../command-sandbox.md). Manually verify terminal rendering, resizing and keyboard changes in a real terminal.

Run the application from the checkout with `bin/syleks`, or use the JAR described in [installation](../installation.md).

## Development conventions

Use four-space Java indentation and two-space XML indentation. Spotless checks Java formatting during Maven verification. Keep changes focused and add behavior-oriented regression tests for editor, terminal-cell and stream-parsing changes. Preserve explicit non-overwriting exports and terminal escape sanitization.

The existing [Java CI](https://github.com/fpapon/syleks/blob/main/.github/workflows/ci.yml) remains independent of the [documentation workflow](github-pages.md). For website changes, follow [contributing documentation](../contributing-docs.md).

## Internal records

Historical [parity audits](https://github.com/fpapon/syleks/tree/main/docs/audits/opencode-parity), [security implementation records](https://github.com/fpapon/syleks/tree/main/docs/audits/security), and [proposed Engineering contracts](https://github.com/fpapon/syleks/tree/main/docs/engineering) are preserved in GitHub. They are not generated as public website pages because they contain historical findings, implementation handoffs or unaccepted proposals. Current user guides describe supported behavior and identify experimental boundaries.
