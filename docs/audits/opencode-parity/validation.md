# Résultats techniques de validation

> **Campagne historique :** les commandes, empreintes et résultats ci-dessous décrivent l’audit initial. Après le correctif P0, utiliser [le suivi shell et sa campagne de validation](shell-security.md) ; le vérificateur des anciennes empreintes signale volontairement la modification du code.

> Pour la phase 2 P1, voir [la validation du moteur agentique commun](agentic-engine.md). Les résultats ci-dessous restent ceux de l’audit initial.

> Pour la phase 3 P1/P2, voir [la validation des agents et des instructions](agents.md).

> Pour la phase 4 P1/P2, voir [la validation des sous-agents, sessions enfants et quotas](subagents.md).

**Résultat final : `BUILD SUCCESS`, 246 tests, 0 failure, 0 error, 0 skipped.** Les huit nouveaux tests sont inclus dans ce total. La réussite des tests de caractérisation ne corrige pas les quatre lignes FAIL de la matrice.

## Environnement

- Linux amd64, noyau `6.12.111+deb13-amd64`.
- JDK **25**, Azul Zulu `25.28+85-CA`, build `25+36-LTS`.
- Maven **3.9.16**.
- `java` n’était pas initialement dans le PATH de l’outil. JDK et Maven étaient déjà installés sous SDKMAN ; aucun changement de version ni téléchargement de JDK.
- Date : 2026-10-18 ; fin de la validation finale **20:37:51 +02:00**, durée Maven **29,838 s**.

Préfixe utilisé pour les commandes Maven dans cet environnement :

```bash
JAVA_HOME=/home/fpa/.sdkman/candidates/java/25-zulu \
PATH=/home/fpa/.sdkman/candidates/java/25-zulu/bin:/home/fpa/.sdkman/candidates/maven/3.9.16/bin:$PATH \
mvn -B --no-transfer-progress clean verify
```

Dans CI, le setup Java 25 existant suffit ; les chemins SDKMAN ci-dessus ne sont pas des prérequis du repository.

## Commandes et issues

| Commande/action | Résultat observé |
|---|---|
| `git status --short`, `git rev-parse HEAD`, lectures `rg`/fichiers | État utilisateur préexistant conservé, commit relevé, inventaire et chemins réels examinés |
| `java -version` initial | Commande absente du PATH ; résolu en sélectionnant le JDK installé |
| `curl …/releases/latest` dans sandbox | DNS bloqué ; même requête autorisée hors sandbox puis réussie |
| `curl` API ref/tag/arbre et archive GitHub du commit | Stable/tag/commit vérifiés, documentation et sources du même commit consultées |
| `mvn -B --no-transfer-progress test` initial dans sandbox | 238 tests, 37 erreurs : création de sockets loopback refusée ; aucun échec d’assertion ; ne constitue pas une régression produit |
| Même suite hors sandbox, avant ajout | **238 tests, zéro échec/erreur/ignoré** |
| `mvn … -Dtest=ParityAuditTest test`, première sonde | 8 tests, une assertion de la sonde attendait « Permission denied » ; le code renvoyait « Tool error: Permission refused ». Assertion ajustée à la sortie réelle, aucun code métier changé |
| `mvn … -Dtest=ParityAuditTest#planExplicitDenyBlocksAnUnadvertisedWriteAtExecution test` | Diagnostic ciblé du libellé et de FAILED persisté ; sonde corrigée pour caractériser ce comportement |
| `mvn … -DspotlessFiles=… spotless:apply` | Commande réussie sans reformater la cible ; filtre non effectif pour cette invocation |
| Premier `mvn … clean verify` après ajout | **246 tests passent**, packaging réussi, échec Spotless sur le nouveau `ParityAuditTest.java` uniquement |
| `mvn -B --no-transfer-progress spotless:apply` | Un seul fichier reformatté : nouveau test ; fichiers préexistants inchangés |
| Dernier `mvn -B --no-transfer-progress clean verify` | **BUILD SUCCESS**, 246/246 tests, compilation release 25, annotation processing Fusion, JAR Shade et Spotless passent |
| JAR `--help` avec home temporaire | Code 0, aide CLI produite |
| JAR `session list` avec data/home/projet temporaires | Code 0, en-tête vide de sessions : runtime Fusion/SQLite du JAR chargeable |
| JAR `mcp list` mêmes répertoires isolés | Code 2, arguments inconnus ; indice CLI complémentaire d’absence MCP, pas test de protocole |
| `python3 …/verify_report.py --render --surefire target/surefire-reports` | Cohérence de 53 exigences, 53 méthodes citées, empreintes et scores vérifiée ; matrice régénérée |

L’ouverture de sockets HTTP locaux a nécessité l’exécution Maven hors sandbox, autorisée pendant la session. Les serveurs de test restent contrôlés, sans dépendance à un service externe. Le téléchargement de la baseline était en lecture seule sur des sources publiques.

## Pièces conservées

- [build-summary.txt](build-summary.txt) : compilation, classes de test, total, packaging, format et résultat final, extraits du dernier journal.
- [test-results.json](test-results.json) : toutes les méthodes/classes Surefire et issues, sans propriétés JVM, variables d’environnement ni XML susceptible de les embarquer.
- [report.json](report.json) : 53 exigences et 53 méthodes de preuve citées, scores/couverture, E2E et limites providers.
- [cli-smoke.json](cli-smoke.json) : arguments et sorties des trois smokes JAR.
- [source-manifest.json](source-manifest.json) : empreintes des sources Java/SQL de cet état audité ; inventaire figé, pas prétention de lecture exhaustive de chaque ligne.
- [baseline-api.json](baseline-api.json), [baseline-sources.json](baseline-sources.json) : métadonnées officielles et références immuables consultées.

Les journaux bruts de travail se trouvent sous `/tmp/syleks-parity-*.log` dans cette session, dont `syleks-parity-clean-verify.log` pour le résultat final. Ces chemins temporaires ne sont pas requis pour reproduire l’audit. Le résumé et les résultats structurés sont conservés dans le repository.

## Tests existants réutilisés

Les domaines persistence/context/project/CLI, providers/Responses/OAuth/credentials/cloud policy, tools/bash/permissions/agents et TUI/éditeur/renderer/modals ont tous été exécutés. En particulier, vrais processus JVM pour locks/crash, SQLite isolé, faux HTTP Responses et génération de composants Fusion sont couverts par la suite. Les liens méthode par méthode figurent dans la matrice et le JSON ; les 246 méthodes sont inventoriées dans test-results.json.

## Non exécuté et limites matérielles

1. **Aucun modèle réel** Ollama ou fournisseur cloud : impossible d’attester support tools/qualité/contexte/reasoning réel du modèle choisi, éligibilité d’un abonnement ou quota. Les faux serveurs prouvent les formats et la boucle Syleks.
2. **Aucun protocole MCP Syleks exécuté** : client absent. Les étapes stdio/HTTP/OAuth/collisions/errors/resources/prompts restent des critères futurs, pas des PASS ni des erreurs attribuées à un serveur indisponible.
3. **Aucune délégation automatique ni orchestration enfant de bout en bout**, car task et sessions enfants absents. L’invocation manuelle isolée et l’absence de sous-session ont été réellement sondées.
4. **Pas de contrôle humain en terminal réel**, de screenshots, de redimensionnement manuel ou d’OAuth avec navigateur ; tests TUI automatisés seulement.
5. **Pas de crash réel pendant un effet filesystem externe** : halt JVM pendant génération existant et frontière tool interrompu sans replay ajoutée. Les quatre fenêtres de reprise avec effets demandent d’autres scénarios.
6. Pas de fuzzing, de course filesystem exhaustive, de processus reparentés ou d’attaque de prompt injection sur un modèle réel. La sonde shell prouve un cas précis, pas toute la surface d’attaque.
7. Aucune construction native GraalVM : hors commande cible ; JAR JVM validé.
8. Pas d’exécution des tests/binaire OpenCode : la baseline compare documentation et source figées, pas une double campagne runtime sur les deux produits.

La part NOT_TESTABLE de la matrice principale est 0 % parce que les absences sont **connues**, et les lignes partielles ou défaillantes ont des limites explicitement documentées. Ce 0 % ne doit pas être lu comme « tous les cas testés » : seules 40/53 lignes ont au moins une sonde exécutée et 10/53 sont complètement PASS. Les validations de modèle réel et terminal restent NOT_TESTABLE dans les limites machine-readable et ne sont pas artificiellement converties en points.

## Modifications réalisées

Création de `ParityAuditTest.java` uniquement côté Java. Création du répertoire de rapports demandé et des artefacts de preuve/validation. **Aucun fichier de production, protocole provider, persistance métier, TUI ou POM modifié par cet audit.** Les changements utilisateur déjà présents, dont `SessionDeletionTest.java`, font partie de l’état testé et n’ont pas été remplacés.

Le commit Syleks seul est insuffisant pour reproduire les modifications préexistantes non commitées ; `source-manifest.json` permet de détecter une divergence. Le vérificateur de rapport échoue volontairement si les sources auditées changent : refaire l’audit au lieu de considérer un résultat ancien comme valide.
