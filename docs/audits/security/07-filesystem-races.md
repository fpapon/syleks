# SEC-07: Make file operations resistant to path races

Status: implemented 26 October 2026. Priority: medium. The earlier path-write race is reproduced deterministically in a temporary fixture; the descriptor-relative regression leaves outside markers unchanged. Dependencies: none.

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

## Enforcement and limits

`fs/SecureFiles` traverses from the filesystem root using `SecureDirectoryStream.newDirectoryStream(..., NOFOLLOW_LINKS)` for every ancestor. The final open is relative to that verified parent and uses `NOFOLLOW_LINKS`. A directory replaced with a symlink after opening cannot redirect the operation. A renamed original directory can still receive the operation through its pinned handle; this is object confinement, not a promise that an adversary cannot move that object outside the current workspace pathname.

Existing-file writes create a random sibling staging entry with `CREATE_NEW` and mode 0600, write and force its channel, then rename through the same parent handle. `SecureDirectoryStream.move` specifies atomic-move semantics; no copy or path-based fallback is used. Failed staging/moves clean up the staging entry relative to the handle. Ordinary replacements preserve POSIX mode bits; credential/configuration/trust replacements keep 0600. Ownership, ACLs, extended attributes and timestamps are not copied. New files use descriptor-relative `CREATE_NEW`, preventing accidental overwrite even if another writer creates the destination after inspection. New-file creation is not an atomic publication of all bytes: an I/O failure or crash can leave a partial new file. `/save` retains this explicit non-overwriting behavior.

Immediately before replacement/deletion, identity (`fileKey`), size, modification time and bounded content must match the pre-effect snapshot. Cancellation is checked before effects and again before the replacement commit. Native edit derives its output from the same byte snapshot that is checked at commit. Patch batches retain all-approvals-before-effects validation and check each commit again. Journal operations retain their cooperative cross-process workspace lock and write-ahead recovery; failed effects remain pending until recovery classifies them, without replay. A batch is not a filesystem transaction; earlier successful files can remain applied after a later conflict. Undo/redo refuses divergent files and uses the same descriptor-relative create/replace/delete operations.

There is **no portable atomic compare-and-replace by inode/content in Java NIO**. The final check and rename/unlink are separate system calls. A hostile same-user process can change content or replace a regular entry in that last interval, tamper with staging entries, move already-open directories, or replace lock inodes. Reads similarly cannot bind a portable channel `fstat` to pathname attributes and do not promise a coherent snapshot against hostile simultaneous in-place writes. The checks detect ordinary concurrent changes, not every malicious scheduling/ABA sequence. OS isolation, trusted private storage and excluding concurrent hostile host writers remain necessary; atomic rename alone does not solve lost updates.

Hard links are allowed for reads: a name inside the workspace does not prove the inode has no outside aliases. Existing-file mutations replace/unlink directory entries and never truncate or chmod the old inode, so outside hard-link aliases retain their bytes and mode. Credential reads no longer chmod existing records. Lock files are opened without truncation or chmod of existing entries; new locks have mode 0600. Hard-linked locks can share advisory lock state with an alias, and malicious unlink/recreation can split lock ownership. No claim of isolation from a same-user host attacker is made.

### Operation inventory

| Path / entry point | Confinement and overwrite behavior |
| --- | --- |
| Native read, grep, write, edit, patch | Workspace path policy plus descriptor-relative no-follow reads and commits. Existing writes replace; new writes never overwrite. Search enumeration is path-based, but file-content reads use the secured primitive; listings are not coherent snapshots. |
| Journal recording, recovery, undo/redo | Workspace policy; secured snapshot reads and restoration commits, cooperative lock, conflict recovery. A caller-supplied `FileMutation.Effect` is trusted application code, not a general sandbox for extensions. |
| `/save` | Explicit authorized absolute destination, not restricted to workspace; descriptor-relative private `CREATE_NEW`. No overwrite or automatic retry to a different path. |
| Session archive export | Produces serialized text returned to the caller; destination writes through `/save` use the preceding contract. SQLite content remains credential-free under the existing export rules. |
| Configuration/agent/instruction reads, config wizard | Bounded secured reads; all symlink ancestors rejected, including links resolving within the project. Initializer template files use non-overwriting secured creation; config save compares the reviewed original and commits privately under its lock. |
| Project trust records | Secured bounded reads, private replacement and checked deletion; user authority stays outside the project. |
| Credentials, refresh records, MCP OAuth | Secured bounded reads, mode-0600 creation/replacement and checked deletion; private directories and descriptor-relative lock opens. OAuth validation and refresh serialization unchanged. |
| Session/workspace/config/credential lock files | Descriptor-relative no-follow channel opens; newly created locks mode 0600. Existing contents and permissions untouched. Advisory locking is not protection against malicious unlink or hard-link aliases. |
| DataPaths and directory bootstrap | Private-directory permission changes use verified parent handles and no-follow attributes; no silent ACL fallback. Initial directory creation remains trusted path-based provisioning: Java has no descriptor-relative mkdir API. Do not initialize storage while a hostile host process can mutate its ancestors. |
| SQLite database/WAL/SHM | JDBC opens pathnames in the private data directory. Database initialization still protects mode bits in place; hard-link aliases and malicious host directory swaps are outside this guarantee. Directory permissions and trusted host provisioning are required. This change does not implement a descriptor-based SQLite VFS. |
| Engineering documents and provider cloud policy | Bounded descriptor-relative document/configuration reads; existing project policy and engineering boundary checks remain in force. |
| External tools | Shell commands, MCP servers and installed extensions are arbitrary code governed by their own permissions/OS sandbox, not `SecureFiles`. |

### Platform capabilities

| Capability | Behavior / evidence |
| --- | --- |
| Linux default Unix NIO provider, POSIX filesystem, stable file keys | Supported and exercised locally on Linux 6.12, Zulu JDK 25. Reads, create/replace/delete, private modes, locking and hard links tested. |
| Other Unix providers exposing SecureDirectoryStream + POSIX attributes + FileChannel | Capability-based support; not runtime-validated here. Symlinked system ancestors must be replaced with explicit physical paths. |
| Provider without SecureDirectoryStream (including default Windows NIO and ZIP filesystem) | Secured access fails closed. ZIP negative fixture verifies no file is created; Windows was not executed. No path-based compatibility mode. |
| Missing POSIX attributes / stable file keys / FileChannel | Required operation fails closed. Reads require stable keys; private creation/replacement requires POSIX mode support; locks/forced replacement staging require FileChannel. No assumed equivalent ACL enforcement. |
| Atomic move unsupported or failed | Existing-file replacement fails and cleans staging, leaving the original in the injected failure fixture. No non-atomic fallback. |

No configuration keys or schema changes. User-visible changes are rejection of symlinked configuration/storage ancestors and unsupported providers, private new native/export files, replacement instead of in-place truncation, and explicit concurrent-change errors. Existing files are not migrated to more permissive modes.

## Implementation record

Implemented 26 October 2026; no commit/PR created by this task.

- Reproduction: `SecureFilesTest.deterministicLegacyPathWriteReproducesParentRace` performs the previous validate/read then path-based write, with a deterministic parent rename/symlink substitution in between. Its synthetic outside marker changes. The corresponding hardened existing/new-file fixtures leave outside markers unchanged and operate only in the pinned original parent.
- Negative fixtures cover final symlinks, symlinked ancestors, hard links (including credential/lock modes), identity/content changes before commit, creation collisions, cancellation, failed atomic moves with cleanup, and unsupported providers. `FileChangeRaceTest` verifies hard-link-safe journal undo/redo, divergence during approval, and failed-effect recovery without replay or outside writes. Existing tests cover patch permissions, `/save` non-overwrite, private credential persistence and refresh, config conflicts and crash recovery.
- Validation environment: Linux `6.12.113+deb13-amd64`, Azul Zulu JDK `25+36-LTS` (`Zulu25.28+85-CA`), Maven `3.9.16`. `JAVA_HOME=/home/fpa/.sdkman/candidates/java/25-zulu`; its bin and Maven 3.9.16 bin were prepended to PATH for the commands below.
- The full suite exposed interrupted channel reads being treated as unreadable grep entries; cancellation propagation was restored and the existing `SearchBudgetTest` passed (6 tests).
- Initial sandboxed regression run could not open local HTTP fixtures (`SocketException: Operation not permitted`); rerun with execution-environment approval. An existing configuration-error wording assertion exposed an incompatibility, corrected before the successful run.

Deliberately bounded scope: directory bootstrap, JDBC/VFS access, host-malicious final-interval races, ACL/ownership preservation and unexecuted non-Linux platforms, as specified above. These are not silently covered by the descriptor-relative file guarantee.

### Executed validation

```sh
mvn -q -Dtest=SecureFilesTest,FileChangeRaceTest,AgentToolsTest,CredentialStoreTest,ConfigurationUpdateTest,ConfigInitializerTest,McpOAuthTest,SessionWirePhaseSevenTest,SessionRecoveryPhaseSevenTest,SessionsPhaseSevenTest,PermissionConfigTest test
mvn -q spotless:apply
mvn -q spotless:apply -Dtest=SearchBudgetTest,SecureFilesTest,FileChangeRaceTest test
mvn -B --no-transfer-progress clean verify
git diff --check
```

- Expanded targeted suite: **67 tests, zero failures/errors/skips**. Cancellation/filesystem/journal rerun: **20 tests, zero failures/errors/skips**.
- Final clean verification: **BUILD SUCCESS**, **596 tests reported: 585 passed, zero failures/errors, 11 skipped**; license checks, executable JAR packaging and Spotless check passed. Final run finished 26 October 2026 at 17:56:27 Europe/Paris (1 minute 45 seconds).
- The 11 skipped cases are `LinuxCommandBackendTest`, gated by `SYLEKS_SANDBOX_TESTS=required`, which was not set for this filesystem task. They are not reported as validated; SEC-03 retains its separate backend evidence/CI requirements. No Windows/macOS/provider-specific OS claims were runtime-tested.
- `git diff --check`: passed. No unrelated formatting changes observed. Synthetic files, credentials, homes and local HTTP fixtures only; no live model service required.
