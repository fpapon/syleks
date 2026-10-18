# Suivi de la phase 3 — agents P1/P2

Implémentation du 18 octobre 2026 après le [moteur agentique commun](agentic-engine.md). Ce document couvre les critères de la phase 3 de la [roadmap](roadmap.md). Les scores, statuts et empreintes de l’audit initial restent historiques : aucune nouvelle parité globale n’est annoncée.

## Comportements livrés

`AgentDefinition` et `AgentCatalog` conservent leurs rôles. Les définitions JSON et Markdown supportent maintenant `disable` et `hidden`, avec héritage et surcharge explicite. Les booléens doivent être de vrais booléens JSON ou `true`/`false` en frontmatter.

- **Désactivé** : absent du catalogue visible, refus de `/agent`, `@name`, `/subagent`, du défaut de démarrage et des accès effectifs à la configuration d’exécution. Un appel direct à `ToolExecutor` avec cet agent ne peut produire aucun effet, même avec un callback d’approbation permissif.
- **Caché** : absent de `/agents` et du cycle Tab, mais accessible explicitement par son nom avec les mêmes contrôles de mode et de permission. Un défaut caché est autorisé. Tab sans agent primaire visible conserve la sélection et ne provoque aucune division par zéro.
- **Modes** : `primary`, `subagent`, `all` restent fonctionnels ; les modes incompatibles avec le point d’entrée sont refusés. La délégation structurée et les sessions enfants restent dans la phase 4.
- **Modèles et paramètres** : modèle/provider, température et limite de tours sont propres à chaque agent et héritent de leurs définitions précédentes. L’adaptateur reçoit le modèle de l’agent, même si la sélection courante du provider diffère. Ollama et Chat Completions transmettent la température ; Responses conserve son payload existant et n’ajoute pas ce champ. Aucun changement du protocole des providers.

Un fichier Markdown contenant uniquement `disable: true` en frontmatter peut désactiver une définition héritée sans recopier son prompt. Les définitions désactivées restent validées : elles ne peuvent dissimuler un modèle configuré inconnu ou un paramètre invalide. Les defaults et le schéma JSON documentent les nouvelles options ; le starter Plan déclare également `write: deny` explicitement. Les defaults ne remplacent pas les fichiers déjà personnalisés.

## Héritage et instructions

Ordre des **définitions d’agents** : defaults → `agent-config.json` utilisateur → Markdown utilisateur → Markdown à la racine Git → Markdown dans chaque répertoire jusqu’au lancement. Les valeurs absentes restent héritées, les valeurs présentes remplacent les champs scalaires, et les règles de permissions sont superposées dans cet ordre. Sans Git, le lancement est la racine du projet. La recherche reste limitée à cette ascendance ; aucun scan des parents hors projet ou des répertoires voisins.

Ordre des **instructions effectives** : prompt système partagé / variable `AGENT_SYSTEM_PROMPT` → `~/.config/syleks/AGENTS.md` → prompt de l’agent actif → `AGENTS.md` racine → instructions de chaque répertoire jusqu’au lancement. Les sections de projet indiquent leur source relative. Cette hiérarchie organise le contexte envoyé au modèle ; elle ne remplace pas les permissions appliquées au point d’exécution.

Le prompt agent et les instructions globales sont chargés au démarrage et renouvelés par `/restart`. Les sections du projet sont un snapshot enregistré au premier tour de la session, conservé lors d’un switch ou d’une reprise, y compris depuis un autre sous-répertoire du même projet. `/new` capture les changements de fichiers et la nouvelle portée de lancement. Les anciennes sessions restent compatibles avec le mécanisme de reprise livré en phase 2 ; aucune migration SQL.

Les liens de fichiers ou répertoires de configuration **de projet** qui résolvent hors du projet sont refusés. Les fichiers utilisateur globaux sont explicitement globaux et peuvent être des liens gérés par l’utilisateur. Liens cassés, fichiers non réguliers, UTF-8 invalide et erreurs de lecture sont des erreurs, pas des absences silencieuses. Un fichier optionnel réellement absent reste facultatif.

## Limites

| Élément | Limite UTF-8 appliquée à l’exécution |
|---|---|
| Fichier Markdown d’agent ou prompt JSON inline | 128 KiB chacun |
| Fichier global/projet d’instructions | 128 KiB chacun |
| Instructions effectives, en incluant les en-têtes | 256 KiB et budget du modèle |
| Fichiers Markdown d’agents sur toutes les couches | 128 fichiers, 1 MiB au total |
| Catalogue effectif, Build et Plan inclus | 128 agents |
| `agent-config.json` utilisateur | 1 MiB |

Les lectures sont bornées avant décodage et parsing. Les dépassements ne tronquent pas silencieusement des restrictions ou instructions. Les contraintes du schéma pour les chaînes comptent des caractères ; le runtime applique aussi les octets UTF-8 et les références effectives.

## Preuves et tests

| Critère de phase 3 | Code principal | Tests exécutés |
|---|---|---|
| disable/hidden et distinction découverte/autorité | `app/AgentDefinition`, `AgentCatalog`, `AppConfig`, `ai/ChatSession` | `AgentConfigTest.disablesInvocationAndHidesDiscoveryWithoutUsingHiddenAsAPermission`, `handlesNoVisiblePrimaryAgentAndRejectsDisabledDefaults` |
| Modes et valeurs invalides | `AgentCatalog.definition/parse`, `AppConfig.agent` | Tests de modes existants dans `AgentConfigTest` ; `inheritsFlagsAndRequiresActualBooleansInJsonAndMarkdown` |
| Héritage racine → lancement et application après redémarrage | `AgentCatalog.load`, `session/ProjectService.root` | `inheritsAgentDefinitionsFromGitRootToLaunchDirectoryAndReloadsOnlyWithNewConfiguration` |
| Modèle, prompt, température et refus réel d’outil | Adaptateurs existants, `ToolExecutor`, `AgentService` | Quatre variantes `AgentEngineParityTest.customHiddenAgentUsesItsOwnModelPromptParametersAndPermissions` |
| Instructions globales/imbriquées, snapshot et isolation | `AppConfig.globalInstructions`, `ContextService`, `ConfigurationFiles` | `InstructionHierarchyTest.sendsGlobalRootAndNestedInstructionsInOrderAndPreservesProjectSnapshotAfterRestart` |
| UTF-8, liens hors projet/cassés, fichiers invalides et limites | `ConfigurationFiles`, `AgentCatalog`, `ContextService` | Deux autres cas `InstructionHierarchyTest` ; `boundsConfigurationAndAgentFilesAndRejectsBrokenOrEscapingProjectLinks`, `boundsTheNumberAndCombinedSizeOfAgentFiles`, `rejectsInvalidGlobalInstructionsAndUnknownConfiguredAgentModelsAtStartup` |
| Schéma, defaults, configuration personnalisée préservée | `agent-config.schema.json`, resources defaults | `ConfigSchemaTest`, `ConfigInitializerTest`, suite configuration existante |

Chemins Java relatifs à `src/main/java/fr/openobject/ai/syleks/` ; tests sous `src/test/java/fr/openobject/ai/syleks/`. Les tests ajoutent **14 cas exécutés** aux 347 de phase 2 : sept tests de configuration, trois d’instructions et quatre variantes de transport. Les autres tests sont conservés.

Le scénario HTTP emploie les vrais services de session, le runtime, l’executor et les adaptateurs, avec serveurs contrôlés locaux. L’agent caché est sélectionné explicitement, lit un fichier temporaire, reçoit un refus d’écriture malgré une policy globale autorisant write, puis termine sa réponse. Le test contrôle le modèle réellement envoyé, les prompts, la température selon le transport, les trois requêtes et les statuts persistés. Aucun modèle permanent, abonnement payant, clé réelle ou donnée client n’est utilisé.

```bash
mvn -B --no-transfer-progress \
  -Dtest=AgentConfigTest,InstructionHierarchyTest,AgentEngineParityTest,ContextServiceTest,ConfigInitializerTest,ConfigSchemaTest test
mvn -B --no-transfer-progress clean verify
```

## Résultats

Avec Java 25 Zulu et Maven 3.9.16, `mvn -B --no-transfer-progress clean spotless:apply verify` a réussi le **18 octobre 2026 à 22:56:58 +02:00** : **361 tests, 0 failure, 0 error, 0 skipped**, compilation Java 25, génération Fusion, packaging et Spotless réussis. Journal de cette session : `/tmp/syleks-agents-verify.log` ; détails générés dans `target/surefire-reports/`.

Après ajout de l’assertion de routage d’agent indépendante de la sélection du provider, `mvn -B --no-transfer-progress clean verify` a également réussi à **22:59:35 +02:00** avec les mêmes **361 tests réussis** et le contrôle Spotless. Journal : `/tmp/syleks-agents-final.log`.

Un contrôle du JAR produit, avec home, workspace et stockage temporaires et sans appel LLM, a exécuté `session list` via le vrai container Fusion : défaut primaire caché accepté (exit 0), puis défaut désactivé refusé (exit 1). Le home du processus et ses variables de modèle ont été isolés du compte utilisateur.

La première campagne ciblée a détecté qu’un lien d’instructions cassé était traité comme absent lors de sa résolution. Les erreurs de présence et de résolution sont maintenant séparées ; le test de régression et la suite complète passent. `git diff --check` passe.

## Suites et limites de validation

Pas de rechargement à chaud : `/restart` est le point d’application documenté. Pas de chargement dynamique d’instructions en fonction de chaque fichier lu en dehors de l’ascendance du lancement. Pas de compatibilité complète YAML, de nouveau paramètre de sampling Responses ni d’inférence de capacités d’un modèle depuis son nom.

Les providers sont simulés pour la reproductibilité ; leurs capacités de modèles réels et les interactions humaines dans un terminal n’ont pas été validées. Les tests de session vérifient `/agents`, la sélection et Tab au niveau du service utilisé par la TUI, sans réécriture du renderer. Le shell reste soumis à ses limites de phase 1 et ne constitue pas une sandbox OS.

Les sessions enfants, le tool task, les quotas et la concurrence de sous-agents appartiennent à la phase 4. Le registre de tools extensible et MCP restent dans les phases 5 et 6. `report.json`, `source-manifest.json`, `test-results.json` et les scores historiques ne sont pas régénérés : un nouvel audit est nécessaire pour certifier le checkout courant.
