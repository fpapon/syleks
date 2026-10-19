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
