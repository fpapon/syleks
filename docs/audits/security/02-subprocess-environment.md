# SEC-02: Isolate subprocess environments

Status: not started. Priority: high. Dependencies: none; coordinate with SEC-03 and SEC-08.

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
