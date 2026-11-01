/*
 * Copyright (c) 2026 - present - Francois Papon - francois.papon@openobject.fr
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package fr.openobject.ai.syleks.sandbox;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Supported backend: Linux x86-64, unprivileged Bubblewrap, systemd user services and cgroup v2. */
public final class LinuxCommandBackend implements CommandExecutionBackend {
    private final CommandSandboxPolicy policy;
    private final Path home;
    static final String GATE =
            """
            set -euo pipefail
            memory=$1; tasks=$2; cpu=$3; shift 3
            cgroup=
            while IFS=: read -r hierarchy controllers path; do
                if [[ $hierarchy = 0 && -z $controllers ]]; then cgroup=/sys/fs/cgroup$path; fi
            done < /proc/self/cgroup
            [[ -n $cgroup && -f $cgroup/cgroup.controllers ]] || exit 125
            actual=$(< "$cgroup/memory.max")
            [[ $actual =~ ^[0-9]+$ ]] && (( actual > 0 && actual <= memory )) || exit 125
            [[ $(< "$cgroup/memory.swap.max") = 0 ]] || exit 125
            actual=$(< "$cgroup/pids.max")
            [[ $actual =~ ^[0-9]+$ ]] && (( actual > 0 && actual <= tasks )) || exit 125
            read -r quota period < "$cgroup/cpu.max"
            [[ $quota =~ ^[0-9]+$ && $period =~ ^[0-9]+$ ]] &&
                (( quota > 0 && period > 0 && quota * 100 <= period * cpu )) || exit 125
            exec "$@"
            """;

    public LinuxCommandBackend(CommandSandboxPolicy policy, Path home) {
        this.policy = policy;
        this.home = home.toAbsolutePath().normalize();
    }

    public Capabilities capabilities() {
        return new Capabilities(true, true, true, true);
    }

    @Override
    public Execution start(
            Path workspace, Map<String, String> environment, String command, int timeout)
            throws Exception {
        requirePlatform();
        var root = workspace.toRealPath();
        validateWorkspace(root);
        // Reserve the project policy mount point. Never follow a project-controlled symlink here.
        var projectPolicy = root.resolve(".syleks");
        try {
            Files.createDirectory(projectPolicy);
        } catch (FileAlreadyExistsException existing) {
            if (!Files.isDirectory(projectPolicy, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Sandbox requires a real .syleks directory");
        }
        // Probe exactly the same mounts, filter and cgroup gate with a harmless executable.
        var probeLog = Files.createTempFile("syleks-sandbox-probe-", ".log");
        try {
            try (var probe = launch(root, environment, List.of("/usr/bin/true"), 15, probeLog)) {
                if (!probe.process().waitFor(20, TimeUnit.SECONDS)
                        || probe.process().exitValue() != 0)
                    throw new IOException(
                            "Required Linux command isolation is unavailable (namespace, seccomp or cgroup probe failed). No command was started. See docs/command-sandbox.md.");
            }
        } finally {
            Files.deleteIfExists(probeLog);
        }
        return launch(
                root,
                environment,
                List.of("/bin/bash", "--noprofile", "--norc", "-c", command),
                timeout + 1,
                null);
    }

    private static void requirePlatform() throws IOException {
        if (!System.getProperty("os.name").equals("Linux")
                || !Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")))
            throw new IOException(
                    "Restricted commands require Linux x86-64; no unrestricted fallback is enabled");
        for (String helper :
                List.of(
                        "/usr/bin/bwrap",
                        "/usr/bin/systemd-run",
                        "/usr/bin/systemctl",
                        "/usr/bin/env",
                        "/bin/bash"))
            if (!Files.isExecutable(Path.of(helper)))
                throw new IOException(
                        "Required Linux command sandbox helper unavailable: " + helper);
        if (!Files.isRegularFile(Path.of("/sys/fs/cgroup/cgroup.controllers")))
            throw new IOException("Restricted commands require cgroup v2");
    }

    void validateWorkspace(Path root) throws IOException, InterruptedException {
        var realHome = Files.exists(home) ? home.toRealPath() : home;
        var security = home.resolve(".config/syleks");
        if (Files.exists(security) && security.toRealPath().startsWith(root))
            throw new IOException("Sandbox workspace contains the global security configuration");
        if (realHome.startsWith(root)
                || Path.of("/usr").startsWith(root)
                || root.startsWith(Path.of("/usr"))
                || root.startsWith(Path.of("/etc"))
                || root.startsWith(Path.of("/proc"))
                || root.startsWith(Path.of("/sys"))
                || root.startsWith(Path.of("/dev"))
                || root.startsWith(Path.of("/run"))
                || root.equals(Path.of("/tmp"))
                || root.startsWith(Path.of("/syleks-home"))
                || Path.of("/syleks-home").startsWith(root))
            throw new IOException("Sandbox workspace overlaps protected host/runtime paths");
        for (String mount : Files.readAllLines(Path.of("/proc/self/mountinfo"))) {
            String mounted =
                    mount.split(" ")[4]
                            .replace("\\040", " ")
                            .replace("\\011", "\t")
                            .replace("\\012", "\n")
                            .replace("\\134", "\\");
            var path = Path.of(mounted);
            if (!path.equals(root) && path.startsWith(root))
                throw new IOException("Sandbox workspace contains a nested host mount");
        }
        try (var files = Files.walk(root)) {
            int entries = 0;
            for (var path : (Iterable<Path>) files::iterator) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException("Command cancelled during sandbox setup");
                if (++entries > 100000)
                    throw new IOException("Sandbox workspace exceeds 100000 preflight entries");
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && ((Number)
                                                Files.getAttribute(
                                                        path,
                                                        "unix:nlink",
                                                        LinkOption.NOFOLLOW_LINKS))
                                        .longValue()
                                > 1)
                    throw new IOException("Sandbox workspace contains a hard-linked file");
            }
        }
        if (Files.isSymbolicLink(root.resolve(".syleks"))
                || Files.isSymbolicLink(root.resolve(".git")))
            throw new IOException("Sandbox policy/Git metadata must not be symlinks");
    }

    private Execution launch(
            Path root,
            Map<String, String> environment,
            List<String> payload,
            int seconds,
            Path probeLog)
            throws Exception {
        String unit = "syleks-command-" + UUID.randomUUID() + ".service";
        long memory = policy.memoryMiB() * 1024L * 1024L;
        var args =
                new ArrayList<>(
                        List.of(
                                "/usr/bin/systemd-run",
                                "--user",
                                "--pipe",
                                "--wait",
                                "--collect",
                                "--quiet",
                                "--expand-environment=no",
                                "--service-type=exec",
                                "--unit=" + unit,
                                "--property=MemoryMax=" + memory,
                                "--property=MemorySwapMax=0",
                                "--property=TasksMax=" + policy.processes(),
                                "--property=CPUQuota=" + policy.cpuPercent() + "%",
                                "--property=RuntimeMaxSec=" + seconds,
                                "--property=TimeoutStopSec=1",
                                "--property=KillMode=control-group",
                                "--property=SendSIGKILL=yes",
                                "--property=NoNewPrivileges=yes",
                                "--property=LimitCORE=0",
                                "/usr/bin/env",
                                "-i",
                                "PATH=/usr/bin:/bin",
                                "/bin/bash",
                                "--noprofile",
                                "--norc",
                                "-c",
                                GATE,
                                "sandbox-gate",
                                Long.toString(memory),
                                Integer.toString(policy.processes()),
                                Integer.toString(policy.cpuPercent()),
                                "/usr/bin/bwrap",
                                "--unshare-all",
                                "--unshare-user",
                                "--disable-userns",
                                "--assert-userns-disabled",
                                "--die-with-parent",
                                "--new-session",
                                "--cap-drop",
                                "ALL",
                                "--clearenv"));
        if (policy.network().equals("allow")) args.add("--share-net");
        args.addAll(List.of("--ro-bind", "/usr", "/usr"));
        for (String runtime : List.of("bin", "sbin", "lib", "lib64")) {
            var source = Path.of("/" + runtime);
            if (Files.isSymbolicLink(source))
                args.addAll(
                        List.of(
                                "--symlink",
                                Files.readSymbolicLink(source).toString(),
                                source.toString()));
            else if (Files.isDirectory(source))
                args.addAll(List.of("--ro-bind", source.toString(), source.toString()));
        }
        for (String runtime :
                List.of("/etc/ld.so.cache", "/etc/hosts", "/etc/resolv.conf", "/etc/ssl/certs"))
            if (Files.exists(Path.of(runtime))) args.addAll(List.of("--ro-bind", runtime, runtime));
        args.addAll(
                List.of(
                        "--proc",
                        "/proc",
                        "--dev",
                        "/dev",
                        "--tmpfs",
                        "/tmp",
                        "--dir",
                        "/syleks-home",
                        "--bind",
                        root.toString(),
                        root.toString(),
                        "--tmpfs",
                        root.resolve(".syleks").toString(),
                        "--remount-ro",
                        root.resolve(".syleks").toString()));
        var git = root.resolve(".git");
        if (Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS))
            args.addAll(List.of("--tmpfs", git.toString(), "--remount-ro", git.toString()));
        else if (Files.exists(git, LinkOption.NOFOLLOW_LINKS))
            args.addAll(List.of("--ro-bind", "/dev/null", git.toString()));
        for (var entry : environment.entrySet()) {
            if (!Set.of("HOME", "TMPDIR", "SSH_AUTH_SOCK", "GPG_AGENT_INFO")
                    .contains(entry.getKey()))
                args.addAll(List.of("--setenv", entry.getKey(), entry.getValue()));
        }
        args.addAll(
                List.of(
                        "--setenv",
                        "HOME",
                        "/syleks-home",
                        "--setenv",
                        "TMPDIR",
                        "/tmp",
                        "--setenv",
                        "GIT_CONFIG_NOSYSTEM",
                        "1",
                        "--setenv",
                        "GIT_CONFIG_GLOBAL",
                        "/dev/null",
                        "--chdir",
                        root.toString(),
                        "--seccomp",
                        "0",
                        "--"));
        args.addAll(payload);
        var builder = control(args).redirectErrorStream(true);
        if (probeLog != null) builder.redirectOutput(probeLog.toFile());
        var process = builder.start();
        var execution =
                new Execution() {
                    public Process process() {
                        return process;
                    }

                    public void close() throws Exception {
                        boolean interrupted = Thread.interrupted();
                        try {
                            var stop =
                                    control(
                                                    List.of(
                                                            "/usr/bin/systemctl",
                                                            "--user",
                                                            "--no-ask-password",
                                                            "stop",
                                                            unit))
                                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                                            .start();
                            if (!stop.waitFor(4, TimeUnit.SECONDS)) {
                                stop.destroyForcibly();
                                throw new IOException(
                                        "Sandbox unit cleanup did not finish; its runtime limit remains active");
                            }
                            if (stop.exitValue() != 0) {
                                var state =
                                        control(
                                                        List.of(
                                                                "/usr/bin/systemctl",
                                                                "--user",
                                                                "show",
                                                                "--property=LoadState",
                                                                "--value",
                                                                unit))
                                                .redirectError(ProcessBuilder.Redirect.DISCARD)
                                                .start();
                                try {
                                    if (!state.waitFor(2, TimeUnit.SECONDS)
                                            || !new String(
                                                            state.getInputStream().readAllBytes(),
                                                            java.nio.charset.StandardCharsets.UTF_8)
                                                    .strip()
                                                    .equals("not-found"))
                                        throw new IOException(
                                                "Unable to confirm sandbox unit cleanup");
                                } finally {
                                    state.destroyForcibly();
                                }
                            }
                        } finally {
                            if (process.isAlive()) process.destroyForcibly();
                            if (interrupted) Thread.currentThread().interrupt();
                        }
                    }
                };
        try (var input = process.getOutputStream()) {
            input.write(LinuxSeccomp.filter(policy.network().equals("allow")));
        } catch (Exception failure) {
            execution.close();
            throw new IOException("Sandbox launch failed before command setup", failure);
        }
        return execution;
    }

    private static ProcessBuilder control(List<String> args) throws IOException {
        var builder = new ProcessBuilder(args);
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin:/bin");
        builder.environment().put("LANG", "C.UTF-8");
        int uid = ((Number) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).intValue();
        builder.environment().put("XDG_RUNTIME_DIR", "/run/user/" + uid);
        builder.environment()
                .put("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/" + uid + "/bus");
        return builder;
    }
}
