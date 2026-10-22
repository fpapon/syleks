# SEC-01: Immutable permission ceiling and project trust

Status: not started. Priority: high. Dependencies: none.

## Finding and relevant code

Confirmed by an isolated configuration probe: an automatically loaded project agent can change a global Bash DENY into ALLOW. `AgentCatalog` loads project definitions after user definitions; `AppConfig.permissions()` overlays agent rules onto global rules.

Read:

- [AgentCatalog.java](../../../src/main/java/fr/openobject/ai/syleks/app/AgentCatalog.java)
- [AppConfig.java](../../../src/main/java/fr/openobject/ai/syleks/app/AppConfig.java)
- [PermissionPolicy.java](../../../src/main/java/fr/openobject/ai/syleks/app/PermissionPolicy.java)
- [ContextService.java](../../../src/main/java/fr/openobject/ai/syleks/session/ContextService.java)
- [ToolExecutor.java](../../../src/main/java/fr/openobject/ai/syleks/ai/ToolExecutor.java)
- [SubagentExecutor.java](../../../src/main/java/fr/openobject/ai/syleks/ai/SubagentExecutor.java)

Existing tests: `AgentConfigTest`, `PermissionConfigTest`, `InstructionHierarchyTest`, `SubagentExecutionTest`, `ToolExecutorTest`, and `McpConfigurationTest`.

## Required behavior

- Separate immutable user constraints from per-agent preferences. Explicit user global restrictions form a ceiling; project definitions and approvals can only restrict that authority. Preserve ordered matching within each layer; combine independent constraints using DENY > ASK > ALLOW.
- Distinguish explicit global configuration from built-in defaults. Do not accidentally interpret every built-in default denial as an immutable ceiling and disable intended agent capabilities. Define neutral/missing-rule semantics explicitly and test them.
- Project trust must be an explicit local user action before accepting project permission expansions, model/provider overrides, or executable integrations. Trust never lifts the global ceiling. Prompt text and Markdown frontmatter cannot declare a project trusted.
- Use canonical project identity, store trust outside the repository, support inspection/revocation, and bind capability expansion to reviewed configuration content or require renewed consent after security-relevant changes. Address new agent files and nested definitions as well as modifications to existing files.
- Project `AGENTS.md` content is repository-supplied guidance. Retain source attribution and ensure it cannot replace security policy. Reconcile with the existing MCP trusted-project mechanism rather than maintaining contradictory trust definitions.
- Protect security policy/trust files from dedicated mutation tools and document that unrestricted shell execution still needs SEC-03. A project agent must not write its own authority and acquire it through `/restart`.
- `/permissions` should show the effective decision, originating source, ceiling restriction, and trust state. CLI, primary agents, children, custom tools, and MCP tools use the same enforcement.

## Migration and acceptance

This changes documented override behavior. Document how users explicitly grant a capability at the ceiling and restrict individual agents. Existing explicit global DENY rules must remain restrictive after migration. ASK constraints remain approval-gated. Never auto-trust a project based on a previous successful launch or a model response.

Acceptance tests must cover global DENY plus project ALLOW; global ASK plus project ALLOW; absent explicit global entry versus built-in defaults; broad and narrow ordered rules; nested agent overrides; revoked trust; changed trusted definitions; project symlinks; restart; an approval callback returning true; remembered grants; and child/ancestor intersections. Denied effects must leave markers absent.

## Completion evidence

Provide the policy combination truth table, schema/configuration migration examples, trust lifecycle design, regression results, and before/after reproduction of the confirmed probe. Include terminal evidence for permission provenance and trust controls. Follow [validation.md](validation.md).
