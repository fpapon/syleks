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
package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.usage.LlmUsageEvent;
import java.util.List;

/** Typed execution notifications shared by persistence and provider event adapters. */
public interface AgentObserver {
    default void reasoning(String delta) {}

    default void state(AgentState state) {}

    default void startTool(ToolCall call) {}

    default void usage(LlmUsageEvent event) {}

    default void contextLimited(int count) {}

    /** Called before admission, with the actual tool schema cost, on the generation worker. */
    default List<java.util.Map<String, Object>> prepareContext(
            List<java.util.Map<String, Object>> messages, int toolOverhead) throws Exception {
        return messages;
    }

    void round(String content, String reasoning, List<ToolCall> calls);

    void toolResult(ToolCall call, String result);

    default void toolResult(ToolCall call, ToolResult result) {
        toolResult(call, result.content());
    }

    void nextRound();
}
