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

import java.net.URI;
import java.util.Locale;

/** Transport policy only: no DNS resolution or private-network destination authorization. */
public final class TransportSecurity {
    private TransportSecurity() {}

    /** Immutable global authority; callers must never populate this from project settings. */
    public record Policy(java.util.Set<String> insecureHttpOrigins) {
        public Policy {
            insecureHttpOrigins = java.util.Set.copyOf(insecureHttpOrigins);
            // Revalidate even directly constructed policies. All stored entries are normalized.
            var normalized = new java.util.HashSet<String>();
            for (String value : insecureHttpOrigins) normalized.add(reviewedOrigin(value));
            insecureHttpOrigins = java.util.Set.copyOf(normalized);
        }

        public static Policy parse(Object value) {
            if (!(value instanceof java.util.List<?> entries) || entries.size() > 64)
                throw new IllegalArgumentException(
                        "insecure_http_origins must be an array of at most 64 exact HTTP origins");
            var origins = new java.util.HashSet<String>();
            for (Object entry : entries) {
                if (!(entry instanceof String text))
                    throw new IllegalArgumentException(
                            "insecure_http_origins entries must be exact HTTP origins");
                origins.add(reviewedOrigin(text));
            }
            return new Policy(origins);
        }

        public URI requireAllowed(URI uri) {
            validate(uri);
            if (isException(uri)) return uri;
            return requireSecure(uri);
        }

        public boolean isException(URI uri) {
            return uri != null
                    && uri.getHost() != null
                    && "http".equalsIgnoreCase(uri.getScheme())
                    && !isLoopback(uri)
                    && insecureHttpOrigins.contains(origin(uri));
        }

        public String warning(URI uri) {
            return isException(uri)
                    ? "INSECURE HTTP: "
                            + origin(uri)
                            + " — credentials, prompts and tool data can be observed or changed in transit."
                    : "";
        }

        public java.util.List<String> warnings() {
            return insecureHttpOrigins.stream()
                    .sorted()
                    .map(URI::create)
                    .map(this::warning)
                    .filter(value -> !value.isEmpty())
                    .toList();
        }
    }

    private static String reviewedOrigin(String value) {
        try {
            URI uri = URI.create(value);
            validate(uri);
            if (!"http".equalsIgnoreCase(uri.getScheme())
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")))
                throw new IllegalArgumentException();
            return origin(uri);
        } catch (IllegalArgumentException invalid) {
            // Do not echo malformed input: it could contain URL credentials or query secrets.
            throw new IllegalArgumentException(
                    "Invalid insecure_http_origins entry: expected an exact http://host[:port] origin without a path, credentials, query or fragment");
        }
    }

    /** Reject ambiguous authorities and URL secrets before building any request. */
    public static URI requireSecure(URI uri) {
        validate(uri);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && isLoopback(uri))) {
            throw new IllegalArgumentException(
                    "Transport requires HTTPS for "
                            + origin(uri)
                            + " (HTTP allowed only for loopback development endpoints)");
        }
        return uri;
    }

    private static void validate(URI uri) {
        if (uri == null
                || uri.getScheme() == null
                || uri.isOpaque()
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getPort() < -1
                || uri.getPort() == 0
                || uri.getPort() > 65535
                || uri.getRawAuthority().endsWith(":")) {
            throw new IllegalArgumentException(
                    "Invalid endpoint: expected an HTTP(S) URL without credentials, query or fragment and a valid port");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
            throw new IllegalArgumentException("Invalid endpoint: expected HTTP(S)");
    }

    /** Literal classification; mapped/scoped IPv6 and DNS suffixes are deliberately excluded. */
    public static boolean isLoopback(URI uri) {
        String host = uri.getHost();
        if (host == null) return false;
        if (host.equalsIgnoreCase("localhost")) return true;
        if (host.startsWith("[") && host.endsWith("]") && !host.contains("%")) {
            String literal = host.substring(1, host.length() - 1);
            if (!literal.matches("[0-9a-fA-F:]+")) return false;
            try {
                var address = java.net.InetAddress.getByName(literal);
                return address instanceof java.net.Inet6Address && address.isLoopbackAddress();
            } catch (java.net.UnknownHostException invalid) {
                return false;
            }
        }
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4 || !octets[0].equals("127")) return false;
        for (String octet : octets) {
            if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) return false;
        }
        return true;
    }

    /** No path, query, fragment or userinfo ever appears in a diagnostic. */
    public static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port == -1) port = scheme.equals("https") ? 443 : 80;
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }
}
