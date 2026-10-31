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
package fr.openobject.ai.syleks.auth;

import java.nio.file.Path;

public final class SecretFixtures {
    private SecretFixtures() {}

    public static ConfigSecrets encrypted(Path root) {
        return new ConfigSecrets(
                root,
                null,
                false,
                () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    public static ConfigSecrets files(Path root) {
        return new ConfigSecrets(root, null, true);
    }
}
