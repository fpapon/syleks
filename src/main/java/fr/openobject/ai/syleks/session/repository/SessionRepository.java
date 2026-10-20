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
package fr.openobject.ai.syleks.session.repository;

import fr.openobject.ai.syleks.session.model.*;

public interface SessionRepository {
    Session createSession(
            String projectId,
            String parentId,
            String title,
            String provider,
            String model,
            String summary);

    java.util.Optional<Session> findSession(String id);

    java.util.List<Session> listSessions(String projectId);

    void updateSession(
            String id,
            String title,
            String provider,
            String model,
            SessionStatus status,
            String summary);

    void deleteSession(String id);

    void account(String id, String accountId);

    void recoverSession(String id);
}
