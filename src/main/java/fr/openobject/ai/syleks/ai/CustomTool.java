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

import java.util.Map;

/** Trusted Java extension: publish a Fusion bean implementing this interface. Never accept untrusted bytecode. */
public interface CustomTool {
    ToolDefinition definition();

    /** Pure permission target extraction, before any effect. */
    default String target(Map<String, Object> arguments) {
        return "*";
    }

    /** Pure description for ASK, including relevant arguments. Override to redact sensitive fields. */
    default String approvalDetails(Map<String, Object> arguments) {
        return arguments.toString();
    }

    ToolResult execute(ToolContext context, Map<String, Object> arguments) throws Exception;
}
