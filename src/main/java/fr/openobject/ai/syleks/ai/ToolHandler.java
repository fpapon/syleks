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

@FunctionalInterface
public interface ToolHandler {
    String execute(ToolCall call) throws Exception;

    default boolean toolsEnabled() {
        return true;
    }

    ToolHandler NONE =
            new ToolHandler() {
                public boolean toolsEnabled() {
                    return false;
                }

                public int roundLimit(int configured) {
                    return 1;
                }

                public String execute(ToolCall call) {
                    throw new IllegalStateException("Tools are disabled for summary generation");
                }
            };

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
