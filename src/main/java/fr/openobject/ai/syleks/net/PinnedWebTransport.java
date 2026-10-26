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

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Direct HTTP/1.1 GET transport. DNS answers are authorized once and sockets use those bytes. */
public final class PinnedWebTransport {
    private static final int MAX_BYTES = 1_000_000;
    private final WebDestinationPolicy policy;
    private final Resolver resolver;
    private final SSLSocketFactory tls;

    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    public PinnedWebTransport(WebDestinationPolicy policy) {
        this(policy, InetAddress::getAllByName, (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    PinnedWebTransport(WebDestinationPolicy policy, Resolver resolver, SSLSocketFactory tls) {
        this.policy = policy;
        this.resolver = resolver;
        this.tls = tls;
    }

    public HttpResponse<InputStream> send(HttpRequest request)
            throws IOException, InterruptedException {
        var task = new FutureTask<>(() -> exchange(request));
        Thread.ofVirtual().name("web-pinned-get").start(task);
        try {
            return task.get(25, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new IOException("Web request deadline exceeded");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) throw io;
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Web request failed");
        } finally {
            // Interruptible virtual-thread sockets close on cancellation, including TLS reads.
            task.cancel(true);
        }
    }

    private HttpResponse<InputStream> exchange(HttpRequest request) throws IOException {
        URI uri = URI.create(WebDestinationPolicy.normalize(request.uri()).toASCIIString());
        String host = uri.getHost().replace("[", "").replace("]", "");
        var answers = resolver.resolve(host);
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Web request cancelled");
        policy.requireAllowed(uri, answers);
        int port =
                uri.getPort() == -1 ? (uri.getScheme().equals("https") ? 443 : 80) : uri.getPort();
        // Proxy.NO_PROXY is intentional: a resolving proxy would invalidate the IP authorization.
        // Neither the default ProxySelector nor JVM/environment proxy settings are consulted.
        try (var plain = new Socket(Proxy.NO_PROXY)) {
            plain.connect(new InetSocketAddress(answers[0], port), 15_000);
            plain.setSoTimeout(25_000);
            if (!plain.getInetAddress().equals(answers[0]))
                throw new IOException("Web peer mismatch");
            if (uri.getScheme().equals("https")) {
                try (var socket = (SSLSocket) tls.createSocket(plain, host, port, true)) {
                    var parameters = socket.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    // DNS host is retained for certificate validation and SNI. IP literals omit
                    // SNI.
                    if (!host.contains(":") && !host.matches("[0-9.]+"))
                        parameters.setServerNames(List.of(new SNIHostName(host)));
                    socket.setSSLParameters(parameters);
                    socket.startHandshake();
                    return get(request, uri, socket);
                }
            }
            return get(request, uri, plain);
        }
    }

    private HttpResponse<InputStream> get(HttpRequest request, URI uri, Socket socket)
            throws IOException {
        String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        String message =
                "GET "
                        + path
                        + " HTTP/1.1\r\nHost: "
                        + uri.getRawAuthority()
                        + "\r\nUser-Agent: Syleks/0.1\r\nAccept: text/html,text/plain,application/json"
                        + "\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(message.getBytes(StandardCharsets.US_ASCII));
        var input = new BufferedInputStream(socket.getInputStream());
        var budget = new int[] {65_536};
        String status = line(input, budget);
        if (!status.matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?"))
            throw new IOException("Invalid HTTP status");
        int code = Integer.parseInt(status.substring(9, 12));
        var headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        for (String line; !(line = line(input, budget)).isEmpty(); ) {
            int colon = line.indexOf(':');
            if (colon < 1 || !line.substring(0, colon).matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+"))
                throw new IOException("Invalid HTTP header");
            headers.computeIfAbsent(line.substring(0, colon), ignored -> new ArrayList<>())
                    .add(line.substring(colon + 1).trim());
        }
        byte[] body = new byte[0];
        // Redirect/error bodies are deliberately never consumed or included in diagnostics.
        if (code == 200) {
            String encoding = first(headers, "Content-Encoding");
            if (encoding != null && !encoding.equalsIgnoreCase("identity"))
                throw new IOException("Unsupported HTTP content encoding");
            String transfer = first(headers, "Transfer-Encoding");
            String length = first(headers, "Content-Length");
            if (transfer != null) {
                if (!transfer.equalsIgnoreCase("chunked")
                        || length != null
                        || headers.get("Transfer-Encoding").size() != 1)
                    throw new IOException("Unsupported HTTP framing");
                var bytes = new ByteArrayOutputStream();
                while (true) {
                    String size = line(input, budget).split(";", 2)[0];
                    long count;
                    try {
                        if (!size.matches("[0-9a-fA-F]{1,8}")) throw new NumberFormatException();
                        count = Long.parseLong(size, 16);
                    } catch (NumberFormatException invalid) {
                        throw new IOException("Invalid HTTP chunk");
                    }
                    if (count == 0) break;
                    if (count > MAX_BYTES - bytes.size())
                        throw new IOException("Response exceeds 1 MB");
                    bytes.write(exact(input, (int) count));
                    if (!line(input, budget).isEmpty())
                        throw new IOException("Invalid HTTP chunk ending");
                }
                body = bytes.toByteArray();
            } else if (length != null) {
                long count;
                try {
                    if (headers.get("Content-Length").size() != 1 || !length.matches("[0-9]{1,10}"))
                        throw new NumberFormatException();
                    count = Long.parseLong(length);
                } catch (NumberFormatException invalid) {
                    throw new IOException("Invalid HTTP length");
                }
                if (count > MAX_BYTES) throw new IOException("Response exceeds 1 MB");
                body = exact(input, (int) count);
            } else {
                body = input.readNBytes(MAX_BYTES + 1);
                if (body.length > MAX_BYTES) throw new IOException("Response exceeds 1 MB");
            }
        }
        return new Response(
                request,
                code,
                HttpHeaders.of(headers, (k, v) -> true),
                new ByteArrayInputStream(body));
    }

    private static String first(Map<String, List<String>> headers, String name) {
        var values = headers.get(name);
        return values == null ? null : values.getFirst();
    }

    private static byte[] exact(InputStream input, int size) throws IOException {
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new IOException("Truncated HTTP body");
        return bytes;
    }

    private static String line(InputStream input, int[] budget) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int c; (c = input.read()) != -1; ) {
            if (--budget[0] < 0) throw new IOException("HTTP framing exceeds limit");
            if (c == '\r') {
                if (input.read() != '\n' || --budget[0] < 0)
                    throw new IOException("Invalid HTTP line");
                return bytes.toString(StandardCharsets.ISO_8859_1);
            }
            if (c == '\n' || c == 0) throw new IOException("Invalid HTTP line");
            bytes.write(c);
        }
        throw new IOException("Truncated HTTP headers");
    }

    private record Response(
            HttpRequest request, int statusCode, HttpHeaders headers, InputStream body)
            implements HttpResponse<InputStream> {
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        public URI uri() {
            return request.uri();
        }

        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
