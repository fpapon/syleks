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
package fr.openobject.ai.syleks.provider;

import java.util.Set;

/**
 * OpenCode Console transport for each model id, from the endpoint table at
 * https://opencode.ai/docs/zen on 2026-10-20. The live catalog
 * (https://opencode.ai/inference/v1/models) lists ids only. Unknown ids are not guessed.
 */
public final class OpenCodeTransports {
    public enum Kind {
        RESPONSES,
        CHAT,
        UNSUPPORTED
    }

    public record Route(Kind kind, String reason) {
        public boolean selectable() {
            return kind != Kind.UNSUPPORTED;
        }
    }

    public static final String ANTHROPIC =
            "This OpenCode model uses the Anthropic messages API, which Syleks does not call.";
    public static final String GOOGLE =
            "This OpenCode model uses the Google API, which Syleks does not call.";
    public static final String SYSTEM_ONE =
            "This OpenCode model uses the System One API, which Syleks does not call.";
    public static final String UNDOCUMENTED = "OpenCode transport not documented for this model.";

    private static final Set<String> ANTHROPIC_QWEN =
            Set.of("qwen3.8-flash", "qwen3.7-max", "qwen3.7-plus", "qwen3.6-plus", "qwen3.5-plus");

    /** Chat completions models. Responses models are recognized by prefix instead. */
    private static final Set<String> CHAT =
            Set.of(
                    "qwen3.8-max",
                    "deepseek-v4.1-flash",
                    "deepseek-v4-pro",
                    "minimax-m3",
                    "minimax-m2.7",
                    "minimax-m2.5",
                    "glm-5.3-flash",
                    "glm-5.3",
                    "glm-5.2",
                    "glm-5.1",
                    "glm-5",
                    "kimi-k2.5",
                    "kimi-k2.6",
                    "kimi-k2.7-code",
                    "kimi-k3",
                    "mistral-large-4",
                    "big-pickle",
                    "glm-5.3-flash-free",
                    "deepseek-v4.1-flash-free",
                    "minimax-m3.1-flash-free",
                    "beam-free",
                    "ll-alpha-free",
                    "longcat-2.5-preview-free",
                    "exo-free",
                    "mimo-v2.6-flash-free",
                    "mimo-v2.5-free",
                    "nemotron-3-ultra-free",
                    "nemotron-3.5-lightning-free",
                    "nemotron-3.5-super-vl-free");

    private OpenCodeTransports() {}

    public static Route classify(String modelId) {
        if (modelId == null || modelId.isBlank()) return new Route(Kind.UNSUPPORTED, UNDOCUMENTED);
        if (modelId.startsWith("claude-")) return new Route(Kind.UNSUPPORTED, ANTHROPIC);
        if (modelId.startsWith("gemini-")) return new Route(Kind.UNSUPPORTED, GOOGLE);
        if (modelId.startsWith("jev-")) return new Route(Kind.UNSUPPORTED, SYSTEM_ONE);
        if (ANTHROPIC_QWEN.contains(modelId)) return new Route(Kind.UNSUPPORTED, ANTHROPIC);
        if (modelId.startsWith("gpt-")
                || modelId.startsWith("grok-")
                || modelId.startsWith("muse-spark-")) return new Route(Kind.RESPONSES, null);
        if (CHAT.contains(modelId)) return new Route(Kind.CHAT, null);
        return new Route(Kind.UNSUPPORTED, UNDOCUMENTED);
    }
}
