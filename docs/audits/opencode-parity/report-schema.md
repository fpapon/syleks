# Contrat du rapport machine

`report.json` est la source des résultats ; `verify_report.py --render` en dérive `matrix.md`. Version du contrat : **1.0.0**. JSON UTF-8, nombres de pourcentage dans [0,100], identifiants stables, `null` pour un score inconnu/non calculable. Modifier une conclusion exige une nouvelle validation, pas seulement un rendu.

| Champ racine | Contrat |
|---|---|
| `schema_version`, `schema_document` | Version sémantique et ce document |
| `audit_date` | Date ISO 8601 de la campagne |
| `baseline` | OpenCode version, date de publication, commit du tag, target_commitish séparé, URLs, génération docs, spec MCP consultée et exclusions expérimentales |
| `syleks` | Version Maven, commit, état dirty initial, fichiers préexistants, indicateur de modifications métier par audit |
| `domains[]` | `id`, `name`, `weight_pct`, `metrics` ; somme des poids 100 |
| `features[]` | Les 53 IDs demandés, uniques ; `domain`, `name`, `expected`, `status`, `observed`, `severity`, `evidence[]`, `tests[]`, `validation_kind`, `gap_id`, `reference` |
| `tests[]` | Méthodes citées : `id` = `ClasseSimple#methode`, nom qualifié, méthode, issue et chemin XML Surefire de reproduction |
| `validation` | Commande, versions, résultat, compilation/format/packaging, compteurs suite, utilisation des services réels et limites |
| `coverage` | Métriques globales et pondérées ; motif si score global testé `null` |
| `limitations[]` | Inconnues transverses hors score, ID, statut et justification |
| `e2e_scenarios[]` | E2E-001..012, statut/observation, `scored=false` pour éviter double comptage |
| `provider_capabilities[]` | Capacités de transport réellement observées et inconnues des modèles, hors score principal |

Statuts autorisés : `PASS_VERIFIED`, `PARTIAL`, `FAIL`, `NOT_IMPLEMENTED`, `NOT_TESTABLE`, `NOT_APPLICABLE`. `severity` = P0/P1/P2/P3 pour un écart, `null` pour un PASS. Une même cause peut toucher plusieurs exigences : le nombre de lignes FAIL ne représente pas un nombre d’exploits indépendants.

Une preuve source contient `path` relatif à la racine et `line` 1-based. Le comportement est décrit par `observed` et les tests référencés. Un `PASS_VERIFIED` exige code + comportement + méthode exécutée avec issue `passed`. Une méthode de caractérisation peut passer tout en laissant sa fonctionnalité FAIL. `static_absence` indique l’absence établie sans test comportemental ; `behavioral` signifie au moins une sonde citée, pas couverture exhaustive des variantes.

## Métriques

- `applicable` : lignes sauf NOT_APPLICABLE.
- `assessed` : lignes applicables sauf NOT_TESTABLE.
- `behaviorally_tested` : lignes connues avec au moins un test exécuté cité.
- `fully_verified` : nombre PASS_VERIFIED.
- `verified_coverage_pct` : fully_verified / applicable.
- `behavioral_coverage_pct` : behaviorally_tested / applicable.
- `present_pct` : (PASS + PARTIAL + FAIL) / assessed. Un FAIL est présent mais incorrect.
- `parity_assessed_pct` : somme (PASS=1, PARTIAL=0,5, FAIL/absent=0) / assessed.
- `parity_tested_pct` : même formule limitée aux lignes avec tests. `null` si aucune.
- `not_testable_pct` : NOT_TESTABLE / applicable ; inconnues de modèles réels séparées dans limitations/providers.
- `weighted_parity_assessed_pct` : somme poids domaine × score audité domaine ; arrondi à deux décimales.
- `weighted_parity_tested_pct` : `null` quand un domaine pondéré n’a aucun test, avec explication. Ne pas réallouer son poids.

PER et ORC partagent le domaine orchestration_security, sans ajouter un septième poids. E2E et providers ne sont pas comptés une seconde fois. Une ligne composite PARTIAL vaut conventionnellement 50 %, indépendamment du nombre de sous-cas : les limites qualitatives restent indispensables.

## Artefacts et contrôle

`test-results.json` contient toutes les classes et méthodes du dernier Surefire, compteurs et issues ; aucune propriété d’environnement ni credential. `source-manifest.json` contient chemin/SHA-256 des Java et migrations SQL. `baseline-sources.json` contient chemin/URL immutable/SHA-256 des sources OpenCode consultées. `baseline-api.json` conserve seulement les champs publics nécessaires au choix de la stable.

`verify_report.py` vérifie couverture des 53 IDs, statuts, preuves/empreintes, présence des tests cités, issues et recalcul des scores. Avec `--surefire`, il recoupe les méthodes et le total avec la campagne locale. Il utilise uniquement Python standard. Les champs descriptifs et sévérités restent des conclusions humaines d’audit, pas des inférences automatiques du script. L’ajout de champs compatibles est mineur ; changement de sens d’un champ/ID ou des dénominateurs exige une version majeure.
