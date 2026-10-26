# LLM providers

| ID | Transport | Authentication | Billing/data boundary |
| --- | --- | --- | --- |
| `ollama` | `/api/chat` NDJSON; discovery `/api/tags` | None | Local for loopback `provider.ollama.options.baseURL` |
| `chatgpt` | Public `/v1/responses` SSE; discovery `/v1/models` | Official Sign in with ChatGPT for open-source clients | Eligible, separately authorized ChatGPT plan usage |
| `openai-api` | Public `/v1/responses` SSE; discovery `/v1/models` | Explicit API key login | Separate OpenAI API billing |
| `spacexsi` | `POST https://api.x.ai/v1/chat/completions` SSE; discovery `GET /v1/language-models` | Explicit API key login (`XAI_API_KEY` only for that command) | SpaceXSI credits. Grok text models only |
| `openrouter` | `POST https://openrouter.ai/api/v1/chat/completions` SSE; discovery `GET /v1/models` | Explicit API key login (`OPENROUTER_API_KEY` only for that command) | OpenRouter credits. Model ids keep `author/slug` |
| `opencode` | Per model: `https://opencode.ai/inference/openai/v1/responses` or `.../chat/completions`; discovery `GET https://opencode.ai/inference/v1/models` | Explicit API key login (`OPENCODE_API_KEY` only for that command) | OpenCode Console credits. Anthropic, Google and System One models are listed and refused |
| `google` | `POST https://generativelanguage.googleapis.com/v1beta/openai/chat/completions` SSE; discovery `GET .../openai/models` | Explicit API key login (`GEMINI_API_KEY` only for that command) | Gemini API billing |
| Existing configured ID | Existing `/chat/completions` adapter | Existing configuration | Depends on endpoint |

Identity, catalog visibility, model capabilities, subscription eligibility and available quota are distinct. Syleks never switches endpoint/provider/billing automatically. No OpenAI model list is bundled.

## Commands

```sh
bin/syleks provider list
bin/syleks provider use ollama
bin/syleks model list
bin/syleks model use ollama/qwen3:8b
bin/syleks auth login chatgpt
bin/syleks provider use chatgpt
bin/syleks model list
bin/syleks model use chatgpt/<slug-from-list>
bin/syleks auth login spacexsi
bin/syleks model use spacexsi/grok-4.7
bin/syleks auth login openrouter
bin/syleks model use openrouter/<author>/<slug>
bin/syleks auth login opencode
bin/syleks auth login google
bin/syleks model use google/gemini-3.8-flash
bin/syleks --new
```

Bare model IDs resolve relative to the active provider. Use qualified `provider/model` IDs in scripts and for Ollama model names containing `/`. Existing chat/session/config commands, environment settings and agent model overrides remain supported. Once explicitly saved, `config.json` supplies the provider/model default unless an agent overrides it.

TUI commands: `/connect`, `/providers`, `/models`, `/accounts`, `/logout`; existing `/model provider/model` also works. Arrow keys navigate, Enter selects, Esc cancels. Type in `/models` to filter; `R` with an empty filter reloads it. The provider and connection state appear beside the conversation title; the model appears above the editor. Authentication/account changes preserve conversation history.

ChatGPT uses **Continue with ChatGPT**. OpenAI, SpaceXSI, OpenRouter, OpenCode and Gemini API keys have a separate masked modal or hidden CLI prompt. A noninteractive `auth login` reads `OPENAI_API_KEY`, `XAI_API_KEY`, `OPENROUTER_API_KEY`, `OPENCODE_API_KEY` or `GEMINI_API_KEY` only for that command. Those variables are never a startup fallback. Keys should not be arguments or committed configuration.

SpaceXSI is the provider name on the xAI API. The host stays `https://api.x.ai/v1` and the environment variable stays `XAI_API_KEY`. Text models come from `GET /v1/language-models` and keep catalog order; image-only models are omitted. `grok-4.7` is the current coding model when it appears in that catalog. Hosted xAI tools stay off.

OpenRouter model ids contain `/`. The selection `openrouter/openai/gpt-6-sol` sends wire model `openai/gpt-6-sol`.

OpenCode Console is pay-as-you-go. The catalog does not name a transport, so Syleks uses the endpoint table from the OpenCode docs dated 2026-10-20. `gpt-*`, `grok-*` and `muse-spark-*` use Responses. The documented chat-completions models, including `kimi-k2.6`, use chat completions. Claude, Qwen models on the Anthropic API, Gemini and Jev stay in `/models` with the reason they cannot be selected, and chat fails before a socket is opened. A model id that is not in that table fails with `transport not documented` rather than a guessed endpoint. OpenCode Go subscription sign-in is not implemented.

Google uses the Gemini API's OpenAI-compatible chat endpoint. A catalog id of `models/gemini-3.8-flash` is stored and sent as `gemini-3.8-flash`. Image and video generation are not called. `GEMINI_API_KEY` is the documented variable; `GOOGLE_API_KEY` is not read. OpenCode models that use Google's native endpoint stay refused: use `auth login google` for a Gemini API key.

## Models, streaming and tools

ChatGPT discovery uses the account's `models` catalog, preserves ordering, displays `display_name`, selects `slug`, and includes only `visibility: "list"`. API-key discovery uses `data[].id`. Catalogs refresh after sign-in/account selection, when opening/refreshing the picker and before cloud inference. Unavailable saved models require explicit replacement. Unknown model capabilities are not inferred from names.

Responses adapters reconstruct context in `input`, use `instructions` for system/developer context and always set `store: false`, `stream: true`. HTTP continuation IDs and unsupported plan-route sampling/output-limit fields are omitted. Only `response.completed` confirms success. Failed/incomplete/truncated streams retain partial output and persist an error status; cancellation retains interrupted output.

Existing function tools retain their permission checks. Complete function-call items are executed only after the response completes successfully; calls/results are adapted for later turns. Hosted tools are not enabled. Transport support for function calls does not prove account/model support; unsupported-capability errors stop generation.

## Sessions and confidentiality

SQLite schema 2 adds a nullable `account_id`. Migration is transactional. Sessions/forks retain provider ID, model selection, local account reference, messages and structured tool history. Tokens are never stored in SQLite or exports. Opening restores references; renewal and catalog verification happen before inference. Missing/signed-out accounts require `/accounts` or `/connect`, without silently selecting another account/provider. Unrepresentable tool history fails explicitly.

Cloud setup displays a notice that messages, necessary history, instructions (including project `AGENTS.md`) and approved tool results leave the local environment. Authentication/selection itself uploads no conversation or repository. The next explicit message sends the required context to the selected provider. Syleks adds no telemetry or automatic whole-repository upload. Individual file access remains permission-controlled.

To forbid cloud requests, create `.syleks/project.json`:

```json
{"allow_cloud": false}
```

Every ancestor policy applies; children cannot relax restrictions. Invalid policies fail closed. `SYLEKS_ALLOW_CLOUD=false` also disables cloud selection, catalogs and generation. Loopback Ollama and configured loopback servers remain usable without Internet/cloud credentials. Remote Ollama endpoints are treated as cloud for this policy.

## Architecture and extensions

`LlmProvider` exposes identity, model catalog, capabilities and cancellable streaming `chat` on the existing generation worker. `ChatRequest` holds canonical context and tool/observer hooks. All built-in adapters and configured Chat Completions adapters use the same Fusion-managed `AgentRuntime` for model/tool rounds, limits, cancellation and typed outcomes. Adapters own wire formats and encode tool history through `ToolHistory`.

`ToolExecutor` validates arguments, checks the active agent's permissions and performs tool effects independently of the terminal and provider. `AgentObserver` carries typed lifecycle and result notifications; `ChatEvent` exposes `STATE_CHANGED` with `AgentState` and `TOOL_RESULT` with `ToolCompletion`. Observers receive tool start before execution, including for denied calls. A failed tool can return its diagnostic to the model for recovery; a cancelled tool stops the loop. Legacy string tool callbacks remain compatible and explicitly represent successful text, without status inference from text prefixes.

`ProviderRegistry` and `ProviderService` use Fusion application scope and constructor injection with `@Init` initialization, compatible with Fusion's lazy proxies. Dedicated services handle authentication, OIDC validation, refresh and credential storage. `TerminalApp` owns terminal output; workers use synchronized session state and queued UI completions.

Extend by implementing/registering `LlmProvider`, with documented authorization and capabilities. No Anthropic/Google subscription OAuth is implied. Existing configured OpenAI-compatible providers use their adapter; `ollama`, `chatgpt`, `openai-api`, `spacexsi`, `openrouter`, `opencode` and `google` are reserved built-in IDs.

See [Authentication](authentication.md), OpenAI's [models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference) and [preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

## Provider configuration examples

The [single-server example](../examples/agent-config-local.json) declares an OpenAI-compatible endpoint and model catalog. The [full reference](../examples/agent-config.json) adds a second provider, a model ID containing `/`, per-provider proxies and per-agent model selection. The [Ollama example](../examples/agent-config-ollama.json) uses the built-in adapter without a custom provider entry. See [installation](../examples/README.md) and [provider fields](configuration.md#provider-and-model-fields).

Reserved `provider.ollama`, `provider.chatgpt`, `provider.openai-api`, `provider.spacexsi`, `provider.openrouter`, `provider.opencode` and `provider.google` entries only configure `options.proxy`; use the account/login flow for cloud credentials. `options.apiKey` on a custom provider is a literal value in `agent-config.json`; it does not read `OPENAI_API_KEY`, `XAI_API_KEY`, `OPENROUTER_API_KEY`, `OPENCODE_API_KEY` or `GEMINI_API_KEY` automatically. An agent's model override can move its requests to another configured provider, including when that agent runs as a child.

## Transport security

All provider chat, model discovery, CLI/setup validation and MCP HTTP requests require HTTPS for remote endpoints by default, even when no credential is configured. Certificate and hostname verification remain enabled. Automatic redirects are disabled, including same-origin redirects: configure the final endpoint directly. A redirect never forwards a token, custom header, or prompt to its target.

| Destination | Provider / MCP HTTP | MCP OAuth |
| --- | --- | --- |
| HTTPS with a valid authority and port | Allowed | Allowed; existing metadata validation still applies |
| HTTP exact `localhost` (case insensitive) | Development only | Existing exact `localhost` fixture only |
| HTTP canonical dotted IPv4 in `127.0.0.0/8` | Development only | Only `127.0.0.1` |
| HTTP IPv6 `::1`, compressed or expanded | Development only | Only `[::1]` |
| HTTP remote or private LAN | Exact global origin exception required | Rejected |
| HTTP misleading localhost suffix or mapped/scoped IPv6 | No automatic loopback trust; exact global exception required | Rejected |
| URL userinfo, query, fragment, empty/zero/out-of-range port | Rejected | Rejected |

To authorize a reviewed remote HTTP server, add its **origin** to `insecure_http_origins` in your global `~/.config/syleks/agent-config.json`:

```json
{
  "insecure_http_origins": ["http://192.168.1.20:8000"],
  "provider": {
    "lan": {
      "options": {"baseURL": "http://192.168.1.20:8000/v1"},
      "models": {"my-model": {}}
    }
  }
}
```

Merge these fields into existing settings and restart chat to apply them. The [complete example](../examples/agent-config-insecure-http.json) also selects the provider/model. The bundled default list is empty. Entries require HTTP, a literal host or exact hostname, and an optional valid port; no wildcard, path (except an optional trailing `/`), credentials, query or fragment. Hostname case and omitted port `80` are normalized. A grant covers all paths on that host/port, including discovery and chat; another host or port requires its own entry. IPv6 authorities retain their literal spelling apart from letter case. The array is limited to 64 entries.

This is global authority only. Trusted project files can use an already granted origin but cannot grant one. No environment variable, tool argument or remembered approval can enable an exception. Setup uses and preserves the existing global list but does not create grants from a URL answer. CLI provider/MCP commands print `INSECURE HTTP` warnings on stderr; chat shows a warning for the selected provider and enabled MCP endpoints, setup warns on the reviewed URL, and connected MCP status includes the warning. Credentials, prompts and tool data sent to an authorized HTTP origin can be observed or changed in transit.

To revoke, remove the origin (or set the list to `[]`) and restart the application. New CLI invocations load the updated policy immediately; an already running chat keeps its loaded policy until restart. Existing requests are not retroactively revoked. Exceptions never authorize redirects and never relax OAuth identity/discovery/token endpoint rules or certificate/hostname verification. MCP with `oauth: true` still requires HTTPS except its narrow loopback fixtures.

Migration: verified HTTPS or a local tunnel remains preferable. Existing files are never rewritten. Remote HTTP without a matching global grant fails validation. The schema checks endpoint syntax and default HTTPS policy; when exceptions are declared, runtime validation enforces exact origin membership and port range. Transport errors omit URL paths, userinfo, query strings and configured credentials. The bundled setup example stays at `http://127.0.0.1:8000/v1`.

Loopback classification is lexical and performs no hostname DNS lookup. IPv6 literal parsing does not resolve DNS. Exact `localhost` still relies on the host resolver; this is a transport policy, not protection against DNS rebinding, malicious proxies, or a same-user host attacker. Actual destination and private-network controls remain SEC-06 work. HTTP development traffic can be observed by the local endpoint and any explicitly configured proxy.
