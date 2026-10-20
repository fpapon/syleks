package fr.openobject.ai.syleks.provider;

import java.util.List;

public interface LlmProvider {
    String id();

    String displayName();

    List<LlmModel> listModels(String accountId) throws Exception;

    ProviderCapabilities capabilities();

    /** Cloud accounts stored in the credential store. Configured servers stay false. */
    default boolean requiresAccount() {
        return false;
    }

    /** Rejects a listed model that cannot be sent. Selectable models use the default. */
    default void validateModel(String modelId) throws java.io.IOException {}

    /** Runs on the caller's generation worker; interruption cancels network I/O. */
    void chat(ChatRequest request, ChatEventListener listener) throws Exception;
}
