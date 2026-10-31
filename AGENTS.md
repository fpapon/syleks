# Repository Guidelines

## Project Structure & Module Organization

This is a single-module Java 25 Maven application: a terminal chat interface for Ollama using Yupiik Fusion and JLine.

- `src/main/java/fr/openobject/ai/syleks/`: production code, grouped into `app` (CLI and configuration), `ai` (streaming client and session), `model` (chat data), and `tui` (input, rendering, terminal loop).
- `src/test/java/fr/openobject/ai/syleks/`: JUnit tests mirroring production packages.
- `pom.xml`: dependencies, Fusion annotation processing, and executable JAR packaging.
- `.github/workflows/ci.yml`: Java 25 build and test workflow.
- `target/`: generated build output; excluded from version control. There is no separate asset directory.

## Build, Test, and Development Commands

Use JDK 25 and Maven 3.9 or newer.

- `mvn clean package`: compile, test, and build the shaded executable JAR.
- `mvn test`: run the unit tests.
- `mvn -Dtest=EditorTest test`: run one test class.
- `mvn spotless:apply`: format production and test Java sources.
- `mvn spotless:check`: check Java formatting without changing files.
- `mvn -B --no-transfer-progress clean verify`: reproduce CI validation.
- `java -jar target/syleks-0.1.0-SNAPSHOT.jar chat`: launch in an interactive terminal; requires a running Ollama server with the selected model installed.

## Coding Style & Naming Conventions

Use four-space Java indentation and two-space XML indentation. Spotless formats Java with Google Java Format's AOSP style; run `mvn spotless:apply` before submitting changes. Formatting is checked during `mvn verify`, including CI. Generated sources under `target/` are excluded. Follow surrounding code and avoid unrelated formatting changes. Use `UpperCamelCase` for classes, `lowerCamelCase` for methods and fields, and lowercase package names. Keep Fusion-managed services consistent with existing annotations and constructor injection.

Keep terminal output owned by `TerminalApp`; background generation should update session state through synchronized methods.

## Testing Guidelines

Tests use JUnit Jupiter 6 and Maven Surefire. Name classes `*Test` and use descriptive behavior-oriented method names. Add regression tests for changed editor, terminal-cell, or stream-parsing behavior. Unit tests should run without a live Ollama server. No coverage threshold is configured. Manually check interactive rendering, resizing, and keyboard changes in a real terminal.

## Commit & Pull Request Guidelines

Git history is unavailable in this checkout, so existing commit conventions cannot be verified. Use concise imperative subjects, such as `Fix Unicode cursor movement`. Describe the change, validation performed, and related issues in pull requests; include terminal screenshots for visible UI changes.

## Configuration & Security

Configure model selection through `model` and Ollama servers through `provider.ollama.options.baseURL`; use `AGENT_SYSTEM_PROMPT` for the system prompt. Never commit credentials or sensitive chat exports. Preserve explicit, non-overwriting `/save` behavior and terminal escape sanitization.
