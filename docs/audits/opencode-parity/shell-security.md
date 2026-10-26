# Suivi du correctif shell P0

Ce suivi concerne la phase 1 de la roadmap et les causes P0 G-TOO-003/G-TOO-007 de l’audit initial. Les scores et preuves du rapport initial restent un état historique ; le correctif ne revendique pas une parité complète des tools ni la résolution des autres écarts.

## Comportement corrigé

Avec `bash={"*":"deny","printf *":"allow","touch *":"deny"}`, `printf ok; touch audit-marker` est désormais refusé avant le lancement du processus. La sonde originale `ParityAuditTest` est devenue le test de non-régression `refusesCompoundShellCommandBeforeExecutingAnyPart` : le fichier témoin doit rester absent et un refus doit revenir au modèle.

`PermissionPolicy.evaluate("bash", command)` et le chemin d’exécution de `ChatSession` utilisent le même `ShellCommandPolicy`. Ce dernier analyse une grammaire littérale volontairement limitée : listes, pipelines, commandes citées/échappées, opérateurs de redirection et descripteurs littéraux. Il calcule toutes les autorisations avant l’appel à `LocalCommandRunner`. Un refus n’exécute donc pas les commandes ou redirections autorisées qui précèdent l’action interdite.

Chaque commande normalisée et chaque redirection constitue une cible distincte. Le résultat agrégé suit DENY > ASK > ALLOW ; l’approbation utilisateur porte sur le script original complet. Les restrictions explicites du script original restent également prises en compte. Un ALLOW du script entier ne peut pas masquer un DENY d’une de ses actions. L’ordre des règles reste « dernière règle correspondante » au sein de chaque cible.

Les substitutions/expansions et autres constructions non analysables sont refusées sous policy granulaire. Les wrappers shell, alias, traps et builtins modifiant l’interprétation du shell sont aussi refusés. Les refus sont calculés avant les grants de session : `always` ne peut relaxer que ASK, jamais un refus ni une syntaxe non supportée.

Une policy effective uniforme `bash: allow` ou `bash: ask` conserve volontairement l’autorité sur le shell complet. Cela inclut une dernière règle inconditionnelle `*` qui remplace les règles précédentes selon la priorité existante. L’utilisation des patterns de commande change : lire [la documentation utilisateur](../../tools-and-permissions.md#shell-and-web-access), notamment pour les redirections qui nécessitent leur propre règle sous allowlist.

## Durcissement de l’entrée shell

Le runner utilise Bash avec `--noprofile --norc`, enlève `BASH_ENV`, `ENV`, `SHELLOPTS`, `BASHOPTS` et les fonctions exportées `BASH_FUNC_*`. Un processus enfant JVM de test reçoit volontairement ces variables : aucun script/fonction importé ne doit créer de fichier ni modifier la sortie, tandis qu’une variable ordinaire reste disponible.

Le timeout, la capture bornée et l’interruption/arrêt des descendants restent couverts par les tests existants `LocalCommandRunnerTest`. Ce correctif n’ajoute pas de sandbox OS ou de gestion de groupes de processus détachés.

## Vérifications reproductibles

```bash
mvn -B --no-transfer-progress clean verify
```

Campagne ciblée :

```bash
mvn -B --no-transfer-progress \
  -Dtest=ShellCommandPolicyTest,CommandToolTest,LocalCommandRunnerTest,ParityAuditTest,PermissionConfigTest test
```

- Tests policy : opérateurs de listes/pipelines, normalisation des mots, redirections et descripteurs, substitutions, syntaxe incomplète, wrappers, règles contradictoires, ASK, restrictions du script complet et policies uniformes.
- Tests intégrés : faux LLM → tool bash → policy → processus ou refus → retour au modèle ; présence de fichiers autorisés et absence de tout effet de préfixe sur script refusé.
- Regression `always` : un premier script approuvé ne débloque ni une commande composée interdite ni une substitution opaque lors du tour suivant.
- Runner : processus JVM réel avec environnement hostile fictif, timeout et annulation des descendants.
- Les fichiers sont exclusivement dans des répertoires temporaires ; aucune donnée client, clé réelle ni provider payant.

## Résultat exécuté

Le 18 octobre 2026 à 21:13:27 +02:00, `mvn -B --no-transfer-progress clean spotless:apply verify` a terminé avec **BUILD SUCCESS** : **332 tests, 0 failure, 0 error, 0 skipped**. Java 25, génération Fusion, packaging du JAR et contrôle Spotless passent. La suite précédente comptait 246 tests ; cette campagne ajoute 86 cas exécutés et renforce les sondes existantes.

Les 13 variantes de précontrôle intégré utilisent des refus ciblant les commandes/redirections individuelles : elles ne reposent pas sur une règle globale contenant simplement le nom du fichier témoin. Les tests `always` utilisent aussi une substitution dont le script complet n’est pas déjà refusé par un pattern de texte. Ils vérifient donc le nouveau contrôle effectif des actions.

Le journal brut de cette session est `/tmp/syleks-shell-final.log` ; les résultats détaillés sont dans `target/surefire-reports/`. Aucun de ces chemins temporaires n’est nécessaire pour reproduire les tests. `git diff --check` passe.

## Limites explicites

Une règle shell contrôle l’invocation du programme et les redirections littérales, pas le code interne des exécutables autorisés, les scripts/interpréteurs généralistes ou tous leurs descendants. Le PATH et les exécutables de l’hôte restent une frontière de confiance. Une denylist de noms de programmes ne constitue pas une sandbox. Pour un contexte restreint, préférer une allowlist de programmes de confiance ; les file tools et leurs permissions restent séparés.

Les constructions Bash hors grammaire sont refusées plutôt que partiellement interprétées sous policy granulaire. Les effets d’un script autorisé ne sont pas transactionnels : une erreur de programme après un premier effet ne déclenche pas de rollback. Le précontrôle garantit l’absence de démarrage d’un script refusé par la policy.

`report.json`, `test-results.json`, les scores et `source-manifest.json` ne sont pas réécrits : ils attestent la campagne d’audit précédente. `verify_report.py` refusera normalement les empreintes modifiées après ce correctif ; ce comportement signale que le rapport initial n’est plus une certification du checkout courant. La preuve du correctif est la nouvelle campagne Maven décrite ici.

## Évolution des approbations

Le suivi ci-dessus est historique. [SEC-05](../security/05-scoped-approvals.md) remplace la portée outil/agent de `always` par une approbation de l’action exacte avec expiration. Les refus granulaires et les vérifications avant effet restent inchangés.
