# CI — what it enforces, and what it does not

Single source of truth for the GMEPay+ pipeline. The workflow itself is
[`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

Written 2026-07-28 while closing the CI/supply-chain and container-hardening
parts of gap **T5-5** (`Documentation/GAP_REGISTER.md`). Before that change the
pipeline had **zero** security gates: no secret scanning, no dependency scanning,
no SAST, no image scanning, no SBOM — and `npm audit` was explicitly switched off
with `--no-audit` at both entry points.

> **Rule for whoever edits the workflow next:** if you turn a *report* job into a
> gate, or a gate into a report, change the table below in the same commit. A
> stale claim about what CI enforces is worse than no claim, because it is the
> thing an auditor and a partner's security questionnaire both read.

---

## 1. Jobs, and whether they block

| Job | What it does | Blocks a merge? |
|---|---|---|
| `build` | `./gradlew build` — compile + unit tests, all modules | **YES** |
| `build` → *Validate Gradle wrapper* | checks `gradle-wrapper.jar` against Gradle's published checksums | **YES** |
| `integration` | Testcontainers ITs (`@Tag("docker")`), serial | **YES** |
| `e2e` | golden path: wallet scans QR → payment APPROVED, verified in transaction-mgmt | **YES** |
| `ui-build` | lint + test + `next build` for both SPAs | **YES** |
| `secret-scan` | `scripts/check_gitleaks_config.py` self-test, then gitleaks over the **working tree** (`.gitleaks.toml`) | **YES** |
| `dependency-scan` | Trivy over the resolved JVM `runtimeClasspath`, **HIGH + CRITICAL** | **YES** |
| `dependency-scan-npm` | `npm audit --omit=dev --audit-level=high`, per app | **YES** |
| `secret-scan-history` | gitleaks over **full git history** | no — report only, on purpose |
| `dependency-scan` → *every severity* | full Trivy JSON, uploaded as an artifact | no — report only |
| `dependency-scan-npm` → *full tree* | `npm audit` incl. dev deps, artifact + job summary | no — report only |
| `compose-smoke` | `docker compose config -q` + boots the core profile | no — `continue-on-error: true`, pre-existing |

### Why two of those are deliberately *not* gates

- **`secret-scan-history`** runs with `--exit-code=0`. History cannot be fixed by
  a CI job: it needs a coordinated rewrite plus rotation of whatever is found, and
  blocking every PR on a commit from months ago is how a gate ends up disabled.
  The job publishes an inventory instead; standing items are recorded under T5-5.
  **Known item:** the `uatstaging` SQL Server login is still reachable in history
  (T0-6 removed it from the working tree). It needs **rotating**.
- **`compose-smoke`** was already `continue-on-error` before this change (ticket
  17.1-G03: flip to required after the first observed green run). Making it a gate
  was out of scope here and remains open.

---

## 2. Secret scanning

Config: [`/.gitleaks.toml`](../.gitleaks.toml). **Read its `DESIGN RULE` header
before touching the allowlist.** In short: an allowlist entry may only silence a
provable placeholder, a code construct, or a test fixture — never a real
credential shape to make the build green. A committed secret is fixed by rotating
and removing it.

On top of gitleaks' bundled ruleset the config adds five repo-specific rules. The
first four require an actual key **body**, so documentation quoting a *shape*
(`pk_live_<32-char-hex>`) does not match:

| Rule | Shape |
|---|---|
| `gmepay-partner-api-key` | `pk_live_` / `pk_test_` / `gpk_…` / `gmepk_…` + ≥24 chars |
| `gmepay-partner-api-secret` | `sk_live_` / `sk_test_` + ≥32 hex |
| `gmepay-webhook-endpoint-secret` | `whsec_…` (HKDF-derived per-endpoint secret, T5-4) |
| `gmepay-internal-platform-token` | `X-Gme-Internal` / `GMEPAY_INTERNAL_AUTH_SECRET` values |
| `gmepay-inline-db-password` | a password inlined into a JDBC / Mongo URI |

`gmepay-internal-platform-token` exists because gitleaks' generic rule keys off
the words *key / api / token / secret / auth / access* and **none of them appears
in `X-Gme-Internal: <value>`** — the platform's most privileged shared secret, the
one gating every `/internal/**` endpoint, went straight through the default
ruleset. A negative-control test found that.

The allowlist has two scopes, and the distinction is load-bearing:

- the **global** block is `regexTarget = "line"` and holds only *placeholder* and
  *code-construct* shapes: `${VAR:-…}` env-var indirection (every compose
  `x-*-secret` anchor), Helm `CHANGE_ME_*` / `REPLACE_*`, `-not-for-prod`,
  `keytool -storepass changeit`, `placeholder` / `YOUR_…` / `changeme`, PEM
  headers appearing in Javadoc or in the `replaceAll("-----BEGIN…")` that strips
  them, and Java/JS plumbing such as `this.authorizationService = authorizationService;`
  (the right-hand side is an identifier, so a quoted literal still fires);
- the **`generic-api-key` rule's own** block is `regexTarget = "match"`, i.e.
  tested against the **captured value** only, and holds the *name/identifier*
  shapes (camelCase identifiers, dotted property paths, `SCREAMING_SNAKE`
  constants, our own module names).

That split is not cosmetic. Name shapes at line scope are **actively dangerous**:
`GMEPAY_INTERNAL_AUTH_SECRET: <real 32-hex>` matches a `GMEPAY_*` name pattern, so
a line-scoped entry would have silenced the line *including the real secret*. An
earlier draft did exactly that and the negative control caught it — hence
`scripts/check_gitleaks_config.py`, which runs as a **blocking step inside
`secret-scan`**: it asserts every regex is RE2-compatible and that 15 planted
credential shapes are still reported. **Run it whenever you widen the allowlist.**
If a real secret flips from CAUGHT to MISSED, narrow the entry; never delete the
test case.

Paths that are muted: `build/`, `.gradle/`, `node_modules/`, `.next/`,
`src/test/**` and `__tests__/**` fixtures, lockfile integrity hashes, the
certificate-only mTLS fixtures, `docker/certs/gme-root-ca.crt`, audit write-ups
that quote credential shapes, and `data/sim-scheme/*.jsonl`.

> **Follow-up on that last one:** `data/sim-scheme/{feed,payments}.jsonl` is
> committed ZeroPay-simulator runtime state (synthetic `authId` / `schemeTxnRef` /
> `payerRef` values — no credentials). It produced 90 false positives on its own.
> `.gitignore` covers `e2e-tests/data/` but not the top-level `data/`, so this
> looks **accidentally committed**; deleting and ignoring it is the real fix and
> belongs to whoever owns the simulators. The path entry only stops it wedging the
> gate meanwhile.

What is **not** silenced, so it keeps showing up as remediation debt:

- `docker/keycloak/realm-gmepay.json` — the four demo users' `"value": "demo"`
  passwords. (Both OIDC clients are now *public*/PKCE, so there is no committed
  client secret left; T0-2/T1-2 removed those.)
- `libs/lib-vault/.../VaultProperties.java` — `gmepay` / `gmepay-minio` MinIO
  credentials baked in as Java field defaults.
- the `uatstaging` login in history (above).

> Note: gitleaks compiles rules with Go's **RE2**, which has no lookahead. Do not
> "improve" a rule into `(?!…)`; the config will fail to load and the gate will
> fail open-ish (job error, not a clean pass). `check_gitleaks_config.py` asserts
> this.

**Honesty note on validation.** The gitleaks binary is not installed on the
Windows build box, so this config has **never been executed by real gitleaks**.
It was validated by `scripts/check_gitleaks_config.py`, which re-implements
gitleaks' matching over the real config and the real tree: 2,192 tracked files,
**0 findings**, 15/15 planted secrets caught, 12/12 known-benign lines quiet. That
re-implementation has **no stopword list**, so it over-reports rather than
under-reports — but expect the first real CI run to need triage, and treat any
finding as a real secret until proven otherwise.

### `.gitignore`

`.gitignore` now covers `*.pem`, `*.p12`, `*.pfx`, `*.jks`, `*.keystore`,
`*.truststore`, `*.key`, `*.asc`, `*.gpg`, `id_rsa*` / `id_dsa*` / `id_ecdsa*` /
`id_ed25519*` / `*.ppk`, and `.env` / `.env.*` / `*.env`. It previously covered
none of them.

`*.crt` / `*.cer` are **excluded from that list on purpose** — a public CA
certificate is not key material, and `docker/certs/gme-root-ca.crt` is imported by
every Dockerfile. Three tracked files are explicitly negated so they stay
`git add`-able: `scripts/backup/inventory.env` (db/user names + host ports, no
credentials), `apps/*/.env.example`, and
`services/config-registry/src/test/resources/certs/*.pem` (certificate-only mTLS
fixtures; the private halves are generated at test time). Verified with
`git ls-files | git check-ignore --stdin --no-index` — empty output, i.e. nothing
tracked today became ignored.

---

## 3. Dependency scanning

**JVM.** This repo resolves everything through the Spring Boot BOM, pins nothing
in-repo, and has no Gradle lockfile or `verification-metadata.xml` — so there is
no manifest for a scanner to read. `.github/gradle/dependency-scan.init.gradle`
is applied with `-I` (CI only, no module build file changes) and flattens each
project's resolved **`runtimeClasspath`** into `build/dependency-scan/jars`;
Trivy identifies the jars directly. `runtimeClasspath` is the deliberate choice:
it is what actually ships inside the bootJar, so a CVE in a test-only library does
not fail the build. `simulators/*` are **not** covered (own `settings.gradle`,
non-deployable test doubles).

**npm.** `npm audit --omit=dev --audit-level=high` per app blocks; the full tree
(including dev dependencies) is reported. This is the counterpart to the
`--no-audit` finding: rather than re-enable auditing inside `npm ci` — where a
newly-published advisory breaks an unrelated build with no way to triage — the
check gets its own job with an explicit threshold.

**Cadence.** `.github/dependabot.yml` covers GitHub Actions, Gradle, both npm
apps and the Docker base images, with the Spring release trains grouped so a Boot
bump and its Framework/Security bumps arrive as one reviewable PR.

### Not done, and why: the framework floor

**Nothing was upgraded in this pass.** Surfacing was the goal. The floor as
audited:

| | Version | Note |
|---|---|---|
| Spring Boot | 3.3.4 | Sept 2024; past OSS support |
| Spring Framework / Security | 6.1.13 / 6.3.3 | transitive from the Boot BOM |
| nimbus-jose-jwt | 9.37.3 | this is the **JWT verification** path |
| Tomcat / Netty / Logback / Hibernate | 10.1.30 / 4.1.113 / 1.5.8 / 6.5.3 | |
| Spring Cloud | 2023.0.3 | two release trains behind |
| Next.js | 14.2.18 (admin-ui) / 14.2.5 (portal) | divergent patch levels, EOL for security backports |
| ESLint | 8.57.x | EOL |

Moving those trains is a coordinated migration with real behavioural risk across
20 services. It is a **decision**, tracked under T5-5 in
`Documentation/GAP_REGISTER.md`, not something to slip into a hardening pass.
Practical consequence to expect: **the first `dependency-scan` run on this floor
may well be red.** That is the gate working. Triage the findings, do not widen the
threshold.

---

## 4. Container hardening

**Images.** All 22 JVM Dockerfiles (`services/*`, `simulators/*`) plus
`apps/admin-ui` now drop root; `apps/partner-portal-ui` already did. That is
**24/24**, up from 1/24.

- JVM images create a system user/group at **UID/GID 10001** (>10000, so it cannot
  collide with a host system account) and end with `USER 10001:10001`.
- The two Next.js images run as **1001** (`nextjs:nodejs`).
- `USER` is **numeric** on purpose: the kubelet can only pre-verify
  `runAsNonRoot` when the image's user is a UID, not a name it would have to
  resolve inside the image.
- `app.jar` is copied `--chown=root:10001 --chmod=0640` — the process can read its
  own code but cannot rewrite it.

**Kubernetes** (`deploy/helm/gmepay/`, `global.podSecurityContext` /
`global.containerSecurityContext`, applied by `templates/_deployment.tpl` to every
service; the three overlays inherit it):

`runAsNonRoot: true`, `runAsUser`/`runAsGroup`/`fsGroup`, `privileged: false`,
`allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`,
`seccompProfile: RuntimeDefault`, `readOnlyRootFilesystem: true`.

`readOnlyRootFilesystem` is on **everywhere**. The paths that are genuinely
written get an `emptyDir` instead of the filesystem being left writable:

| Path | Who needs it |
|---|---|
| `/tmp` (`global.writablePaths`) | every JVM — `java.io.tmpdir`; embedded Tomcat's work dir, and scheme-adapter-zeropay's `${java.io.tmpdir}/gmepay/{inbound,outbound}` staging |
| `/app/build` | `reporting-compliance` — BOK FX + KOFIU feed builders write `./build/{bok-out,kofiu-out}` |
| `/app/data` | `merchant-qr-data` — ZeroPay merchant-feed `inbound-dir` |
| `/app/.next/cache` | both SPAs — `next start` writes the ISR / image-optimiser cache |

No service logs to a file (verified: no `FileAppender` / `logging.file.*`
anywhere), so no log volume is needed.

Per-service overrides are `services.<svc>.podSecurityContext`,
`.containerSecurityContext` and `.writablePaths`. **If you change a Dockerfile's
UID, change `runAsUser` in `values.yaml` in the same commit** — a mismatch makes
the pod fail to start, or (worse) silently pass `runAsNonRoot` as the wrong user.

Caveat worth knowing: `reporting-compliance`'s `/app/build` `emptyDir` dies with
the pod, so a generated filing file does not survive a restart. Acceptable only
because the filing channels are not live (OI-02 / OI-03 are externally gated).
Before real filing it must become a PVC.

**No image genuinely needed to keep root.** All 22 JVM images share one template,
so it was a single propagating edit.

---

## 5. Still open — what CI does **not** enforce

Do not read a green pipeline as covering any of these.

| Gap | Status |
|---|---|
| **SAST** (CodeQL / Semgrep) | absent. No static analysis of our own code runs anywhere. |
| **Container image scanning** | absent. Trivy scans *dependencies*, not built images — nothing scans the base layers (`eclipse-temurin:21-jre`, `node:20-alpine`, `mongo:7`, `postgres:16-alpine`, `keycloak:25.0`). |
| **SBOM generation** | absent. |
| **Base-image digest pinning** | absent — all 24 Dockerfiles + every infra image use mutable tags; own services deploy at `:dev`. Dependabot surfaces moves; it cannot pin. |
| **Gradle `distributionSha256Sum`** | absent from `gradle-wrapper.properties`. The wrapper-validation action checks the committed *jar*, not the distribution URL. |
| **Gradle dependency locking / `verification-metadata.xml`** | absent, while all 22 JVM builds resolve through a deliberately-trusted TLS-inspecting proxy. |
| **`cp-schema-registry:7.6.1` vs `cp-kafka:7.5.0`** | version skew, unaddressed. |
| **Keycloak** | still `start-dev --import-realm` with `KC_HOSTNAME_STRICT_HTTPS: "false"`. |
| **`compose-smoke` as a gate** | still `continue-on-error`. |
| **`maven-publish.yml`** | the audit recommends deleting it; not done. |
| **CODEOWNERS + branch protection** | not present / not evidenced. A blocking job only blocks if branch protection requires it — **someone must mark `secret-scan`, `dependency-scan` and `dependency-scan-npm` as required status checks on `main`.** Until that is done these jobs are advisory in practice. |
| **Framework floor** | see §3. A decision, not a task. |

Explicitly **out of scope of T5-5's CI/container work** and tracked separately in
`Documentation/GAP_REGISTER.md`: PII encryption at rest, TLS in transit
(`ingress.tls: []`), and retention/erasure capability. None of them is affected by
anything on this page.
