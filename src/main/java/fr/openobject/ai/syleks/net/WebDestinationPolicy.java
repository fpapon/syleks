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

import java.io.IOException;
import java.net.*;
import java.util.*;

/** Global-only authority for model-directed web tools, independent of URL permission gates. */
public record WebDestinationPolicy(Set<String> privateOrigins) {
    public WebDestinationPolicy {
        var normalized = new HashSet<String>();
        for (String entry : privateOrigins) {
            URI uri;
            try {
                uri = normalize(URI.create(entry));
                if (!(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                        || uri.getRawQuery() != null
                        || uri.getRawFragment() != null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "web_private_origins requires exact HTTP(S) origins");
            }
            normalized.add(TransportSecurity.origin(uri));
        }
        privateOrigins = Set.copyOf(normalized);
    }

    public String description() {
        return "Web destinations: public addresses; private/loopback origins: "
                + (privateOrigins.isEmpty()
                        ? "none"
                        : String.join(", ", privateOrigins.stream().sorted().toList()))
                + "; metadata/link-local/reserved destinations denied; direct connections (no proxy).";
    }

    public static WebDestinationPolicy parse(Object value) {
        if (!(value instanceof List<?> entries)
                || entries.size() > 64
                || entries.stream().anyMatch(entry -> !(entry instanceof String)))
            throw new IllegalArgumentException(
                    "web_private_origins must be an array of at most 64 origins");
        return new WebDestinationPolicy(
                new HashSet<>(entries.stream().map(String.class::cast).toList()));
    }

    public static URI normalize(URI uri) {
        String host = uri.getHost();
        if (host == null
                || uri.isOpaque()
                || uri.getRawUserInfo() != null
                || !("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getRawFragment() != null
                || uri.getPort() < -1
                || uri.getPort() == 0
                || uri.getPort() > 65535
                || uri.getRawAuthority().endsWith(":"))
            throw new IllegalArgumentException(
                    "Expected HTTP(S) URL with a valid host/port and no credentials");
        host = host.toLowerCase(Locale.ROOT);
        if (host.contains("%"))
            throw new IllegalArgumentException("Scoped addresses are unsupported");
        if (host.startsWith("[")) {
            try {
                String literal =
                        InetAddress.getByName(host.substring(1, host.length() - 1))
                                .getHostAddress();
                host = literal.contains(":") ? "[" + literal + "]" : literal;
            } catch (UnknownHostException invalid) {
                throw new IllegalArgumentException("Invalid web address");
            }
        }
        if (!host.startsWith("[") && host.endsWith("."))
            host = host.substring(0, host.length() - 1);
        if (!host.startsWith("[")
                && (!host.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)*") || host.length() > 253))
            throw new IllegalArgumentException("Invalid web hostname");
        return URI.create(
                uri.getScheme().toLowerCase(Locale.ROOT)
                        + "://"
                        + host
                        + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
                        + uri.getRawPath()
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
    }

    /** All answers must be public unless the user reviewed this exact origin. */
    public void requireAllowed(URI uri, InetAddress[] answers) throws IOException {
        if (answers.length == 0) throw new IOException("Web destination has no addresses");
        boolean granted = privateOrigins.contains(TransportSecurity.origin(normalize(uri)));
        for (var answer : answers) {
            // Metadata/link-local, unspecified, multicast and reserved ranges remain denied even
            // with a service grant. A grant authorizes only ordinary private/loopback services.
            int kind = addressClass(answer);
            if (kind == 2 || (kind == 1 && !granted))
                throw new Denied(
                        "Web destination denied by network policy: "
                                + TransportSecurity.origin(uri));
        }
    }

    public static final class Denied extends IOException {
        private Denied(String message) {
            super(message);
        }
    }

    private static final class InetAddressBytes {
        private static final byte[] AWS_METADATA = {
            (byte) 0xfd, 0, 0x0e, (byte) 0xc2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0x54
        };
    }

    // 0 public, 1 grantable private/loopback, 2 never authorized by a web service grant.
    static int addressClass(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length == 16) {
            // Only ordinary global unicast is public. Exclude transition mechanisms and special
            // purpose allocations, which could embed or translate non-public IPv4 destinations.
            int a = b[0] & 255, c = b[1] & 255;
            if (Arrays.equals(b, InetAddressBytes.AWS_METADATA)) return 2;
            if (address.isLoopbackAddress() || (a & 254) == 252) return 1;
            if ((a & 224) != 32
                    || (a == 32 && c == 2)
                    || (a == 32
                            && c == 1
                            && ((b[2] & 255) < 2 || ((b[2] & 255) == 13 && (b[3] & 255) == 184))))
                return 2;
            // Documentation 3fff::/20.
            if (a == 63 && c == 255 && (b[2] & 240) == 0) return 2;
            return 0;
        }
        int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
        if (a == 10 || a == 127 || (a == 172 && c >= 16 && c <= 31) || (a == 192 && c == 168))
            return 1;
        if ((a == 168 && c == 63 && d == 129 && (b[3] & 255) == 16)
                || a == 0
                || a >= 224
                || (a == 100 && c >= 64 && c <= 127)
                || (a == 169 && c == 254)
                || (a == 192 && c == 0 && (d == 0 || d == 2))
                || (a == 192 && c == 88 && d == 99)
                || (a == 198 && (c == 18 || c == 19))
                || (a == 198 && c == 51 && d == 100)
                || (a == 203 && c == 0 && d == 113)) return 2;
        return 0;
    }
}
