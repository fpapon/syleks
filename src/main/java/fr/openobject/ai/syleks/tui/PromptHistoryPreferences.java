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
package fr.openobject.ai.syleks.tui;

import fr.openobject.ai.syleks.auth.CredentialStore;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.io.IOException;
import java.util.LinkedHashMap;

/** Global user preference only; prompt text is never persisted here. */
@ApplicationScoped
public class PromptHistoryPreferences {
    private final CredentialStore store;
    private boolean enabled = true;

    public PromptHistoryPreferences(CredentialStore store) {
        this.store = store;
    }

    @io.yupiik.fusion.framework.build.api.lifecycle.Init
    public void load() {
        try {
            var state = store.read(store.root().resolve("config.json"));
            var value = state.get("input_history");
            if (state.containsKey("input_history") && !(value instanceof Boolean))
                throw new IllegalArgumentException("input_history must be a boolean");
            enabled = value == null || Boolean.TRUE.equals(value);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load input history settings", e);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public void select(boolean next) throws Exception {
        store.locked(
                "configuration",
                () -> {
                    var file = store.root().resolve("config.json");
                    var state = new LinkedHashMap<>(store.read(file));
                    state.put("input_history", next);
                    store.write(file, state);
                    return null;
                });
        enabled = next;
    }
}
