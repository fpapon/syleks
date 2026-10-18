package fr.openobject.ai.syleks.ai;

@FunctionalInterface
public interface ToolHandler {
    String execute(ToolCall call) throws Exception;

    default int roundLimit(int configured) {
        return configured;
    }

    default void beforeRound() throws Exception {}

    default void afterRound() {}

    /** Compatibility for string-only integrations; execution services override this typed path. */
    default ToolResult executeResult(ToolCall call) throws Exception {
        return ToolResult.success(execute(call));
    }

    @FunctionalInterface
    interface Typed {
        ToolResult execute(ToolCall call) throws Exception;
    }

    static ToolHandler typed(Typed handler) {
        return new ToolHandler() {
            public String execute(ToolCall call) throws Exception {
                return executeResult(call).content();
            }

            public ToolResult executeResult(ToolCall call) throws Exception {
                return handler.execute(call);
            }
        };
    }
}
