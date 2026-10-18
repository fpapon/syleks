package fr.openobject.ai.syleks.auth;

import java.io.IOException;

/** OS vault extension point. References are opaque and contain no credentials. */
interface CredentialVault {
    void put(String reference, String secret) throws IOException;

    String get(String reference) throws IOException;

    void delete(String reference) throws IOException;
}
