# SEC-03: Enforce OS command isolation

Status: not started. Priority: high; larger architecture change. Dependencies: SEC-01 and SEC-02.

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
