# P01 validation and review record

Date: 2026-10-25. Contract approval: **pending**; [ADR 0001](adr/0001-engineering-contracts.md) remains Proposed. No workflow engine, execution ledger, actual framework/skill definitions, database migration, public Java workflow SPI, OAuth change or provider fallback was added.

## Environment and commands

Validation uses the installed Zulu JDK **25+36-LTS** and Maven **3.9.16**, cached dependencies and offline mode. No credentials, production keyring or inference service are required. Build/test files are generated only in `target/` and temporary fixture directories.

Focused parser/loader/runtime-boundary command:

```sh
env JAVA_HOME=/home/fpa/.sdkman/candidates/java/25-zulu PATH=/home/fpa/.sdkman/candidates/java/25-zulu/bin:/home/fpa/.sdkman/candidates/maven/3.9.16/bin:/usr/bin:/bin mvn -o -B --no-transfer-progress -Dtest=EngineeringDocumentsTest,TechnologyPackLoaderTest,EngineeringBoundaryTest test > /tmp/syleks-p01-focused.log 2>&1
```

Initial result: exit **0**, **19 tests, 0 failures, 0 errors, 0 skipped**. The new production and test sources were compiled with release 25. A later full verification is authoritative for the final sources.

After adding the registry-inspection regression and fixing its filesystem edge case, the same three classes were run with `-Dtest=EngineeringBoundaryTest,EngineeringDocumentsTest,TechnologyPackLoaderTest` and output redirected to `/tmp/syleks-p01-final-focused.log`: exit **0**, **20 tests, 0 failures, 0 errors, 0 skipped**.

Formatting command (run during preparation and again after final Java edits):

```sh
env JAVA_HOME=/home/fpa/.sdkman/candidates/java/25-zulu PATH=/home/fpa/.sdkman/candidates/java/25-zulu/bin:/home/fpa/.sdkman/candidates/maven/3.9.16/bin:/usr/bin:/bin mvn -o -B --no-transfer-progress spotless:apply > /tmp/syleks-p01-format.log 2>&1
```

The standalone local HTTP transport test was first attempted inside the sandbox:

```sh
env JAVA_HOME=/home/fpa/.sdkman/candidates/java/25-zulu PATH=/home/fpa/.sdkman/candidates/java/25-zulu/bin:/home/fpa/.sdkman/candidates/maven/3.9.16/bin:/usr/bin:/bin mvn -o -B --no-transfer-progress -Dtest=EngineeringTransportTest test > /tmp/syleks-p01-transport.log 2>&1
```

Result: exit **1**, **1 test, 0 assertion failures, 1 error, 0 skipped**, because the sandbox refused the fixture's loopback socket (`SocketException: Operation not permitted`). This was not counted as passing evidence. The normal approval mechanism authorized rerunning validation outside the sandbox for local socket fixtures, as in P00.

Full offline verification command:

```sh
env JAVA_HOME=/home/fpa/.sdkman/candidates/java/25-zulu PATH=/home/fpa/.sdkman/candidates/java/25-zulu/bin:/home/fpa/.sdkman/candidates/maven/3.9.16/bin:/usr/bin:/bin mvn -o -B --no-transfer-progress verify > /tmp/syleks-p01-verify.log 2>&1
```

The first full run exited **1**: **500 tests, 1 failure, 0 errors, 0 skipped**. All 480 existing tests passed; the new transport test hit context admission because its fixture advertised all tools. Its permissions were narrowed to the read tool it exercises; no production context limit was relaxed. Evidence: `/tmp/syleks-p01-first-verify.log`.

The second full run exited **1**: **501 tests, 1 failure, 0 errors, 0 skipped**. The transport test and existing suite passed. The new registry-inspection regression exposed a filesystem mapping of a non-directory ancestor to `NoSuchFileException`; the guard now inspects the nearest existing ancestor before accepting optional absence. Evidence: `/tmp/syleks-p01-second-verify.log`. The final focused run above confirms that fix.

Final full-run result: exit **0**, `BUILD SUCCESS`, **501 tests, 0 failures, 0 errors, 0 skipped**, across **67 suites**, in **1 minute 56 seconds**. This includes all **480 existing tests** and **21 Engineering tests**. License checking, shaded packaging and Spotless verification passed; formatting had already been applied and the final check reported 210 Java files clean through its up-to-date cache. Existing Shade overlap warnings remain; no dependency changes were made. Per-suite XML/text reports are in `target/surefire-reports/` and the successful log is `/tmp/syleks-p01-verify.log`.

This incremental build does not delete pre-existing generated output, publish artifacts, install dependencies or exercise the release/native profiles. All HTTP fixtures use loopback, synthetic prompts and fake credentials; no repository source is sent to cloud inference.

Packaged CLI smoke commands:

```sh
env -i PATH=/usr/bin:/bin LC_ALL=C.UTF-8 SYLEKS_DATA_DIR=/tmp/syleks-p01-smoke-data SYLEKS_ALLOW_CLOUD=false /home/fpa/.sdkman/candidates/java/25-zulu/bin/java -Duser.home=/tmp/syleks-p01-smoke-home --enable-native-access=ALL-UNNAMED -jar target/syleks-0.1.0-SNAPSHOT.jar --help
env -i PATH=/usr/bin:/bin LC_ALL=C.UTF-8 SYLEKS_DATA_DIR=/tmp/syleks-p01-smoke-data SYLEKS_ALLOW_CLOUD=false /home/fpa/.sdkman/candidates/java/25-zulu/bin/java -Duser.home=/tmp/syleks-p01-smoke-home --enable-native-access=ALL-UNNAMED -jar target/syleks-0.1.0-SNAPSHOT.jar session list
```

Both exited **0**: help printed normally in UTF-8; session list initialized the isolated SQLite store and printed its empty-project header. The concurrent smoke processes emitted a benign JVM performance-data lock warning and the existing SLF4J no-provider warning. No inference, catalog, authentication or MCP command was invoked.

The packaged schema resource was confirmed in the shaded JAR. A temporary same-package probe read the JSON enrollment block from `CONTRACTS.md` and invoked the packaged `EngineeringDocuments.parse` with `project_registry`; both compile and execution exited **0**, accepting the documented example:

```sh
/home/fpa/.sdkman/candidates/java/25-zulu/bin/javac -cp target/syleks-0.1.0-SNAPSHOT.jar -d /tmp/syleks-p01-contract-probe /tmp/SyleksContractProbe.java
env -i PATH=/usr/bin:/bin LC_ALL=C.UTF-8 /home/fpa/.sdkman/candidates/java/25-zulu/bin/java -cp /tmp/syleks-p01-contract-probe:target/syleks-0.1.0-SNAPSHOT.jar fr.openobject.ai.syleks.engineering.SyleksContractProbe docs/engineering/CONTRACTS.md
```

The probe source/classes are temporary local evidence, not a public API or committed artifact. Documentation link targets and named test methods were checked against local files; JSON blocks parsed successfully. `git diff --check` passed. Source changes are confined to the new Engineering package/schema/tests and six existing configuration/provider/tool/MCP integration files; no terminal renderer, existing test or P00 audit was changed.

## Meaningful regression evidence

| Test | Boundary verified |
| --- | --- |
| `engineering.EngineeringDocumentsTest.parsesEveryContractAndRejectsUnknownFieldsAndFutureVersions` | All eight domain contracts and the registry round-trip structurally; unknown fields/version fail without echoing rejected values. |
| `EngineeringDocumentsTest.rejectsAmbiguousMalformedUnboundedAndWrongKindDocuments` | Duplicate keys, trailing JSON, invalid UTF-8, excessive size/depth and wrong discriminators fail closed. |
| `EngineeringDocumentsTest.validatesBudgetsGraphsSpansAndDecisions` | Reservations cannot exceed the declared total; dependency cycles, invalid source spans and incomplete approval decisions are refused. |
| `EngineeringDocumentsTest.releaseReferenceNeverChangesPrivateProvenanceAndPathsStayRelative` | A release reference preserves PRIVATE origin; path escapes and noncanonical relative paths are rejected. |
| `engineering.TechnologyPackLoaderTest.loadsPinnedLocalTextAndRejectsManifestResourceAndVersionTampering` | Manifest/content hash and exact ID/version pins, all-before-return snapshots and immutable resources. |
| `TechnologyPackLoaderTest.refusesLinksAndCancellationWithoutLoadingContent` | Symlinks fail and cancellation preserves interruption. |
| `engineering.EngineeringBoundaryTest.requiresAnExactUserReviewedLocalRouteAndFailsClosedOnRegistryChanges` | Unreviewed/different endpoints, hostnames, proxies and false cloud policy deny; registry removal does not release an active session. |
| `EngineeringBoundaryTest.cloudPermissionAndForgedReleaseMetadataCannotTurnAnApiKeyIntoASubscription` | `allow_cloud=true` does not authorize release; API-key adapters are not subscription integrations; extra release fields cannot grant authority. |
| `EngineeringBoundaryTest.inheritedEnrollmentCannotBeRelaxedAndOtherProjectsKeepLegacyBehavior` | Descendant work inherits enrollment while unrelated projects keep legacy dispatch behavior. |
| `EngineeringBoundaryTest.registryInspectionErrorsAreNotMisclassifiedAsOptionalAbsence` | A non-directory ancestor cannot make enrollment disappear through a filesystem “missing” error. |
| `EngineeringBoundaryTest.blocksToolDispatchAndMcpDiscoveryBeforeApprovalHandlersOrSubprocessEffects` | External tool paths fail before approval/started callbacks; enabled MCP stdio cannot create its marker; native local listing still works under permissions. |
| `EngineeringBoundaryTest.providerServiceAndResponsesTransportRejectBeforeCallingAnyProviderOrCredentials` | Provider/catalog callbacks and Responses credential access are not reached on denial; enrolled direct clients have a NO_PROXY selector. |
| `engineering.EngineeringTransportTest.permitsOnlyTheReviewedLocalFixtureAndBlocksLaterToolOutputAfterPolicyChanges` | A real loopback fixture receives nothing before route approval; after local approval it receives only the initial synthetic prompt. Changing policy during a tool call prevents the next request from transmitting the private output sentinel. |

Class names above are under `fr.openobject.ai.syleks`; sources live in `src/test/java/fr/openobject/ai/syleks/engineering/`. Existing provider/auth/MCP, child ancestry, persistence/recovery, compaction, CLI and TUI regression classes also run in the full suite. Their fixtures establish local behavior, not live provider eligibility or external server interoperability.

## Limits and handover

Not run: live cloud/Ollama inference, sign-in/subscription billing or quota checks, real external MCP servers, actual template-repository interoperability, manual terminal/resize/keyboard checks, native image, dependency vulnerability scanning, release/publishing, OS isolation and backend/network attestation. No template repository location was supplied, so its contents/layout were not guessed or changed. User verification of local server non-forwarding remains necessary.

Review [CONTRACTS.md](CONTRACTS.md) and [COMPATIBILITY.md](COMPATIBILITY.md), then approve or amend ADR decisions A–D. Until approval, JSON v1 and enrollment are proposed interfaces; the conservative boundary is reviewable code, not permission to deploy or start a future workflow. Existing P00 `AUDIT.md` is preserved unchanged.
