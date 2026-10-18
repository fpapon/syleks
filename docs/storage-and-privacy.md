# Storage and privacy

[User guide](README.md) · [Sessions](sessions.md) · [Troubleshooting](troubleshooting.md)

## Where your files live

| Data | Default location |
| --- | --- |
| Configuration and API keys | `~/.config/syleks/agent-config.json` |
| Global agent definitions | `~/.config/syleks/agents/` |
| Launch-directory agent definitions | `.syleks/agents/` |
| Shared project instructions | `AGENTS.md` at the project root |
| Saved sessions for all projects | `~/.local/share/syleks/syleks.db` |

The session database is independent of your current repository. Its data-directory priority is:

1. `SYLEKS_DATA_DIR`, if nonempty, used directly.
2. `$XDG_DATA_HOME/syleks`, if `XDG_DATA_HOME` is nonempty.
3. Java's user-home directory plus `.local/share/syleks`.

For example, `SYLEKS_DATA_DIR=/private/local-data/syleks syleks` uses `/private/local-data/syleks/syleks.db`. Choose a local directory outside Git working trees; Syleks refuses session storage inside a repository. Changing this variable selects a different database and does not move old sessions automatically.

Syleks creates the required directories, including `attachments/`, `tool-outputs/`, `backups/`, and `locks/`. Some are reserved for larger stored content; their presence does not imply automatic attachments or backups. SQLite may also create `syleks.db-wal` and `syleks.db-shm` while running.

New data/configuration files use private permissions where the filesystem supports POSIX permissions (directories `0700`, files `0600`). Other filesystems use their own access controls. Configuration home uses `HOME` with a Java user-home fallback; session storage's fallback specifically uses Java's user home.

## What is saved or sent

Sessions save messages, structured response parts, partial output, model metadata, and tool execution records. There is no mode without persistence in this version. Unsent drafts are kept only in memory.

SQLite schema 4 adds subagent task correlations and durable execution budgets. Child sessions retain their parent/root relation, agent ancestry, status, rounds and diagnostics. Usage remains stored once under the session that made each request. Opening an existing supported database applies the migration transactionally; a newer database schema is refused. Subagents receive configured shared instructions and their explicit task, with their own provider account resolved independently; the parent's conversation and credentials are not automatically copied into their context.

Data stays local unless you explicitly send it through a configured service or export it. Syleks has no implicit cloud synchronization or configured telemetry service. Your chosen model endpoint receives chat context and tool results needed for a response. A remote endpoint is still a remote service, even though the database is local. Enabled web tools contact websites and send search queries to DuckDuckGo.

API keys are stored in the private configuration file, not automatically copied into session metadata. Prompts, read files, command output, or tool arguments can nevertheless contain secrets and become part of the session and model requests. Use a trusted endpoint and protect your device and backups. Syleks does not encrypt the database or exports; use filesystem/device encryption if needed.

Model/server connections can use HTTP or HTTPS. Plain HTTP to a remote server does not provide confidentiality; use a trusted private connection or HTTPS with appropriate server authentication. Shell tools inherit the process environment and normal OS access rights.

## Back up or move sessions

For a straightforward backup:

1. Close **all** Syleks instances cleanly.
2. Copy the complete session data directory to a private backup location.
3. Back up `~/.config/syleks` separately if you also need provider and agent settings. This copy may include API keys.

Do not copy only `syleks.db` while another instance is writing: committed changes may still be in its WAL file. Advanced users can use SQLite's supported online backup mechanism instead. The `backups/` directory is not populated automatically.

To move storage, close all instances, copy the entire data directory, and set `SYLEKS_DATA_DIR` to the new local location before relaunching. Project identity is based on canonical paths; moving a project to another path may make its old sessions belong to the old project location.

Restore while all instances are closed, using a private data directory and a compatible Syleks version. There is no JSON session import command; a JSON export is a portable record rather than a database restore mechanism.

## Updates and database versions

Syleks initializes a new database and applies required versioned SQL migrations automatically at startup. Each migration runs transactionally: a failure rolls it back rather than leaving a partial migration. Existing databases only receive missing migrations. Back up your data before significant application upgrades.

If a database was created by a newer, incompatible Syleks version, startup refuses to use it. Upgrade Syleks; do not change SQLite's schema version manually or try to force a downgrade.

SQLite uses write-ahead logging, foreign-key checks, and a five-second busy timeout. Writes are coordinated within the app, and per-session operating-system locks coordinate protected operations between instances. Keep the database on local storage with working file locks. Do not remove files under `locks/` while Syleks is running.

## Exports and deletion

`/save PATH` creates a readable Markdown export without overwriting existing files. `syleks session export ID` writes a fuller JSON record to standard output; it can contain prompts, internal reasoning, file content, tool arguments/results, and secrets in those contents. Both exports are plaintext. Choose private destinations and avoid committing them.

Deleting a session removes its associated records in the active database. It does not erase previous backups or guarantee secure erasure from disk. Forked sessions remain independent copies. See [Sessions](sessions.md) for export and deletion commands.
