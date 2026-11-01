# Authentication

Syleks implements **Sign in with ChatGPT — ChatGPT plan usage for open-source applications**. A valid identity without the granted `chatgpt.tokens.use.direct` scope is retained with **Plan usage: Disabled**; inference is blocked. Eligibility, workspace policy and quota remain OpenAI decisions. No ChatGPT conversation access is granted.

Official sources checked on 2026-10-18: [quickstart](https://developers.openai.com/siwc/quickstart), [sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), [accounts/sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions), [token reference](https://developers.openai.com/siwc/token-sharing-open-source/token-reference), [inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference), [errors](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery), [preview restrictions](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations). These contracts take precedence over assumptions about subscriptions.

## First connection

```sh
bin/syleks auth login chatgpt
bin/syleks auth status
bin/syleks model list
bin/syleks model use chatgpt/<slug-from-list>
bin/syleks --new
```

Or use `/connect` → ChatGPT in the TUI. The same OAuth service handles both paths; list/status/login commands do not open the TUI.

SpaceXSI, OpenRouter, OpenCode Console and Google use the same masked API-key login as OpenAI API billing. `auth login spacexsi` reads `XAI_API_KEY` only when there is no console; `auth login openrouter` reads `OPENROUTER_API_KEY`; `auth login opencode` reads `OPENCODE_API_KEY`; `auth login google` reads `GEMINI_API_KEY`. None of those variables is consulted at startup. Logout removes the local key and does not call the provider. Revoke the key in that provider's console. SpaceXSI requests go to `https://api.x.ai/v1` with `XAI_API_KEY`. Gemini requests go to `https://generativelanguage.googleapis.com/v1beta/openai/` with `GEMINI_API_KEY`.

Syleks creates a stable `urn:uuid:<UUIDv4>` host ID and starts `http://127.0.0.1:<available-port>/auth/callback` before opening the browser. Initial authorization uses `dynamic_agent_client` and `agent_name_hint=Syleks` at `https://auth.openai.com/api/accounts/authorize`. The callback's issued client ID is used at `https://auth.openai.com/api/accounts/oauth/token`, then saved with the validated registration. The dynamic entrypoint is never persisted as an issued ID. No client secret, partner key, foreign OAuth client, Codex credential, cookie extraction or password automation is used.

Each attempt has fresh random state/nonce and PKCE S256. The exact redirect URI/resource are preserved for form-encoded exchange. An expired code (`invalid_grant`) can restart once with the issued client ID and new state/PKCE. Nimbus JOSE+JWT verifies the ID token's RS256 signature against OpenAI JWKS, issuer, issued-client audience, expiry, nonce, subject and authorized party where applicable. Only verified email is retained. Returning authorization must match the selected issuer/subject. Nimbus caches JWKS and refreshes unknown keys.

The callback accepts only the expected path/method. Malformed/duplicate parameters and wrong states are rejected; wrong-state requests do not consume a valid attempt. Success HTML contains only “Authentication successful. You can return to Syleks.” Errors disclose no tokens/codes. Resources close after success, timeout or cancellation. `SYLEKS_AUTH_TIMEOUT_SECONDS` changes the default 180-second timeout.

## Linux, macOS and SSH

Browser opening is asynchronous: `xdg-open` (Linux), `/usr/bin/open` (macOS), or the Windows URL handler. Browser launch avoids Java AWT initialization, which can abort GraalVM native executables. A manual URL remains available; TUI `C` copies it when a clipboard is supported, `O` retries opening and arrows scroll. Displayed URLs omit retained identity hints. URLs with `id_token_hint` never enter chat history, exports, configuration or diagnostics. Access/refresh tokens never appear in URLs.

On SSH, browser loopback is the browser's machine. After Syleks displays the port, create a tunnel in another terminal and then open the URL locally:

```sh
ssh -N -L 127.0.0.1:<port>:127.0.0.1:<port> user@remote-host
```

Keep both ends on loopback and the same port; do not edit the redirect mid-attempt or expose the listener publicly. Increase the timeout if needed. OpenAI's [VM credential-transfer guide](https://developers.openai.com/siwc/token-sharing-open-source/self-hosted-vms) is another documented approach; Syleks provides no automatic import command. Do not copy host IDs or allow separate hosts to refresh copied token sets independently.

## Accounts and sign-out

```sh
bin/syleks auth accounts
bin/syleks auth login chatgpt                   # add registration
bin/syleks auth use <local-account-id>
bin/syleks auth login chatgpt <local-account-id> # reconnect registration
bin/syleks auth logout chatgpt <local-account-id>
```

`/accounts` offers the same actions. Each registration has a local UUID and independent client ID/identity/credentials, even for matching emails. Returning sign-in reuses its client/host IDs and retained validated identity/email hints; sign-out removes the token hint. Reconnection after missing plan permission uses supported `prompt=consent`, without assuming `force_reconsent` preview rollout.

Sign-out attempts the revocation endpoint from OpenAI discovery, with refresh token and issued client ID, before clearing that account's local tokens. Client/host/identity mappings survive. Temporary failures get bounded retries; if remote revocation is unconfirmed, disconnect Syleks in ChatGPT Settings. Other accounts stay connected. API-key logout only clears local storage: revoke the API key separately in OpenAI API settings.

## Storage

New provider settings/credentials use an absolute `XDG_CONFIG_HOME`, or `~/.config`:

```text
~/.config/syleks/
  config.json                         # provider/model/account references only
  auth/
    host.json                         # stable host ID
    <account-id>.lock                 # OS lock
    <account-id>.refresh-pending.json  # uncertain/in-progress renewal marker
    accounts/<account-id>.json        # registration and protected credential record
```

Unix directories use `0700`, files `0600`. Writes force a private temporary file to disk and atomically replace the record; managed private-path symlinks are rejected. Atomic rename support is required. Existing `agent-config.json` and agent-file paths are preserved. SQLite sessions contain references, never credentials.

`SYLEKS_CREDENTIAL_BACKEND`:

| Value | Behavior |
| --- | --- |
| `auto` (default) | Prefer Linux Secret Service when `/usr/bin/secret-tool` and the user D-Bus session exist; otherwise protected files |
| `secret-service` | Require/unlock the system keyring; fail clearly if unavailable |
| `file` | Explicit protected-file fallback |

The libsecret adapter sends secrets through process stdin/private pipes, never command arguments. Disk records contain opaque vault references. Rotation creates a new vault entry before atomically replacing the reference. Keyring errors do not silently downgrade existing vault records to plaintext. Unlock the keyring to access them. A fake vault covers this path in tests; a real desktop keyring was not exercised.

macOS uses the protected-file fallback: a Keychain bridge is not implemented. Non-POSIX filesystems rely on user directory ACLs and were not validated. The fallback is **not encrypted at rest**; protect the home directory and use encrypted storage as appropriate. Never commit/share credential files or include them in bug reports.

## Expiration and recovery

The currently documented lifetimes are one hour for access and 30 days per replacement refresh token. Syleks follows actual `expires_in`/`earliest_refresh_at` responses, refreshing near expiry with the issued client ID/current refresh token/original resource, without a new scope.

JVM and OS file locks serialize each registration's refresh across processes. Credentials are reread under lock; replacement tokens are committed together before use. A pending marker prevents replay after a process interruption or ambiguous network outcome: reconnect the saved registration rather than resubmitting a possibly consumed refresh token. Known temporary server rejections receive at most three attempts with bounded backoff. Network failures retain credentials. Confirmed unusable/reused/revoked refresh tokens clear only that token set and preserve the registration. Refresh retains the last verified ID-token hint instead of trusting a decoded replacement.

| Error | Recovery |
| --- | --- |
| Browser unavailable | Open/copy the displayed URL while the listener waits |
| Timeout/cancel/state/nonce/client/JWT failure | Retry or reconnect; unvalidated identity is never activated |
| Missing direct-plan scope | Retain identity, disable inference; explicitly grant permission or choose a provider |
| `subscription_sharing_user_not_eligible` | Account/workspace/policy restriction; do not loop OAuth |
| `subscription_sharing_usage_limit_exceeded` | Review ChatGPT Settings → Usage; app limits can apply, reset time is not inferred |
| `subscription_sharing_usage_unavailable`, HTTP 503 | Retain credentials, retry later |
| `subscription_sharing_unsupported_capability` | Change the reported feature/model/parameter |
| `subscription_sharing_route_not_supported` | Check the method/route; no alternate billing fallback |
| `subscription_sharing_invalid_user`, HTTP 401 | Check account/permission; reconnect for confirmed revocation/terminal refresh errors |
| HTTP 403 | Surface region, policy or permission restriction |
| Saved model unavailable | Refresh `/models`, explicitly select replacement |
| Refresh outcome uncertain | Reconnect saved registration; do not replay old refresh token |

Errors retain bounded, redacted diagnostics, machine codes and request IDs when present. Inference is not retried automatically after streamed output. No token/body wire tracing is added; do not enable JDK HTTP header/body logging with credentials.

## Validation

`mvn clean verify` runs simulated HTTP/OIDC/JWKS/browser/vault tests, separate-JVM refresh probes, SQLite integration and TUI keyboard tests. No real token, browser, subscription or paid request is needed. Check the shaded JAR with `--help`, `provider list` and `auth status`.

Real OAuth/subscription inference has **not** been performed in this development environment. Manual acceptance:

1. Launch `bin/syleks --new`, `/connect`, choose ChatGPT and authorize in a real browser. Verify success HTML and actual plan permission.
2. Select a catalog model, send a small prompt and confirm streaming through completion.
3. Restart with `bin/syleks -c`; verify account/history, then renewal after real expiry without editing/replaying tokens.
4. Add/switch/reconnect a second account and sign out one; inspect remote revocation in ChatGPT Settings.
5. Switch Ollama/ChatGPT, resize, filter models, cancel authentication/generation, and inspect retained partial output.
6. With `allow_cloud: false`, verify cloud use fails while loopback Ollama works.

Success depends on actual account authorization/eligibility. JVM packaging does not validate native-image builds, a macOS desktop/browser or a live system keyring.

## Credentials in configuration examples

The [full example](https://github.com/fpapon/syleks/blob/main/examples/agent-config.json) separates three mechanisms: a literal `options.apiKey` for a custom compatible server, proxy-only settings for built-in authenticated providers, and disabled MCP bearer/OAuth servers. Replace or remove placeholder values; `${VARIABLE}` inside a provider key is sent literally. Built-in cloud accounts are managed with the login commands above, not agent frontmatter.

MCP authentication is separate: `bearer_token_env` reads an explicitly named environment variable, while `oauth: true` uses `syleks mcp auth SERVER`. See [MCP authentication](mcp.md#oauth) and [configuration locations](configuration.md#files-that-are-separate-from-agent-configjson). Agent prompts and shared `AGENTS.md` instructions should contain no credentials.

OAuth HTTP requests also enforce the [transport policy](providers.md#transport-security) before sending codes, refresh tokens or other form credentials. The global `insecure_http_origins` setting does not apply to OAuth. Production OpenAI identity endpoints remain fixed HTTPS; MCP OAuth keeps its narrower exact loopback fixtures and existing issuer/PKCE/state checks.

Custom provider keys and MCP header/environment values support destination-bound secret references. See [explicit migration, rotation and storage limits](secret-storage.md).
