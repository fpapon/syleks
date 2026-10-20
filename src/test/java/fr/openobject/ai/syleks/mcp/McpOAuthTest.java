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
package fr.openobject.ai.syleks.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpOAuthTest {
    @TempDir Path home;

    @Test
    void discoversPkceAuthorizesBindsResourceRefreshesAndSeparatesCredentials() throws Exception {
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var challenge = new AtomicReference<String>();
        var refreshes = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        server.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        assertNull(
                                exchange.getRequestHeaders().getFirst("Authorization"),
                                "Tokens must not reach discovery or OAuth endpoints");
                        String path = exchange.getRequestURI().getPath();
                        Object result;
                        if (path.equals("/mcp")) {
                            exchange.getResponseHeaders()
                                    .add(
                                            "WWW-Authenticate",
                                            "Bearer resource_metadata=\"" + base + "/metadata\"");
                            exchange.sendResponseHeaders(401, -1);
                            return;
                        } else if (path.equals("/metadata"))
                            result =
                                    Map.of(
                                            "resource",
                                            base + "/mcp",
                                            "authorization_servers",
                                            List.of(base));
                        else if (path.equals("/.well-known/oauth-authorization-server"))
                            result =
                                    Map.of(
                                            "issuer",
                                            base,
                                            "authorization_endpoint",
                                            base + "/authorize",
                                            "token_endpoint",
                                            base + "/token",
                                            "registration_endpoint",
                                            base + "/register",
                                            "code_challenge_methods_supported",
                                            List.of("S256"));
                        else if (path.equals("/register")) {
                            var registration = json.readTree(exchange.getRequestBody());
                            assertEquals(
                                    "none",
                                    registration.path("token_endpoint_auth_method").asText());
                            assertTrue(
                                    registration
                                            .path("redirect_uris")
                                            .get(0)
                                            .asText()
                                            .startsWith("http://127.0.0.1:"));
                            result = Map.of("client_id", "registered-client");
                        } else if (path.equals("/token")) {
                            var params =
                                    query(
                                            new String(
                                                    exchange.getRequestBody().readAllBytes(),
                                                    StandardCharsets.UTF_8));
                            assertEquals(base + "/mcp", params.get("resource"));
                            assertEquals("registered-client", params.get("client_id"));
                            if (params.get("grant_type").equals("authorization_code")) {
                                assertEquals("fixture-code", params.get("code"));
                                assertEquals(
                                        challenge.get(),
                                        Base64.getUrlEncoder()
                                                .withoutPadding()
                                                .encodeToString(
                                                        MessageDigest.getInstance("SHA-256")
                                                                .digest(
                                                                        params.get("code_verifier")
                                                                                .getBytes(
                                                                                        StandardCharsets
                                                                                                .UTF_8))));
                                result =
                                        Map.of(
                                                "access_token",
                                                "first-mcp-token",
                                                "refresh_token",
                                                "first-refresh",
                                                "token_type",
                                                "Bearer",
                                                "expires_in",
                                                1);
                            } else {
                                refreshes.incrementAndGet();
                                assertEquals("first-refresh", params.get("refresh_token"));
                                result =
                                        Map.of(
                                                "access_token",
                                                "refreshed-mcp-token",
                                                "refresh_token",
                                                "rotated-refresh",
                                                "token_type",
                                                "Bearer",
                                                "expires_in",
                                                3600);
                            }
                        } else throw new AssertionError("Unexpected OAuth path " + path);
                        byte[] bytes = json.writeValueAsBytes(result);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                });
        server.start();
        try {
            var config =
                    McpServerConfig.parse(
                                    Map.of(
                                            "remote",
                                            Map.of(
                                                    "type",
                                                    "http",
                                                    "url",
                                                    base + "/mcp",
                                                    "oauth",
                                                    true)),
                                    home)
                            .get("remote");
            var oauth = new McpOAuth(config, home);
            assertThrows(Exception.class, oauth::accessToken);
            oauth.login(
                    uri -> {
                        try {
                            var params = query(uri.getRawQuery());
                            assertEquals(base + "/mcp", params.get("resource"));
                            assertEquals("S256", params.get("code_challenge_method"));
                            challenge.set(params.get("code_challenge"));
                            try (var http = HttpClient.newHttpClient()) {
                                URI callback =
                                        URI.create(
                                                params.get("redirect_uri")
                                                        + "?code=fixture-code&state="
                                                        + params.get("state"));
                                var wrong =
                                        http.send(
                                                HttpRequest.newBuilder(
                                                                URI.create(
                                                                        params.get("redirect_uri")
                                                                                + "?code=bad&state=wrong"))
                                                        .GET()
                                                        .build(),
                                                HttpResponse.BodyHandlers.discarding());
                                assertEquals(400, wrong.statusCode());
                                assertEquals(
                                        200,
                                        http.send(
                                                        HttpRequest.newBuilder(callback)
                                                                .GET()
                                                                .build(),
                                                        HttpResponse.BodyHandlers.discarding())
                                                .statusCode());
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
            assertEquals("refreshed-mcp-token", oauth.accessToken());
            assertEquals(1, refreshes.get());
            assertEquals("refreshed-mcp-token", new McpOAuth(config, home).accessToken());
            assertEquals(1, refreshes.get());
            try (var files = Files.list(home.resolve(".config/syleks/mcp-auth"))) {
                var credential =
                        files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
                assertEquals(
                        "rw-------",
                        java.nio.file.attribute.PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(credential)));
                String content = Files.readString(credential);
                assertTrue(content.contains("rotated-refresh"));
                assertFalse(content.contains("first-refresh"));
            }
            var other =
                    McpServerConfig.parse(
                                    Map.of(
                                            "other",
                                            Map.of(
                                                    "type",
                                                    "http",
                                                    "url",
                                                    base + "/other",
                                                    "oauth",
                                                    true)),
                                    home)
                            .get("other");
            assertThrows(Exception.class, new McpOAuth(other, home)::accessToken);
            oauth.logout();
            assertThrows(Exception.class, oauth::accessToken);
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, String> query(String value) {
        var result = new HashMap<String, String>();
        for (String item : value.split("&")) {
            String[] pair = item.split("=", 2);
            result.put(
                    URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }
}
