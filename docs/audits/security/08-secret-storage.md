# SEC-08: Extend protected secret storage and redaction

Status: not started. Priority: hardening. Dependencies: SEC-04; coordinate with SEC-02.

## Finding and relevant code

Provider account credentials support an OS vault and protected-file fallback. Custom provider API keys, MCP literal headers, and configured stdio environment values can still contain plaintext secrets. File permissions reduce exposure but are not encryption and do not protect against an unsandboxed process running as the user.

Read [CredentialStore.java](../../../src/main/java/fr/openobject/ai/syleks/auth/CredentialStore.java), [CredentialVault.java](../../../src/main/java/fr/openobject/ai/syleks/auth/CredentialVault.java), [SecretServiceVault.java](../../../src/main/java/fr/openobject/ai/syleks/auth/SecretServiceVault.java), [ConfigInitWizard.java](../../../src/main/java/fr/openobject/ai/syleks/app/ConfigInitWizard.java), [McpServerConfig.java](../../../src/main/java/fr/openobject/ai/syleks/mcp/McpServerConfig.java), [McpOAuth.java](../../../src/main/java/fr/openobject/ai/syleks/mcp/McpOAuth.java), and [OllamaClient.java](../../../src/main/java/fr/openobject/ai/syleks/ai/OllamaClient.java).

## Implementation contract

- Add opaque references for custom-provider and MCP secrets, resolved at the destination-specific transport boundary. Avoid copying resolved values into model context, session metadata, exports, approval details, or subprocess defaults.
- Reuse vault lifecycle semantics: create replacement secret before committing its reference; handle failure without losing the old credential; clean up superseded references safely. Vault failures must not silently downgrade existing records to plaintext.
- Offer explicit user migration from plaintext configuration. Never delete the original secret before successful destination storage and configuration commit. Do not create unprotected backup copies as part of migration.
- Keep protected-file fallback explicit and honest about encryption and platform ACL limitations. Do not invent an encryption key stored beside encrypted data as a substitute for a keyring.
- Apply structured redaction to diagnostic paths, URLs, headers, exceptions, and serialization. Test tokens embedded in server error responses. Redaction should not silently mutate actual requests or exported user-authored content under a claim of guaranteed secret detection.
- Document which values are sensitive, how references are scoped/revoked, how unavailable keyrings behave, and how users rotate credentials. Preserve OAuth verification and refresh concurrency protections.

## Acceptance and evidence

Use fake vaults and synthetic tokens. Exercise migration success, interrupted/failed writes, locked/unavailable vaults, rotation, logout/revocation, multiple destinations/accounts, reference tampering, and file permissions. Assert synthetic secrets are absent from normal diagnostics, approval text, session metadata, and unrelated subprocesses. Cover malformed configuration without echoing values.

Provide a secret-flow inventory, migration/rollback contract, platform behavior, and tests. Run a real keyring check only in an authorized disposable environment; record untested backends explicitly. Follow [validation.md](validation.md).
