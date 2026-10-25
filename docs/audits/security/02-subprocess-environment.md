# SEC-02: Isolate subprocess environments

Status: implemented 25 October 2026. Priority: high. Dependencies: none; coordinate with SEC-03 and SEC-08.

## Finding and relevant code

A harmless runtime probe confirmed that ordinary environment variables are inherited by Bash. `LocalCommandRunner` removes Bash startup variables but retains provider credentials and other unrelated variables if present. MCP stdio already uses an allowlist and explicit additions.

Read [LocalCommandRunner.java](../../../src/main/java/fr/openobject/ai/syleks/ai/LocalCommandRunner.java), [McpConnection.java](../../../src/main/java/fr/openobject/ai/syleks/mcp/McpConnection.java), [AppConfig.java](../../../src/main/java/fr/openobject/ai/syleks/app/AppConfig.java), and [ShellCommandPolicy.java](../../../src/main/java/fr/openobject/ai/syleks/app/ShellCommandPolicy.java). Existing tests: `LocalCommandRunnerTest`, `CommandToolTest`, `ShellCommandPolicyTest`, and `McpTransportTest`.

## Implementation contract

- Clear the inherited command environment and populate a documented minimal allowlist. Provide explicit user-controlled per-purpose additions for development tools that need them. Repository content cannot enable secret passthrough.
- Evaluate PATH, HOME, temporary directories, locale, Java/build-tool settings, SSH/GPG agent sockets, cloud credentials, proxy variables, loader variables, and interpreter startup hooks. Document each default retained variable and why it is necessary.
- A denylist of known token names is insufficient. Exclude unknown variables by default and prevent explicit additions from restoring forbidden startup/injection variables in restricted mode.
- Preserve the existing Bash startup/function defenses. Prevent PATH from implicitly selecting executables in the workspace; support trusted executable resolution and document executable replacement limitations.
- Do not log environment values in command approval details, failures, or test reports. Display approved variable names when useful.
- Keep MCP and command policies consistent without automatically passing MCP-specific credentials to ordinary commands.
- This change removes accidental environment exposure; it does not stop an unsandboxed command reading credential files through HOME or the filesystem. Document that SEC-03 supplies that separate boundary.

## Acceptance and evidence

Launch a child JVM with fake unknown secrets, provider-token variables, Bash exported functions, `BASH_ENV`, loader/interpreter hooks, and agent socket variables. Confirm they are absent or ineffective in executed commands and cannot create forbidden marker files. Verify configured non-secret additions, locale, basic executable discovery, build compatibility, output truncation, timeout, and cancellation. Tests must not print inherited host values.

Document compatibility changes and explicit opt-ins. Supply an environment policy matrix, targeted test results, and a packaged command smoke test in an isolated home. Follow [validation.md](validation.md).

## Implementation record — 25 October 2026

Implemented in this working tree; no commit/PR was created. The scope is the `bash` tool
(including delegated actors through the shared executor) and MCP stdio. Authentication/browser
helper processes are outside this task's command-runner scope.

### Behavior and configuration

`SubprocessEnvironment` now builds an immutable command environment from a shared minimal
baseline. `LocalCommandRunner` clears `ProcessBuilder.environment()` before adding it, retaining
`--noprofile --norc` and excluding all inherited startup/function/loader hooks. Global-only
`command_environment.pass_env` selects a finite set of supported development, proxy and SSH
socket variables. Unknown names and settings are rejected without including values. There is
no unrestricted inheritance mode. A finite supported list, rather than arbitrary additions plus
a hook denylist, is the deliberate design choice that prevents new/unknown injection variables
from being restored. Absent selected variables remain absent.

`trusted_path` replaces PATH, defaults to `/usr/bin:/bin`, and requires absolute existing
directories outside the canonical workspace. Checks run before every launch and follow directory
symlinks; invalid settings fail closed. No inherited empty/relative/workspace PATH entries remain.
This controls implicit discovery; an approved explicit `./script` or whole-shell assignment still
has its ordinary authority. Command approval details contain environment names, never values.

MCP shares the baseline but retains its separate, explicitly trusted server environment map.
Server secrets/settings never flow into command additions. Bare MCP executables are resolved
against the resulting child PATH before `ProcessBuilder` launch, avoiding inherited host-PATH
lookup. Explicit server settings retain their existing ability to configure arbitrary programs.

See the [complete environment policy matrix, configuration example and compatibility notes](../../configuration.md#subprocess-environments).
Schema, bundled defaults, example installation guidance, CLI reference, MCP guide and shell
documentation were updated. Existing restrictive permissions are unchanged; no settings were
silently migrated. SDKMAN/Homebrew/custom installations now need explicit trusted executable
directories; Java/build variables, proxies and agent sockets require supported opt-ins. Project
JSON, even with a current trust pin, cannot supply these command opt-ins.

### Executed validation

Environment: Linux `6.12.113+deb13-amd64`, Zulu OpenJDK `25+36-LTS` (Zulu25.28+85-CA),
Maven `3.9.16`. Maven commands used:

```sh
export JAVA_HOME=/home/fpa/.sdkman/candidates/java/25-zulu
export PATH="$JAVA_HOME/bin:/home/fpa/.sdkman/candidates/maven/3.9.16/bin:/usr/bin:/bin"
mvn -Dtest=SubprocessEnvironmentTest,LocalCommandRunnerTest,CommandToolTest,ShellCommandPolicyTest,McpTransportTest,AppConfigTest,McpIntegrationTest,McpConfigurationTest test
mvn spotless:apply
mvn -B --no-transfer-progress clean verify
git diff --check
```

- Final targeted run: **133 tests, 0 failures, 0 errors, 0 skipped**. Formatting was applied
  before the final targeted run. Full clean verification: **517 tests, 0 failures, 0 errors,
  0 skipped**, shaded JAR built, Spotless check passed. `git diff --check` passed.
- An initial targeted attempt inside the execution sandbox failed three existing MCP HTTP
  fixtures with `Socket: Operation not permitted`. Targeted and full runs were then authorized
  outside that sandbox to allow loopback fixture sockets; these completed successfully. An
  earlier compile attempt caught a missing qualified `Path` reference, corrected before reruns.
- The child-JVM regression clears its own inherited environment and injects only synthetic
  values: unknown/provider/cloud secrets, Bash exported functions/options/startup scripts,
  loader/interpreter hooks, Java startup options, and SSH/GPG sockets. Command environment
  assertions find none of them; explicitly selected `JAVA_HOME` and locale survive. Startup,
  function and workspace-PATH marker files remain absent. Unsupported startup-hook opt-ins
  are rejected; exception assertions do not expose values.
- Real command tests verify `java --version`, `javac Hello.java`, and `java Hello` through
  explicitly selected JDK executable directories. Existing output truncation, closed stdin,
  timeout and process-tree cancellation tests pass. Trusted-path tests reject relative/empty,
  missing and workspace/symlink directories. A currently trusted project cannot enable
  `JAVA_HOME`; an explicit global setting can.
- A real MCP stdio fixture receives its explicit fake server credential and baseline PATH,
  while host secrets/hooks/socket/build variables are absent. A subsequent ordinary command
  cannot see that server credential. Existing MCP discovery, calls and cancellation tests pass.
- Packaged command smoke: a standalone Java source harness used only the shaded
  `target/syleks-0.1.0-SNAPSHOT.jar` on its classpath in isolated temporary HOME/workspace
  `/tmp/sec02-smoke-6ij6mo2j`. It constructed the production policy and runner with explicit JDK
  and Maven directories, then successfully ran `env`, `java --version`, `mvn --version` and
  `printf packaged-smoke`. Synthetic unknown/provider/socket variables were absent; neither
  the fake Bash startup script nor the workspace `env` executable created its marker.
  Invocation (temporary harness retained for this run):

  ```sh
  "$JAVA_HOME/bin/java" --class-path target/syleks-0.1.0-SNAPSHOT.jar \
    /tmp/sec02-smoke-6ij6mo2j/Sec02Smoke.java \
    /tmp/sec02-smoke-6ij6mo2j/home /tmp/sec02-smoke-6ij6mo2j/workspace \
    /home/fpa/.sdkman/candidates/maven/3.9.16/bin
  ```

  The harness process itself was launched with a cleared environment and synthetic test values;
  this command shows its Java arguments. No live model/server or real credentials were used.
- Packaged TUI PTY check in that isolated home: startup, `/demo`, `/help`, help scrolling,
  Escape, resize from 110×32 to 85×24, and Ctrl+D exit completed with exit code 0. The captured
  output contained the help modal and resized composer. No real command approval or sensitive
  value was displayed in this PTY check; approval decisions remain covered by automated tests.
- JSON parse/default checks and a source/schema supported-name consistency check passed.

### Limits and follow-up

Runtime evidence above establishes environment filtering and command compatibility on this
Linux/JDK installation. Windows/macOS execution was not tested; the command backend still
requires `/bin/bash`. No OS sandbox/backend isolation claim is made. HOME and filesystem access
remain available to commands, so credential files, program configuration/startup files and
`/proc` may remain readable. Trusted programs can execute arbitrary code and print sensitive
values in model-visible output. Directory/binary/symlink replacement by a same-user attacker
is not prevented. SEC-03 supplies the separate OS boundary; SEC-08 tracks broader secret
storage/redaction. The server-specific MCP map remains an explicit trusted extension authority,
rather than a restricted command-environment opt-in.
