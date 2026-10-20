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
package fr.openobject.ai.syleks.model;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public record ChatMessage(String role, String content, String time, String displayContent) {
    public ChatMessage(String role, String content, String time) {
        this(role, content, time, content);
    }

    public static ChatMessage now(String role, String content, String displayContent) {
        return new ChatMessage(
                role,
                content,
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                displayContent);
    }

    public static ChatMessage now(String role, String content) {
        return new ChatMessage(
                role, content, LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
    }
}
