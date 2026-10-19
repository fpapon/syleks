# Phase 6 — MCP

Phase 6 P1/P2 implémentée le 19 octobre 2026. La configuration et les commandes sont décrites dans [le guide MCP](../../mcp.md). Ce suivi remplace les constats d’absence MCP de l’audit initial sans réécrire les preuves historiques.

## Implémentation

- `mcp/McpClientManager` possède les connexions et les catalogues ; `McpConnection` gère JSON-RPC, stdio, Streamable HTTP JSON/SSE, négociation, notifications, sessions, délais et fermeture. Aucun SDK supplémentaire ni processus shell intermédiaire.
- `AppConfig` charge les serveurs globaux puis les overrides du workspace explicitement autorisé dans `mcp_trusted_projects`. Types, identifiants, commandes, headers, env, enabled et délais sont validés. Aucun processus projet n’est lancé sans confiance globale.
- `ToolRegistry` installe les outils MCP dans un namespace réservé, valide les schémas et rejette atomiquement un catalogue invalide/excessif par serveur. Les outils natifs et les extensions restent disponibles. Les noms distants incompatibles sont hachés ; toute collision est refusée.
- `ToolExecutor` applique les permissions dynamiques au point d’exécution, y compris appels non annoncés, ASK et DENY. Les plafonds ancestraux des sous-agents utilisent la même opération. Les providers consomment le même catalogue Fusion et les résultats suivent les observers/historiques/repositories existants. Les instances construites directement partagent aussi le registre entre client Ollama et exécuteur.
- `McpOAuth` fournit découverte resource metadata / authorization metadata, client public PKCE S256 avec callback loopback et state, enregistrement dynamique ou `client_id` explicite, resource indicators et refresh rotatif. Fichiers privés et verrous sont séparés des comptes LLM et des exports. Un refresh potentiellement consommé n’est pas rejoué après crash/erreur.
- Resources/list/read/templates et prompts/list/get sont optionnels et exposés comme outils soumis aux permissions. Aucune injection automatique dans les instructions système ; données binaires omises, texte et structured content bornés.
- CLI : `mcp list`, `mcp tools`, `mcp auth SERVER`, `mcp logout SERVER`. Le redémarrage ferme les connexions de l’ancien container. Le serveur stdio et ses descendants sont arrêtés ; une session HTTP reçoit DELETE à la fermeture.

## Preuves déterministes

| Scénario | Test / preuve |
| --- | --- |
| E2E-007 : stdio | `McpIntegrationTest#stdioDiscoversValidatesCallsAndKeepsServersAndBuiltinsDistinct` : vrai processus Java, initialize → initialized → tools/list → tools/call ; Unicode, validation avant effet, deux serveurs, namespace, erreur typée, resources/prompts |
| E2E-008 : HTTP | `McpIntegrationTest#streamableHttpNegotiatesSessionHandlesSseAuthPaginationRefreshAndDelete` : vrai serveur loopback, JSON puis SSE, headers auth/session/version, pagination, notification de changement, nouveau catalogue, DELETE |
| E2E-009 : policy | `McpIntegrationTest#denialAndAskRefusalBlockEffectsEvenWithUnadvertisedCalls` : outil refusé absent du catalogue, appel fabriqué bloqué malgré callback permissif, aucune trace d’effet serveur, ASK refusé avant démarrage |
| Boucle et reprise | `McpIntegrationTest#llmRoundTripPersistsMcpResultAndResumesItAfterRestart` : requête réelle à un faux Ollama HTTP, outil déclaré, appel du modèle, résultat MCP dans la requête suivante, exécution SQLite et contexte reconstruit après réinitialisation |
| Résilience stdio | `McpIntegrationTest#timeoutMalformedMessagesCancellationAndCloseCleanUpSubprocesses` : timeout typé, JSON invalide, reconnexion sans replay, interruption et PID arrêté |
| Résilience HTTP | `McpTransportTest` : redirection rejetée, credential MCP d’env isolé du provider, ID/version invalides, erreur RPC sans echo de secret, SSE bloqué borné par deadline et notification d’annulation sans replay |
| OAuth | `McpOAuthTest` : resource discovery, registration, S256 vérifié côté token endpoint, mauvais state refusé, resource/client vérifiés, refresh et rotation atomique, mode 0600, reprise du token après nouvelle instance, autre ressource sans credential et logout |
| Concurrence et fermeture | `McpTransportTest#concurrentDiscoveryCanBeInterruptedAndShutdownDoesNotWaitForItsDeadline` : attente du catalogue interruptible, fermeture sans attendre la deadline réseau ; `McpIntegrationTest#timeoutTerminatesServerDescendants` : arrêt du processus enfant du serveur |
| Catalogue invalide | `McpIntegrationTest#rejectsOneInvalidCatalogueAtomicallyWhileKeepingOtherServersUsable` : référence externe refusée, processus arrêté, autre serveur utilisable et aucun effet du serveur rejeté |
| Configuration | `McpConfigurationTest` : schéma, mauvaises valeurs, headers réservés, override global/projet autorisé, projet sans confiance ignoré et serveurs disabled sans lancement |

## Validation

```sh
mvn -B --no-transfer-progress clean verify
```

**BUILD SUCCESS**, JDK Azul Zulu 25 et Maven 3.9.16, Linux amd64. Résultat du 19 octobre 2026 à 10:41:23 +02:00 : **427 tests**, 0 échec, 0 erreur, 0 ignoré ; 14 tests MCP supplémentaires aux 413 tests précédents. Compilation Fusion, JAR ombré et Spotless réussis. Les sockets loopback des fixtures nécessitent une exécution hors sandbox réseau. Aucun compte, modèle réel ou serveur MCP externe n’a été utilisé.

Smoke du JAR ombré final : `--help`, `mcp list` et `mcp tools` réussissent dans un HOME/workspace temporaire avec un serveur Java stdio contrôlé. Le statut est Connected, le nom `mcp_fixture__t_echo` est présent et le PID du serveur est arrêté après chaque commande. `git diff --check` réussit.

Les avertissements existants SLF4J, Spotless/Unsafe et Shade sur les ressources partagées restent non bloquants. Aucun changement de disposition ou de touches TUI ; les tests existants de permissions, rendu et redémarrage sont conservés. Aucun contrôle visuel humain dans un terminal réel ni build GraalVM n’est revendiqué.

## Limites explicites

- Streamable HTTP inclut les réponses SSE aux POST ; le transport historique HTTP+SSE, le GET permanent, Last-Event-ID/resumption et les subscriptions ne sont pas retenus. Notifications HTTP reçues seulement dans les flux POST ; notifications stdio lues pendant l’inactivité.
- Le protocole cible 2025-06-18. Les peers stdio 2025-03-26 / 2024-11-05 peuvent négocier ; pas de certification d’interopérabilité externe. Roots, sampling et elicitation ne sont pas annoncés ; une requête serveur non supportée reçoit Method Not Found.
- Échec d’un appel : invalidation puis nouvelle session lors d’un prochain tour, sans replay automatique d’un effet. Échec de découverte initiale : redémarrage nécessaire. Une déconnexion entre effet distant et persistance ne permet pas de savoir si l’effet a eu lieu.
- OAuth sélectionne le premier authorization server, exige S256 et un client public ; pas de client secret, de grant alternatif ni de révocation distante au logout. HTTPS obligatoire sauf loopback. Stockage local protégé, sans intégration keyring propre à MCP.
- Un programme stdio explicitement configuré reste du code de confiance avec les droits OS du processus. Les permissions limitent les appels d’outils ; elles ne constituent pas une sandbox du serveur.
- Budgets : 16 serveurs, 100 outils et 16 pages par serveur, messages/streams 1 MiB, limites du registre partagé (128 outils / 128 Ki caractères), résultats 32 000 caractères. Les pages sont chacune soumises au délai configuré ; plusieurs serveurs/pages peuvent cumuler ces délais lors de la découverte.
