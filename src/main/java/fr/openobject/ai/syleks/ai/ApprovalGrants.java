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
package fr.openobject.ai.syleks.ai;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Exact, ephemeral user grants. No wildcard matching, serialization or model-controlled scope. */
public final class ApprovalGrants {
    public static final Duration LIFETIME = Duration.ofMinutes(15);

    public record Key(
            String agent,
            String operation,
            String target,
            String action,
            String workspace,
            String session,
            String task,
            String policyRevision,
            String context) {}

    public record Grant(String id, Key key, Instant expiresAt, long deadline) {}

    private final Map<Key, Grant> grants = new LinkedHashMap<>();
    private final LongSupplier ticker;
    private final Clock clock;

    public ApprovalGrants() {
        this(System::nanoTime, Clock.systemUTC());
    }

    ApprovalGrants(LongSupplier ticker, Clock clock) {
        this.ticker = ticker;
        this.clock = clock;
    }

    long now() {
        return ticker.getAsLong();
    }

    boolean fresh(long started) {
        return now() - started < LIFETIME.toNanos();
    }

    public synchronized Grant remember(Key key) {
        prune();
        if (grants.size() >= 1024) grants.remove(grants.keySet().iterator().next());
        var grant =
                new Grant(
                        UUID.randomUUID().toString(),
                        key,
                        clock.instant().plus(LIFETIME),
                        now() + LIFETIME.toNanos());
        grants.put(key, grant);
        return grant;
    }

    public synchronized boolean contains(Key key) {
        prune();
        return grants.containsKey(key);
    }

    public synchronized List<Grant> list() {
        prune();
        return List.copyOf(grants.values());
    }

    public synchronized void clear() {
        grants.clear();
    }

    public synchronized boolean revoke(String id) {
        return grants.values().removeIf(grant -> grant.id().equals(id));
    }

    private void prune() {
        long now = now();
        grants.values().removeIf(grant -> now - grant.deadline() >= 0);
    }

    static boolean rememberable(ToolCall call, String operation) {
        return call != null
                && !(call.name().equals("apply_patch")
                        && call.arguments().get("patch") instanceof String patch
                        && patch.lines().anyMatch(line -> line.startsWith("*** Delete File: ")))
                && Set.of(
                                "read",
                                "write",
                                "edit",
                                "apply_patch",
                                "bash",
                                "list",
                                "glob",
                                "grep",
                                "webfetch",
                                "websearch",
                                "skill",
                                "todoread",
                                "todowrite")
                        .contains(call.name())
                && Set.of(
                                "read",
                                "write",
                                "edit",
                                "apply_patch",
                                "bash",
                                "list",
                                "glob",
                                "grep",
                                "webfetch",
                                "websearch",
                                "skill",
                                "todoread",
                                "todowrite")
                        .contains(operation);
    }

    static String canonicalTarget(Path workspace, String operation, String target)
            throws Exception {
        if (Set.of("read", "write", "edit", "apply_patch", "list", "glob", "grep")
                .contains(operation)) {
            Path path = workspace.resolve(target).toAbsolutePath().normalize();
            var suffix = new ArrayDeque<Path>();
            while (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                suffix.addFirst(path.getFileName());
                path = path.getParent();
            }
            path = path.toRealPath();
            for (var part : suffix) path = path.resolve(part);
            return path.toString();
        }
        if (operation.equals("webfetch")) {
            var uri = java.net.URI.create(target).normalize();
            int port =
                    uri.getPort() < 0
                            ? (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80)
                            : uri.getPort();
            return uri.getScheme().toLowerCase(Locale.ROOT)
                    + "://"
                    + uri.getHost().toLowerCase(Locale.ROOT)
                    + ":"
                    + port
                    + (uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        }
        return target == null ? "" : target;
    }

    /** Stable type-tagged hashing; map order is irrelevant, payloads never appear in grant listings. */
    public static String digest(Object value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            encode(digest, value);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void text(MessageDigest digest, String tag, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((tag + bytes.length + ":").getBytes(StandardCharsets.UTF_8));
        digest.update(bytes);
    }

    private static void encode(MessageDigest digest, Object value) {
        if (value == null) text(digest, "null", "");
        else if (value instanceof Map<?, ?> map) {
            text(digest, "map", Integer.toString(map.size()));
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put((String) key, item));
            sorted.forEach(
                    (key, item) -> {
                        text(digest, "key", key);
                        encode(digest, item);
                    });
        } else if (value instanceof List<?> list) {
            text(digest, "list", Integer.toString(list.size()));
            list.forEach(item -> encode(digest, item));
        } else if (value instanceof String string) text(digest, "string", string);
        else if (value instanceof Boolean bool) text(digest, "boolean", bool.toString());
        else if (value instanceof Number number) text(digest, "number", number.toString());
        else throw new IllegalArgumentException("Unsupported approval argument type");
    }

    /** Freeze nested JSON before any validation, permission wait or extension invocation. */
    static Map<String, Object> freeze(Map<String, Object> arguments) {
        @SuppressWarnings("unchecked")
        var frozen = (Map<String, Object>) freezeValue(arguments, 0, new int[1]);
        return frozen;
    }

    private static Object freezeValue(Object value, int depth, int[] count) {
        if (depth > 64 || ++count[0] > 100000)
            throw new IllegalArgumentException("Tool arguments too complex");
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            map.forEach(
                    (key, item) -> {
                        if (!(key instanceof String text))
                            throw new IllegalArgumentException(
                                    "Tool argument keys must be strings");
                        copy.put(text, freezeValue(item, depth + 1, count));
                    });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<>();
            list.forEach(item -> copy.add(freezeValue(item, depth + 1, count)));
            return Collections.unmodifiableList(copy);
        }
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Short
                || value instanceof Byte
                || value instanceof java.math.BigInteger
                || value instanceof java.math.BigDecimal) return value;
        if ((value instanceof Double || value instanceof Float)
                && Double.isFinite(((Number) value).doubleValue())) return value;
        throw new IllegalArgumentException("Tool arguments must be immutable JSON values");
    }
}
