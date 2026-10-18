package fr.openobject.ai.syleks.provider;

import java.util.List;

public interface LlmProvider {
    String id();

    String displayName();

    List<LlmModel> listModels(String accountId) throws Exception;

    ProviderCapabilities capabilities();

    /** Runs on the caller's generation worker; interruption cancels network I/O. */
    void chat(ChatRequest request, ChatEventListener listener) throws Exception;
}
