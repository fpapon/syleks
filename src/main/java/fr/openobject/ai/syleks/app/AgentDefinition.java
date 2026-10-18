package fr.openobject.ai.syleks.app;

/** A reusable prompt and generation settings, independent of transport. */
public record AgentDefinition(
        String name,
        String description,
        Mode mode,
        String model,
        Double temperature,
        String prompt,
        PermissionPolicy permissions,
        Integer maxRounds,
        boolean disable,
        boolean hidden) {
    public enum Mode {
        PRIMARY,
        SUBAGENT,
        ALL;

        public boolean primary() {
            return this != SUBAGENT;
        }

        public boolean subagent() {
            return this != PRIMARY;
        }
    }
}
