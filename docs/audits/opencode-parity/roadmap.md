# Roadmap de mise à niveau

> **Phase 1, contournement shell P0 : implémenté.** Voir [la validation du correctif](shell-security.md). 

> **Phase 2, moteur agentique P1 : implémenté**, voir [le suivi et sa validation](agentic-engine.md). 

> **Phase 3, agents P1/P2 : implémentée**, voir [le suivi des agents et sa validation](agents.md).

> **Phase 4, sous-agents P1/P2 : implémentée**, voir [le suivi des sous-agents et sa validation](subagents.md).

> **Phase 5, tools P1/P2 : implémentée**, voir [le suivi des outils et sa validation](tools.md).

> Les phases 6 à 8 restent à réaliser.

Cette roadmap propose des changements ultérieurs, **non réalisés pendant l’audit**. Conserver Java 25, Fusion, SQLite et la TUI JLine. Réutiliser les services existants au lieu de multiplier les abstractions. Complexité relative : S locale, M plusieurs composants, L état/lifecycle/protocole transversal ; ce ne sont pas des estimations calendaires.

| Phase | Objectif et priorité | Dépendances | Complexité | Fichiers/composants à modifier ou créer | Validation et critères d’acceptation |
|---|---|---|---|---|---|
| 1. Sécurité critique | P0 : fermer le bypass des chaînes shell ; rendre les limites explicites | Aucune nouvelle infrastructure | L | `ai/ChatSession.java`, `LocalCommandRunner.java`, `app/PermissionPolicy.java`, futur analyseur shell, `docs/tools-and-permissions.md` | Transformer la sonde printf+touch en test de refus ; aucune création du témoin, pipelines/substitutions/redirections évalués ou refusés ; cancel arrête les enfants, DENY ne se relaxe pas via always |
| 2. Moteur agentique | P1 : corriger prompt après switch, unifier états, résultats et boucle | Sécurité de phase 1 pour exécution | L | `ai/ChatSession.java`, `OllamaClient.java`, `provider/ResponsesLlmProvider.java`, `session/AgentService.java`, `ContextService.java`, `provider/ChatEvent.java`, futur runtime/executor Fusion | Build→Plan→Build envoie le bon prompt ; policies au point d’exécution ; mêmes scénarios simulés NDJSON/SSE/Responses ; refus ≠ échec ≠ interruption ; suite existante conservée |
| 3. Agents | P1/P2 : configuration complète et instructions cohérentes | Runtime corrigé | M | `app/AgentDefinition.java`, `AgentCatalog.java`, `AppConfig.java`, `agent-config.schema.json`, defaults, `session/ContextService.java`, menus TUI existants | disable/hidden/modes testés ; modèles/paramètres par agent ; hiérarchie d’instructions et taille bornées ; invalidité fail-closed ; reload/redémarrage documenté |
| 4. Sous-agents | P1 puis P2 : task réel, enfants persistants, résultats et concurrence | Runtime/tool executor, agents ; évolution ciblée des sessions | L | Futurs `TaskTool`, `SubagentExecutor`, `TaskManager`, catalogue tools ; `AgentService`, `SessionService`, modèle/repositories et migrations ; `SessionPicker`/`TerminalApp` pour navigation | E2E-003/004/005 passent ; enfant reçoit payload minimal, provider/policy/quota propres et plafond session ; profondeur bornée, corrélation résultats, cancel/deadline, arbre après restart ; usage sans doublon |
| 5. Tools | P1/P2 : registre extensible, patch, skill/todo/question | Point d’exécution sécurisé de phase 2 | L | `ToolRegistry/ToolExecutor`, `CustomTool/ToolContext`, `PatchTool`, `AuxiliaryTools`, `TodoRepository`, migration V005, schémas/config/providers, commandes TUI existantes | Extension test sans changer moteur central ; collisions refusées ; schémas à exécution ; patch sûr, fichiers Unicode/binaires/gros ; deny sur tous points d’entrée ; aucun accès TUI depuis handler |
| 6. MCP | P1 : client stdio puis Streamable HTTP, auth et intégration ; P2 resources/prompts | Registre/executor, policy dynamique et lifecycle | L | Futurs `mcp/McpClientManager`, registry/transports/auth ; `AppConfig`/schema ; catalogue tools ; observer/session persistence | E2E-007/008/009 sur serveurs simulés : handshake réel, découverte, LLM call/result/history, refus effectif, collision impossible, timeouts/cancel/process cleanup ; auth indépendante du provider ; catalogue/context bornés ; SSE si retenu explicite |
| 7. Sessions et contexte | P1/P2 : résumés, fork au message, import et reprise robuste | Enfants et événements typés ; peut progresser en parallèle après phase 2 | L | `session/ContextCompactor.java`, `ContextService.java`, `SessionService.java`, `AgentService.java`, repositories/migrations ciblées, `app/Main.java`, commandes TUI | Résumé dans requête réelle et DB complète ; roundtrip import/export validé ; fork préfixe/tool pairs cohérents ; quatre frontières crash sans replay ; usage parent/enfants exact ; undo/revert refuse divergence externe |
| 8. Optimisations et ergonomie | P3 : budget/tokens, observabilité, grands projets, navigation et aide | Cycles de vie stabilisés | M | `usage/*`, file/search tools, `TerminalApp`, renderer/picker existants, docs, CI | Mesures reproductibles sur fixtures volumineuses ; réactivité/cancel/redimensionnement vérifiés en terminal réel ; plafonds de sortie, délais et ressources ; rapport d’audit renouvelé avec couverture accrue |

## Ordre pratique

Traiter d’abord le shell et le prompt périmé : ces défauts concernent déjà des fonctions disponibles. Extraire le point de permission/exécution avant d’exposer des outils supplémentaires. Livrer task minimal séquentiel avec une vraie session enfant avant le parallélisme. Implémenter MCP stdio et HTTP avec leurs fixtures au moment du client, puis auth et fonctionnalités optionnelles ; ne pas assimiler webfetch au transport MCP.

Les travaux de sessions nécessaires aux enfants font partie de la phase 4 ; la phase 7 traite leurs fonctionnalités avancées. De même, le noyau ToolExecutor peut apparaître en phase 2 avant l’extension complète en phase 5. Ces dépendances évitent un ordre circulaire.

## Jalons mesurables

1. Zéro effet dans les sondes de commande interdite ; bon prompt après switch ; diagnostics de refus exacts.
2. Parcours Build et Plan complets sur faux providers, état et usage persistés ; pas de régression CLI/TUI.
3. Parent délègue via task, enfant isolé travaille, parent reprend, arbre restauré et annulation bornée.
4. Serveur MCP contrôlé appelé par le LLM via policy commune, sans collision ni fuite de credential.
5. Résumé réel, reprise après crashes d’effets externes et couverture des variantes non testées identifiées dans test-plan.md.

Ne pas utiliser le score seul comme critère de livraison. Exiger tous les tests P0/P1 pertinents et un contrôle manuel TUI avant chaque jalon comportant des changements visibles. Les modèles réels peuvent faire l’objet d’une campagne optionnelle explicitement autorisée, séparée des tests CI déterministes et sans abonnement obligatoire.
