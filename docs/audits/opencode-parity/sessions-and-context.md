# Phase 7 — Sessions et contexte

Phase 7 P1/P2 implémentée le 19 octobre 2026. Le [guide des sessions](../../sessions.md) décrit les commandes, le format d’archive et les limites. Les constats historiques SES/ORC de la matrice initiale restent conservés ; ce suivi fournit les preuves actuelles.

## Changements

- **Résumé réellement généré** : `/compact` appelle le provider/modèle/compte sélectionné avec la permission `chat`, sans déclaration ni autorité d’outils. `ContextCompactor` découpe une transcription bornée, maintient un résumé roulant et refuse une sortie vide/surdimensionnée. `SummaryRepository` persiste le texte et sa frontière inclusive ; `ContextService` remplace seulement le préfixe couvert dans les prochaines requêtes. Le transcript SQLite complet ne change pas. Le résumé reste une donnée utilisateur non autoritaire, épinglée dans le budget système/réserve/tools.
- **Fork au message** : `/messages`, `/fork MESSAGE-ID`, `session messages ID` et `session fork ID [MESSAGE-ID]`. Préfixe inclusif, refus d’une paire call/result coupée, références et exécutions remappées. Un résumé couvrant des messages postérieurs n’est pas copié. Aucun usage, enfant, todo ou droit de restauration de fichier n’est dupliqué.
- **Archives transactionnelles** : export version 2 et import v1/v2, projet canonique contrôlé, identifiants frais et relations messages/tools/enfants remappées. Résumés, todos et usage hiérarchique sont conservés. Données invalides, schémas inconnus, identités dupliquées, références incohérentes et compteurs négatifs sont refusés avant installation. Aucun compte/credential/approval n’est importé. État en cours → interrompu, sans reprise automatique.
- **Journal de fichiers** : la migration V006 ajoute `session_summaries` et `file_changes`. `FileMutation` relie les effets natifs `write`, `edit` et `apply_patch` au journal durable avant l’I/O. Les patches partagent un groupe. Les résultats persistés comportent une partie DIFF de provenance. `/changes` et `/diff` consultent l’historique ; `/undo` et `/redo` restaurent les contenus après contrôle du lot et de toutes les autorisations `write`. Un parent peut restaurer les effets de ses descendants.
- **Reprise robuste** : aux frontières d’un effet, le journal PENDING conserve les snapshots avant/après. La récupération observe les fichiers et classe APPLIED / NO_EFFECT / CONFLICT, sans écrire. Les opérations de restauration ont aussi des états durables PENDING_UNDO/PENDING_REDO. Les appels sans résultat ne sont pas reconstruits comme appels exécutables ; un diagnostic signale l’effet externe indéterminé. Les résultats legacy sont associés par référence non ambiguë, même si le provider réutilise un call ID dans un autre tour.
- **Concurrence** : lease de session existante conservée pendant compaction/fork/import/export/restauration ; verrou de workspace pour les effets journalisés entre instances partageant le même répertoire de données. Les callbacks de génération/maintenance mettent à jour l’état de `ChatSession` ; l’affichage appartient toujours à `TerminalApp`.

## Critères et preuves

| Critère roadmap | Preuve déterministe |
| --- | --- |
| Résumé dans une vraie requête ; DB intacte | `SessionWirePhaseSevenTest#generatedSummaryReachesActualNextProviderRequestWithoutRawOlderTurnsOrToolDeclarationsOnSummaryCall` : faux Ollama HTTP, premier appel sans tools, réponse générée persistée puis envoyée à la requête suivante, ancien texte brut absent du payload mais présent dans SQLite, reprise et usage comptés |
| Providers et données non autoritaires | `ResponsesProviderTest#summaryRequestsOmitToolsAndKeepSavedSummaryAsUserReferenceData` : payload Responses sans tools, résumé en user input, marqueur interne absent du wire ; tests multiprovider préexistants conservés |
| Budget / échec / annulation | `SessionsPhaseSevenTest` : source bornée, résumé épinglé, frontières persistées, absence de mutation des messages et conservation du résumé précédent en cas d’échec ; `SessionWirePhaseSevenTest` : annulation pendant stream, aucun résumé partiel committé, lease récupérable |
| Fork préfixe / paires cohérentes | `SessionsPhaseSevenTest#forkAtMessageCopiesOnlyCoherentPrefixAndDoesNotLeakNewerSummaryOrUsage` et `#refusesForkBoundaryInsideToolPairAndRemapsCompletePair` : refus de frontière interne, remapping réel, exclusion du futur et consommation nulle dans le fork |
| Roundtrip import/export | `SessionsPhaseSevenTest#archiveRoundTripRemapsTreeToolsTodosSummariesAndExactUsageWithoutRestoringFileAuthority` et `#roundTripsToolPairsAndRejectsForgedReferencesAtomically` : UUID frais, copie indépendante, enfants/todos/résumé, paires remappées, snapshots inactifs, import répété sans écrasement |
| Validation fail-closed | `SessionsPhaseSevenTest#validatesEntireArchiveBeforeWritingAndRejectsCrossProjectDuplicatesAndBrokenReferences` : JSON invalide/trailing, projet différent, positions/versions/roles/usage invalides, références falsifiées, aucune session partielle |
| Quatre frontières crash sans replay | `SessionRecoveryPhaseSevenTest#recoversFourActualProcessCrashBoundariesWithoutReplayingEffects` : quatre JVM avec `Runtime.halt`, avant appel / pendant effet / résultat reçu avant commit / après commit ; compteur externe à zéro ou une entrée après deux réouvertures et une continuation ; tool INTERRUPTED ou SUCCESS selon frontière |
| Usage parent/enfant exact | Roundtrip : 30 tokens directs + 30 enfant = 60 arbre, conservation après import indépendant et nouvel import, aucune copie de consommation au fork ; tests d’idempotence et sous-agents existants conservés |
| Undo/redo et divergence | `SessionsPhaseSevenTest#nativeWritesEditsAndPatchBatchesSupportDivergenceCheckedUndoRedoAcrossRestart` : vrais fichiers Unicode, création/edit/patch, undo/redo après réouverture, divergence d’un fichier refusée avant de toucher l’autre ; symlink refusé, redo invalidé par nouvelle édition et parent restaurant les effets enfant |
| Approbation de restauration | `SessionWirePhaseSevenTest#chatUndoHonorsAskAndExplicitDenyWithoutChangingRecordedFiles` : passage par le panel ASK, refus sans effet puis approval once appliquée |
| CLI réel et migrations | `CliSessionTest#exposesImportPrefixForkMessageIdsAndFileHistoryCommandsThroughRealCli` : export/import/fork au message/changes/diff/help dans des JVM distinctes ; `SessionsPhaseSevenTest#upgradesSchemaFiveWithoutLosingMessages` et migrations antérieures : schéma 6 sans perte |

## Validation reproductible

```sh
mvn -B --no-transfer-progress clean verify
```

Le build utilise Azul Zulu Java 25 et Maven 3.9.16. Les fixtures HTTP/OAuth/MCP nécessitent des sockets loopback ; la validation complète est lancée hors sandbox réseau. Aucun modèle réel, compte externe ou abonnement n’est utilisé. Spotless est appliqué aux sources Java avant vérification.

Résultat final du 19 octobre 2026 à **12:01:52 +02:00** : **BUILD SUCCESS**, **445 tests**, 0 échec, 0 erreur, 0 ignoré, durée 1 minute. Cela ajoute 18 scénarios aux 427 tests de la phase 6. Compilation Fusion release 25, migrations, JAR ombré et contrôle Spotless réussis. Les avertissements existants SLF4J, Spotless/Unsafe et Shade restent non bloquants.

Smoke sur le JAR final et un HOME/workspace temporaire : `--help`, export v2/import, fork préfixe, messages, changes/diff et undo/redo réussissent. Un vrai PTY ouvre la session, affiche l’aide, passe de 100×32 à 40×14 puis 120×36, exécute `/messages`, `/changes`, `/diff`, `/undo`, `/redo` et `/quit`. Les contenus/absences des fichiers sont vérifiés après les restaurations et le processus termine avec le code 0. Aucun modèle n’est contacté dans ce smoke. `git diff --check` et les liens locaux de documentation réussissent.

## Limites explicites

- Résumé déclenché par l’utilisateur, sans appel modèle automatique lors d’une omission de contexte. Dernier tour conservé, source 16 MiB, au plus 64 requêtes, résumé au plus 16 000 octets UTF-8 et plus petit selon la fenêtre. Une erreur conserve le résumé précédent ; les requêtes déjà effectuées restent comptées.
- Archives 32 MiB / 128 sessions ; même projet canonique pour v2, même project ID local pour v1. Les branches fork ne font pas partie d’un arbre task exporté. Identifiants nouveaux à chaque import ; aucune écriture dans le workspace et aucune restauration automatique de fichiers. Les snapshots importés restent ABANDONED, les références de compte ne sont pas réactivées.
- Le journal concerne les contenus des outils natifs instrumentés. Ni shell/MCP/custom arbitraire, ni attributs de fichiers, ni Git, ni annulation d’effets réseau/processus. Snapshots de 1 MB par fichier. Le contrôle de divergence et le verrou coopératif ne constituent pas un compare-and-swap atomique contre tous les processus externes.
- Pas de transaction filesystem multi-fichiers ni de garantie exactly-once sur un effet externe. Un crash en plein lot peut laisser un préfixe appliqué ; la reprise inspecte sans rejouer. Un CONFLICT durable bloque les restaurations automatiques de cet historique. Les fichiers déjà changés ne sont pas restaurés silencieusement pour « réparer » une reprise.
- Historique complet et snapshots conservés jusqu’à suppression de session ; exports potentiellement sensibles et non chiffrés. Pas de nettoyage/rétention automatique ajouté en phase 7.
- Les tests TUI simulés vérifient aide, rendu, permissions et restart ; un smoke PTY complète les commandes. Aucune revue visuelle humaine ni compilation native GraalVM n’est revendiquée.
