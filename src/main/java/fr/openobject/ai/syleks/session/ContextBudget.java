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
package fr.openobject.ai.syleks.session;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Replaceable estimator; UTF-8 bytes conservatively bound token counts without a tokenizer dependency. */
public interface ContextBudget {
    int estimate(List<Map<String, Object>> messages);

    ContextBudget UTF8 =
            messages ->
                    messages.stream()
                            .mapToInt(
                                    m -> 16 + m.toString().getBytes(StandardCharsets.UTF_8).length)
                            .sum();
}
