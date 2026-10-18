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
