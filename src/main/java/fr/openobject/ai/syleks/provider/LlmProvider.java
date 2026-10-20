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
