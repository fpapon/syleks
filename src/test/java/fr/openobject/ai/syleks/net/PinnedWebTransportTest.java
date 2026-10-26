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
package fr.openobject.ai.syleks.net;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import fr.openobject.ai.syleks.ai.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.SSLSocketFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PinnedWebTransportTest {
    @TempDir Path workspace;

    private PinnedWebTransport transport(
            WebDestinationPolicy policy, PinnedWebTransport.Resolver resolver) {
        return new PinnedWebTransport(
                policy, resolver, (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    @Test
    void deniedAndMixedAnswersNeverReachListenerAndGrantPinsTheResolvedAddress() throws Exception {
        var hits = new AtomicInteger();
        var lookups = new AtomicInteger();
        var hostHeader = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        hits.incrementAndGet();
                        hostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
                        exchange.sendResponseHeaders(200, 0); // Exercise chunked decoding.
                        exchange.getResponseBody().write("fixture".getBytes());
                    }
                });
        server.start();
        try {
            String origin = "http://alias.invalid:" + server.getAddress().getPort();
            var request = HttpRequest.newBuilder(URI.create(origin + "/path?q=synthetic")).build();
            var loopback = InetAddress.getByName("127.0.0.1");
            var publicOnly = new WebDestinationPolicy(Set.of());
            assertThrows(
                    IOException.class,
                    () -> transport(publicOnly, h -> new InetAddress[] {loopback}).send(request));
            assertThrows(
                    IOException.class,
                    () ->
                            transport(
                                            publicOnly,
                                            h ->
                                                    new InetAddress[] {
                                                        InetAddress.getByName("8.8.8.8"), loopback
                                                    })
                                    .send(request));
            assertEquals(0, hits.get());
            var pinned =
                    transport(
                            new WebDestinationPolicy(Set.of(origin)),
                            h -> {
                                assertEquals("alias.invalid", h);
                                return new InetAddress[] {
                                    lookups.getAndIncrement() == 0
                                            ? loopback
                                            : InetAddress.getByName("127.0.0.2")
                                };
                            });
            var previousProxy = ProxySelector.getDefault();
            ProxySelector.setDefault(
                    new ProxySelector() {
                        public List<Proxy> select(URI uri) {
                            throw new AssertionError("Web requests must connect directly");
                        }

                        public void connectFailed(
                                URI uri, SocketAddress address, IOException failure) {
                            fail("Unexpected proxy");
                        }
                    });
            try {
                try (var body = pinned.send(request).body()) {
                    assertEquals("fixture", new String(body.readAllBytes()));
                }
            } finally {
                ProxySelector.setDefault(previousProxy);
            }
            assertEquals(1, lookups.get(), "Connect must not resolve the hostname again");
            assertEquals(1, hits.get());
            assertEquals("alias.invalid:" + server.getAddress().getPort(), hostHeader.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void directTransportDoesNotDelegateResolutionToAProxy() throws Exception {
        var selected = new AtomicInteger();
        var previous = ProxySelector.getDefault();
        ProxySelector.setDefault(
                new ProxySelector() {
                    public List<Proxy> select(URI uri) {
                        selected.incrementAndGet();
                        throw new AssertionError("Proxy must not resolve web destinations");
                    }

                    public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                        fail("Unexpected proxy");
                    }
                });
        try {
            var transport =
                    transport(
                            new WebDestinationPolicy(Set.of()),
                            h -> new InetAddress[] {InetAddress.getByName("127.0.0.1")});
            assertThrows(
                    IOException.class,
                    () ->
                            transport.send(
                                    HttpRequest.newBuilder(
                                                    URI.create("http://proxy-resolves.invalid"))
                                            .build()));
            assertEquals(0, selected.get());
        } finally {
            ProxySelector.setDefault(previous);
        }
    }

    @Test
    void redirectsRecheckNetworkPolicyAndPortAndUrlPermissionsBeforeRequests() throws Exception {
        var sourceHits = new AtomicInteger();
        var trapHits = new AtomicInteger();
        var source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var trap = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String target = "http://127.0.0.1:" + trap.getAddress().getPort() + "/trap";
        source.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        sourceHits.incrementAndGet();
                        exchange.getResponseHeaders().add("Location", target);
                        exchange.sendResponseHeaders(302, -1);
                    }
                });
        trap.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        trapHits.incrementAndGet();
                        var bytes = "SYNTHETIC-PRIVATE-BODY".getBytes();
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                });
        source.start();
        trap.start();
        try {
            String origin = "http://127.0.0.1:" + source.getAddress().getPort();
            var call = new ToolCall("1", "webfetch", Map.of("url", origin + "/redirect"));
            var tools = new AgentTools(workspace, new WebDestinationPolicy(Set.of(origin)));
            var error =
                    assertThrows(IOException.class, () -> tools.execute(call, (a, b, c) -> true));
            assertFalse(error.getMessage().contains("SYNTHETIC-PRIVATE-BODY"));
            assertEquals(1, sourceHits.get());
            assertEquals(0, trapHits.get());
            var both =
                    new AgentTools(
                            workspace,
                            new WebDestinationPolicy(Set.of(origin, target.replace("/trap", ""))));
            assertThrows(
                    ToolPermissionDeniedException.class,
                    () -> both.execute(call, (a, b, c) -> !b.equals(target)));
            assertEquals(0, trapHits.get());
            assertEquals("SYNTHETIC-PRIVATE-BODY", both.execute(call, (a, b, c) -> true));
            assertEquals(1, trapHits.get());
            assertThrows(
                    IOException.class,
                    () -> new AgentTools(workspace).execute(call, (a, b, c) -> true));
            assertEquals(3, sourceHits.get()); // Default denial did not reach source.
        } finally {
            source.stop(0);
            trap.stop(0);
        }
    }

    @Test
    void boundsAndRejectsAmbiguousOrTruncatedWireResponses() throws Exception {
        for (String reply :
                List.of(
                        "HTTP/1.1 200 OK\r\nContent-Length: 1000001\r\n\r\n",
                        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1000000\r\n",
                        "HTTP/1.1 200 OK\r\nContent-Length: 7\r\nContent-Length: 7\r\n\r\nfixture",
                        "HTTP/1.1 200 OK\r\nContent-Length: 7\r\nTransfer-Encoding: chunked\r\n\r\n",
                        "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n",
                        "HTTP/1.1 200 OK\r\nContent-Length: 7\r\n\r\nshort",
                        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n7\r\nshort",
                        "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\n",
                        "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n",
                        "HTTP/1.1 200 OK\nContent-Length: 7\n\nfixture",
                        "HTTP/1.1 200 OK\r\nX-Large: " + "x".repeat(65_536) + "\r\n\r\n",
                        "HTTP/1.1 200 OK\r\n\r\n" + "x".repeat(1_000_001))) {
            assertThrows(IOException.class, () -> wireReply(reply));
        }
        assertEquals("fixture", wireReply("HTTP/1.1 200 OK\r\n\r\nfixture"));
        assertEquals("fixture", wireReply("HTTP/1.1 200 OK\r\nContent-Length: 7\r\n\r\nfixture"));
        assertEquals(
                "fixture",
                wireReply(
                        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nfix\r\n4;ext=ok\r\nture\r\n0\r\n\r\n"));
    }

    private String wireReply(String reply) throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            var worker =
                    new FutureTask<Void>(
                            () -> {
                                try (var peer = server.accept()) {
                                    peer.setSoTimeout(5000);
                                    var reader =
                                            new BufferedReader(
                                                    new InputStreamReader(peer.getInputStream()));
                                    while (!reader.readLine().isEmpty()) {}
                                    try {
                                        peer.getOutputStream()
                                                .write(
                                                        reply.getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .US_ASCII));
                                    } catch (SocketException closedByClient) {
                                        /* Rejection may close before write completes. */
                                    }
                                }
                                return null;
                            });
            Thread.ofVirtual().start(worker);
            String origin = "http://127.0.0.1:" + server.getLocalPort();
            try {
                var transport = new PinnedWebTransport(new WebDestinationPolicy(Set.of(origin)));
                try (var body =
                        transport.send(HttpRequest.newBuilder(URI.create(origin)).build()).body()) {
                    return new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            } finally {
                worker.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void cancellationClosesAnInFlightConnection() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            String origin = "http://127.0.0.1:" + server.getLocalPort();
            var client = new PinnedWebTransport(new WebDestinationPolicy(Set.of(origin)));
            var result = new CompletableFuture<Throwable>();
            var worker =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            client.send(
                                                    HttpRequest.newBuilder(URI.create(origin))
                                                            .build());
                                            result.complete(null);
                                        } catch (Throwable failure) {
                                            result.complete(failure);
                                        }
                                    });
            server.setSoTimeout(5000);
            try (var peer = server.accept()) {
                peer.setSoTimeout(5000);
                var reader = new BufferedReader(new InputStreamReader(peer.getInputStream()));
                while (!reader.readLine().isEmpty()) {}
                worker.interrupt();
                assertInstanceOf(InterruptedException.class, result.get(5, TimeUnit.SECONDS));
                assertEquals(
                        -1, peer.getInputStream().read(), "Cancellation must close the socket");
            }
        }
    }
}
