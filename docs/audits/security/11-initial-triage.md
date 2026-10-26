# SEC-11 initial advisory triage

Snapshot: 26 October 2026, Trivy 0.76.1. These are scanner-reported advisory matches, not confirmed reachability or exploitation. No suppressions were applied. The complete dependency reports and CycloneDX inventories were generated under `target/sec11-security/`; hosted runs upload equivalent files in `security-reports`. The isolated local repository was populated with Dependency Plugin 3.11.0 `go-offline -Pnative,release`; hosted builds may resolve additional artifacts.

All rows remain **open**, owned by repository maintainers pending assignment. Rows containing `shaded` track [SEC-11-T1](11-ci-supply-chain.md#sec-11-t1-runtime-advisories); other rows track [SEC-11-T2](11-ci-supply-chain.md#sec-11-t2-build-advisories). Upgrade candidates below come from advisory metadata and need compatibility verification. Empty fix metadata does not justify accepting the finding.

| Package | Version | Scope | Severities | Advisory IDs |
| --- | --- | --- | --- | --- |
| `com.fasterxml.jackson.core:jackson-core` | 2.14.1 | maven | HIGH | CVE-2025-52999, CVE-2026-68494, CVE-2026-89425 |
| `com.fasterxml.jackson.core:jackson-core` | 2.16.1 | maven | HIGH, MEDIUM | CVE-2026-18401, CVE-2026-68494, CVE-2026-89425 |
| `com.fasterxml.jackson.core:jackson-core` | 2.17.1 | maven, shaded | HIGH, MEDIUM | CVE-2026-18401, CVE-2026-68494, CVE-2026-89407, CVE-2026-89425 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.14.1 | maven | HIGH, MEDIUM | CVE-2026-19032, CVE-2026-54512, CVE-2026-54513, CVE-2026-54514, CVE-2026-54515, CVE-2026-59889, CVE-2026-68497, CVE-2026-77310, CVE-2026-83557, CVE-2026-91776, CVE-2026-91777 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.16.1 | maven | HIGH, MEDIUM | CVE-2026-19032, CVE-2026-54512, CVE-2026-54513, CVE-2026-54514, CVE-2026-54515, CVE-2026-59888, CVE-2026-59889, CVE-2026-68497, CVE-2026-77310, CVE-2026-83557, CVE-2026-91776, CVE-2026-91777 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.17.1 | maven, shaded | HIGH, MEDIUM | CVE-2026-19032, CVE-2026-54512, CVE-2026-54513, CVE-2026-54514, CVE-2026-54515, CVE-2026-59888, CVE-2026-59889, CVE-2026-68497, CVE-2026-77310, CVE-2026-83557, CVE-2026-91776, CVE-2026-91777 |
| `com.google.guava:guava` | 16.0.1 | maven | LOW, MEDIUM | CVE-2018-10237, CVE-2020-8908, CVE-2023-2976 |
| `commons-beanutils:commons-beanutils` | 1.7.0 | maven | HIGH | CVE-2019-10086, CVE-2025-48734 |
| `commons-beanutils:commons-beanutils` | 1.9.4 | maven | HIGH | CVE-2025-48734 |
| `commons-io:commons-io` | 2.11.0 | maven | HIGH | CVE-2024-47554 |
| `commons-io:commons-io` | 2.6 | maven | HIGH, MEDIUM | CVE-2021-29425, CVE-2024-47554 |
| `commons-lang:commons-lang` | 2.4 | maven | MEDIUM | CVE-2025-48924 |
| `dom4j:dom4j` | 1.1 | maven | CRITICAL, HIGH | CVE-2018-1000632, CVE-2020-10683 |
| `io.airlift:aircompressor` | 0.27 | maven | HIGH | CVE-2025-67721 |
| `net.i2p.crypto:eddsa` | 0.3.0 | maven | MEDIUM | CVE-2020-36843 |
| `org.apache.commons:commons-compress` | 1.20 | maven | HIGH, MEDIUM | CVE-2021-35515, CVE-2021-35516, CVE-2021-35517, CVE-2021-36090, CVE-2024-25710 |
| `org.apache.commons:commons-compress` | 1.24.0 | maven | MEDIUM | CVE-2024-25710, CVE-2024-26308 |
| `org.apache.commons:commons-lang3` | 3.12.0 | maven | MEDIUM | CVE-2025-48924 |
| `org.apache.commons:commons-lang3` | 3.14.0 | maven | MEDIUM | CVE-2025-48924 |
| `org.apache.commons:commons-lang3` | 3.8.1 | maven | MEDIUM | CVE-2025-48924 |
| `org.apache.httpcomponents.client5:httpclient5` | 5.3.1 | maven | MEDIUM | CVE-2026-64607 |
| `org.apache.httpcomponents.core5:httpcore5` | 5.2.4 | maven | HIGH | CVE-2026-54399 |
| `org.apache.httpcomponents.core5:httpcore5-h2` | 5.2.4 | maven | HIGH | CVE-2026-54428 |
| `org.apache.johnzon:johnzon-mapper` | 1.2.15 | maven | MEDIUM | CVE-2023-33008 |
| `org.apache.maven:maven-core` | 3.1.1 | maven | CRITICAL | CVE-2021-26291 |
| `org.apache.maven:maven-core` | 3.2.5 | maven | CRITICAL | CVE-2021-26291 |
| `org.apache.sshd:sshd-common` | 2.7.0 | maven | CRITICAL, HIGH, MEDIUM | CVE-2022-45047, CVE-2023-35887, CVE-2024-41909 |
| `org.apache.sshd:sshd-core` | 2.7.0 | maven | CRITICAL | CVE-2022-45047 |
| `org.apache.sshd:sshd-sftp` | 2.7.0 | maven | MEDIUM | CVE-2023-35887 |
| `org.apache.velocity:velocity` | 1.7 | maven | HIGH | CVE-2020-13936 |
| `org.apache.velocity:velocity-tools` | 2.0 | maven | MEDIUM | CVE-2020-13959 |
| `org.bouncycastle:bcpg-jdk18on` | 1.81 | maven | HIGH | CVE-2026-3505 |
| `org.bouncycastle:bcprov-jdk15on` | 1.69 | maven | MEDIUM | CVE-2023-33201, CVE-2023-33202, CVE-2024-29857, CVE-2024-30171, CVE-2024-34447 |
| `org.bouncycastle:bcprov-jdk18on` | 1.81 | maven | CRITICAL, HIGH, MEDIUM | CVE-2025-14813, CVE-2026-0636, CVE-2026-13506, CVE-2026-8763 |
| `org.codehaus.plexus:plexus-archiver` | 4.2.7 | maven | HIGH | CVE-2023-37460 |
| `org.codehaus.plexus:plexus-utils` | 3.0.15 | maven | CRITICAL, HIGH, MEDIUM | CVE-2017-1000487, CVE-2022-4244, CVE-2022-4245, CVE-2025-67030 |
| `org.codehaus.plexus:plexus-utils` | 3.4.1 | maven | HIGH | CVE-2025-67030 |
| `org.codehaus.plexus:plexus-utils` | 3.4.2 | maven | HIGH | CVE-2025-67030 |
| `org.codehaus.plexus:plexus-utils` | 3.5.1 | maven | HIGH | CVE-2025-67030 |
| `org.codehaus.plexus:plexus-utils` | 3.6.0 | maven | HIGH | CVE-2025-67030 |
| `org.codehaus.plexus:plexus-utils` | 4.0.0 | maven | HIGH | CVE-2025-67030 |
| `org.codehaus.plexus:plexus-utils` | 4.0.1 | maven | HIGH | CVE-2025-67030 |
| `org.codehaus.plexus:plexus-utils` | 4.0.2 | maven | HIGH | CVE-2025-67030 |
| `org.eclipse.jetty:jetty-http` | 9.4.46.v20220331 | maven | HIGH, LOW, MEDIUM | CVE-2022-2047, CVE-2023-40167, CVE-2024-6763, CVE-2025-11143, CVE-2026-2332 |
| `org.eclipse.jetty:jetty-security` | 9.4.46.v20220331 | maven | HIGH | CVE-2026-10050 |
| `org.eclipse.jetty:jetty-server` | 9.4.46.v20220331 | maven | HIGH, LOW, MEDIUM | CVE-2023-26048, CVE-2023-26049, CVE-2024-13009, CVE-2024-8184, CVE-2026-6790 |
| `org.eclipse.jetty:jetty-xml` | 9.4.46.v20220331 | maven | LOW | GHSA-58qw-p7qm-5rvh |
| `org.eclipse.jgit:org.eclipse.jgit` | 5.13.1.202206130422-r | maven | HIGH, MEDIUM | CVE-2023-4759, CVE-2025-4949 |
| `org.iq80.snappy:snappy` | 0.4 | maven | MEDIUM | CVE-2024-36124 |
| `org.jline:jline-builtins` | 3.30.3 | maven, shaded | HIGH, MEDIUM | CVE-2026-77421, CVE-2026-77422 |
| `org.jline:jline-reader` | 3.30.3 | maven, shaded | MEDIUM | CVE-2026-77420 |
| `org.jline:jline-remote-telnet` | 3.30.3 | maven, shaded | HIGH | CVE-2026-56740, CVE-2026-56741 |
| `org.jsoup:jsoup` | 1.10.2 | maven | HIGH, MEDIUM | CVE-2021-37714, CVE-2022-36033, CVE-2026-75140 |

The shaded-JAR findings concern Jackson core/databind 2.17.1 and JLine builtins/reader/remote-telnet 3.30.3. Advisory metadata suggests patched branches at Jackson 2.18.11 / 2.21.7 / 2.22.3 and JLine 3.30.15 / 4.3.1 for these findings. This is a candidate remediation direction, not a validated dependency update. Build inventory findings include older Maven/Plexus, Commons, cryptography, SSH, Jetty and Git libraries; trace each to its owning plugin before selecting an upgrade or override.
