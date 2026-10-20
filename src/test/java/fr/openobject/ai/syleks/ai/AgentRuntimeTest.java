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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentRuntimeTest {
    private final AgentRuntime runtime = new AgentRuntime();
    private final AgentRuntime.History history =
            new AgentRuntime.History() {
                public Map<String, Object> assistant(AgentRuntime.Turn turn) {
                    return Map.of("role", "assistant", "tool_calls", turn.calls());
                }

                public Map<String, Object> result(ToolCall call, ToolResult result) {
                    return Map.of("role", "tool", "content", result.content());
                }
            };
    private final ToolCall call = new ToolCall("one", "test", Map.of());

    private AgentRuntime.Turn tools(ToolCall... calls) {
        return new AgentRuntime.Turn("", "", List.of(calls));
    }

    private AgentRuntime.Turn done() {
        return new AgentRuntime.Turn("answer", "", List.of());
    }

    @Test
    void runsMultipleToolsInOrderAndReturnsTheirTypedResultsBeforeTheNextTurn() throws Exception {
        var observed = new Events();
        var turns = new AtomicInteger();
        runtime.run(
                List.of(),
                3,
                context -> {
                    if (turns.getAndIncrement() == 0)
                        return tools(call, new ToolCall("two", "test", Map.of()));
                    assertEquals(
                            List.of("assistant", "tool", "tool"),
                            context.stream().map(m -> m.get("role")).toList());
                    assertEquals("Tool error: literal output", context.getLast().get("content"));
                    return done();
                },
                history,
                ToolHandler.typed(
                        c ->
                                c.id().equals("one")
                                        ? ToolResult.denied("refused")
                                        : ToolResult.success("Tool error: literal output")),
                observed);
        assertEquals(
                List.of(ToolResult.Status.DENIED, ToolResult.Status.SUCCESS), observed.results);
        assertEquals(
                List.of(
                        AgentState.THINKING,
                        AgentState.TOOL_CALLING,
                        AgentState.WAITING_TOOL,
                        AgentState.TOOL_CALLING,
                        AgentState.WAITING_TOOL,
                        AgentState.THINKING,
                        AgentState.COMPLETED),
                observed.states);
        assertEquals(1, observed.next);
    }

    @Test
    void persistsAnInterruptedOutcomeAndNeverStartsAnotherModelRound() {
        var observed = new Events();
        var turns = new AtomicInteger();
        assertThrows(
                InterruptedException.class,
                () ->
                        runtime.run(
                                List.of(),
                                -1,
                                context -> {
                                    turns.incrementAndGet();
                                    return tools(call);
                                },
                                history,
                                c -> {
                                    throw new InterruptedException("cancelled");
                                },
                                observed));
        assertEquals(1, turns.get());
        assertEquals(List.of(ToolResult.Status.CANCELLED), observed.results);
        assertEquals(AgentState.CANCELLED, observed.states.getLast());
        assertEquals(0, observed.next);
    }

    @Test
    void treatsAnExplicitCancelledResultAsCancellationRatherThanARecoverableFailure() {
        var observed = new Events();
        assertThrows(
                InterruptedException.class,
                () ->
                        runtime.run(
                                List.of(),
                                -1,
                                context -> tools(call),
                                history,
                                ToolHandler.typed(c -> ToolResult.cancelled()),
                                observed));
        assertEquals(AgentState.CANCELLED, observed.states.getLast());
        assertEquals(1, observed.results.size());
    }

    @Test
    void turnsToolExceptionsIntoFailuresThatTheModelCanDiagnose() throws Exception {
        var turns = new AtomicInteger();
        var observed = new Events();
        runtime.run(
                List.of(),
                2,
                context -> {
                    if (turns.getAndIncrement() == 0) return tools(call);
                    assertTrue(
                            context.getLast()
                                    .get("content")
                                    .toString()
                                    .contains("fixture failure"));
                    return done();
                },
                history,
                c -> {
                    throw new IOException("fixture failure");
                },
                observed);
        assertEquals(List.of(ToolResult.Status.FAILED), observed.results);
        assertEquals(AgentState.COMPLETED, observed.states.getLast());
    }

    @Test
    void marksTransportFailureAsFailedAndEnforcesTheLastRoundWithoutAnEmptyNextRound() {
        var observed = new Events();
        assertThrows(
                IOException.class,
                () ->
                        runtime.run(
                                List.of(),
                                1,
                                context -> {
                                    throw new IOException("transport");
                                },
                                history,
                                c -> "unused",
                                observed));
        assertEquals(AgentState.FAILED, observed.states.getLast());
        var limited = new Events();
        assertThrows(
                IOException.class,
                () ->
                        runtime.run(
                                List.of(),
                                1,
                                context -> tools(call),
                                history,
                                c -> "result",
                                limited));
        assertEquals(AgentState.FAILED, limited.states.getLast());
        assertEquals(0, limited.next);
    }

    @Test
    void cancellationFlagTakesPrecedenceOverATransportIOException() {
        var observed = new Events();
        try {
            assertThrows(
                    InterruptedException.class,
                    () ->
                            runtime.run(
                                    List.of(),
                                    1,
                                    context -> {
                                        Thread.currentThread().interrupt();
                                        throw new IOException("closed on cancellation");
                                    },
                                    history,
                                    c -> "unused",
                                    observed));
            assertEquals(AgentState.CANCELLED, observed.states.getLast());
        } finally {
            Thread.interrupted();
        }
    }

    private static final class Events implements AgentObserver {
        final List<AgentState> states = new ArrayList<>();
        final List<ToolResult.Status> results = new ArrayList<>();
        int next;

        public void state(AgentState state) {
            states.add(state);
        }

        public void round(String content, String reasoning, List<ToolCall> calls) {}

        public void toolResult(ToolCall call, String result) {
            fail("Expected typed outcome");
        }

        public void toolResult(ToolCall call, ToolResult result) {
            results.add(result.status());
        }

        public void nextRound() {
            next++;
        }
    }
}
