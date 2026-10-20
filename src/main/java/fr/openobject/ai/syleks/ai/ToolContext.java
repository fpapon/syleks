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

import java.nio.file.Path;
import java.util.List;

/** Per-invocation state. No provider, terminal or mutable parent history is exposed. */
public record ToolContext(
        Path workspace,
        String sessionId,
        String actor,
        AgentTools.Gate permissions,
        Questioner questions,
        FileMutation mutations) {
    public ToolContext(
            Path workspace,
            String sessionId,
            String actor,
            AgentTools.Gate permissions,
            Questioner questions) {
        this(workspace, sessionId, actor, permissions, questions, FileMutation.DIRECT);
    }

    @FunctionalInterface
    public interface Questioner {
        String ask(String question, List<String> options) throws Exception;
    }

    public void require(String operation, String target, String details) throws Exception {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("Tool cancelled");
        if (!permissions.allow(operation, target, details))
            throw new ToolPermissionDeniedException(
                    "Permission refused: " + operation + " / " + target);
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("Tool cancelled");
    }
}
