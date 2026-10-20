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
package fr.openobject.ai.syleks.app;

import fr.openobject.ai.syleks.tui.TerminalApp;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

/**
 * Entry point invoked as `java -jar target/syleks-*.jar chat`.
 */
@DefaultScoped
@Command(name = "chat", description = "Open the local AI terminal conversation")
public class ChatCommand implements Runnable {
    private final TerminalApp app;

    public ChatCommand(Configuration configuration, TerminalApp app) {
        this.app = app;
    }

    @RootConfiguration("-")
    public record Configuration() {}

    @Override
    public void run() {
        try {
            app.run();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to open terminal UI", e);
        }
    }
}
