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
package fr.openobject.ai.syleks.auth;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Protected local fallback when no portable Java keyring is installed. Never used by session exports. */
@DefaultScoped
public class CredentialStore {
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final Path root;
    private final JsonMapper json;
    private final CredentialVault vault;

    public CredentialStore(JsonMapper json) {
        this(json, configRoot(), SecretServiceVault.detect());
    }

    CredentialStore(JsonMapper json, Path root) {
        this(json, root, null);
    }

    CredentialStore(JsonMapper json, Path root, CredentialVault vault) {
        this.json = json;
        this.root = root.toAbsolutePath().normalize();
        this.vault = vault;
    }

    public static Path configRoot() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return (xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
                        ? Path.of(xdg)
                        : Path.of(System.getProperty("user.home"), ".config"))
                .resolve("syleks");
    }

    public Path root() {
        return root;
    }

    /** Approval invalidation fingerprints; never unlock the vault or expose token values. */
    public String approvalContext(Collection<String> ids) throws IOException {
        var context = new LinkedHashMap<String, Object>();
        context.put("selection", read(root.resolve("config.json")));
        for (String id : new TreeSet<>(ids)) {
            var value = read(accountPath(id));
            var metadata = new LinkedHashMap<String, Object>();
            for (String field :
                    List.of(
                            "provider",
                            "email",
                            "issuer",
                            "subject",
                            "client_id",
                            "host_id",
                            "scopes",
                            "received_at",
                            "expires_at",
                            "vault_ref")) metadata.put(field, value.get(field));
            metadata.put(
                    "has_token",
                    value.get("access_token") instanceof String token && !token.isBlank());
            metadata.put(
                    "credential_revision",
                    fr.openobject.ai.syleks.ai.ApprovalGrants.digest(
                            List.of(
                                    Objects.toString(value.get("access_token"), ""),
                                    Objects.toString(value.get("refresh_token"), ""),
                                    Objects.toString(value.get("id_token"), ""))));
            context.put(id, metadata);
        }
        return fr.openobject.ai.syleks.ai.ApprovalGrants.digest(context);
    }

    private void directory(Path path) throws IOException {
        if (path.startsWith(root) && !path.equals(root)) directory(path.getParent());
        if (Files.isSymbolicLink(path)) throw new IOException("Unsafe credential directory");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (path.getParent() != null && !Files.exists(path.getParent()))
                directory(path.getParent());
            try {
                Files.createDirectory(
                        path,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException e) {
                Files.createDirectory(path);
            } catch (FileAlreadyExistsException ignored) {
            }
        }
        protect(path, true);
    }

    static void protect(Path path, boolean directory) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Unsafe credential symlink");
        try {
            Files.setPosixFilePermissions(
                    path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException ignored) {
            /* OS ACLs must protect this directory. */
        }
    }

    public Map<String, Object> read(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        directory(file.getParent());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe credential file");
        protect(file, false);
        try {
            Map<?, ?> map = json.fromString(Map.class, Files.readString(file));
            var result = new LinkedHashMap<String, Object>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        } catch (RuntimeException e) {
            throw new IOException("Invalid protected Syleks record");
        }
    }

    public void write(Path file, Map<String, Object> value) throws IOException {
        directory(file.getParent());
        if (Files.isSymbolicLink(file)) throw new IOException("Unsafe credential symlink");
        Path temp;
        try {
            temp =
                    Files.createTempFile(
                            file.getParent(),
                            ".write-",
                            ".tmp",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            temp = Files.createTempFile(file.getParent(), ".write-", ".tmp");
        }
        try {
            protect(temp, false);
            Files.writeString(temp, json.toString(value));
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(
                    temp,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @FunctionalInterface
    public interface Operation<T> {
        T run() throws Exception;
    }

    public <T> T locked(String key, Operation<T> operation) throws Exception {
        if (!key.matches("[A-Za-z0-9-]{1,80}"))
            throw new IllegalArgumentException("Invalid credential lock");
        var dir = root.resolve("auth");
        directory(dir);
        var file = dir.resolve(key + ".lock");
        var mutex = LOCKS.computeIfAbsent(file, ignored -> new ReentrantLock());
        mutex.lockInterruptibly();
        try {
            if (Files.isSymbolicLink(file)) throw new IOException("Unsafe credential lock");
            try {
                Files.createFile(
                        file,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                if (!Files.exists(file)) Files.createFile(file);
            } catch (FileAlreadyExistsException ignored) {
            }
            protect(file, false);
            try (var channel = FileChannel.open(file, StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                return operation.run();
            }
        } finally {
            mutex.unlock();
        }
    }

    public String hostId() throws Exception {
        return locked(
                "host",
                () -> {
                    var file = root.resolve("auth/host.json");
                    var value = read(file);
                    if (value.get("ext_agent_host_id") instanceof String id) return id;
                    String id = "urn:uuid:" + UUID.randomUUID();
                    write(file, Map.of("ext_agent_host_id", id));
                    return id;
                });
    }

    private Path accountPath(String id) {
        UUID.fromString(id);
        return root.resolve("auth/accounts").resolve(id + ".json");
    }

    private Path renewalPath(String id) {
        UUID.fromString(id);
        return root.resolve("auth").resolve(id + ".refresh-pending.json");
    }

    boolean refreshPending(String id) {
        return Files.exists(renewalPath(id), LinkOption.NOFOLLOW_LINKS);
    }

    void startRefresh(String id) throws IOException {
        write(renewalPath(id), Map.of("started_at", Instant.now().toString()));
    }

    void clearRefresh(String id) throws IOException {
        Files.deleteIfExists(renewalPath(id));
    }

    public Credentials load(String id) throws IOException {
        var value = read(accountPath(id));
        if (value.isEmpty())
            throw new IOException("Account unavailable; select an account or /connect");
        if (value.get("vault_ref") instanceof String reference) {
            if (vault == null)
                throw new IOException(
                        "This account uses Secret Service; enable and unlock that keyring to load it");
            try {
                Map<?, ?> secrets = json.fromString(Map.class, vault.get(reference));
                for (String field : List.of("access_token", "refresh_token", "id_token"))
                    value.put(field, secrets.get(field));
            } catch (RuntimeException e) {
                throw new IOException("Invalid system keyring record");
            }
        }
        var account =
                new ProviderAccount(
                        str(value, "provider"),
                        id,
                        str(value, "email"),
                        str(value, "issuer"),
                        str(value, "subject"),
                        str(value, "client_id"),
                        str(value, "host_id"));
        var scopes = new HashSet<String>();
        if (value.get("scopes") instanceof List<?> list)
            for (var scope : list) scopes.add(String.valueOf(scope));
        return new Credentials(
                account,
                Set.copyOf(scopes),
                str(value, "access_token"),
                str(value, "refresh_token"),
                str(value, "id_token"),
                instant(value, "received_at"),
                instant(value, "expires_at"),
                instant(value, "earliest_refresh_at"));
    }

    public void save(Credentials c) throws IOException {
        if ("dynamic_agent_client".equals(c.account().clientId()))
            throw new IOException("Registration has no issued client ID");
        var a = c.account();
        var value = new LinkedHashMap<String, Object>();
        value.put("provider", a.providerId());
        value.put("email", a.email());
        value.put("issuer", a.issuer());
        value.put("subject", a.subject());
        value.put("client_id", a.clientId());
        value.put("host_id", a.hostId());
        value.put("scopes", new ArrayList<>(c.scopes()));
        value.put("access_token", c.accessToken());
        value.put("refresh_token", c.refreshToken());
        value.put("id_token", c.idToken());
        value.put("received_at", c.receivedAt().toString());
        value.put("expires_at", c.expiresAt().toString());
        value.put("earliest_refresh_at", c.earliestRefreshAt().toString());
        String previous = str(read(accountPath(a.id())), "vault_ref");
        String reference = null;
        if (vault != null && c.connected()) {
            reference = UUID.randomUUID().toString();
            var secrets = new LinkedHashMap<String, Object>();
            for (String field : List.of("access_token", "refresh_token", "id_token"))
                secrets.put(field, value.remove(field));
            vault.put(reference, json.toString(secrets));
            value.put("vault_ref", reference);
        }
        try {
            write(accountPath(a.id()), value);
        } catch (IOException e) {
            if (reference != null) {
                try {
                    vault.delete(reference);
                } catch (IOException ignored) {
                }
            }
            throw e;
        }
        if (previous != null && vault != null) {
            try {
                vault.delete(previous);
            } catch (IOException ignored) {
                /* Current atomic record already points to the new token set. */
            }
        }
        clearRefresh(a.id());
    }

    public List<Credentials> accounts() throws IOException {
        var dir = root.resolve("auth/accounts");
        if (!Files.exists(dir)) return List.of();
        directory(dir);
        try (var files = Files.list(dir)) {
            var result = new ArrayList<Credentials>();
            for (var path :
                    files.filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))
                            .sorted()
                            .toList())
                result.add(load(path.getFileName().toString().substring(0, 36)));
            return List.copyOf(result);
        }
    }

    public static String str(Map<?, ?> value, String key) {
        return value.get(key) instanceof String s ? s : null;
    }

    private static Instant instant(Map<?, ?> value, String key) {
        var s = str(value, key);
        return s == null ? Instant.EPOCH : Instant.parse(s);
    }
}
