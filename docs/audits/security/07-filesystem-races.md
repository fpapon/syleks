# SEC-07: Make file operations resistant to path races

Status: not started. Priority: medium; potential local race, not reproduced during review. Dependencies: none.

## Finding and relevant code

File tools reject symlinks and recheck confinement, but validation and subsequent path-based reads/writes are separate operations. Existing-file mutation uses a comparison followed by `Files.write`. A process able to mutate the workspace concurrently could change a path component between those operations. Treat this as a hypothesis to validate, not a confirmed exploit.

Read [AgentTools.java](../../../src/main/java/fr/openobject/ai/syleks/ai/AgentTools.java), [FileMutation.java](../../../src/main/java/fr/openobject/ai/syleks/ai/FileMutation.java), [FileChangeJournal.java](../../../src/main/java/fr/openobject/ai/syleks/session/FileChangeJournal.java), [ConfigurationFiles.java](../../../src/main/java/fr/openobject/ai/syleks/app/ConfigurationFiles.java), [CredentialStore.java](../../../src/main/java/fr/openobject/ai/syleks/auth/CredentialStore.java), and [DataPaths.java](../../../src/main/java/fr/openobject/ai/syleks/session/storage/DataPaths.java).

## Implementation contract

- Inventory read/write/edit/patch, file journal undo/redo, save/export, configuration, lock files, and credential paths. Specify their confinement and overwrite guarantees separately.
- Prefer operations relative to verified open directory handles using `SecureDirectoryStream` where available, and no-follow opens. Protect every traversed component, not only the final filename.
- Stage replacement data privately and commit relative to the verified parent, with atomic replacement where required. Preserve file permissions and non-overwrite semantics. Do not describe atomic rename alone as protection against parent substitution or lost updates.
- Define concurrent modification handling using file identity/content checks at an enforceable commit boundary. Document residual same-user races or unsupported filesystem behavior accurately.
- Decide how hard links are handled, especially for in-place writes and credential/lock files. Canonical path checks do not establish that an inode has no aliases outside the workspace.
- Required security guarantees must fail closed on unsupported platforms/providers. Offer explicit documented capability distinctions rather than silently claiming equivalent protection.

## Acceptance and evidence

Add deterministic synchronization hooks or fixtures to swap final entries and parent directories between validation/open/commit. Assert no outside marker changes and no accidental overwrite. Cover existing/new files, symlinked parents, hard-link aliases, file changes after approval, cancellations, failed atomic moves, and journal rollback/undo/redo. Preserve `/save` CREATE_NEW behavior and private credential permissions.

Provide a reproduced race or a clearly documented unsuccessful reproduction, the resulting enforcement design, a platform capability table, and regression results. Follow [validation.md](validation.md).
