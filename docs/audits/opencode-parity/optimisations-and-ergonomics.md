# Phase 8 — Optimisations et ergonomie

Cette campagne complète les audits des phases 1–7. La matrice initiale de 53 exigences et ses empreintes restent historiques ; les résultats ci-dessous portent sur les optimisations et les parcours effectivement vérifiés. Ils ne constituent pas une nouvelle déclaration de parité complète avec OpenCode.

## Changements livrés

- `ChatRenderer` réutilise le transcript inchangé et les messages déjà enveloppés/colorés pendant le streaming. La clé inclut contenu, largeur et position ; un changement de largeur, de message ou de session invalide les résultats concernés. Cache LRU de 512 messages, poids maximal 2 000 000 unités (texte, lignes et spans ; ce n'est pas une mesure d'octets heap). Le cache de frame retient au plus 50 000 lignes et 2 000 000 caractères source. Les sources dépassant ces plafonds restent affichables sans être retenues dans ce cache.
- Le picker calcule ses correspondances une fois par changement de liste/requête. Filtre insensible à la casse sur titre, UUID et modèle ; compteur, pages adaptées à la hauteur, Home/End, sélection bornée pour les pages. Les flèches gardent leur navigation circulaire. Aucune navigation pendant une confirmation de suppression.
- `/usage` affiche fenêtre configurée, réserve de sortie et allowance d'entrée, partagée par instructions, historique et schémas. Les compteurs réellement rapportés et le dernier contexte envoyé restent séparés de ces plafonds. Les longues valeurs sont enveloppées, avec pages et Home/End. L'aide explique les nouveaux contrôles.
- Les recherches annoncent résultats, entrées visitées, caractères de texte inspectés et raison d'arrêt. Plafonds : 10 000 entrées, profondeur 32 (`list` : 1), 8 000 000 caractères de texte, 8 000 000 accès regex, cinq secondes de travail, 32 000 caractères de sortie avec pied de page réservé. Les attentes d'autorisation sont exclues du délai. Chaque fichier reste limité à 1 MB. Un fichier supplémentaire peut être décodé avant de constater qu'il dépasse le budget de texte restant.
- Le moteur regex utilise un `CharSequence` qui partage un budget sur tout l'appel et contrôle l'interruption pendant le backtracking. Épuisement du travail ou de la pile : résultat explicitement incomplet ; aucune thread auxiliaire abandonnée. L'annulation se propage comme interruption.
- Reconstruction du contexte : une lecture des parts par message au lieu de deux. Reprise du journal : seuls les snapshots PENDING sont chargés. Une nouvelle édition abandonne l'historique redo par un UPDATE SQL, sans charger tous ses BLOBs. La lecture des métriques vides réutilise une valeur immuable.
- CI conserve les mesures et rapports Surefire et lance un smoke du JAR dans un vrai PTY, contre une fixture HTTP locale. Aucun service modèle externe ni credential n'est nécessaire.

## Mesures reproductibles

```sh
mvn -B --no-transfer-progress clean verify
python3 scripts/phase8-smoke.py
```

Java 25 et Maven 3.9+, Python 3 sur Linux/macOS pour le PTY ; accès aux sockets loopback pour les fixtures. Résultats dans `target/performance/` et `target/surefire-reports/`, archivés par CI dans `validation-reports`. Les timings sont des observations, sans seuil machine qui rendrait la CI instable. Les tests imposent des invariants de réutilisation, de plafonds et d'équivalence de rendu.

Fixture transcript : 400 messages, paragraphes Markdown, code Java, caractères larges et emoji, terminal 120×36 ; 20 frames inchangées et 10 mises à jour du dernier message. L'équivalence de toutes les cellules, styles compris, est vérifiée contre un renderer neuf à 35, 80 et 120 colonnes et trois positions de scroll. Fixture picker : 10 000 sessions, filtre « Task 9 » (1 111 correspondances), 1 000 déplacements. Fixture fichiers : 10 050 entrées ; autre scénario : 12 fichiers de 900 000 caractères, motifs adversariaux et une ligne qui dépasse le plafond de sortie.

Mesure locale Azul Zulu Java 25, même fixture avant/après changement :

| Opération | Avant | Après (run ciblé) |
|---|---:|---:|
| Première frame | 58,93 ms | 35,15 ms |
| Frame inchangée, moyenne | 11,35 ms | 0,092 ms |
| Mise à jour streaming, moyenne | 8,72 ms | 0,432 ms |

Mesures à froid dans des JVM distinctes, sans prétendre à une précision de microbenchmark. Les valeurs du build complet peuvent varier selon l'ordre des tests et la compilation JIT ; les fichiers `.properties` donnent les mesures du run courant.

## Validation finale

Le 19 octobre 2026 à **13:30:50 +02:00**, `mvn -B --no-transfer-progress clean verify` termine en **BUILD SUCCESS** : **457 tests**, 0 échec, 0 erreur, 0 ignoré, durée 1 min 1 s. Cela ajoute 12 scénarios aux 445 tests de la phase 7. Compilation Java 25/Fusion, JAR ombré et Spotless réussissent. Les avertissements existants SLF4J, Spotless/Unsafe et Shade restent non bloquants.

Dans ce build complet : première frame 19,09 ms, frame inchangée 0,043 ms et mise à jour streaming 0,178 ms ; filtre sur 10 000 sessions 0,243 ms. La fixture de 10 050 fichiers s'arrête à **10 000 entrées en 134,99 ms**, avec résultat marqué `truncated: entry limit`.

Le smoke PTY sur le JAR final réussit, avec fermeture du flux d'annulation observée en **250 ms** (résolution du polling : 250 ms, pas une mesure exacte de latence), compteur INTERRUPTED vérifié en SQLite et sortie code 0. Le HOME, le projet et la base sont temporaires ; le serveur est exclusivement une fixture locale. `git diff --check` et les liens locaux des documents modifiés sont vérifiés.

## Couverture renouvelée

| Périmètre | Preuve actuelle | Verdict sur ce périmètre |
|---|---|---|
| Rendu et gros historiques | `PerformanceFixtureTest`, `RenderingCacheTest`, `CanvasTest` : cellules, styles, streaming, resize, invalidation et rétention bornée | PASS_VERIFIED |
| Navigation et aide | `SessionPickerTest`, `UsageModalTest`, `HelpModalTest` : 10 000 sessions, UUID/modèle, pages/bords, confirmation de suppression, longues valeurs, tailles minimales | PASS_VERIFIED |
| Budget et observabilité | `UsageModalTest`, `TokenUsageSidebarTest`, suite `usage/*` : allowance/réserve, compteurs exacts/partiels/inconnus, débordement, statut interrompu | PASS_VERIFIED pour les métriques et plafonds affichés ; aucun prix estimé ni tokenizer exact |
| Recherches volumineuses | `SearchBudgetTest`, `AgentToolsTest` : entrées/texte/sortie, regex adversarial, pile, interruption après permission, refus de lecture | PASS_VERIFIED sur fixtures locales |
| Sessions, reprise et contexte | `ContextServiceTest`, `SessionsPhaseSevenTest`, `SessionRecoveryPhaseSevenTest`, suites d'archives et de wire : associations outils/résultats, abandon redo, reprise sans replay | PASS_VERIFIED dans les limites de la phase 7 |
| TUI packagée | `scripts/phase8-smoke.py` : historique de 403 messages, aide/picker/usage, Home/End/pages, 120×36 → 40×14 → 120×36 et 80×24, streaming annulé et métrique INTERRUPTED persistée, sortie code 0 | Résultat consigné dans `target/performance/terminal.json` |
| Parité générale | Les preuves des [phases précédentes](roadmap.md) restent applicables ; les lacunes hors phase 8 ne sont pas reclassées automatiquement | PARTIAL |

## Limites restantes

- Les délais de recherche sont coopératifs : contrôles entre entrées/lignes et pendant les accès regex de `grep`. Un appel filesystem bloqué ou un appel individuel au matcher glob Java peut dépasser le délai ; ce n'est pas un timeout imposé par le noyau. Le scan conserve l'ordre du filesystem et ne garantit pas une recherche exhaustive ; demander un sous-répertoire pour réduire le travail. Aucun répertoire de build/vendor n'est exclu silencieusement.
- Les caches bornent la rétention supplémentaire. Ils ne plafonnent pas le transcript lui-même, les snapshots persistés, les allocations transitoires d'un rendu ou les archives de session. Une première ouverture/redimensionnement de très grands historiques peut encore être coûteux. Le cache par message peut être évincé au-delà de ses plafonds.
- Les agrégats de tokens sont toujours recalculés depuis l'arbre persistant sur événements ; aucun quota monétaire, facturation ou budget global de sous-agents n'est ajouté. Les plafonds configurés doivent être supportés par le serveur. La stratégie de contexte est une estimation conservative UTF-8, pas un tokenizer du modèle ni une mesure live du prochain prompt.
- Journal natif, confinement des tools, processus détachés, TOCTOU et sandbox OS gardent les limites décrites dans les audits précédents. LSP, commandes personnalisées et options OpenCode non prévues par ces phases restent hors couverture. Aucune campagne de qualité avec un modèle réel, inspection visuelle humaine ou build natif GraalVM n'est revendiquée.
