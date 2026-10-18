# Campagne de validation

La campagne utilise la suite Maven existante et huit nouvelles sondes dans `src/test/java/fr/openobject/ai/syleks/session/ParityAuditTest.java`. Aucun test ne demande de compte, clé payante, navigateur, service Ollama permanent ou serveur MCP externe. Java 25 et Maven 3.9+ suffisent ; les tests existants ouvrant HTTP loopback nécessitent que l’environnement l’autorise.

## Reproduction

```bash
mvn -B --no-transfer-progress clean verify
python3 docs/audits/opencode-parity/verify_report.py --surefire target/surefire-reports
```

Pour les seules nouvelles sondes :

```bash
mvn -B --no-transfer-progress -Dtest=ParityAuditTest test
```

Cette dernière commande ne produit pas tous les XML nécessaires au contrôle du rapport complet. Refaire `clean verify` avant `verify_report.py --surefire` pour une comparaison exacte de la campagne complète. `verify_report.py` sans option vérifie le rapport figé et les empreintes sources ; `--render` régénère la matrice à partir du JSON.

Pas de profil `parity` : ces tests courts et déterministes appartiennent au cycle normal déjà utilisé par GitHub Actions. Ne pas masquer les sondes derrière un profil optionnel ni ajouter un faux test MCP qui validerait seulement sa fixture. Une mise à jour du métier doit renouveler l’audit et ses empreintes ; ne pas modifier le rapport pour cacher une dérive.

## Nouvelles sondes exécutées

| Méthode `ParityAuditTest` | Frontière vérifiée | Résultat fonctionnel |
|---|---|---|
| `buildReadsEditsAndRunsAJavaTestThroughNativeToolRounds` | ChatSession → sérialisation Ollama → parser → policy → read/edit/bash → observer SQLite → prochains tours | PASS sur lecture, édition exacte, lancement réel Java dans temp et retour code 0 ; quatre requêtes, trois tools |
| `planExplicitDenyBlocksAnUnadvertisedWriteAtExecution` | Faux modèle émet un tool interdit, non annoncé | Fichier absent et refus retourné ; défaut diagnostique : FAILED enregistré au lieu d’un refus typé |
| `characterizesPersistedBuildPromptSurvivingSwitchToPlan` | Build→Plan→Build dans même UUID et payload réellement sérialisé | **Défaut reproduit** : ancien system Build envoyé sous Plan |
| `characterizesManualSubagentIsolationWithoutAChildSessionAndRestoresHistory` | @explore → requête isolée, fermeture/réouverture DB | Isolation du marqueur parent validée ; **absence d’enfant confirmée** |
| `characterizesMissingStructuredTaskTool` | Appel task émis par faux modèle via parser réel | **Absence confirmée** : unsupported tool task ; pas d’enfant |
| `characterizesCompoundShellCommandBypassingAnExplicitCommandDeny` | Policy ordonnée → chaîne bash → processus local | **Défaut reproduit** : fichier témoin créé par sous-commande pourtant DENY |
| `confinesDedicatedToolsAgainstTraversalSymlinksAndFictionalSecretRead` | File tools, deux espaces temporaires, faux secret | Refus traversal, chemin absolu extérieur, lien vers autre projet et gate read DENY |
| `interruptedToolIsNotReplayedAfterReopeningTheDatabase` | Pending/start/interrupt → fermeture services → DB réouverte | Statut INTERRUPTED, appel exclu du contexte ; fichier témoin absent |

Les méthodes `characterizes…` réussissent en attestant le comportement observé, y compris lorsqu’il s’agit d’un défaut. Elles devront évoluer vers des assertions du comportement corrigé lors d’une future implémentation. Elles ne font pas passer AGT-005, TOO-003 ou TOO-007 en conformité.

## Fixtures et exécution réelle

`SessionFixture` démarre les codecs/composants Fusion utilisés par les tests, une configuration home/projet isolée, `SqliteDatabase`, repositories, services de contexte/session et usage. `Wire` remplace uniquement l’envoi HTTP de `OllamaClient`, capture le corps de requête et retourne du NDJSON ; parsing, schémas, boucle, policies, tools et persistance restent réels. Le test Build lance réellement le JDK courant en mode source sur `Probe.java`, corrigé dans le workspace temporaire.

`CommandToolTest` simule séparément NDJSON Ollama et SSE Chat Completions, y compris fragments d’arguments, plusieurs appels, approbations, annulations et limites de tours. `AuthFixture` et `ResponsesProviderTest` utilisent HTTP local et identités/tokens fictifs pour SSE Responses, catalogues, refus scope, erreur/quota, cancellation et fonction-call-output. `ProviderServiceTest` traverse sélection/compte/persistance/reprise avec ces services simulés. `CliSessionTest` démarre de vraies JVM et teste CLI Fusion, halt, kill, locks et écritures concurrentes.

Les tests TUI existants couvrent modals/pickers/permissions et plusieurs parcours avec terminal simulé. **Aucun contrôle visuel humain sur un terminal réel** n’a été réalisé. L’API ChatSession exercée correspond au chemin utilisé par `/agent`, `/subagent`, `@agent` et les autres commandes inspectées dans `TerminalApp`, sans revendiquer un test de touches réelles pour chaque commande.

## Les douze scénarios demandés

| ID | Statut | Exécution et limite précise |
|---|---|---|
| E2E-001 | PARTIAL | Lecture→édition exacte→test Java→réponse exécuté ; application d’un patch structuré impossible car apply_patch absent |
| E2E-002 | PARTIAL | Write explicitement interdit bloqué même si demandé par le LLM ; pas de preuve de politique Plan complète incluant tous les chemins shell |
| E2E-003 | NOT_IMPLEMENTED | @explore manuel testé ; aucune délégation automatique au tool task |
| E2E-004 | NOT_IMPLEMENTED | Pas de gestionnaire d’enfants concurrents/corrélation ; pas de test de concurrence fictif |
| E2E-005 | NOT_IMPLEMENTED | Réouverture testée : seule la session parent existe, aucune sous-session à restaurer |
| E2E-006 | FAIL | Refus simples fonctionnels, mais contournement command-pattern par shell composé reproduit |
| E2E-007 | NOT_IMPLEMENTED | Pas de client stdio MCP à connecter |
| E2E-008 | NOT_IMPLEMENTED | Pas de client Streamable HTTP MCP à connecter |
| E2E-009 | NOT_IMPLEMENTED | Pas de tools MCP/policies associées |
| E2E-010 | PARTIAL | Vrai halt JVM existant + frontière tool interrompu ajoutée ; pas de crash après effet externe avant commit du résultat |
| E2E-011 | PARTIAL | Tours tools NDJSON, Chat Completions et Responses testés séparément ; le scénario complet read/edit/test n’est pas paramétré sur tous les providers |
| E2E-012 | PARTIAL | File tools et sessions refusent autre projet ; shell autorisé garde accès hôte, pas de sandbox générale de projet |

Ces scénarios sont transverses : ils ne s’ajoutent pas aux 53 lignes de score. Les absences d’implémentation sont distinguées des validations indisponibles dans cet environnement.

## Matrice providers : protocole implémenté et modèle réel

P = comportement du transport validé avec simulation ; partiel = support local limité ; inconnu = aucune validation d’un modèle réel. Les capacités déclarées `ProviderCapabilities(local, streaming, functionTools)` ne fournissent ni contexte exact du modèle ni garantie commerciale.

| Capacité | Ollama NDJSON | OpenAI-compatible Chat Completions | ChatGPT Responses | OpenAI API Responses |
|---|---|---|---|---|
| Texte | P : fragments/parsing et chat persistant | P : SSE et réponse finale | P : HTTP local / delta | P : adaptateur commun + route API testée |
| Streaming | P : done requis, erreurs/partiels | P : reconstruction SSE | P : response.completed requis | P : même classe, sans campagne exhaustive dédiée de toutes les erreurs |
| Function tool calling | P : arguments et tool_name, plusieurs tours | P : fragments, call_id et tool_call_id | P : function_call puis function_call_output après succès | Partiel : même code Responses, test key/catalogue ; scénario complet non rejoué sous identité API |
| Appels multiples / parallèle | Multiples acceptés, exécutés séquentiellement | Multiples acceptés, exécutés séquentiellement | Plusieurs items possibles, exécution séquentielle | Même moteur ; exécution parallèle absente |
| Structured output | Pas d’option format/schema exposée | Pas de response_format/schema exposé | Pas de text.format/schema exposé | Même limite |
| Reasoning | thinking parsé/retourné pendant tool rounds | Parsing dépend format, aucune capacité modèle garantie | Pas de sélection d’effort/variant ; usage reasoning distinct si fourni | Même limite, aucun effort/variant exposé |
| Limites de contexte | Config + estimation UTF-8, num_ctx/num_predict | Config + max_tokens / budget | Budget local ; limite réelle compte/modèle inconnue | Budget local ; plafond protocolaire non déduit du catalogue |
| Usage metrics | Compteurs terminaux, durées et inconnu en cas de coupure | include_usage et agrégation locale | usage terminal et cache/reasoning non doublés | Même parsing partagé, facturation réelle inconnue |
| Capacités du modèle sélectionné | **NOT_TESTABLE** : aucun modèle réel | **NOT_TESTABLE** | **NOT_TESTABLE** : aucun abonnement réel | **NOT_TESTABLE** : aucune clé réelle |

Aucune revendication sur l’accès effectif ChatGPT, la validité commerciale de son mode authentifié, le contexte réel, le raisonnement interne ou la qualité des modifications d’un vrai modèle. Les tests attestent le contrat des adaptateurs Syleks sur leurs fixtures, pas la conformité universelle des endpoints externes.

## Couverture fichiers et shell

| Outil / groupe | Déjà exécuté | À compléter pour couverture exhaustive |
|---|---|---|
| read | Nominal/plage, deny, traversal, symlink, binaire, >1 MB, type limit invalide | Unicode combiné/emoji, UTF-8 tronqué, fichier supprimé pendant read, timeout filesystem, toutes frontières de sortie |
| write | Création, overwrite explicite, deny, invocation LLM interdite | Unicode, gros contenu, parent absent, répertoires read-only, races avec liens/éditeurs |
| edit | Match unique/ambigu, replace_all, réel Java, ancien texte vide | Unicode multi-octets, absent, races, contenu binaire, fichier trop gros sur chaque chemin |
| list/glob/grep | Recherche, filtre deny, exclusion symlinks, résultats limités | Gros arbres 10000 entrées, profondeur 32, .gitignore, regex coûteuse, chaque erreur de racine |
| apply_patch | Absence statique | Tous scénarios après implémentation |
| bash | Cwd, stdout/stderr fusionnés, exit non nul, stdin fermé, timeout, kill enfants, cancel, sortie 32000 chars, refus/ASK/always, bypass composé | Enfants détachés/reparentés, groupes de processus, substitutions/pipelines/redirections malveillantes, env/cwd par appel |
| webfetch/search | HTML, redirect gate, erreurs HTTP, contenu binaire/trop grand, parsing search simulé | Débit lent, toute chaîne de redirections, DNS rebinding/réseaux privés selon policy, moteur search réel |

La borne et le contrôle d’accès ne garantissent pas une défense contre toute prompt injection. Le prompt système marque les fichiers/pages comme non fiables ; aucun test avec vrai modèle hostile n’a été exécuté. Les traces peuvent contenir des secrets renvoyés par un outil : les faux marqueurs sont les seules données confidentielles utilisées par ces sondes.

## MCP : scénarios prêts à implémenter, non exécutés

Aucun serveur simulé MCP n’a été ajouté, car Syleks n’a pas de client auquel le relier. Tester un serveur tout seul ne validerait pas l’application et ne justifierait aucun PASS. Les fixtures suivantes constituent les critères de la phase MCP de la roadmap.

- **Stdio contrôlé** : processus Java test utilisant stdin/stdout réservés au JSON-RPC, handshake du protocole négocié, notifications initialized, tools/list paginé et tools/call echo ; stderr séparé ; EOF et fermeture contrôlés. Vérifier PID et absence de processus après close/cancel.
- **HTTP contrôlé** : `HttpServer` loopback, initialize/session/version selon transport négocié, GET/POST/DELETE nécessaires au Streamable HTTP, payloads JSON et événements SSE ; session expirée, reconnexion, timeout et cancel. Ne pas réduire à un endpoint JSON echo.
- **Catalogue** : deux serveurs même nom de tool, collision après normalisation, collision natif, list_changed, tool supprimé/désactivé pendant session ; schéma invalide, arguments refusés avant effet.
- **Autorisations** : faux LLM appelle tool autorisé puis interdit ; seuls les effets autorisés existent et les deux décisions sont traçables ; descriptions serveur non fiables et budget borné.
- **Auth** : headers fictifs, 401, serveur sans auth puis OAuth PKCE local, state invalide, découverte, registration si annoncée, refresh/logout ; credentials hors session/export.
- **Résilience et facultatif** : JSON malformé, mauvais id JSON-RPC, sortie trop grande, processus tué, déconnexion ; resources/read/templates et prompts/list/get avec paramètres ; aucune exigence arbitraire sur extensions non utilisées par OpenCode.

## Sécurité/reprise encore à éprouver

Compléter les quatre frontières de crash (avant appel, pendant effet, résultat reçu avant commit, commit puis crash), avec des compteurs d’effets externes dans temp et sans replay automatique. Tester politiques parent-session vs agent, délégation récursive et quotas dès que task existe. Les credentials doivent rester fictifs ; exclure propriétés JVM/env des rapports de test archivés. Ne jamais exécuter les sondes shell sur le repository réel.
