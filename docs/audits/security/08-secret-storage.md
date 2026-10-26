# SEC-08: Extend protected secret storage and redaction

Status: implemented 26 October 2026. Priority: hardening. Dependencies: SEC-04; coordinate with SEC-02.

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

## Implementation record — 26 October 2026

Implementation is in this checkout; no commit or PR was created. [Secret reference documentation](../../secret-storage.md) contains the secret-flow inventory, migration/rollback contract, scope, revocation, rotation, platform behavior, and configuration examples.

- Existing string fields accept reserved `syleks-secret:UUID` references without changing configuration shape. Custom-provider keys resolve only at the validated inference transport; MCP headers/environment resolve only for their configured transport. Scope includes provider ID/full endpoint, or MCP ID/full URL or exact command plus canonical workspace, and field. The vault payload repeats its binding. Session metadata, model request bodies and approval descriptions never receive automatic resolved credentials.
- New records live under the existing protected global `HOME/.config/syleks/auth/config-secrets/` tree (Java user-home fallback). Deliberately use the same protected root as global policy, rather than following provider-account XDG storage into an otherwise unprotected location. API keys and references remain masked by the configuration form. Schema/help and user/example documentation describe the reserved prefix; bundled defaults remain key-free.
- `secrets migrate` explicitly migrates all nonempty provider-key/MCP-header/MCP-environment literals in global configuration, including non-secret environment settings. It does not migrate project files or alter OAuth. `secrets rotate REF` accepts masked console input or bounded stdin; it creates replacement storage, commits all references atomically under the configuration lock, then revokes old local records. `secrets revoke REF` removes local authority before best-effort keyring cleanup. Remote key revocation remains the user's responsibility. Restart active chats to clear already-resolved transport values.
- Before-commit failures preserve the original configuration and clean newly allocated entries best effort; concurrent edits are retained. No plaintext backup is created. Process interruption can leave an orphaned new entry; local revocation can leave a keyring orphan when deletion is unavailable. After-commit cleanup failure reports committed configuration. No crash-proof/device-failure guarantee is made.
- New configuration references require an available keyring unless `SYLEKS_CREDENTIAL_BACKEND=file` explicitly selects private **unencrypted** files. Existing vault-backed references/accounts never silently downgrade when a keyring disappears or fails. Secure filesystem operations require POSIX permissions/secure directory streams; unsupported backends fail closed. No Windows ACL/macOS keychain backend was added. Same-user unsandboxed programs can read secrets.
- Structured diagnostic serialization masks sensitive fields; known-value replacement covers raw/JSON/URL-encoded values before bounds, URL details are omitted, and common bearer/key/JWT/control filtering remains. Custom inference HTTP/stream/parser diagnostics discard raw causes; oversized error bodies are omitted. MCP errors omit hostile response bodies. Provider catalog diagnostics share bounded redaction. Actual requests, successful server/model content, and user-authored exports are not silently rewritten; arbitrary encoded-secret detection is not guaranteed.

### Regression evidence

Fake vaults and synthetic credentials cover migration of multiple destinations, interrupted/concurrent configuration commits, failed vault and reference writes, rollback cleanup, unavailable/locked vaults, rotation, logout/local revocation, multiple provider accounts, swapped/malformed references, symlinks and private permissions. Deterministic before-commit edits preserve the user edit and leave no newly allocated vault entries. Failed storage preserves the original literal configuration and outside marker. Tests assert no exception cause/value echo and no account downgrade.

Local HTTP and real child-process fixtures confirm the intended provider Authorization/MCP header or stdio environment receives the synthetic secret. Provider request payloads/configuration model metadata omit resolved values; MCP rebindings to other URL/header/command/workspace fail before an additional request or child effect. Host secret sentinels are absent from configured-child inherited defaults and unrelated subprocesses. HTTP error echoes, streamed error echoes and malformed JSON do not expose the token. Structured diagnostic serialization omits synthetic sensitive fields/URL credentials. Source tracing confirms approval details are built without resolving configuration secrets; no blanket guarantee is made about secrets deliberately read by tools or returned as successful server content.

### Validation environment and commands

Linux x86-64 `6.12.113+deb13-amd64`; Zulu JDK 25 (`25+36-LTS`); Maven 3.9.12. Commands used `JAVA_HOME=/home/fpa/.sdkman/candidates/java/25-zulu` and `/home/fpa/.sdkman/candidates/maven/3.9.12/bin/mvn`:

```sh
mvn -Dtest=ConfigSecretsTest,CredentialStoreTest test
mvn -Dtest=ConfigSecretsTest,CredentialStoreTest,SecretTransportTest,McpSecretTransportTest,ConfigInitWizardTest,ConfigSchemaTest test
mvn spotless:apply
mvn -B --no-transfer-progress -Dtest=ConfigSecretsTest,CredentialStoreTest,SecretTransportTest,McpSecretTransportTest,ConfigInitWizardTest,ConfigSchemaTest test spotless:apply
mvn -B --no-transfer-progress clean verify
git diff --check
```

The initial socket-based test attempt was blocked by the execution sandbox; rerunning through normal approval passed. Spotless initially encountered a read-only Maven cache; the approved run passed. One full run completed all tests but failed formatting after a final concurrent source edit; formatting and verification were repeated. Final focused validation passed **36 tests, zero failures/errors/skips**. Final full verification and packaged smoke results are recorded below.

Real Secret Service/keyring, real remote providers, macOS/Windows, and kernel sandbox opt-in tests were not exercised for SEC-08. Keyring lifecycle evidence is fake-vault evidence only. The eleven LinuxCommandBackendTest cases are skipped unless `SYLEKS_SANDBOX_TESTS` is enabled; SEC-08 makes no new kernel isolation claim. Existing OAuth verification and refresh-concurrency suites remain part of full verification.

Final results:

- `mvn -B --no-transfer-progress clean verify`: **BUILD SUCCESS**, 608 tests, zero failures/errors, 11 opt-in kernel tests skipped; 1 minute 44 seconds. Spotless check, executable packaging and existing OAuth/refresh suites passed. Log: `/tmp/syleks-sec08-verify-final.log`.
- Final focused test/format command: **BUILD SUCCESS**, 36 tests, zero failures/errors/skips. Log: `/tmp/syleks-sec08-focused.log`.
- `python3 /tmp/syleks-sec08-cli.py`: **PASS** against the packaged JAR with a disposable HOME and explicit file backend: migrated three literals, second migration changed zero values, stdin rotation removed the old record, local revocation removed the replacement, malformed configuration produced no synthetic-secret echo. This was a disposable smoke harness, not a committed build dependency.
- `python3 /tmp/syleks-sec08-pty.py`: **PASS** against the packaged JAR in a disposable PTY: keyboard navigation to the key field, updated reference label, masked synthetic value, resize 110×30 → 65×18 → 110×30, and Escape cancellation without creating configuration. No sensitive transcript or real credential was captured.
- `git diff --check` and relative documentation-link target checks: **PASS**.

Deliberate boundaries: migration is explicit/global-only, file fallback is explicit/unencrypted, active transports require restart after revocation, and secret detection is diagnostic best effort. These are documented product contracts rather than deferred enforcement. Real keyring/platform/backend checks listed above remain untested; no real account or network destination was contacted.
