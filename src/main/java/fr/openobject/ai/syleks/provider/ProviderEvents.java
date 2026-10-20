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

import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.usage.LlmUsageEvent;
import java.util.List;

/** Shared typed execution notifications for all transport adapters. */
final class ProviderEvents {
    private ProviderEvents() {}

    static OllamaClient.Observer observer(
            OllamaClient.Observer original, ChatEventListener listener) {
        return new OllamaClient.Observer() {
            public void state(AgentState state) {
                if (original != null) original.state(state);
                listener.onEvent(ChatEvent.state(state));
            }

            public void usage(LlmUsageEvent event) {
                if (original != null) original.usage(event);
                listener.onEvent(new ChatEvent(ChatEvent.Type.USAGE, "", event));
            }

            public void contextLimited(int count) {
                if (original != null) original.contextLimited(count);
            }

            public void round(String text, String reasoning, List<ToolCall> calls) {
                if (original != null) original.round(text, reasoning, calls);
            }

            public void startTool(ToolCall call) {
                if (original != null) original.startTool(call);
                listener.onEvent(new ChatEvent(ChatEvent.Type.TOOL_CALL, "", call));
            }

            public void toolResult(ToolCall call, String result) {
                toolResult(call, ToolResult.success(result));
            }

            public void toolResult(ToolCall call, ToolResult result) {
                if (original != null) original.toolResult(call, result);
                listener.onEvent(
                        new ChatEvent(
                                ChatEvent.Type.TOOL_RESULT,
                                result.content(),
                                new ChatEvent.ToolCompletion(call, result)));
            }

            public void nextRound() {
                if (original != null) original.nextRound();
            }
        };
    }
}
