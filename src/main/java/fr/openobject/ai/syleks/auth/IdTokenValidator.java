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

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.net.URI;
import java.util.Set;

@DefaultScoped
public class IdTokenValidator {
    private final URI jwks;
    private final String issuer;
    private com.nimbusds.jose.jwk.source.JWKSource<SecurityContext> keys;

    private synchronized com.nimbusds.jose.jwk.source.JWKSource<SecurityContext> keys()
            throws Exception {
        if (keys == null) keys = JWKSourceBuilder.<SecurityContext>create(jwks.toURL()).build();
        return keys;
    }

    @io.yupiik.fusion.framework.build.api.lifecycle.Destroy
    public synchronized void close() {
        try {
            if (keys instanceof java.io.Closeable closeable) closeable.close();
        } catch (java.io.IOException ignored) {
        }
    }

    public IdTokenValidator(OpenAiOAuthEndpoints endpoints) {
        this(endpoints.jwks(), endpoints.issuer());
    }

    IdTokenValidator(URI jwks, String issuer) {
        this.jwks = jwks;
        this.issuer = issuer;
    }

    public record Identity(String issuer, String subject, String email) {}

    public Identity validate(String token, String client, String nonce) throws Exception {
        try {
            var processor = new DefaultJWTProcessor<SecurityContext>();
            processor.setJWSKeySelector(
                    new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys()));
            var verifier =
                    new DefaultJWTClaimsVerifier<SecurityContext>(
                            Set.of(client),
                                    new JWTClaimsSet.Builder()
                                            .issuer(issuer)
                                            .claim("nonce", nonce)
                                            .build(),
                            Set.of("exp", "iat", "sub", "nonce"), Set.of());
            verifier.setMaxClockSkew(30);
            processor.setJWTClaimsSetVerifier(verifier);
            var claims = processor.process(token, null);
            if (claims.getIssueTime().toInstant().isAfter(java.time.Instant.now().plusSeconds(30)))
                throw new IllegalArgumentException();
            if (claims.getSubject() == null || claims.getSubject().isBlank())
                throw new IllegalArgumentException();
            if (claims.getAudience().size() > 1 && !client.equals(claims.getStringClaim("azp")))
                throw new IllegalArgumentException();
            String azp = claims.getStringClaim("azp");
            if (azp != null && !azp.equals(client)) throw new IllegalArgumentException();
            return new Identity(
                    claims.getIssuer(),
                    claims.getSubject(),
                    Boolean.TRUE.equals(claims.getBooleanClaim("email_verified"))
                            ? claims.getStringClaim("email")
                            : null);
        } catch (Exception e) {
            throw new java.io.IOException(
                    "Invalid OpenAI ID token (signature, issuer, audience, expiry or nonce)");
        }
    }
}
