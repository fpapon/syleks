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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApprovalGrantsTest {
    @TempDir Path root;

    private ApprovalGrants.Key key(
            String agent,
            String op,
            String target,
            String action,
            String workspace,
            String session,
            String task,
            String policy,
            String context) {
        return new ApprovalGrants.Key(
                agent, op, target, action, workspace, session, task, policy, context);
    }

    @Test
    void exactIdentityIncludesEveryBoundaryAndNeverUsesWildcards() {
        var store = new ApprovalGrants();
        var original =
                key(
                        "build",
                        "write",
                        "/project/*.txt",
                        "payload-A",
                        "/project",
                        "session1",
                        "child1",
                        "policy1",
                        "account1");
        store.remember(original);
        assertTrue(store.contains(original));
        for (var changed :
                List.of(
                        key(
                                "plan",
                                "write",
                                "/project/*.txt",
                                "payload-A",
                                "/project",
                                "session1",
                                "child1",
                                "policy1",
                                "account1"),
                        key(
                                "build",
                                "edit",
                                "/project/*.txt",
                                "payload-A",
                                "/project",
                                "session1",
                                "child1",
                                "policy1",
                                "account1"),
                        key(
                                "build",
                                "write",
                                "/project/other.txt",
                                "payload-A",
                                "/project",
                                "session1",
                                "child1",
                                "policy1",
                                "account1"),
                        key(
                                "build",
                                "write",
                                "/project/*.txt",
                                "payload-B",
                                "/project",
                                "session1",
                                "child1",
                                "policy1",
                                "account1"),
                        key(
                                "build",
                                "write",
                                "/project/*.txt",
                                "payload-A",
                                "/other",
                                "session1",
                                "child1",
                                "policy1",
                                "account1"),
                        key(
                                "build",
                                "write",
                                "/project/*.txt",
                                "payload-A",
                                "/project",
                                "session2",
                                "child1",
                                "policy1",
                                "account1"),
                        key(
                                "build",
                                "write",
                                "/project/*.txt",
                                "payload-A",
                                "/project",
                                "session1",
                                "child2",
                                "policy1",
                                "account1"),
                        key(
                                "build",
                                "write",
                                "/project/*.txt",
                                "payload-A",
                                "/project",
                                "session1",
                                "child1",
                                "policy2",
                                "account1"),
                        key(
                                "build",
                                "write",
                                "/project/*.txt",
                                "payload-A",
                                "/project",
                                "session1",
                                "child1",
                                "policy1",
                                "account2")))
            assertFalse(store.contains(changed), changed.toString());
    }

    @Test
    void monotonicExpiryAndRevocationDoNotExtendOnReuse() {
        var ticks = new AtomicLong();
        var store = new ApprovalGrants(ticks::get, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var key = key("build", "bash", "printf ok", "hash", "/project", "s", "primary", "p", "c");
        var grant = store.remember(key);
        assertEquals(Instant.EPOCH.plusSeconds(900), grant.expiresAt());
        ticks.set(ApprovalGrants.LIFETIME.toNanos() - 1);
        assertTrue(store.contains(key));
        ticks.incrementAndGet();
        assertFalse(store.contains(key));
        assertTrue(store.list().isEmpty());
        grant = store.remember(key);
        assertTrue(store.revoke(grant.id()));
        assertFalse(store.contains(key));
        assertFalse(store.revoke(grant.id()));
        store.remember(key);
        store.clear();
        assertFalse(store.contains(key));
    }

    @Test
    void canonicalTargetsKeepLiteralMetacharactersAndDifferentDestinationsSeparate()
            throws Exception {
        Files.createDirectories(root.resolve("directory"));
        assertEquals(
                root.resolve("directory/*.txt").toString(),
                ApprovalGrants.canonicalTarget(root, "write", "directory/./*.txt"));
        assertEquals(
                "http://example.test:80/path?q=one",
                ApprovalGrants.canonicalTarget(root, "webfetch", "http://EXAMPLE.test/path?q=one"));
        assertNotEquals(
                ApprovalGrants.canonicalTarget(root, "webfetch", "http://example.test/path?q=one"),
                ApprovalGrants.canonicalTarget(root, "webfetch", "http://example.test/path?q=two"));
        assertEquals("printf  x", ApprovalGrants.canonicalTarget(root, "bash", "printf  x"));
    }

    @Test
    void freezesNestedArgumentsAndHashesAllPayloadWithoutDependingOnMapOrder() {
        var nested = new ArrayList<Object>(List.of("before"));
        var args = new LinkedHashMap<String, Object>();
        args.put("items", nested);
        args.put("path", "target");
        var call = new ToolCall("one", "custom_fixture", args);
        String identity = ApprovalGrants.digest(call.arguments());
        nested.set(0, "after");
        args.put("path", "other");
        assertEquals(identity, ApprovalGrants.digest(call.arguments()));
        assertEquals(
                identity,
                ApprovalGrants.digest(Map.of("path", "target", "items", List.of("before"))));
        assertNotEquals(identity, ApprovalGrants.digest(args));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ((List<?>) call.arguments().get("items")).clear());
    }

    @Test
    void irreversibleAndUnknownEffectsNeverOfferMemory() {
        for (String name :
                List.of("task", "chat", "save", "question", "custom_fixture", "mcp_fixture_tool"))
            assertFalse(ApprovalGrants.rememberable(new ToolCall("id", name, Map.of()), name));
        assertTrue(
                ApprovalGrants.rememberable(new ToolCall("id", "apply_patch", Map.of()), "edit"));
    }
}
