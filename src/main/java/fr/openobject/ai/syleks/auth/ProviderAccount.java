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

/** Non-sensitive registration metadata. An email never serves as the registration's key. */
public record ProviderAccount(
        String providerId,
        String id,
        String email,
        String issuer,
        String subject,
        String clientId,
        String hostId) {
    public String label() {
        return (email == null || email.isBlank() ? providerId : email) + " · " + id;
    }
}
