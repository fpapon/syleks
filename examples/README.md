# Configuration and agent examples

These files document supported Syleks settings. Addresses, models, keys, proxy names and paths are examples; replace them before use. No example downloads a model or installs a tool server.

| File | Use |
| --- | --- |
| [agent-config.json](agent-config.json) | Full reference: every top-level setting, multiple providers/models, proxies, every agent field, permissions, child budgets, stdio/HTTP/OAuth MCP variants |
| [agent-config-local.json](agent-config-local.json) | One OpenAI-compatible endpoint, Build/Plan and read-only specialists; no proxy or active MCP servers |
| [agent-config-ollama.json](agent-config-ollama.json) | Ollama with the same tool workflow and bounded delegation; no custom provider required |
| [permissions.json](permissions.json) | Permission **fragment** to merge into an existing configuration |
| [mcp-project.json](mcp-project.json) | Project MCP example for `.syleks/agent-config.json`; requires global workspace trust |
| [engineering/project-registry.json](engineering/project-registry.json) | Proposed Engineering v1 enrollment contract: private project, cloud disabled, token-budget declaration and no approved inference routes |

The full reference includes mutually exclusive alternatives as **separate server entries**. All its MCP servers are disabled; enable only the ones you configure. Remove the example proxies unless you have such a proxy. `mcp_trusted_projects` is deprecated and grants no authority. Use `syleks trust inspect` and `syleks trust grant <reviewed-sha256>` after reviewing the project definitions.

## Engineering contract example

[engineering/project-registry.json](engineering/project-registry.json) uses the separate [Engineering contract schema](../src/main/resources/engineering/contracts-v1.schema.json), rather than the agent configuration schema. The public contract remains proposed pending [ADR 0001](../docs/engineering/adr/0001-engineering-contracts.md) approval.

Replace `root_path` with an existing absolute project directory before using it as `~/.config/syleks/engineering-projects.json`. Keep this user-owned file outside the enrolled project and restart after changes. Empty `technology_packs` and `local_routes` are intentional: the example installs no template content and authorizes no inference endpoint. The token budget is a validated contract declaration; P01 does not implement its aggregate spending ledger. See [Engineering contracts](../docs/engineering/CONTRACTS.md) for pinned external template paths and reviewed local routing.

## Install a JSON configuration example

1. Run `syleks config init --defaults` to create the user directory, local schema and starter agents.
2. Back up any existing `~/.config/syleks/agent-config.json` privately. Choose **one** JSON configuration above and copy its content into that file. Merge settings instead if you want to retain an existing configuration.
3. Change `$schema` from `../agent-config.schema.json` to `./agent-config.schema.json` to use the schema installed beside the file. Schema paths are editor metadata; they do not change runtime behavior.
4. Replace addresses/model IDs and remove unused provider entries, proxy settings and credentials. Provider API keys are literal values; `${ENV_VAR}` and `{env:NAME}` are not expanded. MCP's `bearer_token_env` is the explicit environment-based alternative for MCP HTTP tokens.
5. Inspect existing global and project agent Markdown files. They override JSON agent fields and prompts. To use these JSON policies, reconcile the Markdown definitions, or install the matching examples below. For the Ollama variant, remove `model` from copied Markdown definitions to inherit Ollama, or replace it with your exact Ollama ID.
6. Run `/restart` in chat, or launch `syleks --new`. Check `/agents`, `/permissions build`, `/permissions reviewer` and the selected model before using tools.

`agent-config.json` uses `local/qwen3:8b` by default. Architect demonstrates a separate model on `remote/team/reasoner`: configure that server or change Architect's `model`. Optional empty model entries such as `{}` use fallback token budgets. Use `model: "ollama/qwen3:8b"` to select the built-in Ollama provider.

## Install agent and subagent definitions

Copy selected `.md` files from [agents/](agents/) into `~/.config/syleks/agents/` for global use, or `.syleks/agents/` in your project for project overrides. Project model overrides and capability expansions require the same content review. Explicit global denials remain ceilings; the full examples intentionally retain restrictive mutation and command ceilings. Both primary and subagent files go in **the same `agents/` directory**; there is no separate runtime `subagents/` directory. Keep the filenames: they define the names used in commands and permissions.

| Definition | Mode | Behavior |
| --- | --- | --- |
| [build.md](agents/build.md) | `primary` | Read/search, approval-gated edits/patches/shell, optional Explore/Reviewer delegation |
| [plan.md](agents/plan.md) | `primary` | Inspect and plan with native read/search tools; deny file changes and shell |
| [architect.md](agents/architect.md) | `primary` | Compare designs using a separate model; may delegate to Explore/Reviewer |
| [explore.md](agents/explore.md) | `subagent` | Locate code and return evidence; deny changes, shell and nested tasks |
| [reviewer.md](agents/reviewer.md) | `subagent` | Inspect source/tests and return actionable findings; deny changes, shell and nested tasks |
| [triage.md](agents/triage.md) | `all`, hidden | Read-only investigation, selectable or invocable by explicit name |
| [retired.md](agents/retired.md) | `subagent`, disabled | Disable an inherited definition; no body is needed |

The active examples show every supported frontmatter field. Build/Plan/Explore/Reviewer/Triage select `local/qwen3:8b`; Architect selects `remote/team/reasoner`. Declare those provider/model entries in JSON, change them, or omit the `model` line to inherit model selection. A child without `model` inherits the parent's current model. The disabled definition still undergoes field/model validation.

Example after installing the full reference and desired Markdown definitions:

```text
/agent build
/permissions
@explore Find the entry point and summarize the configuration-loading path. Include file references.
@reviewer Review src/main/java/fr/openobject/ai/syleks/app/AppConfig.java for validation bugs. Do not change files.
/children
/parent
/agent triage
```

`/parent` applies after opening a child with `/children` or `/session ID`. A manual subagent answer returns to the parent automatically. Triage is hidden from lists and Tab but available explicitly. Retired cannot run. Parent permissions cap every child tool: a child `allow` cannot undo an ancestor `deny`.

See the [configuration reference](../docs/configuration.md), [agent format and inheritance](../docs/agents.md), [subagent task protocol](../docs/subagents.md), [permission rules](../docs/tools-and-permissions.md), and [MCP setup](../docs/mcp.md) for defaults, limits and runtime behavior.

Command examples use the [minimal subprocess environment](../docs/configuration.md#subprocess-environments).
For a Java/Maven installation outside `/usr/bin` and `/bin`, add its absolute executable directories
to global `command_environment.trusted_path` and opt into `JAVA_HOME` with `pass_env` if needed.
Project and agent examples cannot enable environment passthrough.

Provider and MCP examples require HTTPS for remote endpoints by default. [The insecure HTTP example](agent-config-insecure-http.json) shows an explicit global origin exception for a reviewed LAN server; its credentials and conversation data travel in plaintext. Other HTTP examples are loopback fixtures; see the [transport decision matrix and migration guidance](../docs/providers.md#transport-security).

[The restricted command example](agent-config-sandbox.json) shows the global-only Linux sandbox policy, including its separate network capability and resource limits. Merge it into global settings and restart; see [dependencies and migration](../docs/command-sandbox.md). PATH additions do not expose SDKs outside restricted runtime mounts.

For native web tools, merge `"web_private_origins": ["https://intranet.example:8443"]` into global settings only when you intend to expose that internal service to model-directed requests. URL permissions still apply, and metadata/reserved addresses remain denied. The full reference defaults to no private grants. See the [destination matrix and migration guide](../docs/tools-and-permissions.md#web-destination-policy); provider/MCP and command network settings are separate.

Custom provider keys and MCP header/environment values support destination-bound secret references. See [explicit migration, rotation and storage limits](../docs/secret-storage.md).
