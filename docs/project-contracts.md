# Engineering project contracts

[User guide](README.md) · [Configuration](configuration.md) · [Storage and privacy](storage-and-privacy.md)

A project contract enrolls a workspace in Syleks' Engineering security boundary. It declares the project's privacy policy, references versioned local technology packs, and describes a token budget. Enrollment applies when Syleks starts in that directory or a descendant directory, including delegated agent work.

The v1 contract is **proposed and awaiting public API approval**. The current implementation validates contracts and packs and enforces runtime restrictions. It does not execute workflows, issue release approvals, run repository analysis, or enforce an aggregate token-spending ledger. Existing chats outside enrolled projects retain their usual behavior.

## Create a project registry

1. Open the [project registry example](https://github.com/fpapon/syleks/blob/main/examples/engineering/project-registry.json). Copy its contents into `~/.config/syleks/engineering-projects.json`, or merge its project entry into an existing registry. Preserve existing entries and keep a private backup before editing.
2. Replace `root_path` with your project's existing absolute directory and choose unique project and budget IDs. Keep `allow_cloud: false` and `provenance: "PRIVATE"` for confidential work.
3. Keep the registry outside every enrolled project. Protect its directory and file with private filesystem permissions; on POSIX systems, use `0700` for the directory and `0600` for the file. Registry and pack paths must not contain symlinks.
4. Leave `technology_packs` and `local_routes` empty initially. No template is installed, and no inference endpoint is authorized by the example.
5. Restart Syleks, or use `/restart` in chat. The existing TUI remains available; model requests are refused until you configure a reviewed local route.

The registry uses the same configuration home as `agent-config.json`: `HOME`, falling back to Java's `user.home`. `XDG_CONFIG_HOME` does not relocate it. Setup and `/config` do not create or edit the registry; edit it separately.

The file is a `project_registry` envelope with `schema_version: 1`, a `projects` array and a `local_routes` array. Each project entry contains:

| Field | What to enter |
| --- | --- |
| `schema_version` / `kind` | `1` and `"engineering_project"` |
| `id` | A unique project identifier, such as `my-project` |
| `root_path` | An existing absolute project directory |
| `allow_cloud` | `false` to prohibit cloud inference; `true` does not currently enable it |
| `provenance` | `PRIVATE` for confidential origin, or `PUBLIC` for public origin; this is not release authorization |
| `technology_packs` | References to separately maintained, version/hash-pinned local packs, or `[]` |
| `token_budget` | The versioned budget declaration shown in the example; aggregate spending enforcement is not implemented yet |

Use the separate [Engineering schema](https://github.com/fpapon/syleks/blob/main/src/main/resources/engineering/contracts-v1.schema.json) for editor validation. Unknown fields and unsupported versions are rejected. Unlike `agent-config.json`, these contracts do not accept a `$schema` property; associate the schema through your editor's settings instead.

## Review and authorize local inference

Before authorizing a route, verify that the server performs inference locally and does not forward prompts, instructions or tool results elsewhere. A provider named Local, a local CLI, or a loopback address alone does not establish that.

After that review, add an entry to the registry's `local_routes` array. For example, a reviewed Ollama server could use:

```json
{
  "provider": "ollama",
  "endpoint": "http://127.0.0.1:11434/api/chat",
  "reviewed_by": "your-name",
  "reviewed_at": "2026-10-25T12:00:00Z"
}
```

Replace the reviewer and timestamp with your actual review details. The endpoint must match the full inference URL exactly, including the path and port. Ollama appends `/api/chat` to its configured base URL; configured OpenAI-compatible servers append `/chat/completions`. Select a matching provider/model through the existing [provider controls](providers.md).

Engineering routes accept only literal `127.0.0.1` or `[::1]` endpoints. `localhost`, LAN addresses and remote hosts are not accepted. Configured proxies are rejected, and implicit system proxies are disabled for enrolled inference. The bundled setup's LAN HTTP endpoint is not automatically authorized. Restart after adding or editing a route.

Route review is a user assertion about the server, not an OS sandbox or a guarantee against a compromised or forwarding listener.

## Reference a separate technology pack

Framework, skill and workflow definitions belong in a separate local template checkout. Syleks does not clone it, download dependencies or run its scripts. Each `technology_packs` reference supplies:

| Field | Meaning |
| --- | --- |
| `id` | The pack ID from its `pack.json` manifest |
| `version` | The exact manifest version, such as `1.0.0` |
| `local_path` | The absolute directory containing that manifest |
| `manifest_sha256` | The lowercase SHA-256 of the manifest's exact bytes |

The manifest also pins each referenced file's digest. Syleks verifies the selected pack's ID, version, paths and contents before accepting it. Changed content requires reviewed version/digest pins and a restart. Loading a pack does not install it into existing agent/skill directories or insert its contents into current chat prompts. See the [pack contract](https://github.com/fpapon/syleks/blob/main/docs/engineering/CONTRACTS.md#local-template-repository-interface) for the manifest format and size limits.

## Understand the current restrictions

| Action in an enrolled project | Current behavior |
| --- | --- |
| Inference through an exact reviewed direct local route | Allowed subject to existing model, permission and context checks |
| Cloud inference | Blocked, including when `allow_cloud` is `true`, until a trusted payload-release gateway exists |
| Shell, web tools, MCP discovery/invocation/authentication, custom Java tools | Blocked before dispatch |
| Native file, skill, todo, question and delegation tools | Available subject to existing permissions and ancestor restrictions; delegated inference still needs an authorized route |
| Existing session navigation, prompt editing and rendering | Retained |
| Workflow execution, release issuance and aggregate budget accounting | Not implemented |

The boundary checks requests before sending context, including summaries and subsequent tool results. An Allow once/Always tool approval cannot override it. Multiple matching ancestor project entries apply together; a child entry cannot relax an ancestor's cloud prohibition.

Private provenance records where information came from. A summary, derived finding, `release_id`, or gate document saying Approved does not make private information public or authorize transmission. Future cloud support requires separate trusted authorization for the exact outbound representation and destination, plus an eligible official subscription integration. P01 provides no such release executor and does not repurpose API keys or OAuth credentials to bypass the restriction.

The budget declaration is validated but does not reserve or charge tokens in P01. Existing model context/output settings and agent/subagent execution limits remain active; see [Token usage](token-usage.md).

## Apply changes and resolve errors

Restart after creating, changing or removing the registry, including changes for another project. Syleks compares the running registry with its startup snapshot; editing or deleting it cannot relax a running session. To leave Engineering mode, deliberately remove the project's entry and restart. Other enrolled projects retain their entries and restrictions.

| Message | What to check |
| --- | --- |
| `INVALID_REGISTRY` | Check JSON syntax, version, required fields, existing root directories, registry location, symlinks and selected pack hashes/versions. Invalid enrollment stops startup rather than reverting to unrestricted operation. |
| `CONFIGURATION_CHANGED_RESTART_REQUIRED` | Restart to load the deliberately edited registry. Restore or repair an accidentally changed file first. |
| `UNVERIFIED_LOCAL_ROUTE` | Compare the exact provider, inference endpoint and proxy settings with the reviewed route. |
| `CLOUD_DISABLED` | The enrolled project prohibits cloud inference. Use a reviewed local route. |
| `AUTHORIZED_RELEASE_REQUIRED` / `OFFICIAL_SUBSCRIPTION_REQUIRED` | Cloud execution is unavailable under the current Engineering boundary; changing credentials does not enable it. |
| `EXTERNAL_TOOL_BLOCKED` | The requested tool can route data or execute outside the local tool boundary and is unavailable in Engineering mode. |

This registry is separate from `.syleks/project.json`, which controls the existing provider cloud policy, and from `agent-config.json`, which configures providers, agents and ordinary permissions. Those policies still apply. For contract details and the pending design decision, see [Engineering contracts](https://github.com/fpapon/syleks/blob/main/docs/engineering/CONTRACTS.md), [compatibility](https://github.com/fpapon/syleks/blob/main/docs/engineering/COMPATIBILITY.md) and [ADR 0001](https://github.com/fpapon/syleks/blob/main/docs/engineering/adr/0001-engineering-contracts.md).
