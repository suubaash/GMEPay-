> 작업: T5-5 CI gates + container hardening / 출처: agent

# T5-5 — CI supply-chain gates and container hardening

Closes the **CI/supply-chain** and **container-hardening** halves of gap T5-5
(CISO audit 2026-07-28 §13/§14). The privacy half of the same gap — PII
encryption at rest, TLS in transit, retention/erasure — is **not** in scope here
and is untouched.

Evidence the audit gave: *zero* supply-chain or security gates in CI, an
out-of-support framework floor, and **23 of 24 containers running as root**. The
T0-6/T0-7 write-up's residual list also named, explicitly, "no `.gitignore` key
rules, no gitleaks job".

---

## 1. Secret scanning — `secret-scan` (BLOCKING) + `secret-scan-history` (report)

`Documentation/services_backlog/platform-infra.md:2057` specified a
TruffleHog/gitleaks workflow in Phase 2. It was never implemented. It is now
`.github/workflows/ci.yml` + a new `/.gitleaks.toml`.

The working-tree job blocks: pinned `ghcr.io/gitleaks/gitleaks:v8.18.4`,
`--config=/repo/.gitleaks.toml --no-git --redact --exit-code=1`, SARIF uploaded as
an artifact. `--redact` is mandatory — an unredacted finding prints the secret into
a CI log, which is itself a disclosure. The official **image** is used rather than
`gitleaks-action@v2` because that action needs a `GITLEAKS_LICENSE` for
organisation-owned repos, and a security gate must not be able to fail for a
licensing reason.

The history job is **report-only on purpose** (`--exit-code=0`): history is fixed
by *rotating* the credential, and blocking every PR on a months-old commit is how
a gate ends up switched off. It writes the finding count to the job summary with
that instruction in it. **The `uatstaging` SQL Server login still needs rotating**
(T0-6 removed it from the working tree only).

### 1a. Two things the default ruleset could not see

**`X-Gme-Internal` was invisible.** gitleaks' `generic-api-key` keys off the words
*key / api / token / secret / client / passwd / password / auth / access*. None of
them appears in `X-Gme-Internal: <value>` — so the platform's most privileged
shared secret, the one gating every `/internal/**` endpoint, sailed straight
through. New rule `gmepay-internal-platform-token` covers
`X-Gme-Internal` / `GMEPAY_INTERNAL_AUTH_SECRET` / `gmepay.internal.auth.secret`.

Four more repo-specific rules, each requiring an actual key **body** so
documentation quoting a shape (`pk_live_<32-char-hex>`) does not match:
`gmepay-partner-api-key`, `gmepay-partner-api-secret`,
`gmepay-webhook-endpoint-secret` (`whsec_`, T5-4), `gmepay-inline-db-password`.

### 1b. The allowlist is two-scoped, and that is load-bearing

The brief said: keep the *intentional* non-prod literals quiet, but do not
allowlist a real credential shape to make it pass. Getting that right needed a
structural split rather than one list.

- **Global `[allowlist]`, `regexTarget = "line"`** — placeholder and
  code-construct shapes only: `${VAR:-…}` env-var indirection (every compose
  `x-*-secret` anchor), Helm `CHANGE_ME_*` / `REPLACE_*`, `-not-for-prod`,
  `keytool -storepass changeit`, `placeholder`/`YOUR_…`/`changeme`, PEM headers in
  Javadoc or inside the `replaceAll("-----BEGIN…")` that strips them, and Java/JS
  plumbing (`this.authorizationService = authorizationService;` — the RHS is an
  identifier, so a *quoted literal* still fires).
- **`generic-api-key`'s own `[rules.allowlist]`, `regexTarget = "match"`** —
  tested against the **captured value**, holding the name/identifier shapes:
  camelCase identifiers, dotted property paths, `SCREAMING_SNAKE` constants, our
  own module names.

Why the split: an earlier draft put the name shapes at line scope. A negative
control showed that
`GMEPAY_INTERNAL_AUTH_SECRET: 8f3a91c04be7d25a6019fbc8734ee1a2` matched the
`GMEPAY_*` name pattern and the whole line — **including the real secret** — was
silenced. Same class of bug caught a second time: the camelCase-identifier
exemption `^[a-z][a-z0-9]*(?:[A-Z]…)+$` also matched base64
(`aG91c2Vib2F0Q2FybmV5MTk4N1p6`), so it now requires ≥3 leading lower-case chars
and Capital+lower-case humps. A third: the `SCREAMING_SNAKE` exemption without a
mandatory underscore also matched `AKIAIOSFODNN7EXAMPLE`.

That is why **`scripts/check_gitleaks_config.py`** exists and runs as a blocking
step *inside* `secret-scan`. An allowlist fails **silently** — widen an entry and
the gate keeps passing while detecting nothing. The script asserts (1) every regex
compiles and uses no construct Go's RE2 rejects (lookahead/lookbehind/backrefs — a
bad regex makes gitleaks fail to *load*, which is not a clean scan), (2) 15 planted
credential shapes stay CAUGHT, (3) 12 known-benign lines stay quiet, over the real
tracked tree. Its header says: if a real secret flips to MISSED, narrow the entry;
never delete the test case.

### 1c. Deliberately NOT muted

- `docker/keycloak/realm-gmepay.json` — the four demo users' `"value": "demo"`.
  (Both OIDC clients are now public/PKCE, so no committed *client* secret remains;
  T0-2/T1-2 removed those. Verified by reading the realm.)
- `libs/lib-vault/.../VaultProperties.java` — `gmepay` / `gmepay-minio` MinIO
  credentials as Java field defaults.
- the `uatstaging` login in history.

### 1d. One follow-up found while doing this

`data/sim-scheme/{feed,payments}.jsonl` is committed ZeroPay-simulator runtime
state (synthetic `authId` / `schemeTxnRef` / `payerRef` — no credentials) and
produced **90** of the 134 raw false positives. `.gitignore` covers
`e2e-tests/data/` but **not** the top-level `data/`, so this looks accidentally
committed. Deleting + ignoring it is the real fix and belongs to whoever owns the
simulators; the path entry only stops it wedging the gate meanwhile.

---

## 2. `.gitignore` key material

Added: `*.pem`, `*.p12`, `*.pfx`, `*.jks`, `*.keystore`, `*.truststore`, `*.key`,
`*.asc`, `*.gpg`, `id_rsa*` / `id_dsa*` / `id_ecdsa*` / `id_ed25519*` / `*.ppk`,
and `.env` / `.env.*` / `*.env`. The file previously covered **none** of them.

Checked first, per the brief:

| Tracked file | Handling |
|---|---|
| `docker/certs/gme-root-ca.crt` | `*.crt`/`*.cer` deliberately **not** added — a public CA cert is not key material, and every Dockerfile imports this one |
| `docker/certs/gme-truststore` | extension-less; no pattern touches it. (The audit called it *untracked*; it is in fact tracked — that claim is stale) |
| `scripts/backup/inventory.env` | negated. Non-secret db/user names + host ports; sourced by the backup/restore scripts |
| `apps/*/.env.example` | negated via `!*.env.example` |
| `services/config-registry/src/test/resources/certs/*.pem` | negated. Certificate-only mTLS fixtures; the private halves are generated at test time |

Verified with `git ls-files | git check-ignore --stdin --no-index` → **empty**,
i.e. nothing tracked today became ignored. Positive check confirmed `.env`,
`.env.local`, `docker/certs/partner.pem`, `foo/bar/id_rsa`,
`services/qr-service/signing.p12`, `my.jks`, `apps/admin-ui/.env.production` are
now all ignored.

---

## 3. Dependency scanning

**JVM — `dependency-scan`, blocks on HIGH/CRITICAL.** The repo resolves through
the Spring Boot BOM, pins nothing in-repo, and has no Gradle lockfile or
`verification-metadata.xml`, so **no manifest exists for a scanner to read**.
Rather than bulk-introduce lockfiles (which changes what every module resolves —
out of scope), `.github/gradle/dependency-scan.init.gradle` is applied with `-I`
(CI only, **zero module build-file changes**) and flattens each project's resolved
**`runtimeClasspath`** into `build/dependency-scan/jars`; Trivy identifies the jars
directly. `runtimeClasspath` is deliberate — it is what ships inside the bootJar,
so a CVE in a test-only library does not fail the build. A second Trivy pass at
`--exit-code 0` uploads every severity as an artifact. `simulators/*` are not
covered (own `settings.gradle`, non-deployable test doubles).

**npm — `dependency-scan-npm`.** `npm audit --omit=dev --audit-level=high` per app
blocks; the full tree including dev deps is reported. This is the counterpart to
the audit's `--no-audit` finding at `ci.yml` and `apps/admin-ui/Dockerfile`:
re-enabling auditing inside `npm ci` would let a newly-published advisory break an
unrelated build with no way to triage, so the check gets its own job with an
explicit threshold instead. (`--no-audit` itself is left in place.)

**Cadence.** `.github/dependabot.yml` — Actions, Gradle, both SPAs, Docker base
images; Spring release trains grouped so a Boot bump arrives with its
Framework/Security bumps as one reviewable PR.

**Framework floor: surfaced, not fixed — by instruction and by judgement.**
Nothing was upgraded. Spring Boot 3.3.4 (→ Spring Framework 6.1.13, Spring
Security 6.3.3, **nimbus-jose-jwt 9.37.3 on the JWT verification path**, Tomcat
10.1.30), Spring Cloud 2023.0.3, Next.js 14.2.18 vs 14.2.5, EOL ESLint 8. Moving
those trains is a coordinated migration across 20 services — a decision, recorded
in `Documentation/GAP_REGISTER.md` T5-5(c) and `docs/CI.md` §3. **Expect the first
`dependency-scan` run on this floor to be red. That is the gate working — triage
the findings, do not widen the threshold.**

Also added, cheap and in-scope: `gradle/actions/wrapper-validation@v4` in the
`build` job. The wrapper jar is committed executable code and
`gradle-wrapper.properties` still has no `distributionSha256Sum`.

---

## 4. Containers: 24/24 non-root (was 1/24)

**Images.** All 22 JVM Dockerfiles share one runtime-stage template, so this was a
single propagating edit, applied identically:

```
FROM eclipse-temurin:21-jre AS runtime
RUN groupadd --system --gid 10001 gmepay \
 && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin gmepay
WORKDIR /app
COPY --from=build --chown=root:10001 --chmod=0640 /workspace/app.jar /app/app.jar
USER 10001:10001
```

- UID/GID **10001** — above 10000 so it cannot collide with a host system account.
- `USER` is **numeric** on purpose: the kubelet can only pre-verify `runAsNonRoot`
  when the image user is a UID, not a name it must resolve inside the image.
- The jar stays **root-owned, mode 0640** — the process reads its own code but
  cannot rewrite it. `--chmod` needs BuildKit; all 22 already declare
  `# syntax=docker/dockerfile:1.6`.
- `eclipse-temurin` is Ubuntu-based, so `groupadd`/`useradd`/`/usr/sbin/nologin`
  all exist. Build stage order is unchanged — the CA-import and bootJar steps in
  the `build` stage were not touched, and the new `RUN` sits in the `runtime`
  stage before the `COPY`, so nothing depends on ordering that moved.
- Three images pre-create a writable directory owned by 10001, because they write
  (or read) under the WORKDIR by default: `reporting-compliance`
  (`./build/{bok-out,kofiu-out}`), `merchant-qr-data` (`./data/zeropay-inbound`),
  `sim-nepal-qr` (`data/sim-nepal-qr` JSONL). `sim-ninepay` / `sim-sendmn` persist
  nothing, verified, so they get nothing extra.

`apps/admin-ui` was the 23rd root image; it now creates `nextjs:1001` and ends
`USER 1001:1001`, mirroring `apps/partner-portal-ui` (the one image that already
dropped root) so the chart needs a single override UID for both SPAs. `.next` is
`--chown`ed because `next start` writes the ISR / image-optimiser cache.

**No image genuinely needed to keep root.**

**Kubernetes** — `deploy/helm/gmepay/values.yaml` `global.podSecurityContext` /
`global.containerSecurityContext`, applied to every service by
`templates/_deployment.tpl`; the three overlays inherit it (they override only
registry/tag/endpoints):

`runAsNonRoot: true`, `runAsUser`/`runAsGroup`/`fsGroup`, `privileged: false`,
`allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`,
`seccompProfile: RuntimeDefault`, `readOnlyRootFilesystem: true`.

`readOnlyRootFilesystem` is on **everywhere** — the brief's instruction was to
mount an `emptyDir` rather than leave the filesystem writable, so:

| emptyDir mount | Who needs it |
|---|---|
| `/tmp` (`global.writablePaths`, fleet-wide) | `java.io.tmpdir` — embedded Tomcat's work dir, and scheme-adapter-zeropay's `${java.io.tmpdir}/gmepay/{inbound,outbound}` settlement staging |
| `/app/build` | `reporting-compliance` BOK FX + KOFIU feed builders |
| `/app/data` | `merchant-qr-data` ZeroPay feed inbound |
| `/app/.next/cache` | both SPAs |

No service logs to a file (verified — no `FileAppender` / `logging.file.*`
anywhere in `services/*` or `libs/*`), so no log volume is needed.

Two implementation notes worth keeping: the template does
`mergeOverwrite (deepCopy global…) svc…` because `mergeOverwrite` **mutates its
first argument** — without the `deepCopy` the first service rendered would
permanently rewrite `global.*` for every later one. And per-service overrides
exist (`podSecurityContext` / `containerSecurityContext` / `writablePaths`)
precisely because the two SPAs run as 1001, not 10001. **Chart `runAsUser` and
Dockerfile UID must change together.**

Caveat stated rather than hidden: `reporting-compliance`'s `/app/build` emptyDir
dies with the pod, so a generated filing file does not survive a restart.
Acceptable only because the filing channels are not live (OI-02/OI-03 externally
gated); before real filing it must become a PVC.

---

## 5. Documentation

`docs/CI.md` is new and is the single source of truth for **what CI enforces and
what it does not** — a per-job blocks/reports table, why two jobs are deliberately
not gates, the secret-scan design, the dependency-scan design, the container
hardening, and a "still open" table (SAST, image scanning, SBOM, digest pinning,
`distributionSha256Sum`, dependency locking, Kafka/schema-registry skew, Keycloak
`start-dev`, `compose-smoke`, `maven-publish.yml`, CODEOWNERS/branch protection,
framework floor). `README.md` links it from *Build & test*, and `ci.yml`'s header
points at it with the instruction to update it in the same commit as any
gate/report change.

**The single most important open item is procedural:** a blocking job only blocks
once someone marks `secret-scan`, `dependency-scan` and `dependency-scan-npm` as
**required status checks** on `main`. Branch protection and CODEOWNERS are still
absent, so until that is configured these gates are advisory in practice.

---

## 6. Files changed

**CI / supply chain** — `.github/workflows/ci.yml` (wrapper validation +
4 new jobs), new `.github/gradle/dependency-scan.init.gradle`, new
`.github/dependabot.yml`, new `/.gitleaks.toml`, new
`scripts/check_gitleaks_config.py`, `.gitignore`.

**Containers** — all 19 `services/*/Dockerfile`, all 3 `simulators/*/Dockerfile`,
`apps/admin-ui/Dockerfile`.

**Kubernetes** — `deploy/helm/gmepay/templates/_deployment.tpl`,
`deploy/helm/gmepay/values.yaml` (global security context + `writablePaths`, and
security-only per-service overrides on `admin-ui`, `partner-portal-ui`,
`reporting-compliance`, `merchant-qr-data`). The three overlays were **not**
edited — they inherit. Values files were re-read immediately before each write
because another agent is adding a service entry to them concurrently.

**Docs** — new `docs/CI.md`, `README.md`, `Documentation/GAP_REGISTER.md`.

**Ownership note.** The constraint list named `payment-executor`,
`transaction-mgmt`, `revenue-ledger`, `notification-webhook`,
`settlement-reconciliation`, `kyb-adapter` and `config-registry` as off-limits,
while also explicitly granting *every* `services/*/Dockerfile`. Those Dockerfiles
were taken as the more specific grant, because the finding is "23 of 24 images run
as root" and leaving 6 of them root would not close it — the edit is byte-identical
across all 22 and touches nothing those services build or run. **No Java source,
no `build.gradle`, and no `docker-compose.yml` was modified.**

One consequence worth flagging: the KYB agent's brand-new, still-untracked
`services/kyb-adapter/Dockerfile` appeared during this pass and the same
`services/*/Dockerfile` sweep hardened it in the working tree. It was **not
staged** here — it is theirs to commit — so it will land with the non-root block
already applied. If they instead recreate that file, it needs the same 3-line
`groupadd`/`useradd` + `--chown/--chmod` + `USER 10001:10001` block, or the 25th
image will ship as root and `runAsNonRoot` in the chart will refuse to start the
pod. Likewise, whoever adds the `kyb-adapter` **service entry** to
`deploy/helm/gmepay/values*.yaml` needs no security fields at all: it inherits
`global.podSecurityContext` / `global.containerSecurityContext` automatically, and
only needs a `writablePaths` entry if it writes under its WORKDIR.

---

## 7. Verification (static only — nothing was run in Docker, nothing started)

| Check | Result |
|---|---|
| `gradlew testClasses` (whole repo) | ✅ BUILD SUCCESSFUL, 101 tasks |
| `python scripts/check_internal_auth_wiring.py` | ✅ **93/93** |
| `python scripts/check_monitoring_wiring.py` | ✅ **37/37** |
| `python scripts/check_gitleaks_config.py` | ✅ exit 0 — 43 regexes compile, 0 RE2-incompatible, **2,192 tracked files → 0 findings**, **15/15** planted secrets CAUGHT, **12/12** benign lines quiet |
| PyYAML parse | ✅ `ci.yml` (9 jobs), `dependabot.yml`, all 4 Helm values + `Chart.yaml`, `docker-compose.yml` (unmodified) |
| `tomllib` parse of `.gitleaks.toml` | ✅ 5 rules, 20 line-scope allowlist regexes, 16 allowlist paths, 5 value-scope regexes |
| `.gitignore` regression | ✅ `git ls-files \| git check-ignore --stdin --no-index` → empty (nothing tracked became ignored); 7 planted key-material paths all ignored |
| Dockerfile review (read, not built) | ✅ all 22 JVM + admin-ui: `build` stage untouched, `runtime` stage order is `FROM → RUN useradd → WORKDIR → COPY --chown/--chmod → [mkdir] → USER → EXPOSE → ENTRYPOINT`; every file still declares `# syntax=docker/dockerfile:1.6` (required for `--chmod`) |

**Not verifiable here, stated plainly:** the gitleaks binary, Trivy, Helm and
Docker are all absent from this box. So (a) `.gitleaks.toml` has never been
executed by real gitleaks — the self-test re-implements the matching over the real
config and real tree, has *no* stopword list (so it over-reports rather than
under-reports), but a clean result is a strong signal, not proof; (b)
`helm template` was never run — the chart edit was validated by PyYAML-parsing the
values, asserting the new keys programmatically, and reviewing the template's
indentation against the existing `imagePullSecrets` (nindent 8) and `resources`
(nindent 12) blocks it sits beside; (c) no image was built, so "the images still
work" is a review claim, not a test result.
