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

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorkProgressTest {
    private final AtomicLong now = new AtomicLong();
    private final WorkProgress work = new WorkProgress(now::get);

    private void advance(long seconds) {
        now.addAndGet(seconds * 1_000_000_000L);
    }

    @Test
    void tracksReasoningAndTaskTimeWithoutCountingApprovalWaitsAndFreezesAtCompletion() {
        work.start("build");
        advance(3);
        work.phase("Reasoning about your request / waiting for model", "reasoning");
        advance(2);
        assertTrue(work.lines().getFirst().contains("Reasoning 00:05"));
        work.phase("Reading source", "task");
        advance(4);
        work.phase("Waiting for permission", "waiting");
        advance(10);
        work.phase("Reading source", "task");
        advance(2);
        assertTrue(
                work.lines().getFirst().contains("Elapsed 00:21 · Reasoning 00:05 · Tasks 00:06"));
        work.phase("Writing the answer", "answer");
        advance(2);
        work.stop();
        advance(100);
        assertEquals("00:23", work.elapsed());
        assertTrue(work.lines().isEmpty());
        work.reset();
        assertEquals("", work.elapsed());
        work.start("plan");
        assertEquals("00:00", work.elapsed());
    }

    @Test
    void tracksConcurrentTasksAndNestedDelegationWithIndependentClocksAndCompletionTimes() {
        work.start("build");
        work.childStarted("first", "build", "explore", "Inspect alpha");
        advance(2);
        work.childStarted("second", "build", "explore", "Inspect beta");
        work.childStarted("nested", "explore", "reviewer", "Review findings");
        work.childPhase("first", "Reading alpha");
        work.childReasoning("first", "Check ");
        work.childReasoning("first", "the imports");
        advance(3);
        var rows = work.lines();
        assertEquals(4, rows.size());
        assertTrue(rows.get(1).contains("00:05 elapsed"));
        assertTrue(rows.get(1).contains("Reading alpha · 00:03"));
        assertTrue(rows.get(1).contains("Reasoning: Check the imports"));
        assertTrue(rows.get(2).contains("00:03 elapsed"));
        assertTrue(rows.get(3).contains("delegated by explore"));
        assertTrue(work.childFinished("first", "COMPLETED").contains("COMPLETED · 00:05 elapsed"));
        assertEquals(3, work.lines().size());
        assertTrue(work.childFinished("second", "TIMED_OUT").contains("Task: Inspect beta"));
        work.stop();
        work.childReasoning("nested", "late update");
        assertEquals("", work.childFinished("nested", "CANCELLED"));
        assertTrue(work.lines().isEmpty());
    }

    @Test
    void boundsDescriptionsAndReasoningAndRemovesTerminalControls() {
        work.start("build");
        work.childStarted(
                "child", "build", "explore", "inspect\nsource\u001b\tfile " + "🚀".repeat(300));
        work.childReasoning("child", "🚀".repeat(1000));
        String line = work.lines().getLast();
        assertTrue(line.contains("Task: inspect source file "));
        assertFalse(line.contains("\u001b"));
        assertTrue(line.length() < 1400);
        assertFalse(WorkProgress.brief("🚀".repeat(300)).endsWith("\ud83d…"));
        assertEquals("61:01", WorkProgress.duration(3661_000_000_000L));
    }
}
