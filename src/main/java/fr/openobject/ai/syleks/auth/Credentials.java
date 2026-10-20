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

import java.time.Instant;
import java.util.Set;

public record Credentials(
        ProviderAccount account,
        Set<String> scopes,
        String accessToken,
        String refreshToken,
        String idToken,
        Instant receivedAt,
        Instant expiresAt,
        Instant earliestRefreshAt) {
    public boolean connected() {
        return accessToken != null && !accessToken.isBlank()
                || account.providerId().equals("chatgpt") && idToken != null && !idToken.isBlank();
    }

    public boolean planAuthorized() {
        return accessToken != null
                && !accessToken.isBlank()
                && scopes.contains("chatgpt.tokens.use.direct");
    }

    public Credentials signedOut() {
        return new Credentials(
                account, Set.of(), null, null, null, receivedAt, Instant.EPOCH, Instant.EPOCH);
    }

    @Override
    public String toString() {
        return "Credentials[account=" + account.id() + ", secrets=REDACTED]";
    }
}
