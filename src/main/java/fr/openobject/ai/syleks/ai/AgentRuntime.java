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

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** One execution loop for every provider. Transport adapters only produce complete turns. */
@DefaultScoped
public class AgentRuntime {
    private static final Logger LOGGER = Logger.getLogger(AgentRuntime.class.getName());

    public record Turn(String content, String reasoning, List<ToolCall> calls) {
        public Turn {
            calls = List.copyOf(calls);
        }
    }

    @FunctionalInterface
    public interface Source {
        Turn next(List<Map<String, Object>> history) throws Exception;
    }

    public interface History {
        Map<String, Object> assistant(Turn turn);

        Map<String, Object> result(ToolCall call, ToolResult result);
    }

    public void run(
            List<Map<String, Object>> history,
            int maxRounds,
            Source source,
            History encoding,
            ToolHandler tools,
            AgentObserver observer)
            throws Exception {
        var messages = new ArrayList<>(history);
        maxRounds = tools.roundLimit(maxRounds);
        try {
            for (int round = 0; maxRounds == -1 || round < maxRounds; round++) {
                interrupted();
                state(observer, AgentState.THINKING);
                tools.beforeRound();
                Turn turn;
                try {
                    turn = source.next(List.copyOf(messages));
                } finally {
                    tools.afterRound();
                }
                interrupted();
                if (observer != null)
                    observer.round(turn.content(), turn.reasoning(), turn.calls());
                if (turn.calls().isEmpty()) {
                    state(observer, AgentState.COMPLETED);
                    return;
                }
                messages.add(encoding.assistant(turn));
                for (var call : turn.calls()) {
                    interrupted();
                    state(observer, AgentState.TOOL_CALLING);
                    if (observer != null) observer.startTool(call);
                    ToolResult result;
                    try {
                        state(observer, AgentState.WAITING_TOOL);
                        result = tools.executeResult(call);
                    } catch (InterruptedException e) {
                        boolean interrupted = Thread.interrupted();
                        try {
                            if (observer != null) observer.toolResult(call, ToolResult.cancelled());
                        } catch (RuntimeException failure) {
                            e.addSuppressed(failure);
                        } finally {
                            if (interrupted) Thread.currentThread().interrupt();
                        }
                        throw e;
                    } catch (Exception e) {
                        if (Thread.currentThread().isInterrupted())
                            throw new InterruptedException("Tool cancelled");
                        LOGGER.fine("Tool execution failed: " + e.getClass().getName());
                        result = ToolResult.failed(e);
                    }
                    if (observer != null) observer.toolResult(call, result);
                    if (result.status() == ToolResult.Status.CANCELLED)
                        throw new InterruptedException("Tool cancelled");
                    interrupted();
                    messages.add(encoding.result(call, result));
                }
                if (observer != null && (maxRounds == -1 || round + 1 < maxRounds))
                    observer.nextRound();
            }
            throw new IOException(
                    "Stopped after "
                            + maxRounds
                            + " model/tool rounds; send a new message to continue");
        } catch (InterruptedException e) {
            state(observer, AgentState.CANCELLED);
            throw e;
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                state(observer, AgentState.CANCELLED);
                var cancelled = new InterruptedException("Generation cancelled");
                cancelled.initCause(e);
                throw cancelled;
            }
            state(observer, AgentState.FAILED);
            LOGGER.warning("Agent execution failed: " + e.getClass().getName());
            throw e;
        }
    }

    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("Generation cancelled");
    }

    private static void state(AgentObserver observer, AgentState state) {
        LOGGER.fine(() -> "Agent state: " + state);
        if (observer != null) observer.state(state);
    }
}
