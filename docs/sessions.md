# Sessions persistantes Syleks

Syleks conserve les conversations localement dans SQLite et les retrouve par projet. La reprise recharge les échanges **dans le contexte envoyé au modèle**, ainsi que dans la TUI. Aucun outil n'est exécuté automatiquement lors d'une reprise ou d'un fork.

## Démarrage et commandes CLI

Construire avec Java 25 et Maven 3.9+ :

```sh
mvn clean verify
export PATH="$PWD/bin:$PATH"
syleks --help
syleks                         # ouvre la TUI ; propose les sessions du projet si elles existent
syleks --new                   # nouvelle conversation
syleks -c                      # dernière session non archivée ; création si aucune n'existe
syleks --continue
syleks session list
syleks -s 4fe29dd4-0bbc-49df-b89b-98bbf970d610
syleks --session 4fe29dd4-0bbc-49df-b89b-98bbf970d610
syleks --fork 4fe29dd4-0bbc-49df-b89b-98bbf970d610
```

Remplacer les UUID d'exemple par ceux de `session list`. Le script `bin/syleks` lance le JAR construit et active l'accès natif nécessaire à JLine et SQLite. On peut aussi utiliser directement :

```sh
java --enable-native-access=ALL-UNNAMED -jar /chemin/syleks/target/syleks-0.1.0-SNAPSHOT.jar -c
java --enable-native-access=ALL-UNNAMED -jar /chemin/syleks/target/syleks-0.1.0-SNAPSHOT.jar chat
mvn compile exec:exec
```

Le lancement historique avec `chat` reste accepté. Le répertoire de lancement détermine le projet, même si le JAR se trouve ailleurs. `--help` ne crée pas de base. `session list` produit des colonnes séparées par des tabulations. Codes de retour : `0` succès, `2` arguments ou identifiant invalides, `1` erreur d'exécution (stockage indisponible, verrou occupé, etc.).

Les identifiants d'un autre projet sont refusés, même pour l'export et la suppression. Il n'y a pas d'option d'accès interprojets dans cette version : se placer explicitement dans le projet concerné.

## Commandes interactives

| Commande | Effet |
| --- | --- |
| `/config` ou Ctrl+G | Crée ou modifie la configuration dans une modale ; Save propose de redémarrer le chat |
| `/restart` | Recharge la configuration dans un nouveau conteneur Fusion, avec la session et les brouillons conservés |
| `/new` | Crée et ouvre une nouvelle session sans modifier la précédente |
| `/sessions` ou Ctrl+R | Ouvre la sélection des sessions du projet |
| `/resume` | Ouvre la dernière session non archivée |
| `/rename nouveau titre` | Renomme la session active |
| `/fork` | Copie toute la conversation dans une session indépendante |
| `/model provider/model` | Sélection explicite d'un modèle de remplacement |
| `/clear` | Ouvre une nouvelle session ; l'ancienne reste disponible |
| `/save chemin.md` | Export Markdown existant, avec autorisation et sans écrasement |
| `/help` | Liste les commandes et raccourcis |
| `/quit` | Interrompt la génération, sauvegarde les fragments et ferme la TUI |

Dans le sélecteur, utiliser ↑/↓ puis Entrée. La frappe filtre les titres sans distinction de casse ; Retour arrière efface le filtre, Échap ferme la fenêtre. Le point `●` indique la session active. Le titre, la dernière activité (UTC), le modèle, le nombre de messages persistés (instructions et outils compris) et le statut apparaissent dans la liste.

Les opérations de session et le changement de modèle s'exécutent sur un worker, hors de la boucle de rendu. Le changement recharge l'historique, ferme les modales et remet le viewport à zéro. Les brouillons sont gardés en mémoire par session pendant cette exécution. Ctrl+R permet de changer de session avec un brouillon non envoyé. Les brouillons ne sont pas sauvegardés sur disque et disparaissent à la fermeture. Les autorisations « always » sont réinitialisées lors du changement de session ; elles ne sont jamais restaurées depuis la base.

Il faut terminer ou annuler la génération et résoudre les éventuelles demandes d'autorisation avant de changer de session.

## Emplacement et configuration

Ordre de priorité :

1. `SYLEKS_DATA_DIR/syleks.db` si `SYLEKS_DATA_DIR` est défini et non vide.
2. `$XDG_DATA_HOME/syleks/syleks.db` si `XDG_DATA_HOME` est défini et non vide.
3. `~/.local/share/syleks/syleks.db`, avec le home Java, sinon.

```sh
export SYLEKS_DATA_DIR="$HOME/.local/share/syleks-private"
syleks --new
```

La base est globale à l'utilisateur. `attachments/`, `tool-outputs/`, `backups/` et `locks/` sont créés sous ce répertoire. Les trois premiers sont réservés aux fichiers volumineux ; les messages et résultats actuels sont stockés en JSON dans SQLite. Aucun chemin de pièce jointe fourni par le modèle n'est résolu par cette livraison.

Un chemin de données situé dans une arborescence Git est refusé (y compris les worktrees). Les chemins sont normalisés et les liens symboliques vers la base ou ses répertoires directs sont refusés. Les répertoires utilisent `0700` et les fichiers `0600` sur les systèmes POSIX. SQLite crée ses fichiers WAL à partir des permissions de la base. Les ACL du système s'appliquent ailleurs. Il n'y a ni synchronisation cloud, ni service supplémentaire, ni sauvegarde des clés API de configuration dans les métadonnées de session. Le stockage n'est pas chiffré : utiliser le chiffrement du système de fichiers si nécessaire.

La configuration fournisseur reste dans `~/.config/syleks/agent-config.json`. Les variables existantes `OLLAMA_URL`, `OLLAMA_MODEL`, `AGENT_SYSTEM_PROMPT` restent disponibles. Le modèle sauvegardé dans la session est restauré ; changer le modèle par défaut ne remplace pas silencieusement celui d'une session existante.

## Architecture

Le projet reste monomodule, avec injection et composants générés par **Yupiik Fusion 1.2.0**.

- `ProjectService` utilise `git rev-parse --show-toplevel`, qui reconnaît les worktrees. Sans Git, le répertoire de lancement devient la racine. Les liens symboliques sont résolus si possible. Une contrainte unique sur le chemin canonique identifie le projet ; deux clones ou worktrees ayant le même remote restent distincts. Les identifiants de connexion intégrés à un remote HTTP sont retirés avant stockage.
- `SessionService` gère création, chargement, liste, reprise, renommage, modèle, suppression, fork et export. Les identifiants sont des UUID. Le titre initial vient du premier message utilisateur, sans requête LLM. Les dates sont stockées en UTC.
- `ConversationService` gère ordre, messages, parties et restauration du transcript. Les messages ont un statut ; les parties sont versionnées et peuvent contenir du texte, du raisonnement, des appels/résultats d'outils, du code, des références de fichiers et des diffs.
- `ContextService` reconstruit le protocole Ollama ou OpenAI et sélectionne des tours complets selon un budget. Les instructions système et `AGENTS.md` à la racine du projet sont enregistrées au premier tour. Les parties internes ou visuelles ne deviennent pas automatiquement du texte envoyé au modèle.
- `AgentService` complète la machine d'état existante de `ChatSession` : il associe chaque génération à la session, enregistre le message utilisateur et le message assistant avant l'appel réseau, puis suit le streaming et les outils existants.
- Les interfaces `ProjectRepository`, `SessionRepository`, `MessageRepository`, `MessagePartRepository` et `ToolExecutionRepository` sont implémentées par `SqliteRepositories`. Les services ne contiennent pas de SQL. `SqliteDatabase` fournit les transactions composables.
- `TerminalApp` conserve la propriété du terminal. `ChatSession` garde son état visuel synchronisé. Le worker de génération et celui de la TUI effectuent les accès SQLite sans garder le moniteur visuel pendant ces accès.

Seule dépendance applicative ajoutée : `org.xerial:sqlite-jdbc:3.53.2.0`, embarquée dans le JAR ombré avec ses bibliothèques SQLite. Voir la [documentation du driver](https://github.com/xerial/sqlite-jdbc). Aucun JPA, Hibernate ou framework applicatif supplémentaire.

## Transactions, migrations et concurrence

Chaque connexion active `foreign_keys=ON` et `busy_timeout=5000`. La base utilise WAL. Les écritures sont sérialisées par chemin de base dans le processus et utilisent des transactions `BEGIN IMMEDIATE` ; SQLite coordonne les autres processus. Les lectures ont leurs propres connexions. Aucune transaction n'est gardée ouverte pendant un appel HTTP ou l'attente d'une autorisation.

Le schéma se trouve dans `src/main/resources/db/migrations/V001__sessions.sql`. `PRAGMA user_version` contient sa version courante (`1`). Au démarrage, seule la migration nécessaire est exécutée, dans une transaction comprenant la modification de version. Une erreur provoque un rollback du DDL et des données de cette migration. Une version supérieure à celle prise en charge produit une erreur demandant de mettre Syleks à jour ; aucune rétrogradation destructive n'est tentée.

Pour une future migration, ajouter un script SQL versionné, étendre la liste des versions prises en charge dans `SqliteDatabase` et ajouter des tests de migration depuis chaque schéma supporté. Ne pas modifier une migration déjà distribuée. Avant une mise à jour importante, fermer toutes les instances puis copier le répertoire de données, ou utiliser la commande de sauvegarde SQLite ; ne pas copier uniquement `syleks.db` pendant qu'une instance écrit dans son WAL. `backups/` ne contient pas de sauvegardes automatiques.

Un verrou de fichier par UUID protège une génération ainsi que les opérations de renommage, changement de modèle, fork et suppression. Deux instances peuvent lire une session et travailler sur des sessions différentes. Une génération concurrente sur la même session est refusée avec un message explicite. Les fichiers de verrou restent en place : **ne pas les supprimer manuellement pendant l'exécution**, car cela contournerait le verrou de l'inode. Le système libère le verrou lors de la mort du processus ; aucun délai de lease ne risque d'expirer pendant un outil long.

La liste et l'ouverture récupèrent les générations abandonnées seulement si le verrou de session est libre. Les messages `GENERATING` et outils `PENDING`/`RUNNING` passent alors à `INTERRUPTED`. La session est signalée comme interrompue. Une instance encore active n'est pas interrompue par le démarrage d'une autre.

## Streaming et interruptions

Le texte est immédiatement disponible pour le renderer. Un buffer en mémoire est sauvegardé toutes les **600 ms**, ainsi qu'en fin de tour, de génération, d'annulation ou d'erreur. Il n'y a pas d'écriture par token. Une panne brutale peut perdre les fragments reçus depuis le dernier flush ; les fragments déjà validés restent disponibles.

Les exécutions d'outils conservent identifiant d'appel, nom, paramètres, statut, mode d'autorisation, résultat, dates et erreur. Le fork réécrit leurs liens de messages. Les appels interrompus sans résultat sont exclus du prochain contexte protocolaire. Ils restent dans l'historique et l'export, mais ne sont jamais rejoués au chargement.

Ctrl+C ou Échap annule la génération suivant les raccourcis existants. `/quit`, Ctrl+D et EOF attendent la finalisation et le flush. Une erreur réseau marque le message en erreur et garde le texte partiel. Après un arrêt non nettoyé, ouvrir la session suffit à effectuer la récupération.

## Fenêtre de contexte et modèles

Les limites `provider.models.<id>.limit.context` et `.output` sont prioritaires. Pour Ollama, ou si ces limites ne sont pas configurées :

```sh
export SYLEKS_CONTEXT_WINDOW=16384  # défaut : 8192
export SYLEKS_RESPONSE_TOKENS=2048  # défaut : 2048, plafonné au quart de la fenêtre sans limite output explicite
syleks -c
```

Ces valeurs doivent être des entiers strictement positifs et la réserve globale doit être inférieure à la fenêtre globale. La requête Ollama fixe `num_ctx` et `num_predict` ; OpenAI utilise `max_tokens`. Vérifier que le modèle et le serveur acceptent la fenêtre choisie.

`ContextBudget` fournit une estimation conservatrice basée sur les octets UTF-8, sans tokenizer externe. `ContextCompactor` est une extension de sélection ; l'implémentation actuelle conserve les tours utilisateur les plus récents, avec leurs réponses et paires d'outils. Le prompt système, les définitions d'outils et la réserve de réponse consomment aussi du budget. Les tours plus anciens écartés du contexte sont signalés dans la TUI. Si le dernier tour ou les instructions dépassent le budget, la génération s'arrête avec une erreur explicite : augmenter la fenêtre, choisir un autre modèle ou ouvrir `/new`.

La base conserve toujours l'historique intégral. Un résumé persisté peut être injecté par le service, mais **aucune génération automatique de résumé n'est implémentée**. Le budget estimé peut donc retenir moins d'historique qu'un tokenizer précis. Les instructions enregistrées au premier tour sont conservées lors de la reprise ; ouvrir une nouvelle session pour enregistrer de nouvelles instructions projet.

Un fournisseur retiré de la configuration ne bloque pas la consultation de son historique. La TUI affiche le modèle indisponible et demande `/model provider/model` pour le remplacer explicitement. Si Ollama ne possède plus le modèle, son erreur est affichée et le modèle sauvegardé est conservé. Aucune substitution ou installation de modèle n'est automatique.

## Fork, suppression et export

Le fork copie **toute la conversation**, ses parties et les métadonnées d'outils dans une transaction. Il conserve le projet, le modèle et la référence au parent, attribue de nouveaux UUID aux entités copiées et ne déclenche aucune génération. Les copies évoluent indépendamment. Supprimer le parent ne supprime pas les forks ; leur référence au parent devient nulle.

```sh
syleks session export SESSION_UUID > conversation.json
syleks session delete SESSION_UUID
```

L'export JSON est explicite, versionné et envoyé sur stdout ; il inclut métadonnées, messages, parties structurées et exécutions d'outils. **Il peut contenir prompts, raisonnement, code confidentiel, paramètres et résultats d'outils, voire des secrets présents dans ces contenus.** Choisir un emplacement protégé et ne pas le committer. Le programme ne choisit pas de chemin d'export JSON ; la redirection appartient au shell et peut écraser un fichier. Utiliser `set -C` avant la redirection pour interdire cet écrasement. L'export Markdown `/save` conserve sa règle `CREATE_NEW` et son contrôle de permission.

La suppression est explicite et irréversible dans l'application ; les enfants sont supprimés par clés étrangères en cascade. Elle n'est pas un effacement sécurisé des blocs du disque ou des anciennes sauvegardes. Aucun mode sans persistance n'est fourni dans cette version.

## Dépannage et vérification

- **Session occupée** : terminer ou annuler la génération dans l'autre terminal, puis réessayer. Après la mort de l'autre processus, le verrou système se libère automatiquement.
- **Database locked** : une autre écriture a dépassé les cinq secondes d'attente. Réessayer ; utiliser un stockage local prenant correctement en charge les verrous SQLite et de fichiers, pas un partage réseau.
- **Schéma trop récent** : mettre à jour Syleks. Ne pas modifier `user_version` manuellement pour contourner ce contrôle.
- **Données introuvables** : vérifier `SYLEKS_DATA_DIR`, `XDG_DATA_HOME`, l'utilisateur système et la racine Git. Un autre worktree possède ses propres sessions.
- **Modèle indisponible** : utiliser `/model` ou rétablir sa configuration et son installation sur le serveur.
- **Contexte trop volumineux** : ajuster les limites ou démarrer `/new` ; aucune donnée persistée n'est supprimée pour résoudre ce problème.
- **Répertoire refusé** : déplacer `SYLEKS_DATA_DIR` hors du repository et vérifier les permissions.

Les tests n'utilisent pas de serveur Ollama réel : le faux fournisseur passe par le client et ses parseurs de streaming. Ils couvrent les migrations et leur rollback, les clés étrangères, les écritures concurrentes, les forks, les budgets, les paires d'outils, les erreurs réseau, l'annulation, la logique du sélecteur et les brouillons. Des JVM distinctes vérifient reprise réelle, arrêt via `Runtime.halt`, concurrence et commandes CLI avec bootstrap Fusion.

```sh
mvn -B --no-transfer-progress clean verify
mvn -Dtest=SqlitePersistenceTest,ContextServiceTest,PersistentStreamingTest,CliSessionTest,ProjectServiceTest,SessionPickerTest test
```

Pour un contrôle manuel avec Ollama : envoyer deux questions dans `syleks --new`, quitter, relancer `syleks -c`, puis demander un rappel d'un détail précédent. Créer une autre session avec `/new`, naviguer avec `/sessions`, tester un brouillon via Ctrl+R, puis `/fork`. Vérifier les historiques indépendants, redimensionner le terminal et interrompre une réponse. Tester aussi depuis deux repositories et deux terminaux. Le build natif GraalVM reste un profil séparé et n'est pas validé par les tests JVM.

## Fichiers de cette évolution

Fichiers modifiés :

- `.github/workflows/ci.yml`
- `README.md`
- `pom.xml`
- `src/main/java/fr/openobject/ai/syleks/ai/ChatSession.java`
- `src/main/java/fr/openobject/ai/syleks/ai/OllamaClient.java`
- `src/main/java/fr/openobject/ai/syleks/ai/ToolCallBuffer.java`
- `src/main/java/fr/openobject/ai/syleks/app/AppConfig.java`
- `src/main/java/fr/openobject/ai/syleks/model/ChatSnapshot.java`
- `src/main/java/fr/openobject/ai/syleks/tui/ChatRenderer.java`
- `src/main/java/fr/openobject/ai/syleks/tui/TerminalApp.java`
- `src/main/resources/META-INF/native-image/fr.openobject.ai/syleks/reachability-metadata.json`

Fichiers créés :

- `bin/syleks`
- `docs/sessions.md`
- `src/main/java/fr/openobject/ai/syleks/app/Main.java`
- `src/main/java/fr/openobject/ai/syleks/session/AgentService.java`
- `src/main/java/fr/openobject/ai/syleks/session/ContextBudget.java`
- `src/main/java/fr/openobject/ai/syleks/session/ContextCompactor.java`
- `src/main/java/fr/openobject/ai/syleks/session/ContextService.java`
- `src/main/java/fr/openobject/ai/syleks/session/ConversationService.java`
- `src/main/java/fr/openobject/ai/syleks/session/ProjectService.java`
- `src/main/java/fr/openobject/ai/syleks/session/SessionService.java`
- `src/main/java/fr/openobject/ai/syleks/session/model/Message.java`
- `src/main/java/fr/openobject/ai/syleks/session/model/MessagePart.java`
- `src/main/java/fr/openobject/ai/syleks/session/model/MessageStatus.java`
- `src/main/java/fr/openobject/ai/syleks/session/model/PartType.java`
- `src/main/java/fr/openobject/ai/syleks/session/model/Project.java`
- `src/main/java/fr/openobject/ai/syleks/session/model/Session.java`
- `src/main/java/fr/openobject/ai/syleks/session/model/SessionStatus.java`
- `src/main/java/fr/openobject/ai/syleks/session/model/ToolExecution.java`
- `src/main/java/fr/openobject/ai/syleks/session/model/ToolStatus.java`
- `src/main/java/fr/openobject/ai/syleks/session/repository/MessagePartRepository.java`
- `src/main/java/fr/openobject/ai/syleks/session/repository/MessageRepository.java`
- `src/main/java/fr/openobject/ai/syleks/session/repository/ProjectRepository.java`
- `src/main/java/fr/openobject/ai/syleks/session/repository/SessionRepository.java`
- `src/main/java/fr/openobject/ai/syleks/session/repository/ToolExecutionRepository.java`
- `src/main/java/fr/openobject/ai/syleks/session/storage/DataPaths.java`
- `src/main/java/fr/openobject/ai/syleks/session/storage/SessionLocks.java`
- `src/main/java/fr/openobject/ai/syleks/session/storage/SqliteDatabase.java`
- `src/main/java/fr/openobject/ai/syleks/session/storage/SqliteRepositories.java`
- `src/main/java/fr/openobject/ai/syleks/tui/SessionPicker.java`
- `src/main/java/fr/openobject/ai/syleks/tui/SessionViewState.java`
- `src/main/resources/db/migrations/V001__sessions.sql`
- `src/test/java/fr/openobject/ai/syleks/session/CliSessionTest.java`
- `src/test/java/fr/openobject/ai/syleks/session/ContextServiceTest.java`
- `src/test/java/fr/openobject/ai/syleks/session/PersistentStreamingTest.java`
- `src/test/java/fr/openobject/ai/syleks/session/ProjectServiceTest.java`
- `src/test/java/fr/openobject/ai/syleks/session/SessionFixture.java`
- `src/test/java/fr/openobject/ai/syleks/session/SessionProcess.java`
- `src/test/java/fr/openobject/ai/syleks/session/SqlitePersistenceTest.java`
- `src/test/java/fr/openobject/ai/syleks/tui/SessionPickerTest.java`
