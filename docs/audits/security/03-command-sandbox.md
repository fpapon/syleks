# SEC-03: Enforce OS command isolation

Status: implementation ready, locally validated 26 October 2026; hosted CI execution pending. Priority: high; larger architecture change. Dependencies: SEC-01 and SEC-02.

## Finding and relevant code

The command runner invokes Bash as the current user. Granular parsing constrains literal invocations, not executable behavior. An allowed build tool, interpreter, script, or child process can access files and the network under the user's OS permissions. Process-tree cleanup is not a sandbox.

Read [LocalCommandRunner.java](../../../src/main/java/fr/openobject/ai/syleks/ai/LocalCommandRunner.java), [ToolExecutor.java](../../../src/main/java/fr/openobject/ai/syleks/ai/ToolExecutor.java), [TaskManager.java](../../../src/main/java/fr/openobject/ai/syleks/ai/TaskManager.java), [SubagentExecutor.java](../../../src/main/java/fr/openobject/ai/syleks/ai/SubagentExecutor.java), and the [previous shell audit](../opencode-parity/shell-security.md).

## Implementation contract

- Introduce a command execution backend with an explicit capability description and restricted/unrestricted modes. Start with one documented, supported Linux backend; do not claim cross-platform enforcement from a working-directory setting or a JVM API.
- Define read-only runtime mounts, narrowly scoped workspace write access, a private temporary directory/home, and no access to host credential stores, trust records, SSH agents, or unrelated project data. Configuration policy files inside a writable workspace need protected treatment.
- Network is disabled by default in restricted mode, including subprocesses. Approved network access must be an enforceable separate capability; an application URL rule does not constrain arbitrary command sockets. Coordinate with SEC-06.
- Bound memory, CPU, process count, runtime, and output. Stop all contained processes on timeout/cancellation, including detached/background descendants. Deny host socket access and privileged escalation paths.
- Probe actual backend availability/capabilities before execution. When required isolation cannot be established, fail before starting the command. An unrestricted fallback requires an explicit user choice outside repository/model control and must be clearly shown.
- Preserve shell permission parsing and approvals as independent checks. Do not grant the sandbox helper arbitrary executable authority through model-controlled options.
- Specify the treatment of binaries, symlinks, mounts, build caches, Git configuration/hooks, and other execution sources. Avoid exposing writable shared caches containing secrets or executable host state.

## Acceptance and evidence

Run real backend tests for allowed workspace edits and denied reads/writes outside scope, host credential fixtures, localhost/private/public network attempts, host sockets, nested processes, background/detached children, symlink traversal, and resource exhaustion. Test unavailable backend, partial capability support, malformed policy, launch failures, timeout, and cancellation. Verify no prefix effect occurs after failed setup.

Use disposable fixtures, never host secrets or real external endpoints. Run backend integration checks in CI on a supported runner; separate unavailable-platform results from passed checks. Provide the backend threat model, exact enforced capabilities, dependency/install requirements, safe migration defaults, and packaged evidence. Follow [validation.md](validation.md).

## Implementation record — 26 October 2026

Implemented in the working tree; no commit or PR created. `sandbox.CommandExecutionBackend` separates command process authority from shell authorization. `LocalCommandRunner` defaults to the Linux backend; `ToolExecutor` supplies the immutable global `command_sandbox` policy for primary and delegated calls. Existing shell parsing, all-actions-before-effects authorization, ancestor constraints, output limits and interruption handling remain in place. Explicit host-mode fixtures preserve platform-independent permission/engine tests; the separate backend suite requires real kernel enforcement.

The supported backend uses Bubblewrap namespaces/mounts, an inherited x86-64 seccomp filter and systemd user-service cgroup v2 limits. Every invocation probes the actual mounts/filter and checks finite kernel memory/task/CPU limits before running its payload. The actual launch repeats those checks. Failed or partial setup never falls back to host execution. Systemd controls the entire cgroup; PID namespace cleanup and explicit unit shutdown stop detached descendants on normal exit, cancellation and timeout.

Global configuration defaults to restricted mode, network denied, 1024 MiB memory, no swap, 64 tasks and 100% CPU bandwidth. `network: "allow"` grants all IPv4/IPv6 destinations separately; Unix sockets remain denied. `mode: "unrestricted"` is an explicit global user downgrade. Projects (even trusted ones), agent preferences, environment variables, command arguments, remembered approvals and provider HTTP exceptions cannot grant either capability. Schema, bundled defaults, examples, CLI help, approval details and `/permissions` describe the policy. Unrestricted chat warnings remain outside model history and exports; setup preserves existing policy.

See [command isolation](../../command-sandbox.md) for the complete capability table, setup/migration example, resource ranges, execution-source treatment and threat model. Read-only system runtime mounts and a narrowly bound writable workspace replace host-root access. Private HOME/tmp and hidden root `.syleks`/existing `.git` metadata exclude host credentials, trust records, configuration, sockets and caches. Hard-linked files, nested mounts, metadata symlinks, overlapping system/home paths and a global security configuration resolving into the workspace are refused. An absent `.syleks` mount directory is reserved during setup. Existing Git metadata is deliberately hidden, so metadata-dependent Git operations are unsupported by this first backend. Runtime mounts do not expose home/opt SDK installations; those require a supported system installation or an explicit host-mode choice.

Validation environment: Linux amd64, Debian kernel `6.12.113+deb13-amd64`, Bubblewrap `0.12.0`, systemd `257.13-1~deb13u1`, unified cgroup v2 with a working user manager; Zulu OpenJDK `25+36-LTS`, Maven `3.9.16`. Maven used these inline environment assignments:

```sh
JAVA_HOME=/home/fpa/.sdkman/candidates/java/25-zulu
PATH=/home/fpa/.sdkman/candidates/java/25-zulu/bin:/home/fpa/.sdkman/candidates/maven/3.9.16/bin:$PATH
SYLEKS_SANDBOX_TESTS=required
```

Commands and evidence:

```sh
mvn -B --no-transfer-progress spotless:apply -Dtest=LinuxCommandBackendTest,CommandSandboxPolicyTest,CommandToolTest,LocalCommandRunnerTest,ShellCommandPolicyTest,ToolExecutorTest,SubagentExecutionTest,ParityAuditTest,AppConfigTest test
# PASS: 155 tests, no failures/errors/skips at that stage.
mvn -B --no-transfer-progress spotless:apply -Dtest=LinuxCommandBackendTest,CommandSandboxPolicyTest,SubprocessEnvironmentTest,AgentEngineParityTest,ConfigSchemaTest,ConfigInitWizardTest test
# PASS: 45 tests, no failures/errors/skips after SDK fixture corrections and additional backend cases.
mvn -B --no-transfer-progress -Dtest=LinuxCommandBackendTest test
# PASS: final 11 real backend tests, no failures/errors/skips.
mvn -B --no-transfer-progress clean verify
# All 551 tests passed, 0 failures/errors/skips. Final Spotless check then flagged indentation in the new nested-mount test.
mvn -B --no-transfer-progress spotless:apply verify -DskipTests
# BUILD SUCCESS: license checks, shaded JAR and formatting passed after the whitespace-only fix; tests deliberately not repeated.
git diff --check
# PASS.
```

The last formatter run changed only indentation of the nested-mount test's `--map-root-user` argument. Production code and test behavior did not change after the 551-test run. A complete 550-test `clean verify` also passed before that final fixture was added. The final full-suite log is `/tmp/syleks-sec03-final-verify.log`; the subsequent format/package check is `/tmp/syleks-sec03-format-package.log`.

Negative evidence: host/outside and synthetic SSH credential fixtures are unreadable; host markers remain unchanged after symlink writes (an identically named file can exist only in private tmpfs). Policy/Git metadata writes and runtime mount writes fail. Default socket creation fails for IPv4, IPv6 and Unix families, covering loopback, private and documentation-address targets without contacting real endpoints. A host TCP fixture receives no connection from a denied invocation, but does receive a synthetic payload after the global network grant. An actual Unix socket bound inside the workspace receives no connection even with that grant. Namespace/mount privilege attempts fail. Sixteen-task and 64 MiB fixtures stop fork/memory exhaustion; finite CPU quota is checked in kernel cgroup files and a busy loop times out. Output truncation, nested/background/detached descendants, normal completion, timeout and cancellation are tested. A real nested tmpfs in a disposable user/mount namespace, hardlinks, metadata/global-policy symlinks, missing/partial capabilities and launch-error seams all reject before command prefix markers appear.

Packaged evidence: CLI `--help` documents the modes. `/tmp/SyleksSandboxSmoke.java`, run against the shaded JAR using the JDK source launcher, verifies workspace edits, absent/unchanged synthetic host fixtures and denied network sockets with the real backend. A temporary HOME/project/data PTY smoke (`python3 /tmp/syleks-sec03-pty.py`) verifies the unrestricted warning, `/permissions` and scrolling, resize from 120×30 to 100×24, help modal, Escape and Ctrl+D. All fixtures contain synthetic data; no screenshot, production credential, live model or external service was used. These temporary scripts/logs are local evidence, not required repository artifacts.

The execution sandbox blocked initial namespace/user-bus probes; real backend runs used approved access outside it. Earlier verification attempts found test fixture JSON, host-SDK assumptions and namespace-local mount privilege errors; those attempts are not counted as passing. No unavailable backend check is reported as a pass. UI smoke assertions were corrected for viewport scrolling and terminal control timing before the passing run; no rendering/layout behavior was changed.

CI is configured on Ubuntu 24.04 to install Bubblewrap, enable unprivileged namespaces on the disposable runner, start the user manager and require `SYLEKS_SANDBOX_TESTS=required` during full verification. The hosted workflow has not been run in this session; its successful run is the remaining acceptance evidence before marking SEC-03 fully implemented in this backlog. Outside required test mode, backend integration tests are explicitly skipped; production commands still always enforce/probe restricted mode.

Limitations: Linux x86-64 only; no native-image build was performed. Workspace disk consumption is not quota-limited. The user-selected workspace and public system runtime contents are readable, including secrets placed there by the user; no automatic secret detection is claimed. External same-user path mutation, kernel exploits, administrator-owned helper compromise, and trusted extension/MCP process isolation are excluded. Concurrent filesystem race hardening remains SEC-07; provider/web destination controls remain SEC-06. No required enforcement silently falls back or depends on prompt text.
