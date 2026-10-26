# Command isolation

The `bash` tool uses restricted OS isolation by default, including primary, delegated and extension-dispatched calls through `ToolExecutor`. Shell permissions and approvals are checked first and remain independent. A denied script has no command prefix effects. A shell approval cannot change sandbox authority.

## Global policy and migration

Merge the following into `~/.config/syleks/agent-config.json` and restart:

```json
{
  "command_sandbox": {
    "mode": "restricted",
    "network": "deny",
    "memory_mib": 1024,
    "processes": 64,
    "cpu_percent": 100
  }
}
```

These are also the defaults when the setting is absent. Only the global file supplies this policy. Trusted project JSON, agent files, children, environment variables, tool arguments, remembered approvals and `insecure_http_origins` cannot widen it. Setup preserves the setting. `/permissions` and shell approval details show the mode and limits. Unrestricted chat also shows a view-only warning outside model history and exports.

`network: "allow"` is a separate user capability: it enables IPv4/IPv6 networking for all command descendants, to **all destinations**, including localhost and private networks. It is not an endpoint allowlist. Unix sockets remain denied. Provider and web-tool networking is separate; SEC-06 tracks their destination policy. Remove the grant and restart to revoke it for future commands.

Restricted execution fails before the command starts if enforcement is unavailable. There is no automatic fallback. On an unsupported machine, the user can deliberately merge `"command_sandbox": {"mode": "unrestricted"}` into the global file and restart. This grants commands the user's host filesystem and network authority; only environment filtering, command permission checks, runtime/output bounds and best-effort descendant cleanup remain. It is a security downgrade, not a backend compatibility mode. Existing files are never rewritten to select it.

## Supported Linux backend

The supported backend is Linux **x86-64**, Bubblewrap **0.8 or newer**, systemd **254 or newer** with a working per-user manager, unified cgroup v2 with delegated `memory`, `pids` and `cpu` controllers, and kernel user/PID/mount/network namespaces and seccomp BPF. Install distro packages (Debian/Ubuntu: `bubblewrap`, `systemd`, `dbus-user-session`) and use a login session with a running systemd user manager. Helpers are fixed at `/usr/bin/bwrap`, `/usr/bin/systemd-run` and `/usr/bin/systemctl`; repositories cannot provide helper arguments or replacements. System runtime/helper installations are trusted administrator-owned inputs. Native Syleks and JVM Syleks use the same backend.

A harmless probe runs with the same mounts, filter and limits before each command. Both probe and actual launch validate the **kernel cgroup files** before entering Bubblewrap: finite `memory.max`, zero `memory.swap.max`, finite `pids.max`, and a `cpu.max` quota no larger than requested. Missing controllers and silently unenforced systemd resource properties fail closed. Bubblewrap must successfully establish the required namespaces and seccomp filter before executing the payload. Do not treat `bwrap --version` alone as an enforcement check.

The CI job on Ubuntu 24.04 requires these real tests (`SYLEKS_SANDBOX_TESTS=required`). It enables unprivileged user namespaces on its disposable runner and starts the user manager. Outside that supported test mode the backend integration class is explicitly skipped, not counted as passed. Production execution always probes; the test variable changes no application authority.

## Enforced scope

| Capability | Restricted behavior |
| --- | --- |
| Runtime reads | Read-only `/usr`, standard `/bin`, `/sbin`, `/lib`, `/lib64` runtime links/mounts, and selected loader, host/DNS and certificate files under `/etc` |
| Workspace | Canonical launch workspace mounted writable at its original absolute path; other projects, user configuration and credential stores are absent |
| Project policy | Root `.syleks` hidden behind an empty read-only mount; a missing `.syleks` directory is reserved during setup and left empty on the host |
| Git metadata | Existing root `.git` directory hidden behind an empty read-only mount; worktree `.git` files masked with `/dev/null`; symlink metadata is rejected |
| Home and temporary files | Per-command private `/syleks-home` and `/tmp`, discarded when the sandbox exits; no host caches shared |
| Networking | New network namespace and denied socket creation by default; explicit network grant shares host IP networking but permits only AF_INET/AF_INET6 sockets |
| Host sockets | AF_UNIX and other socket families denied by an inherited seccomp filter, even for socket paths inside the writable workspace |
| Privileges | No new privileges, no capabilities, no further user namespaces; seccomp also rejects mount/setns/unshare, ptrace, keyring/kernel administration and io_uring entry points; alternate syscall ABIs are killed |
| Resources | Cgroup total memory (including private tmpfs), no swap, total tasks including threads, CPU bandwidth quota (100% = one CPU), service runtime ceiling, 32,000 captured characters |
| Lifecycle | Private PID namespace plus systemd control-group kill; timeout, interruption and ordinary completion stop contained detached/background descendants |

`memory_mib` accepts integers 64–16384, `processes` 8–512 and `cpu_percent` 10–400. The existing command timeout accepts 1–300 seconds; a second systemd runtime ceiling allows one additional second for shutdown. CPU quota is a bandwidth limit, not a cumulative CPU-time budget; quota plus the wall-clock limit bounds total CPU consumption. Core dumps are disabled. Memory and process limits can make compiler/JVM launches fail; choose global values appropriate to your machine. Disk writes inside the workspace are not quota-limited.

## Execution sources and limitations

Only the working tree is exposed. Existing Git config, hooks, index and worktree administration are hidden, so metadata-dependent Git operations will fail in this first backend. Repository scripts and binaries are executable after normal shell permission checks, and their descendants retain the sandbox. A newly created `.git` in a workspace without existing metadata is ordinary untrusted workspace content; it is hidden on subsequent launches. Do not execute newly generated hooks/scripts on the host without review.

Runtime mounts are public system installations, not a place for user secrets. SDKs under the user's home or `/opt` are not exposed by adding `trusted_path` or `JAVA_HOME`; install trusted tools in the supported system runtime locations or explicitly choose unrestricted host execution. Commands can maintain caches under private HOME/tmp or inside the workspace; the latter are untrusted executable project content. No host Maven/Gradle/npm/pip/Git cache is mounted. `SSH_AUTH_SOCK` is removed even if the environment policy selected it. Proxy values explicitly opted into `command_environment.pass_env` remain sensitive values provided by the user, but sockets still obey sandbox policy.

Workspace symlinks cannot reach unmounted host files. Writing through an absolute symlink into private `/tmp` may create a private file with the same pathname; it does not touch the host target. Hard-linked regular files and nested host mounts in the workspace are rejected before execution because they could expose outside inodes. Preflight scanning is interruptible and rejects workspaces over 100,000 entries. Workspaces overlapping home, protected system paths, the host temporary root, or the resolved global security configuration directory are rejected. Concurrent mutation by an external same-user attacker between path validation and mounting is outside this backend's threat model; avoid concurrent external writers (SEC-07 tracks race-resistant file access). Files already present inside the chosen working tree are available to commands, regardless of separate file-tool permissions.

This isolates arbitrary command descendants, not the entire Syleks JVM/native process. Trusted Java extensions can run their own code, and MCP stdio servers retain their separately documented OS authority. Kernel exploits, malicious administrator-installed helpers, hostile same-user host processes, side channels and disk exhaustion are outside the guarantees. Global policy and user trust records are never mounted into restricted commands.

Backend references: [Bubblewrap security model](https://github.com/containers/bubblewrap#sandbox-security), [systemd transient services](https://www.freedesktop.org/software/systemd/man/latest/systemd-run.html), [cgroup v2 controllers](https://docs.kernel.org/admin-guide/cgroup-v2.html), [inherited seccomp filters](https://www.kernel.org/doc/html/latest/userspace-api/seccomp_filter.html).
