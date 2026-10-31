# Encrypted configuration and secret references

Custom provider `options.apiKey`, MCP HTTP `headers` and MCP stdio `environment` values accept a literal, an inline `syleks-encrypted:v1:...` encrypted value, or an opaque `syleks-secret:UUID` reference. Both prefixes are reserved. References contain no secret and resolve only inside the destination transport, after endpoint validation. Configuration setup masks its API key/reference field and never resolves it. Blank optional provider keys remain absent.

## Encrypt values in agent-config.json

Close active chats, review `~/.config/syleks/agent-config.json`, and provide a Base64-encoded random 32-byte key in `SYLEKS_CONFIG_KEY`. For example, to generate a new key for initial setup:

```sh
export SYLEKS_CONFIG_KEY="$(openssl rand -base64 32)"
syleks secrets encrypt
syleks chat
```

**Keep this key in a password manager or another secure location outside the configuration file.** Supply the same key in the environment whenever starting Syleks. The example generates a new key each time; do not rerun the generation step when opening an already encrypted configuration. Losing the key makes the encrypted values unrecoverable. Do not commit the key or put it in shell history. Syleks does not generate a key file or pass this host variable to tool subprocesses by default.

`secrets encrypt` replaces every nonempty literal custom-provider `options.apiKey`, MCP HTTP header, and MCP stdio environment value in the global file with inline ciphertext. Existing encrypted values and keyring references are preserved. Other settings are unchanged. Encryption uses AES-256-GCM, a fresh random 96-bit nonce, and a 128-bit authentication tag. The version and destination scope are authenticated. No keyring or sidecar secret records are needed. As with migration, the configuration update is locked and atomic, concurrent edits are preserved, and no plaintext backup is created.

For example, the resulting provider configuration has this shape (the ciphertext below is a placeholder):

```json
{
  "provider": {
    "custom": {
      "options": {
        "baseURL": "https://provider.example/v1",
        "apiKey": "syleks-encrypted:v1:BASE64_NONCE_AND_CIPHERTEXT"
      }
    }
  }
}
```

Only the destination transport decrypts the value. A missing/invalid key, wrong key, malformed ciphertext, modified ciphertext, or changed destination refuses access before sending credentials or launching an MCP child. Destination bindings are the same as secret references, described below. Plaintext and references remain supported alongside encrypted values. The setup wizard preserves encrypted values and masks the key field; after entering a new literal, run `secrets encrypt` again.

`secrets rotate <encrypted-value>` reads a new credential using the same masked console/stdin flow as reference rotation and stores new ciphertext under the current encryption key. Remove encrypted values directly from configuration to stop using them; `secrets revoke` applies only to stored references. Old ciphertext remains decryptable with its original key, so revoke superseded API keys at the provider. To change the encryption key, obtain fresh credentials, replace encrypted fields with those literals, then run `secrets encrypt` with the new key and restart chats. This encrypts supported secret fields, not the whole JSON document or OAuth account storage. Anyone with both the configuration and the key can decrypt it; protect the host and key separately.

## Explicit migration and rotation

Close active chats and review global `~/.config/syleks/agent-config.json`, then run:

```sh
syleks secrets migrate
```

Migration protects every nonempty literal custom-provider API key and MCP header/environment value in that file, including disabled servers and non-secret environment settings. It does not inspect project files, guess which environment names contain secrets, modify OAuth credentials, or migrate `bearer_token_env` (which already names a host variable). Remove unnecessary values first. Plaintext configuration remains supported until you explicitly migrate it. The configuration wizard still saves entered literals; run migration after saving a new key, or enter an existing reference valid for that destination.

The default `SYLEKS_CREDENTIAL_BACKEND=auto` uses Linux Secret Service when detected. **New configuration references require a keyring**, unless you explicitly select the unencrypted protected-file backend:

```sh
SYLEKS_CREDENTIAL_BACKEND=file syleks secrets migrate
```

This explicit fallback differs from legacy provider-account creation, where `auto` can select protected files. Neither account rotation nor reference resolution silently downgrades an existing vault-backed record. A missing/locked keyring refuses access; unlock it and retry. Selecting `file` does not unlock existing keyring records.

Migration creates replacement entries before atomically committing configuration under the configuration-save lock. Vault/storage/write failures leave the original configuration intact, and best-effort cleanup removes newly created entries. Concurrent edits are detected and preserved. No plaintext backup is made. A process crash can leave an unreferenced entry; the original configuration is never removed before commit. As with existing atomic file operations, this is not a guarantee against device failure or a malicious same-user process. A post-commit cleanup failure reports that the new configuration was committed.

To rotate a referenced value, obtain a replacement from the destination service and run:

```sh
syleks secrets rotate syleks-secret:00000000-0000-0000-0000-000000000000
```

Use the real reference from your global configuration. The command reads a masked value from an interactive console, or one UTF-8 line on stdin when redirected (maximum 8192 bytes). Avoid shell command arguments/history containing the secret. Rotation writes new storage and references, commits configuration, then revokes superseded local references. Failure before commit retains the old credential. Revoke the old credential at the remote service after successful rotation; local deletion cannot revoke a remote API key.

```sh
syleks secrets revoke syleks-secret:00000000-0000-0000-0000-000000000000
```

Revocation removes the local reference first, then deletes its vault entry. It can leave a vault orphan if the keyring is unavailable; remove that entry using the system keyring manager. It does not edit configuration: remove/replace dangling references before reconnecting. `mcp logout` continues to remove OAuth credentials only. Restart all active chats after migration, rotation or revocation; existing transports may retain a resolved value until closed.

## Scope, storage and platform limits

Provider references bind to provider ID and full inference endpoint. MCP references bind to server ID, transport, full URL or exact command argument list plus canonical launch workspace, and header/environment field name (header names ignore case). Migrate global stdio values from the intended launch workspace; other workspaces need their own references. Changing any bound field requires creating a new reference with a newly supplied literal. Copying a reference to another endpoint, server, command or field fails before sending a request/launching a process. Vault payloads repeat the scope binding, so swapping just the on-disk vault identifier cannot rebind a secret. References are local capabilities; do not publish them unnecessarily. Files outside project authority hold the actual records. This is not protection against a same-user host attacker who can rewrite those records or read process memory.

Reference records live at `HOME/.config/syleks/auth/config-secrets/`, with the existing Java user-home fallback when HOME is absent. This deliberately shares the protected global configuration root used by native-tool permission ceilings and the command sandbox. Unlike provider-account records, these configuration references do not follow XDG_CONFIG_HOME. Changing HOME selects different records; it does not migrate them automatically.

On Linux, Secret Service uses `/usr/bin/secret-tool` and the user D-Bus session. Vault payloads travel over private process pipes, never command arguments. Protected-file records contain **unencrypted** secrets, with directories `0700` and files `0600`. Both record backends require the existing secure POSIX filesystem operations; unsupported platforms/filesystems fail closed. No Windows ACL or macOS keychain adapter is provided. Permissions do not protect against unsandboxed programs running as the user. No real keyring backend was tested for SEC-08; fake vaults and temporary private files cover lifecycle failures.

## Secret-flow inventory and diagnostics

| Input | Storage | Resolution destination | Other exposure |
| --- | --- | --- | --- |
| Custom-provider key/reference | Global config literal, or private record plus optional vault payload | Provider Authorization header only | Config contains reference after migration; no resolved value in model metadata/payload |
| MCP literal header/reference | Global/project config literal, or private record/vault | Bound MCP HTTP headers only | MCP approval/status/error text omits credentials |
| MCP stdio environment/reference | Global/project config literal, or private record/vault | Bound configured child only | Never inserted into host environment or unrelated command defaults |
| MCP `bearer_token_env` | Host environment | Named MCP Authorization header | Host environment is not bulk inherited by children |
| Provider account OAuth/API keys | Existing private account records and optional vault | Existing provider account transport | Account metadata separated from token fields; refresh locking unchanged |
| MCP OAuth | Existing protected `mcp-auth` files | Verified MCP resource OAuth transport | Existing state/PKCE/refresh behavior unchanged |

Diagnostic redaction removes known request credentials, their JSON/URL-encoded forms, structured sensitive fields, URL details, common bearer/key/JWT patterns, and terminal controls before bounding output. Custom HTTP errors and streaming/parser exceptions drop raw causes. Oversized custom HTTP error bodies are omitted rather than truncating an echoed token. MCP protocol errors omit server error bodies; child stderr is discarded. Provider catalog diagnostics share the bounded redactor. Normal successful model/MCP content is not rewritten.

Redaction is best effort, not guaranteed secret detection. It does not inspect arbitrary encodings, provider model labels, successful model/tool content, user-authored messages or exported files. If users/tools deliberately read secret files or include credentials in conversation, those contents can enter model context, persistence and exports. User-authored exports remain intact; automatic transport credentials are never added to them. Review confidential content and protect backups separately. See [storage and privacy](storage-and-privacy.md) and [subprocess environments](configuration.md#subprocess-environments).
