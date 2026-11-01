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

/** Explicit local OCI backends. Images and engine installations are trusted global inputs. */
public final class ContainerCommandBackend implements CommandExecutionBackend {
    private final CommandSandboxPolicy policy;
    private final Path home;

    public ContainerCommandBackend(CommandSandboxPolicy policy, Path home) {
        this.policy = policy;
        this.home = home.toAbsolutePath().normalize();
        if (!Set.of("docker", "podman").contains(policy.backend()))
            throw new IllegalArgumentException("Expected a container backend");
    }

    public Capabilities capabilities() {
        return new Capabilities(true, true, true, true);
    }

    public Execution start(
            Path workspace, Map<String, String> environment, String command, int timeout)
            throws Exception {
        if (!System.getProperty("os.name").equals("Linux")
                || !Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")))
            throw new IOException("Container command isolation requires local Linux x86-64");
        if (!Files.isExecutable(Path.of("/usr/bin/" + policy.backend())))
            throw new IOException(
                    "Required container sandbox engine unavailable: " + policy.backend());
        var root = workspace.toRealPath();
        new LinuxCommandBackend(policy, home).validateWorkspace(root);
        // OCI mount syntax cannot represent these characters safely.
        if (root.toString().contains(",") || root.toString().contains(":"))
            throw new IOException(
                    "Container workspace path contains an unsupported mount delimiter");
        try {
            Files.createDirectory(root.resolve(".syleks"));
        } catch (FileAlreadyExistsException existing) {
            if (!Files.isDirectory(root.resolve(".syleks"), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Sandbox requires a real .syleks directory");
        }
        var profile = Files.createTempFile("syleks-seccomp-", ".json");
        try {
            Files.writeString(profile, seccomp(policy.network().equals("allow")));
            try (var probe = launch(root, environment, ":", 15, profile)) {
                if (!probe.process().waitFor(20, TimeUnit.SECONDS)
                        || probe.process().exitValue() != 0)
                    throw new IOException(
                            "Container isolation probe failed; no command was started. See docs/command-sandbox.md");
            }
            Files.writeString(profile, seccomp(policy.network().equals("allow")));
            return launch(root, environment, command, timeout + 1, profile);
        } finally {
            // The engine loads the profile during create, before the attached process can run.
            // Keep it until Execution.close(), including failed or interrupted launches.
            if (!activeProfiles.contains(profile)) Files.deleteIfExists(profile);
        }
    }

    private final Set<Path> activeProfiles = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private Execution launch(
            Path root, Map<String, String> environment, String command, int seconds, Path profile)
            throws Exception {
        String name = "syleks-command-" + UUID.randomUUID();
        var log = Files.createTempFile("syleks-container-create-", ".log");
        try {
            var create =
                    control(arguments(root, environment, command, seconds, profile, name))
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile())
                            .start();
            try {
                if (!create.waitFor(20, TimeUnit.SECONDS) || create.exitValue() != 0)
                    throw new IOException(
                            "Unable to create isolated container; check the engine and pinned image");
            } finally {
                create.destroyForcibly();
            }
        } catch (Exception failure) {
            try {
                remove(name);
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        } finally {
            Files.deleteIfExists(log);
        }
        var start = engine();
        start.addAll(List.of("start", "--attach", name));
        final Process process;
        try {
            process = control(start).redirectErrorStream(true).start();
        } catch (Exception failure) {
            try {
                remove(name);
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        process.getOutputStream().close();
        activeProfiles.add(profile);
        return new Execution() {
            public Process process() {
                return process;
            }

            public void close() throws Exception {
                boolean interrupted = Thread.interrupted();
                try {
                    remove(name);
                } finally {
                    process.destroyForcibly();
                    activeProfiles.remove(profile);
                    Files.deleteIfExists(profile);
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        };
    }

    private void remove(String name) throws Exception {
        boolean interrupted = Thread.interrupted();
        try {
            var args = engine();
            args.addAll(List.of("rm", "--force", "--volumes", name));
            var cleanup =
                    control(args)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            try {
                if (!cleanup.waitFor(5, TimeUnit.SECONDS) || cleanup.exitValue() != 0)
                    throw new IOException("Unable to confirm container cleanup: " + name);
            } finally {
                cleanup.destroyForcibly();
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private ArrayList<String> engine() {
        return new ArrayList<>(
                policy.backend().equals("docker")
                        ? List.of("/usr/bin/docker", "--host=unix:///var/run/docker.sock")
                        : List.of("/usr/bin/podman", "--remote=false"));
    }

    List<String> arguments(
            Path root,
            Map<String, String> environment,
            String command,
            int seconds,
            Path profile,
            String name)
            throws IOException {
        var args = engine();
        long memory = policy.memoryMiB() * 1024L * 1024L;
        int uid = ((Number) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).intValue();
        int gid = ((Number) Files.getAttribute(Path.of("/proc/self"), "unix:gid")).intValue();
        args.addAll(
                List.of(
                        "create",
                        "--name=" + name,
                        "--pull=never",
                        "--read-only",
                        "--log-driver=none",
                        "--cap-drop=ALL",
                        "--security-opt=no-new-privileges",
                        "--security-opt=seccomp=" + profile,
                        "--cgroupns=private",
                        "--ipc=private",
                        "--network=" + (policy.network().equals("allow") ? "host" : "none"),
                        "--memory=" + memory,
                        "--memory-swap=" + memory,
                        "--pids-limit=" + policy.processes(),
                        "--cpus="
                                + java.math.BigDecimal.valueOf(policy.cpuPercent(), 2)
                                        .toPlainString(),
                        "--ulimit=core=0:0",
                        "--user=" + uid + ":" + gid,
                        "--tmpfs=/tmp:rw,nosuid,nodev,mode=1777,size=" + memory,
                        "--tmpfs=/syleks-home:rw,nosuid,nodev,mode=1777,size=" + memory,
                        "--mount=type=bind,src=" + root + ",dst=" + root,
                        "--tmpfs=" + root.resolve(".syleks") + ":ro,nosuid,nodev,size=4096",
                        "--workdir=" + root,
                        "--entrypoint=/usr/bin/timeout"));
        if (policy.backend().equals("podman"))
            args.addAll(List.of("--userns=keep-id", "--pid=private", "--http-proxy=false"));
        var git = root.resolve(".git");
        if (Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS))
            args.add("--tmpfs=" + git + ":ro,nosuid,nodev,size=4096");
        else if (Files.exists(git, LinkOption.NOFOLLOW_LINKS))
            args.add("--mount=type=bind,src=/dev/null,dst=" + git + ",readonly");
        args.addAll(
                List.of(
                        policy.containerImage(),
                        "--signal=KILL",
                        Integer.toString(seconds),
                        "/bin/bash",
                        "--noprofile",
                        "--norc",
                        "-c",
                        LinuxCommandBackend.GATE,
                        "sandbox-gate",
                        Long.toString(memory),
                        Integer.toString(policy.processes()),
                        Integer.toString(policy.cpuPercent()),
                        "/usr/bin/env",
                        "-i"));
        for (var entry : environment.entrySet())
            if (!Set.of("HOME", "TMPDIR", "SSH_AUTH_SOCK", "GPG_AGENT_INFO")
                    .contains(entry.getKey())) args.add(entry.getKey() + "=" + entry.getValue());
        args.addAll(
                List.of(
                        "HOME=/syleks-home",
                        "TMPDIR=/tmp",
                        "GIT_CONFIG_NOSYSTEM=1",
                        "GIT_CONFIG_GLOBAL=/dev/null",
                        "/bin/bash",
                        "--noprofile",
                        "--norc",
                        "-c",
                        command));
        return List.copyOf(args);
    }

    private ProcessBuilder control(List<String> args) {
        var builder = new ProcessBuilder(args).directory(home.toFile());
        builder.environment().clear();
        builder.environment().put("HOME", home.toString());
        builder.environment().put("PATH", "/usr/bin:/bin");
        builder.environment().put("LANG", "C.UTF-8");
        try {
            int uid = ((Number) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).intValue();
            builder.environment().put("XDG_RUNTIME_DIR", "/run/user/" + uid);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return builder;
    }

    static String seccomp(boolean network) {
        // libseccomp's architecture check kills non-native ABIs. Explicitly exclude x32/compat.
        String socket =
                network
                        ? java.util.stream.Stream.concat(
                                        java.util.stream.Stream.of(
                                                "{\"index\":0,\"value\":2,\"op\":\"SCMP_CMP_LT\"}",
                                                "{\"index\":0,\"value\":10,\"op\":\"SCMP_CMP_GT\"}"),
                                        java.util.stream.IntStream.rangeClosed(3, 9)
                                                .mapToObj(
                                                        domain ->
                                                                "{\"index\":0,\"value\":"
                                                                        + domain
                                                                        + ",\"op\":\"SCMP_CMP_EQ\"}"))
                                .map(
                                        arg ->
                                                "{\"names\":[\"socket\"],\"action\":\"SCMP_ACT_ERRNO\",\"errnoRet\":1,\"args\":["
                                                        + arg
                                                        + "]}")
                                .collect(java.util.stream.Collectors.joining(","))
                        : "{\"names\":[\"socket\"],\"action\":\"SCMP_ACT_ERRNO\",\"errnoRet\":1}";
        return """
                {"defaultAction":"SCMP_ACT_ALLOW","architectures":["SCMP_ARCH_X86_64"],"syscalls":[
                  {"names":["socketpair","ptrace","pivot_root","mount","umount2","swapon","swapoff","reboot",
                    "init_module","delete_module","kexec_load","add_key","request_key","keyctl","unshare",
                    "perf_event_open","name_to_handle_at","open_by_handle_at","setns","finit_module","bpf",
                    "userfaultfd","io_uring_setup","io_uring_enter","io_uring_register","open_tree","move_mount",
                    "fsopen","fsconfig","fsmount","fspick","mount_setattr"],"action":"SCMP_ACT_ERRNO","errnoRet":1},
                  %s,
                  {"names":["clone3"],"action":"SCMP_ACT_ERRNO","errnoRet":38},
                  %s]}
                """
                .formatted(
                        java.util.stream.LongStream.of(
                                        0x00020000L,
                                        0x02000000L,
                                        0x04000000L,
                                        0x08000000L,
                                        0x10000000L,
                                        0x20000000L,
                                        0x40000000L)
                                .mapToObj(
                                        bit ->
                                                "{\"names\":[\"clone\"],\"action\":\"SCMP_ACT_ERRNO\",\"errnoRet\":1,"
                                                        + "\"args\":[{\"index\":0,\"value\":"
                                                        + bit
                                                        + ",\"op\":\"SCMP_CMP_MASKED_EQ\",\"valueTwo\":"
                                                        + bit
                                                        + "}]}")
                                .collect(java.util.stream.Collectors.joining(",")),
                        socket);
    }
}
