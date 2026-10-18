package fr.openobject.ai.syleks.provider;

/** IDs are provider-local; selection() is the portable session reference. */
public record LlmModel(
        String providerId, String id, String displayName, ProviderCapabilities capabilities) {
    public String selection() {
        return providerId + "/" + id;
    }
}
