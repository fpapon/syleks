package fr.openobject.ai.syleks.session;

import java.util.List;
import java.util.Map;

/** Selects whole user turns. Persisted history is never modified by context selection. */
public interface ContextCompactor {
    List<Map<String, Object>> select(List<Map<String, Object>> messages, int budget, ContextBudget estimator);

    ContextCompactor RECENT_TURNS = (messages, budget, estimator) -> {
        var selected = new java.util.ArrayList<Map<String, Object>>();
        int lastUser = -1;
        for (int i = 0; i < messages.size(); i++) if ("user".equals(messages.get(i).get("role"))) lastUser = i;
        if (lastUser < 0) return List.of();
        int end = messages.size();
        for (int i = lastUser; i >= 0; i--) {
            if (!"user".equals(messages.get(i).get("role"))) continue;
            var turn = messages.subList(i, end);
            var candidate = new java.util.ArrayList<>(turn);
            candidate.addAll(selected);
            if (estimator.estimate(candidate) > budget) {
                if (selected.isEmpty()) throw new IllegalStateException("The latest conversation turn exceeds the context budget; select a larger-context model or start /new");
                break;
            }
            selected = candidate;
            end = i;
        }
        return List.copyOf(selected);
    };
}
