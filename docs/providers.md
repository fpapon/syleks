# LLM providers

| ID | Transport | Authentication | Billing/data boundary |
| --- | --- | --- | --- |
| `ollama` | `/api/chat` NDJSON; discovery `/api/tags` | None | Local for loopback `OLLAMA_URL` |
| `chatgpt` | Public `/v1/responses` SSE; discovery `/v1/models` | Official Sign in with ChatGPT for open-source clients | Eligible, separately authorized ChatGPT plan usage |
| `openai-api` | Public `/v1/responses` SSE; discovery `/v1/models` | Explicit API key login | Separate OpenAI API billing |
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
bin/syleks --new
```

Bare model IDs resolve relative to the active provider. Use qualified `provider/model` IDs in scripts and for Ollama model names containing `/`. Existing chat/session/config commands, environment settings and agent model overrides remain supported. Once explicitly saved, `config.json` supplies the provider/model default unless an agent overrides it.

TUI commands: `/connect`, `/providers`, `/models`, `/accounts`, `/logout`; existing `/model provider/model` also works. Arrow keys navigate, Enter selects, Esc cancels. Type in `/models` to filter; `R` with an empty filter reloads it. The provider and connection state appear beside the conversation title; the model appears above the editor. Authentication/account changes preserve conversation history.

ChatGPT uses **Continue with ChatGPT**. OpenAI API keys have a separate masked modal/hidden CLI prompt. A noninteractive `auth login openai-api` accepts `OPENAI_API_KEY` only as an explicit login action; it is never used as a fallback for ChatGPT. Keys should not be arguments or committed configuration.

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

Extend by implementing/registering `LlmProvider`, with documented authorization and capabilities. No Anthropic/Google subscription OAuth is implied. Existing configured OpenAI-compatible providers use their adapter; `ollama`, `chatgpt` and `openai-api` are reserved built-in IDs.

See [Authentication](authentication.md), OpenAI's [models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference) and [preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

## Provider configuration examples

The [single-server example](../examples/agent-config-local.json) declares an OpenAI-compatible endpoint and model catalog. The [full reference](../examples/agent-config.json) adds a second provider, a model ID containing `/`, per-provider proxies and per-agent model selection. The [Ollama example](../examples/agent-config-ollama.json) uses the built-in adapter without a custom provider entry. See [installation](../examples/README.md) and [provider fields](configuration.md#provider-and-model-fields).

Reserved `provider.ollama`, `provider.chatgpt` and `provider.openai-api` entries only configure `options.proxy`; use the existing account/login flow for cloud credentials. `options.apiKey` on a custom provider is a literal value in `agent-config.json`; it does not read `OPENAI_API_KEY` automatically. An agent's model override can move its requests to another configured provider, including when that agent runs as a child.
