# Référentiel figé

Audit du 18 octobre 2026, checkout Syleks `d1c179a9c412b2a86be3a6a2bd62f7e2643b4e0a`, version Maven `0.1.0-SNAPSHOT`, **avec modifications utilisateur préexistantes**. Les empreintes du code effectivement audité sont dans [source-manifest.json](source-manifest.json). Le commit seul ne reproduit donc pas cet état.

## OpenCode retenu

| Élément | Valeur vérifiée |
|---|---|
| Dernière stable publique renvoyée par GitHub `/releases/latest` | **v1.19.3** |
| Publication | **2026-10-16T12:31:52Z** |
| Tag résolu en commit | **e3c4eb242585dcd77be1a867391b7c25fd47ce52** |
| draft / prerelease | false / false |
| Release | [publication officielle](https://github.com/anomalyco/opencode/releases/tag/v1.19.3) |
| API release | [latest](https://api.github.com/repos/anomalyco/opencode/releases/latest) |
| Résolution indépendante du tag | [git ref](https://api.github.com/repos/anomalyco/opencode/git/ref/tags/v1.19.3) |

Le `target_commitish` de la release vaut `60c9be092129745688bb3c3a752affc6cbd66ede`. **Ce champ n’est pas utilisé comme commit du tag** : le ref et l’arbre Git répondent `e3c4eb2…`. Les champs utiles des réponses API sont archivés dans [baseline-api.json](baseline-api.json).

## Génération documentaire et sources

La page web Agents a été consultée pour orientation. La comparaison normative utilise ensuite **les onze fichiers MDX présents dans le commit du tag**, pas le site vivant. Il n’est pas possible de prouver que le site déployé correspond exactement à ce commit ; cette incertitude est neutralisée par les liens figés. [baseline-sources.json](baseline-sources.json) donne les URL immuables et SHA-256 des documents et sources consultés.

| Documentation officielle demandée | Source utilisée au tag |
|---|---|
| [Agents](https://opencode.ai/docs/agents/) | `packages/web/src/content/docs/agents.mdx` |
| [Tools](https://opencode.ai/docs/tools/) | `tools.mdx` |
| [Custom tools](https://opencode.ai/docs/custom-tools/) | `custom-tools.mdx` |
| [MCP](https://opencode.ai/docs/mcp-servers/) | `mcp-servers.mdx` |
| [Permissions](https://opencode.ai/docs/permissions/) | `permissions.mdx` |
| [CLI](https://opencode.ai/docs/cli/) | `cli.mdx` |
| [TUI](https://opencode.ai/docs/tui/) | `tui.mdx` |
| [Configuration](https://opencode.ai/docs/config/) | `config.mdx` |
| [Règles](https://opencode.ai/docs/rules/) | `rules.mdx` |
| [Skills](https://opencode.ai/docs/skills/) | `skills.mdx` |
| [Serveur applicatif](https://opencode.ai/docs/server/) | `server.mdx` |

Les noms seuls n’établissent pas le comportement : `tool/task.ts`, `tool/registry.ts`, `tool/shell.ts`, `agent/subagent-permissions.ts`, `mcp/index.ts`, `mcp/catalog.ts` et `mcp/oauth-provider.ts` ont également été examinés. Tous viennent du **même commit**. Le chemin interactif de `packages/opencode` est retenu ; les nouvelles abstractions de `packages/core` ne sont pas présumées être les outils effectivement sélectionnés par ce chemin. En particulier, `ToolRegistry` utilise `ShellTool`, exposé sous le nom `bash` ; c’est ce chemin qui analyse les commandes shell pour les permissions.

## Capacités de comparaison

Agents configurables Build/Plan et modes primary/subagent/all ; invocation manuelle et tool `task` ; sessions enfants avec résultat corrélé et profondeur bornée ; persistance/reprise/fork/compactage ; tools natifs et extensions ; permissions allow/ask/deny et patterns ; clients MCP stdio et distant ; intégration des tools MCP au modèle ; configuration/règles/skills ; séparation TUI et serveur applicatif. Les formats Java/Syleks sont acceptés dès lors que le comportement est équivalent.

Le rôle MCP attendu est **client de serveurs externes**, pas serveur MCP embarqué. Le serveur HTTP applicatif OpenCode est un autre rôle. Son absence dans Syleks est décrite architecturalement, sans inventer une exigence de serveur MCP.

## Stable, expérimental et protocole

`OPENCODE_EXPERIMENTAL_BACKGROUND_SUBAGENTS` conditionne le mode background du tool task ; `OPENCODE_EXPERIMENTAL_LSP_TOOL` conditionne le tool LSP. Ces options sont identifiées séparément, pas exigées comme fonctionnalités stables activées par défaut. Le manque de sous-agents simultanés reste un écart sur la capacité de délégation concurrente ; il ne prouve pas que tous les mécanismes background du tag sont stables. Les autres routes core/v2 ou changements postérieurs au tag ne sont pas utilisés pour augmenter les exigences.

La [spécification MCP consultée](https://modelcontextprotocol.io/specification/2026-07-28) est celle vers laquelle l’URL non versionnée redirigeait le jour de l’audit. Elle sert à distinguer rôles et capacités, **pas à imposer rétroactivement cette version de protocole à OpenCode**. Le tag emploie le SDK MCP, `initialize` via `client.connect`, stdio, Streamable HTTP et fallback SSE. Aucune négociation réseau réelle OpenCode n’a été exécutée pour affirmer une version MCP wire particulière.

La compatibilité de base d’un client tools exige transport, dialogue protocolaire, découverte et appels avec gestion des erreurs ; un GET JSON ne suffit pas. Resources/templates et prompts sont optionnels au protocole mais présents dans le client OpenCode du tag (`mcp/index.ts` et `catalog.ts`), donc décrits à part dans MCP-008. Sampling, extensions Tasks, Apps et Skills over MCP ne sont pas des exigences de cette matrice. Les collisions sont une exigence de sûreté à tester, pas une certification que le préfixage OpenCode résout toute collision après normalisation.

## Limites

Sources publiques récupérées après autorisation réseau ; aucun binaire OpenCode, test OpenCode ni provider réel exécuté. La référence prouve une release publiée et les comportements documentés/implémentés dans ses sources, pas une certification de leur fonctionnement universel. Aucune analyse d’artefact livré nécessitant rétro-ingénierie n’a été entreprise.
