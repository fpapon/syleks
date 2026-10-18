package fr.openobject.ai.syleks.session;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Replaceable estimator; UTF-8 bytes conservatively bound token counts without a tokenizer dependency. */
public interface ContextBudget {
    int estimate(List<Map<String, Object>> messages);

    ContextBudget UTF8 = messages -> messages.stream().mapToInt(m ->
            16 + m.toString().getBytes(StandardCharsets.UTF_8).length).sum();
}
