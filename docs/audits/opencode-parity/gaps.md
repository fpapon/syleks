# Écarts et risques

> **Suivi :** le contournement shell G-TOO-003/G-TOO-007 décrit ci-dessous a depuis été corrigé. [Validation et limites du correctif](shell-security.md). Les autres écarts et le score restent ceux de l’audit initial.

La suite verte certifie les observations des tests, pas la parité. Les défauts ci-dessous sont laissés dans le code métier. P0 = risque de modification non autorisée démontré ; aucun scénario n’a utilisé le repository réel comme cible.

## Dix écarts prioritaires

| Rang | Priorité | Écart | Conséquence |
|---|---|---|---|
| 1 | P0 | G-TOO-003 / G-TOO-007 : shell composé | Une règle autorisant la première commande laisse exécuter la seconde pourtant refusée. |
| 2 | P1 | G-AGT-005 / G-ORC-004 : prompt périmé | Plan continue avec les instructions système Build sauvegardées. |
| 3 | P1 | G-SUB-002 : task absent | Le LLM ne peut pas déléguer automatiquement. |
| 4 | P1 | G-SUB-005 / G-SES-010 : sessions enfants absentes | Historique, reprise et navigation enfant indisponibles. |
| 5 | P1 | G-MCP-002 / 003 / 005 : aucun client MCP | Aucun serveur externe ne peut alimenter les tools LLM. |
| 6 | P1 | G-PER-005 : exécution couplée au chat | Pas de point commun sûr pour extensions et MCP futurs. |
| 7 | P1 | G-SUB-006 / G-ORC-002 : fins et diagnostics | Pas de résultat task typé ; refus fichier enregistré comme échec. |
| 8 | P1 | G-AGT-007 / G-ORC-005 : limites et reprise | Mode illimité sans détection, effet externe et commit non atomiques. |
| 9 | P2 | G-SES-006 : absence de résumé | Les tours anciens sont omis, leurs décisions ne sont pas résumées. |
| 10 | P2 | G-TOO-006 / G-TOO-002 : extensions et patch | Catalogue fermé, aucun apply_patch. |

## Interprétation du risque shell

`bash={"*":"deny","printf *":"allow","touch *":"deny"}` bloque `touch audit-marker` seul mais autorise `printf ok; touch audit-marker`. La sonde utilise un workspace JUnit temporaire et un fichier vide fictif. La documentation Syleks avertit déjà que les patterns portent sur la chaîne entière ; cela explique le comportement sans fournir la granularité OpenCode attendue. Le chemin interactif `ShellTool.collect` du tag collecte les commandes et leurs patterns ; aucune promesse de sandbox OS n’est inférée.

Un agent autorisé explicitement à bash possède les droits de l’utilisateur hôte. Lire un fichier via un shell approuvé malgré read DENY est une conséquence de cette frontière, pas une deuxième vulnérabilité indépendante démontrée. Les agents enfants peuvent avoir des droits propres : ne pas transformer cette possibilité normale en preuve d’escalade sans plafond de session réellement contourné.

## Fiches par exigence non conforme

### G-AGT-001 — Définition des agents

- **Exigence / statut / sévérité :** AGT-001 / PARTIAL / P2.
- **Attendu :** Registre, prompt, modèle/provider, paramètres, tools, permissions, activation.
- **Observé et impact :** Catalogue JSON/Markdown, modèle qualifié, température, max_rounds et permissions opérationnels ; pas de disable ni top_p/options arbitraires.
- **Code :** `src/main/java/fr/openobject/ai/syleks/app/AgentCatalog.java:27`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Catalogue/config et runtime existants.
- **Correction proposée :** Étendre le catalogue existant avec activation, outils et options de génération validées, sans nouveau registre concurrent.
- **Tests et acceptation :** Définition désactivée absente de la sélection et des payloads ; provider/modèle/options propres effectivement transmis.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-AGT-002 — Modes des agents

- **Exigence / statut / sévérité :** AGT-002 / PARTIAL / P2.
- **Attendu :** primary, subagent, all et visibilité interne.
- **Observé et impact :** Trois modes validés ; aucun hidden ni agent système de compactage/titre/résumé.
- **Code :** `src/main/java/fr/openobject/ai/syleks/app/AgentDefinition.java:13`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Catalogue/config et runtime existants.
- **Correction proposée :** Ajouter visibilité et agents système internes uniquement avec leurs cycles de vie définis.
- **Tests et acceptation :** Agent hidden absent des menus mais invocable via mécanisme autorisé ; primary/subagent/all respectés.
- **Complexité relative :** S (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-AGT-003 — Build et Plan

- **Exigence / statut / sévérité :** AGT-003 / PARTIAL / P1.
- **Attendu :** Tools de développement et restrictions Plan contrôlées à exécution.
- **Observé et impact :** Lecture/édition/bash démontrés ; write DENY bloque réellement. Plan dépend de sa configuration : les agents intégrés nus partagent les permissions globales, le starter fournit des règles différentes. Pas de apply_patch.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ChatSession.java:527`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Catalogue/config et runtime existants.
- **Correction proposée :** Livrer une policy Plan explicite au moteur et un chemin patch sécurisé ; garder le shell soumis à autorisation.
- **Tests et acceptation :** Plan refuse write/edit/patch/commandes non approuvées même si un faux LLM les émet.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-AGT-004 — Agents personnalisés

- **Exigence / statut / sévérité :** AGT-004 / PARTIAL / P2.
- **Attendu :** Définitions globales/projet, modèles et permissions distincts.
- **Observé et impact :** JSON global puis Markdown global puis projet ; erreurs explicites ; prise en compte après redémarrage. Pas de disable/hidden, pas de rechargement à chaud.
- **Code :** `src/main/java/fr/openobject/ai/syleks/app/AgentCatalog.java:63`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Catalogue/config et runtime existants.
- **Correction proposée :** Prendre en compte activation et lifecycle de configuration ; documenter redémarrage ou reload atomique.
- **Tests et acceptation :** Un changement invalide ne remplace pas un catalogue valide ; modèle et permissions distincts visibles au runtime.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-AGT-005 — Changement d’agent

- **Exigence / statut / sévérité :** AGT-005 / FAIL / P1.
- **Attendu :** Build → Plan → Build : prompt et permissions effectifs, session conservée.
- **Observé et impact :** Session conservée mais après Build → Plan le prompt BUILD_ONLY_MARKER persiste, PLAN_ONLY_MARKER absent de la requête ; permissions calculées sur Plan.
- **Code :** `src/main/java/fr/openobject/ai/syleks/session/ContextService.java:122`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Catalogue/config et runtime existants.
- **Correction proposée :** Séparer instructions projet persistées et prompt agent courant ; reconstruire ce dernier à chaque tour et reprise.
- **Tests et acceptation :** Build→Plan→Build : marqueur et droits correspondants dans chaque requête, historique et UUID conservés.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-AGT-006 — Instructions de projet

- **Exigence / statut / sévérité :** AGT-006 / PARTIAL / P2.
- **Attendu :** Instructions globales/projet/héritées, priorité et budget.
- **Observé et impact :** Prompt partagé global et seul AGENTS.md racine (128 KiB max), figé dans la session. Pas de hiérarchie de fichiers AGENTS ni de règles par sous-répertoire. Budget conservateur ; isolation des projets testée.
- **Code :** `src/main/java/fr/openobject/ai/syleks/session/ContextService.java:28`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Catalogue/config et runtime existants.
- **Correction proposée :** Résoudre instructions globales/racine/héritées avec ordre explicite et budget ; borner les fichiers et isoler projets.
- **Tests et acceptation :** Deux projets et sous-répertoires, règles héritées ordonnées, overflow explicite, aucune contamination.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-AGT-007 — Limites et interruption

- **Exigence / statut / sévérité :** AGT-007 / PARTIAL / P1.
- **Attendu :** Limites d’étapes, détection de boucle, erreurs répétées, annulation/reprise.
- **Observé et impact :** 12 tours par défaut, override 1..1000 ou -1, interruption et reprise ; pas de détection sémantique des boucles ni de limite dédiée aux tools/erreurs. -1 peut continuer sans borne.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/OllamaClient.java:171`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Catalogue/config et runtime existants.
- **Correction proposée :** Ajouter compteurs étapes/tools/erreurs et détection répétition, cancel/deadline communs.
- **Tests et acceptation :** Boucle identique ou erreurs répétées bornées ; annulation interrompt transports et processus.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-SUB-001 — Configuration spécialisée

- **Exigence / statut / sévérité :** SUB-001 / PARTIAL / P1.
- **Attendu :** Rôle, prompt, provider/modèle, permissions et limites distincts.
- **Observé et impact :** Définition réutilisable et override modèle/permissions/rounds ; pas de deadline par sous-agent ni quota de tokens. Modèle distinct configuré, pas validé avec modèle réel.
- **Code :** `src/main/java/fr/openobject/ai/syleks/app/AgentDefinition.java:4`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Runtime commun, tool task, permissions effectives et sessions enfants.
- **Correction proposée :** Ajouter quota et deadline par tâche, sélection de provider/modèle et permissions effectives validées.
- **Tests et acceptation :** Faux providers différents, paramètres inspectés et limite de tâche réellement appliquée.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-SUB-002 — Délégation automatique structurée

- **Exigence / statut / sévérité :** SUB-002 / NOT_IMPLEMENTED / P1.
- **Attendu :** Tool task utilisable par l’agent principal.
- **Observé et impact :** ToolSchemas ne déclare pas task ; faux LLM émettant task reçoit unsupported tool task. Aucun agent parent ne reprend automatiquement après un enfant.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ToolSchemas.java:10`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Runtime commun, tool task, permissions effectives et sessions enfants.
- **Correction proposée :** Implémenter task comme outil structuré passant par le runtime commun et retournant un résultat corrélé.
- **Tests et acceptation :** Faux LLM délègue, enfant travaille, résultat revient, parent produit une réponse finale.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-SUB-004 — Isolation du contexte

- **Exigence / statut / sévérité :** SUB-004 / PARTIAL / P1.
- **Attendu :** Prompt, autorisations, cycle et historique propres.
- **Observé et impact :** Requête enfant sans marqueur secret fictif parent, prompt spécialisé ; cycle propre et policy du callee. Historique et workspace restent partagés, pas de session ni historique enfant autonome ; bash hérite de l’environnement hôte.
- **Code :** `src/main/java/fr/openobject/ai/syleks/session/AgentService.java:211`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Runtime commun, tool task, permissions effectives et sessions enfants.
- **Correction proposée :** Créer contexte/historique de session enfant et payload de délégation explicite ; définir la frontière environnement/workspace.
- **Tests et acceptation :** Marqueurs secrets parent exclus, propres instructions/droits/history ; accès second projet refusé par défaut.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-SUB-005 — Sessions enfants

- **Exigence / statut / sévérité :** SUB-005 / NOT_IMPLEMENTED / P1.
- **Attendu :** Parent/enfant, messages/tools, navigation et restauration.
- **Observé et impact :** Une seule session après invocation et réouverture ; la mention et la réponse enfant sont écrites dans le parent. parent_session_id sert au fork, pas à la délégation.
- **Code :** `src/main/java/fr/openobject/ai/syleks/session/AgentService.java:181`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Runtime commun, tool task, permissions effectives et sessions enfants.
- **Correction proposée :** Créer une session enfant à chaque tâche, parent_id persistant, navigation TUI et réouverture.
- **Tests et acceptation :** Parent/enfant/outils après fermeture et nouvelle JVM ; retour parent et task_id stable.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-SUB-006 — Résultats et erreurs

- **Exigence / statut / sévérité :** SUB-006 / PARTIAL / P1.
- **Attendu :** Résultats/erreurs/annulation/deadline retournés au parent.
- **Observé et impact :** Résultat manuel et annulation disponibles ; mêmes erreurs LLM/tools que le chat. Aucun résultat task corrélé, agrégation parent ni timeout de sous-agent. Refus de fichier classé FAILED.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ChatSession.java:768`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Runtime commun, tool task, permissions effectives et sessions enfants.
- **Correction proposée :** Introduire résultats typés success/failure/cancel/timeout avec diagnostics persistants.
- **Tests et acceptation :** Quatre fins distinctes, erreur LLM et erreur outil, parent informé sans perdre les diagnostics.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-SUB-007 — Sous-agents simultanés

- **Exigence / statut / sévérité :** SUB-007 / NOT_IMPLEMENTED / P2.
- **Attendu :** Unités concurrentes coordonnées ; background expérimental séparé.
- **Observé et impact :** task unique dans ChatSession ; nouvelle invocation refusée pendant exécution. Pas de scheduler, collecte, limite de concurrence ni résolution de conflits. Background OpenCode est expérimental, exclu des exigences stables supplémentaires.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ChatSession.java:163`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Runtime commun, tool task, permissions effectives et sessions enfants.
- **Correction proposée :** Ajouter TaskManager avec quota de concurrence, annulation groupée, collecte par task_id et stratégie fichiers partagés.
- **Tests et acceptation :** Deux enfants distincts, plafond mesuré, résultat corrélé, cancel et conflit d’édition sans perte silencieuse.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-SUB-008 — Droits de délégation

- **Exigence / statut / sévérité :** SUB-008 / PARTIAL / P1.
- **Attendu :** Restrictions task par nom/pattern et profondeur bornée.
- **Observé et impact :** Gate task du caller puis chat du callee pour invocation manuelle ; wildcard last-match. Aucune délégation LLM/récursive ni profondeur configurable. Permissions agent du parent ne constituent pas une sandbox de l’enfant (également distinctes chez OpenCode).
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ChatSession.java:172`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Runtime commun, tool task, permissions effectives et sessions enfants.
- **Correction proposée :** Appliquer task par nom/pattern et plafond de session hérité ; borner la profondeur indépendamment du prompt.
- **Tests et acceptation :** Sous-agent interdit ne démarre pas ; récursion au-delà de la borne refusée ; enfant ne relaxe pas un DENY de session.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-SUB-009 — Consommation des sous-agents

- **Exigence / statut / sévérité :** SUB-009 / PARTIAL / P2.
- **Attendu :** Usage par enfant et agrégation parent sans doublon.
- **Observé et impact :** Usage par invocation avec modèle/provider persisté, affecté directement à la session courante ; aucun identifiant d’agent/enfant ni agrégation hiérarchique. Événements généraux idempotents testés, attribution enfant non testable sans sessions enfants.
- **Code :** `src/main/java/fr/openobject/ai/syleks/session/AgentService.java:258`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Runtime commun, tool task, permissions effectives et sessions enfants.
- **Correction proposée :** Ajouter association usage task/agent/enfant et agrégation parent explicite sans recopier les événements.
- **Tests et acceptation :** Même invocation comptée une fois après restart/fork ; totaux hiérarchiques et modèles distincts.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-SES-004 — Gestion des sessions

- **Exigence / statut / sévérité :** SES-004 / PARTIAL / P2.
- **Attendu :** Listing, recherche/projet, renommage, suppression, export/import.
- **Observé et impact :** CLI list/delete/export ; TUI filtre titres, rename/delete, /save sans overwrite ; pas d’import ni commande archive malgré enum ARCHIVED.
- **Code :** `src/main/java/fr/openobject/ai/syleks/app/Main.java:124`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Repositories SQLite, contexte et migrations ciblées ; enfants dépendants de task.
- **Correction proposée :** Ajouter import transactionnel validé et, si retenu, archive accessible ; préserver contrôles projet.
- **Tests et acceptation :** Roundtrip export/import, conflits UUID, JSON invalide, autre projet et archive/reprise.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-SES-005 — Fork

- **Exigence / statut / sévérité :** SES-005 / PARTIAL / P2.
- **Attendu :** Branche entière ou depuis un message, indépendance et usage.
- **Observé et impact :** Copie transactionnelle entière avec parent et références tools remappées, sans copie usage ; pas de fork à partir d’un message.
- **Code :** `src/main/java/fr/openobject/ai/syleks/session/SessionService.java:144`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Repositories SQLite, contexte et migrations ciblées ; enfants dépendants de task.
- **Correction proposée :** Définir fork à un message, copier uniquement préfixe cohérent et relations tools complètes.
- **Tests et acceptation :** Frontières user/assistant/tool, indépendance, pas de copie de consommation passée.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-SES-006 — Compactage

- **Exigence / statut / sévérité :** SES-006 / PARTIAL / P2.
- **Attendu :** Résumé réel réduisant le contexte et gardant l’historique.
- **Observé et impact :** Budget estimé UTF-8, tours anciens omis, DB intacte ; RECENT_TURNS ne résume rien. Champ summary lu mais aucun chemin utilisateur de génération de résumé.
- **Code :** `src/main/java/fr/openobject/ai/syleks/session/ContextCompactor.java:11`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Repositories SQLite, contexte et migrations ciblées ; enfants dépendants de task.
- **Correction proposée :** Implémenter résumé généré par provider simulable, sauvegardé en DB et utilisé pour les tours suivants.
- **Tests et acceptation :** Résumé présent dans prochain payload, décisions conservées, historique complet intact, restart.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-SES-008 — Historique des modifications

- **Exigence / statut / sévérité :** SES-008 / NOT_IMPLEMENTED / P2.
- **Attendu :** Diff/revert/undo/redo des fichiers.
- **Observé et impact :** Édition directe et coloration diff ne sont pas des snapshots. Enum DIFF sans production ni commandes undo/redo/revert. Aucun mécanisme de restauration des fichiers.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/AgentTools.java:235`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Repositories SQLite, contexte et migrations ciblées ; enfants dépendants de task.
- **Correction proposée :** Journaliser les modifications avec contenu/diff et contrôle de divergence avant revert ; exposer undo/redo.
- **Tests et acceptation :** Undo/redo réel des fichiers, conflit avec édition externe refusé, crash sans corruption.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-SES-010 — Arbre des sous-sessions

- **Exigence / statut / sévérité :** SES-010 / NOT_IMPLEMENTED / P1.
- **Attendu :** Relations parent/enfants, messages, tools et statistiques.
- **Observé et impact :** Pas d’enfants issus de délégation ; le modèle de fork ne suffit pas.
- **Code :** `src/main/java/fr/openobject/ai/syleks/session/AgentService.java:221`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Repositories SQLite, contexte et migrations ciblées ; enfants dépendants de task.
- **Correction proposée :** Réutiliser relation parent avec un type fork/task explicite et requêtes hiérarchiques.
- **Tests et acceptation :** Arbre non ambigu, messages et statistiques enfants consultables après restart.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-TOO-001 — Registre et contrats d’outils

- **Exigence / statut / sévérité :** TOO-001 / PARTIAL / P1.
- **Attendu :** Déclarations JSON, validation, résultats et extensibilité.
- **Observé et impact :** ToolCall/ToolHandler, schémas natifs et validations explicites ; catalogue statique, résultats String, pas de registre dynamique/extension. Exceptions converties en texte.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/AgentTools.java:32`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** ToolRegistry/ToolExecutor et PermissionService ; shell sûr avant extension.
- **Correction proposée :** Créer ToolRegistry et ToolExecutor Fusion, schéma validé à exécution, résultats et erreurs typés.
- **Tests et acceptation :** Doublon refusé, arguments invalides refusés, tool désactivé absent et inexécutable.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-TOO-002 — Outils fichiers et recherche

- **Exigence / statut / sévérité :** TOO-002 / PARTIAL / P2.
- **Attendu :** read/write/edit/apply_patch/glob/grep ; list selon disponibilité.
- **Observé et impact :** read/write/edit/glob/grep/list opérationnels et bornés ; apply_patch absent. list est un bonus Syleks, pas un tool déclaré dans tools.mdx retenu. Pas de suivi .gitignore, gros fichiers >1 MB refusés au lieu de pagination disque.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/AgentTools.java:130`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** ToolRegistry/ToolExecutor et PermissionService ; shell sûr avant extension.
- **Correction proposée :** Ajouter patch borné et sûr, grands fichiers paginés, règles ignore ; garder confinement et Unicode.
- **Tests et acceptation :** Fichier absent, refus, traversal, symlink, binaire, gros UTF-8 et sortie bornée pour chaque outil.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-TOO-003 — Shell

- **Exigence / statut / sévérité :** TOO-003 / FAIL / P0.
- **Attendu :** Cwd, sortie/exit, timeout/cancel, enfants, bornes et contrôle.
- **Observé et impact :** Cwd fixe au lancement, environnement hérité, stdout/stderr fusionnés, timeout 1..300 s, sortie 32000 caractères. Commandes évaluées en bloc : printf * autorise aussi une seconde commande touch pourtant DENY. Pas de cwd/env par appel.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/LocalCommandRunner.java:43`.
- **Risque de sécurité :** Risque démontré de modification sans autorisation de la sous-commande ; priorité immédiate.
- **Dépendances :** ToolRegistry/ToolExecutor et PermissionService ; shell sûr avant extension.
- **Correction proposée :** Analyser les constructions shell et évaluer chaque action ; refuser/ASK les constructions non analysables ; ajouter workdir/env bornés.
- **Tests et acceptation :** La sonde printf+touch doit être refusée sans créer le témoin ; pipelines, substitutions, redirections et processus enfants testés.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-TOO-004 — Outils complémentaires

- **Exigence / statut / sévérité :** TOO-004 / PARTIAL / P1.
- **Attendu :** Web, skill, todo, question, task ; LSP expérimental séparé.
- **Observé et impact :** Fetch/search testés avec réponses simulées ; aucune API skill/todo/question/task. LSP expérimental absent sans pénalité stable distincte. Recherche DuckDuckGo live non vérifiée.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/AgentTools.java:90`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** ToolRegistry/ToolExecutor et PermissionService ; shell sûr avant extension.
- **Correction proposée :** Ajouter skill, todo, question puis task via contrats communs ; laisser LSP expérimental séparé.
- **Tests et acceptation :** LLM charge un skill, suit tâches et reçoit réponse utilisateur structurée sans contourner policy.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-TOO-006 — Tools personnalisés

- **Exigence / statut / sévérité :** TOO-006 / NOT_IMPLEMENTED / P2.
- **Attendu :** Extension sans modifier le moteur, découverte/schema/policy.
- **Observé et impact :** ToolHandler est un callback interne ; noms et dispatch codés dans AgentTools/ChatSession/ToolSchemas. Fusion ne découvre pas de plugins tools.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ChatSession.java:573`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** ToolRegistry/ToolExecutor et PermissionService ; shell sûr avant extension.
- **Correction proposée :** Découvrir des ToolDefinition Fusion ou providers de tools, contrôlés par le même ToolExecutor.
- **Tests et acceptation :** Plugin test ajouté sans modification du dispatch central ; schema, collisions, exception et DENY vérifiés.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-TOO-007 — Sécurité des outils

- **Exigence / statut / sévérité :** TOO-007 / FAIL / P0.
- **Attendu :** Confinement fichiers, commandes, sorties et secrets.
- **Observé et impact :** Traversal, symlinks ordinaires, refus read validés ; bypass shell composé confirmé. Prompt injection atténuée par prompt uniquement ; aucune garantie contre symlink race TOCTOU, bash peut lire hors projet et l’environnement. Pas de fuite réelle de credentials constatée.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ChatSession.java:602`.
- **Risque de sécurité :** Risque démontré de modification sans autorisation de la sous-commande ; priorité immédiate.
- **Dépendances :** ToolRegistry/ToolExecutor et PermissionService ; shell sûr avant extension.
- **Correction proposée :** Corriger le shell composé et traiter la confinement/race par primitives filesystem robustes ; politique de secrets et données non fiables explicite.
- **Tests et acceptation :** Tests TOCTOU contrôlés, faux secrets, arguments malveillants, fichiers injectant instructions ; aucune action refusée exécutée.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-PER-002 — Portées des permissions

- **Exigence / statut / sévérité :** PER-002 / PARTIAL / P1.
- **Attendu :** Global/projet/agent/sous-agent/tool/argument.
- **Observé et impact :** Global JSON puis agent JSON puis global Markdown puis projet Markdown ; cible chemin/commande/nom. Pas de policy projet autonome générale, permissions de session persistées ni clé MCP libre. Modèle child séparé mais pas plafond de session.
- **Code :** `src/main/java/fr/openobject/ai/syleks/app/AppConfig.java:433`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Runtime commun, événements et policy effective ; journalisation pour reprise.
- **Correction proposée :** Séparer permissions globales/agent de plafonds projet/session ; supporter espaces de noms tool dynamiques.
- **Tests et acceptation :** Matrice de priorités, agent peut préciser ses droits sans relaxer un plafond de session.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-PER-004 — Validation utilisateur

- **Exigence / statut / sévérité :** PER-004 / PARTIAL / P1.
- **Attendu :** Action/paramètres, attente, approbation/refus/annulation et trace.
- **Observé et impact :** Modal TUI et approve once/always avec id anti-ancien prompt ; DENY reste prioritaire. Trace persistée pour tools mais refus fichier marqué FAILED et non CANCELLED, paramètres/résultats stockés sans redaction générale.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ChatSession.java:267`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Runtime commun, événements et policy effective ; journalisation pour reprise.
- **Correction proposée :** Propager un résultat de refus typé jusqu’au repository ; réduire/redacter les données sensibles des traces selon politique documentée.
- **Tests et acceptation :** DENY/refus/cancel ne deviennent pas FAILED ni SUCCESS ; décision et cible auditables sans faux secret.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-PER-005 — Contrôle au point d’exécution

- **Exigence / statut / sévérité :** PER-005 / PARTIAL / P1.
- **Attendu :** Contrôle cohérent indépendant de la TUI sur chaque entrée.
- **Observé et impact :** Gates dans ChatSession, donc contrôles hors renderer ; sous-agent utilise sa policy. Pas de ToolExecutor indépendant ; API AgentTools accepte tout Gate fourni, extensions/MCP absents ; bash composé contourne la granularité. Pas d’escalade parent→enfant démontrée au-delà des droits explicitement configurés.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ChatSession.java:683`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Runtime commun, événements et policy effective ; journalisation pour reprise.
- **Correction proposée :** Centraliser la policy effective dans ToolExecutor avec contexte d’invocation obligatoire.
- **Tests et acceptation :** CLI, agent, enfant, plugin et MCP traversent le même gate et ne peuvent fournir un allow arbitraire.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-MCP-001 — Configuration MCP

- **Exigence / statut / sévérité :** MCP-001 / NOT_IMPLEMENTED / P1.
- **Attendu :** Plusieurs serveurs global/projet, env/headers/timeouts/enabled.
- **Observé et impact :** Aucun client MCP, transport JSON-RPC, configuration ou branche MCP dans le registre/dispatch/dépendances. Aucun test de connexion possible : les scénarios restent bloqués par absence d’implémentation, pas par indisponibilité externe. OAuth LLM et GET webfetch ne valent pas MCP.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ToolSchemas.java:10`.
- **Risque de sécurité :** Aucune attaque MCP active possible sans client ; risque futur de code/réseau/credentials si intégré sans contrôles. Absence non classée P0.
- **Dépendances :** ToolRegistry/ToolExecutor, configuration serveur puis transports ; auth séparée.
- **Correction proposée :** Créer McpServerRegistry utilisateur/projet et modèle de configuration validé.
- **Tests et acceptation :** Serveur simulé contrôlé, cas nominal et défaillances de test-plan.md ; aucune exécution non autorisée et sortie/contexte bornés.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-MCP-002 — Transport stdio

- **Exigence / statut / sévérité :** MCP-002 / NOT_IMPLEMENTED / P1.
- **Attendu :** Processus, initialize, négociation, découverte, call et close.
- **Observé et impact :** Aucun client MCP, transport JSON-RPC, configuration ou branche MCP dans le registre/dispatch/dépendances. Aucun test de connexion possible : les scénarios restent bloqués par absence d’implémentation, pas par indisponibilité externe. OAuth LLM et GET webfetch ne valent pas MCP.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ToolSchemas.java:10`.
- **Risque de sécurité :** Aucune attaque MCP active possible sans client ; risque futur de code/réseau/credentials si intégré sans contrôles. Absence non classée P0.
- **Dépendances :** ToolRegistry/ToolExecutor, configuration serveur puis transports ; auth séparée.
- **Correction proposée :** Client stdio : lifecycle processus, JSON-RPC, handshake SDK-compatible, discovery/call/close.
- **Tests et acceptation :** Serveur simulé contrôlé, cas nominal et défaillances de test-plan.md ; aucune exécution non autorisée et sortie/contexte bornés.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-MCP-003 — Transport distant

- **Exigence / statut / sévérité :** MCP-003 / NOT_IMPLEMENTED / P1.
- **Attendu :** Streamable HTTP et fallback SSE existants dans le tag.
- **Observé et impact :** Aucun client MCP, transport JSON-RPC, configuration ou branche MCP dans le registre/dispatch/dépendances. Aucun test de connexion possible : les scénarios restent bloqués par absence d’implémentation, pas par indisponibilité externe. OAuth LLM et GET webfetch ne valent pas MCP.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ToolSchemas.java:10`.
- **Risque de sécurité :** Aucune attaque MCP active possible sans client ; risque futur de code/réseau/credentials si intégré sans contrôles. Absence non classée P0.
- **Dépendances :** ToolRegistry/ToolExecutor, configuration serveur puis transports ; auth séparée.
- **Correction proposée :** Client Streamable HTTP avec sessions protocole, fallback SSE si retenu et timeouts/cancel.
- **Tests et acceptation :** Serveur simulé contrôlé, cas nominal et défaillances de test-plan.md ; aucune exécution non autorisée et sortie/contexte bornés.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-MCP-004 — Catalogue MCP

- **Exigence / statut / sévérité :** MCP-004 / NOT_IMPLEMENTED / P1.
- **Attendu :** tools/list, tools/call, schémas et catalogue changeant.
- **Observé et impact :** Aucun client MCP, transport JSON-RPC, configuration ou branche MCP dans le registre/dispatch/dépendances. Aucun test de connexion possible : les scénarios restent bloqués par absence d’implémentation, pas par indisponibilité externe. OAuth LLM et GET webfetch ne valent pas MCP.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ToolSchemas.java:10`.
- **Risque de sécurité :** Aucune attaque MCP active possible sans client ; risque futur de code/réseau/credentials si intégré sans contrôles. Absence non classée P0.
- **Dépendances :** ToolRegistry/ToolExecutor, configuration serveur puis transports ; auth séparée.
- **Correction proposée :** Implémenter pagination tools/list, schémas, conversion results/errors et notifications de catalogue.
- **Tests et acceptation :** Serveur simulé contrôlé, cas nominal et défaillances de test-plan.md ; aucune exécution non autorisée et sortie/contexte bornés.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-MCP-005 — Intégration MCP au LLM

- **Exigence / statut / sévérité :** MCP-005 / NOT_IMPLEMENTED / P1.
- **Attendu :** Tools namespacés, permissions, historique, résultat au modèle.
- **Observé et impact :** Aucun client MCP, transport JSON-RPC, configuration ou branche MCP dans le registre/dispatch/dépendances. Aucun test de connexion possible : les scénarios restent bloqués par absence d’implémentation, pas par indisponibilité externe. OAuth LLM et GET webfetch ne valent pas MCP.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ToolSchemas.java:10`.
- **Risque de sécurité :** Aucune attaque MCP active possible sans client ; risque futur de code/réseau/credentials si intégré sans contrôles. Absence non classée P0.
- **Dépendances :** ToolRegistry/ToolExecutor, configuration serveur puis transports ; auth séparée.
- **Correction proposée :** Brancher les tools MCP au ToolRegistry/ToolExecutor et persister calls/results.
- **Tests et acceptation :** Serveur simulé contrôlé, cas nominal et défaillances de test-plan.md ; aucune exécution non autorisée et sortie/contexte bornés.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-MCP-006 — Collisions MCP

- **Exigence / statut / sévérité :** MCP-006 / NOT_IMPLEMENTED / P2.
- **Attendu :** Isolation des noms, outils natifs protégés, déconnexion.
- **Observé et impact :** Aucun client MCP, transport JSON-RPC, configuration ou branche MCP dans le registre/dispatch/dépendances. Aucun test de connexion possible : les scénarios restent bloqués par absence d’implémentation, pas par indisponibilité externe. OAuth LLM et GET webfetch ne valent pas MCP.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ToolSchemas.java:10`.
- **Risque de sécurité :** Aucune attaque MCP active possible sans client ; risque futur de code/réseau/credentials si intégré sans contrôles. Absence non classée P0.
- **Dépendances :** ToolRegistry/ToolExecutor, configuration serveur puis transports ; auth séparée.
- **Correction proposée :** Namespacer les identifiants, refuser collisions après normalisation et retirer proprement les tools déconnectés.
- **Tests et acceptation :** Serveur simulé contrôlé, cas nominal et défaillances de test-plan.md ; aucune exécution non autorisée et sortie/contexte bornés.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-MCP-007 — Authentification MCP

- **Exigence / statut / sévérité :** MCP-007 / NOT_IMPLEMENTED / P1.
- **Attendu :** Headers/OAuth PKCE/découverte/refresh propres aux serveurs.
- **Observé et impact :** Aucun client MCP, transport JSON-RPC, configuration ou branche MCP dans le registre/dispatch/dépendances. Aucun test de connexion possible : les scénarios restent bloqués par absence d’implémentation, pas par indisponibilité externe. OAuth LLM et GET webfetch ne valent pas MCP.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ToolSchemas.java:10`.
- **Risque de sécurité :** Aucune attaque MCP active possible sans client ; risque futur de code/réseau/credentials si intégré sans contrôles. Absence non classée P0.
- **Dépendances :** ToolRegistry/ToolExecutor, configuration serveur puis transports ; auth séparée.
- **Correction proposée :** Auth MCP séparée : headers puis OAuth PKCE, discovery, stockage/refresh/logout.
- **Tests et acceptation :** Serveur simulé contrôlé, cas nominal et défaillances de test-plan.md ; aucune exécution non autorisée et sortie/contexte bornés.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-MCP-008 — Resources et prompts MCP

- **Exigence / statut / sévérité :** MCP-008 / NOT_IMPLEMENTED / P2.
- **Attendu :** Resources/read/templates et prompts/list/get optionnels supportés dans le tag.
- **Observé et impact :** Aucun client MCP, transport JSON-RPC, configuration ou branche MCP dans le registre/dispatch/dépendances. Aucun test de connexion possible : les scénarios restent bloqués par absence d’implémentation, pas par indisponibilité externe. OAuth LLM et GET webfetch ne valent pas MCP.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ToolSchemas.java:10`.
- **Risque de sécurité :** Aucune attaque MCP active possible sans client ; risque futur de code/réseau/credentials si intégré sans contrôles. Absence non classée P0.
- **Dépendances :** ToolRegistry/ToolExecutor, configuration serveur puis transports ; auth séparée.
- **Correction proposée :** Ajouter resources/templates/prompts facultatifs avec intégration de contexte explicite.
- **Tests et acceptation :** Serveur simulé contrôlé, cas nominal et défaillances de test-plan.md ; aucune exécution non autorisée et sortie/contexte bornés.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-MCP-009 — Résilience MCP

- **Exigence / statut / sévérité :** MCP-009 / NOT_IMPLEMENTED / P1.
- **Attendu :** Timeout/erreurs/arrêt/JSON invalide/cancel/reconnect.
- **Observé et impact :** Aucun client MCP, transport JSON-RPC, configuration ou branche MCP dans le registre/dispatch/dépendances. Aucun test de connexion possible : les scénarios restent bloqués par absence d’implémentation, pas par indisponibilité externe. OAuth LLM et GET webfetch ne valent pas MCP.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ToolSchemas.java:10`.
- **Risque de sécurité :** Aucune attaque MCP active possible sans client ; risque futur de code/réseau/credentials si intégré sans contrôles. Absence non classée P0.
- **Dépendances :** ToolRegistry/ToolExecutor, configuration serveur puis transports ; auth séparée.
- **Correction proposée :** Définir retries non destructifs, gestion EOF/process death, payloads malformés, bornes et cancel.
- **Tests et acceptation :** Serveur simulé contrôlé, cas nominal et défaillances de test-plan.md ; aucune exécution non autorisée et sortie/contexte bornés.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-MCP-010 — Sécurité MCP

- **Exigence / statut / sévérité :** MCP-010 / NOT_IMPLEMENTED / P1.
- **Attendu :** Contrôle lancement/réseau/credentials/tools et budget descriptions.
- **Observé et impact :** Aucun client MCP, transport JSON-RPC, configuration ou branche MCP dans le registre/dispatch/dépendances. Aucun test de connexion possible : les scénarios restent bloqués par absence d’implémentation, pas par indisponibilité externe. OAuth LLM et GET webfetch ne valent pas MCP.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ToolSchemas.java:10`.
- **Risque de sécurité :** Aucune attaque MCP active possible sans client ; risque futur de code/réseau/credentials si intégré sans contrôles. Absence non classée P0.
- **Dépendances :** ToolRegistry/ToolExecutor, configuration serveur puis transports ; auth séparée.
- **Correction proposée :** Appliquer confiance serveur, gates par tool, restrictions réseau/process, isolation credentials et budget catalogue.
- **Tests et acceptation :** Serveur simulé contrôlé, cas nominal et défaillances de test-plan.md ; aucune exécution non autorisée et sortie/contexte bornés.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-ORC-002 — États et transitions

- **Exigence / statut / sévérité :** ORC-002 / PARTIAL / P1.
- **Attendu :** Idle/thinking/tool/permission/completed/failed/cancelled.
- **Observé et impact :** États UI String, task/revision/retiring, statut session/message/tool séparés ; annulation et attente testées. Pas d’automate typé unique ; refus fichier FAILED et erreur shell exit non nul peut être SUCCESS du tool.
- **Code :** `src/main/java/fr/openobject/ai/syleks/session/AgentService.java:392`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Runtime commun, événements et policy effective ; journalisation pour reprise.
- **Correction proposée :** Utiliser états/événements typés et distinguer refus, erreur tool, exit non nul et interruption.
- **Tests et acceptation :** Transitions idle→LLM→permission/tool→fin, cancel et échec cohérents en UI et DB.
- **Complexité relative :** M (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-ORC-003 — Orchestration des enfants

- **Exigence / statut / sévérité :** ORC-003 / NOT_IMPLEMENTED / P1.
- **Attendu :** Délégation/agrégation/annulation et arbre persistant.
- **Observé et impact :** Exécution manuelle dans ChatSession ; pas de graphe de tâches ni résultat task, pas d’arbre de délégation.
- **Code :** `src/main/java/fr/openobject/ai/syleks/ai/ChatSession.java:162`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Runtime commun, événements et policy effective ; journalisation pour reprise.
- **Correction proposée :** Ajouter orchestration de task sur le même runtime avec arbre, corrélation et agrégation.
- **Tests et acceptation :** Parent reprend après résultats multiples, erreurs et cancel, arbre restauré.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-ORC-004 — Gestion du contexte

- **Exigence / statut / sévérité :** ORC-004 / FAIL / P1.
- **Attendu :** Système/historique/tools/résumés/budget/changement d’agent.
- **Observé et impact :** Paires calls/results et budget conservés, mais prompt agent obsolète après switch ; pas de résumé généré. Historique reconstruit avec envelope dépendant du provider sélectionné.
- **Code :** `src/main/java/fr/openobject/ai/syleks/session/ContextService.java:45`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Runtime commun, événements et policy effective ; journalisation pour reprise.
- **Correction proposée :** Corriger prompt agent dynamique puis contexte canonique indépendant du wire provider et compactage.
- **Tests et acceptation :** Même historique rejoué sur faux Ollama/Responses, instructions agent à jour, paires valides et résumé borné.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

### G-ORC-005 — Reprise et effets de bord

- **Exigence / statut / sévérité :** ORC-005 / PARTIAL / P1.
- **Attendu :** Interruption aux frontières tool et absence de replay dangereux.
- **Observé et impact :** Appels interrompus non rejoués et statut persistant ; crash réel génération testé. Pas de journal atomique avec effet filesystem/process externe, pas de sondes crash après effet mais avant résultat sur toutes les frontières. Exactly-once non démontré.
- **Code :** `src/main/java/fr/openobject/ai/syleks/session/AgentService.java:472`.
- **Risque de sécurité :** Pas de fuite ou corruption démontrée par cette ligne ; contrôler droits, données et effets lors de l’implémentation.
- **Dépendances :** Runtime commun, événements et policy effective ; journalisation pour reprise.
- **Correction proposée :** Journaliser intention/start/result avec état effet indéterminé ; ne pas réexécuter sans décision explicite, clés d’idempotence si supportées.
- **Tests et acceptation :** Halt avant start, pendant effet et après effet avant résultat ; reprise sans duplication automatique.
- **Complexité relative :** L (S : locale ; M : plusieurs composants ; L : lifecycle/protocole ou état transversal).

## Inconnues et risques non démontrés

Course filesystem TOCTOU, attaque regex coûteuse, processus enfants détachés/reparentés après sortie du shell, saturation de descripteurs, redaction exhaustive des données de conversation et prompt injection par un vrai modèle n’ont pas été éprouvés. Ce sont des tests à prévoir, sans les présenter comme exploits confirmés. Les clients cloud simulés ne démontrent ni éligibilité d’un abonnement ni capacités tool d’un modèle réel.
