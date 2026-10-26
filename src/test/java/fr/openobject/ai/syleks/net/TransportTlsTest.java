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

import com.sun.net.httpserver.*;
import fr.openobject.ai.syleks.app.AppConfigFixtures;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransportTlsTest {
    @TempDir Path root;

    @Test
    void neverForwardsCredentialsAcrossOriginsOrDowngradesAndKeepsHostnameVerification()
            throws Exception {
        // Synthetic, short-lived localhost certificate. No production trust configuration changes.
        Path store = root.resolve("fixture.p12");
        var keytoolBuilder =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "keytool")
                                        .toString(),
                                "-genkeypair",
                                "-alias",
                                "fixture",
                                "-keyalg",
                                "RSA",
                                "-keysize",
                                "2048",
                                "-storetype",
                                "PKCS12",
                                "-keystore",
                                store.toString(),
                                "-storepass",
                                "fixture-pass",
                                "-keypass",
                                "fixture-pass",
                                "-dname",
                                "CN=localhost",
                                "-ext",
                                "SAN=dns:localhost",
                                "-validity",
                                "2",
                                "-noprompt")
                        .redirectErrorStream(true)
                        .redirectOutput(root.resolve("keytool.log").toFile());
        keytoolBuilder.environment().clear();
        var keytool = keytoolBuilder.start();
        assertTrue(keytool.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, keytool.exitValue(), () -> "Fixture certificate generation failed");
        var keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            keys.load(input, "fixture-pass".toCharArray());
        }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, "fixture-pass".toCharArray());
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        var tls = SSLContext.getInstance("TLS");
        tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        var source = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.setHttpsConfigurator(new HttpsConfigurator(tls));
        var tlsTrap = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tlsTrap.setHttpsConfigurator(new HttpsConfigurator(tls));
        var plaintextTrap = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var delivered = new AtomicInteger();
        var received = new AtomicInteger();
        HttpHandler trap =
                exchange -> {
                    delivered.incrementAndGet();
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                };
        tlsTrap.createContext("/", trap);
        plaintextTrap.createContext("/", trap);
        String httpsTarget = "https://localhost:" + tlsTrap.getAddress().getPort() + "/trap";
        String httpTarget = "http://127.0.0.1:" + plaintextTrap.getAddress().getPort() + "/trap";
        source.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        received.incrementAndGet();
                        assertEquals(
                                "Bearer fake-token",
                                exchange.getRequestHeaders().getFirst("Authorization"));
                        assertEquals(
                                "fake-key", exchange.getRequestHeaders().getFirst("X-Api-Key"));
                        exchange.getResponseHeaders()
                                .add(
                                        "Location",
                                        exchange.getRequestURI().getPath().equals("/downgrade")
                                                ? httpTarget
                                                : httpsTarget);
                        exchange.sendResponseHeaders(307, -1);
                    }
                });
        source.start();
        tlsTrap.start();
        plaintextTrap.start();
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            var config = AppConfigFixtures.defaults(mapper.instance(), root);
            try (var client =
                    config.configureProxy(
                                    "",
                                    HttpClient.newBuilder()
                                            .sslContext(tls)
                                            .followRedirects(HttpClient.Redirect.ALWAYS))
                            .build()) {
                for (String path : new String[] {"/cross-origin", "/downgrade"}) {
                    var uri =
                            URI.create("https://localhost:" + source.getAddress().getPort() + path);
                    var response =
                            client.send(
                                    HttpRequest.newBuilder(TransportSecurity.requireSecure(uri))
                                            .timeout(Duration.ofSeconds(5))
                                            .header("Authorization", "Bearer fake-token")
                                            .header("X-Api-Key", "fake-key")
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding());
                    assertEquals(307, response.statusCode());
                }
                // The trusted certificate has only a localhost DNS SAN, so an IP URL must still
                // fail.
                assertThrows(
                        SSLHandshakeException.class,
                        () ->
                                client.send(
                                        HttpRequest.newBuilder(
                                                        URI.create(
                                                                "https://127.0.0.1:"
                                                                        + source.getAddress()
                                                                                .getPort()
                                                                        + "/"))
                                                .timeout(Duration.ofSeconds(5))
                                                .GET()
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding()));
                assertEquals(2, received.get());
                assertEquals(
                        0,
                        delivered.get(),
                        "Neither redirect target may receive any request or credential");
            }
        } finally {
            source.stop(0);
            tlsTrap.stop(0);
            plaintextTrap.stop(0);
        }
    }
}
