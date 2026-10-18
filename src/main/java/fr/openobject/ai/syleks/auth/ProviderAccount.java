package fr.openobject.ai.syleks.auth;

/** Non-sensitive registration metadata. An email never serves as the registration's key. */
public record ProviderAccount(
        String providerId,
        String id,
        String email,
        String issuer,
        String subject,
        String clientId,
        String hostId) {
    public String label() {
        return (email == null || email.isBlank() ? providerId : email) + " · " + id;
    }
}
