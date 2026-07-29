> 작업: T1-4 KYB deployability / 출처: agent

# T1-4 — kyb-adapter is deployable, KYB documents are durable, and a stub screening can no longer masquerade as a completed check

**Scope:** `services/kyb-adapter`, `libs/lib-kyb`, `libs/lib-vault`, `services/config-registry`,
`docker-compose.yml`, `deploy/helm/gmepay/values.yaml`, `deploy/CHANGELOG.md`, `docs/COMPOSE.md`.
The Octa vendor integration was **not** implemented and no KYB decision engine was invented.

**Builds:** `:libs:lib-kyb:test` 13 · `:libs:lib-vault:test` · `:services:kyb-adapter:test` 27 ·
`:services:config-registry:test` 472 — all green. `gradlew testClasses` green fleet-wide.
`check_internal_auth_wiring.py` 94/94, `check_monitoring_wiring.py` 37/37,
`docker/keycloak/check-topology.mjs` 101/101, PyYAML-parse of `docker-compose.yml` + all four Helm
values files OK. No server and no Docker was started.

---

## 1. The gap, restated precisely

Four separate facts combined into one dangerous outcome:

| Fact | Consequence |
|---|---|
| `services/kyb-adapter` had no Dockerfile, no compose entry, and was explicitly excluded from Helm | the service could not run **anywhere** |
| `GMEPAY_KYB_ADAPTER_CLIENT` was set in no deployment file | `StubKybClient` (`matchIfMissing=true`) won in **every** environment — "screening" was keyword matching inside config-registry |
| `StubKybAdapter`'s no-trigger-word branch was called `CLEAR` | the platform's only reachable verdict was indistinguishable from a real clean screening |
| `ActivationGateService` tested `"CLEAR".equals(kyb.getScreeningStatus())` | **any partner not literally named "SANCTIONED …" could be activated LIVE on a screening that never ran** |

The last row is the real defect. Everything else is what made it invisible.

---

## 2. Deployable (task 1)

- **`services/kyb-adapter/Dockerfile`** — copied from the current peer shape (checked against
  `config-registry`, `rate-fx`, `merchant-qr-data`, `reporting-compliance`), including the T5-5
  non-root runtime another agent had just landed: `groupadd/useradd 10001`, jar
  `--chown=root:10001 --chmod=0640`, numeric `USER 10001:10001`, nothing writable under `/app`.
- **`docker-compose.yml`** — host port **9104**. All published ports were enumerated first:
  `8080..8099` is fully allocated (8097 Keycloak), `9000/9001` MinIO, `9103/9106/9107` the scheme
  simulators, `5433..5447`, `6379`, `27017`, `29092`. Profiles `core, full` — config-registry is a
  core service and now calls this one, so a core stack without it would 502 every screening run.
  `SPRING_KAFKA_BOOTSTRAP_SERVERS` for the `gmepay.kyb.*` fan-out; `depends_on: kafka (healthy)`.
- **`deploy/helm/gmepay/values.yaml`** — one `services:` entry; the three overlays inherit and needed
  no edit (they do not enumerate services). Re-read immediately before writing, since another agent
  is editing securityContext fields in the same file.
- **Internal auth — yes, it needs it.** `/v1/kyb/screen`, `/v1/kyb/verify`, `/v1/kyb/result/**` accept
  a partner's legal names, tax id and full UBO set (names, ownership %, PEP flags) and return a
  compliance verdict; no api-gateway route fronts the service and config-registry is its only
  legitimate caller. So the surface is now behind lib-errors' shared `InternalAuthFilter`, with
  `KybInternalAuthEnforcedConfig` (modelled on rate-fx's) refusing to boot if the gate is not armed,
  the secret is blank, or a required pattern was dropped. `/v1/kyb/health` stays **anonymous** — a
  probe must reach it and it carries no partner data. Callers: `RestKybClient` /
  `RestKybVerifyClient` now build their `RestClient` through `KybInternalAuth.gated(...)`, which
  presents `X-Gme-Internal` and WARNs (naming the env var) when the secret is blank rather than
  silently bypassing. `check_internal_auth_wiring.py` derived the requirement from the code by itself
  and now asserts kyb-adapter as **BOOT-CRITICAL** on all four surfaces; `run-fleet.ps1` already
  exports the secret fleet-wide and already lists kyb-adapter, so it needed no change.
- `server.port` became `${SERVER_PORT:8098}` (it was a hardcoded 8098; env still won, but the chart
  and compose both state 8080 and the file should not contradict them).
- `deploy/CHANGELOG.md` — new top entry; the old "kyb-adapter excluded (no Dockerfile)" line is
  marked `[SUPERSEDED 2026-07-28]` in place rather than rewritten.

## 3. Documents stop vanishing (task 2)

`GMEPAY_VAULT_ENDPOINT` was in the Helm values but in **no** compose service, so lib-vault's
`@ConditionalOnProperty(prefix="gmepay.vault", name="endpoint")` backed off and `InMemoryVaultClient`
won — while compose ran MinIO the whole time. Every uploaded KYB document (business registration,
AOA, UBO declaration, Wolfsberg CBDDQ) sat on the heap and died on restart, while its
`partner_document` row survived pointing at an object that no longer existed.

Compose now sets, on `config-registry`, exactly the three keys `VaultProperties` binds —
`GMEPAY_VAULT_ENDPOINT: http://minio:9000`, `GMEPAY_VAULT_ACCESS_KEY`, `GMEPAY_VAULT_SECRET_KEY`
(interpolated from the same `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` defaults the `minio` service
uses, so the credentials cannot drift apart) — plus `depends_on: minio (healthy)`. `region` and
`path-style` keep their correct MinIO defaults. Additionally `InMemoryVaultAutoConfiguration` now logs
one WARN when it is chosen, so this state can never be silent again.

Proved by `ComposeVaultWiringTest` (config-registry, slice level, no Docker): it parses the **real**
`docker-compose.yml`, asserts the three keys and their values, then drives lib-vault's two
auto-configurations with exactly those values and asserts the resolved `VaultClient` is
`MinioVaultClient` — and, in a third case, that without the endpoint it is the in-memory one.

## 4. Provenance, end to end (task 3)

The keyword matching was **not** improved. What changed is that a result now says who produced it and
whether that producer is an authority — and the "clean" claim became unreachable for a non-authority.

- **`libs/lib-kyb`**: new `ScreeningProvenance(providerId, authoritative, caveat)`. Its compact
  constructor rejects `providerId = "stub"` with `authoritative = true`, rejects an authoritative
  result carrying a caveat, and rejects a non-authoritative one **without** a caveat. Factories:
  `stub()`, `unknown()`, `vendor(id)` (which refuses the two reserved ids).
- **`ScreeningResult`** gained the component, plus a new `Status.NOT_SCREENED_NO_PROVIDER`. Its
  compact constructor **normalises a null provenance to `unknown()`** (absence of provenance is never
  authority) and **coerces `CLEAR` → `NOT_SCREENED_NO_PROVIDER` whenever the provenance is not
  authoritative**. Coercion rather than rejection because the type is also a wire DTO: a payload from
  an older or mis-wired producer must be made honest, not turned into a 500 that hides it. `HIT` /
  `NEEDS_REVIEW` are preserved as-is — they already fail closed, and staging a HIT by naming a partner
  `"SANCTIONED HOLDINGS"` is how demos exercise the blocked path. Helpers `authoritative()`,
  `screeningPerformed()`, `caveat()`.
- **`StubKybAdapter`** stamps `ScreeningProvenance.stub()`, so its clean branch is now
  `NOT_SCREENED_NO_PROVIDER`. Its `runFullKyb` registry flags (`licenseVerified` / `uboVerified` /
  `registryVerified`) are now **false** on every stub run: they used to be true off a `CLEAR`, i.e. it
  reported a verified license, UBO register and corporate registry without contacting any of the three.
- **kyb-adapter** persists `screening_provider_id` / `screening_authoritative` / `screening_caveat`
  (V002), returns them on `POST /v1/kyb/verify` and `GET /v1/kyb/result/{ref}` (including on an
  idempotent replay, read back off the row), publishes them on `gmepay.kyb.screening` and
  `gmepay.kyb.verification`, WARNs once per unscreened run, and reports them on `/v1/kyb/health`
  (derived by screening a synthetic probe through the **live** provider bean, so the answer reflects
  what is actually wired; a provider that throws — the Octa placeholder — reports
  `authoritative=false` with the failure as the caveat).
- **config-registry** stores the same three columns on `partner_kyb` (V042) on both the screen and the
  verify path, carries them forward on a wizard step-3 save (so a save cannot strip the caveat off a
  verdict), and **appends them to `KybJson.canonical`** so the ADR-007 audit hash seals WHO produced
  the verdict, not only what it was (same append-only precedent as V036).
- **Where it can still be read naively** — the two `VARCHAR` fields on `KybView` are in
  `libs/lib-api-contracts`, which I do not own, so provenance is not a new wire field on the wizard's
  read model. It surfaces instead through the **status value itself** (`NOT_SCREENED_NO_PROVIDER` is
  what the UI renders) plus the caveat on the KYB row and the verify response. See §7 for the
  consumers that should show the caveat explicitly.

## 5. The stub cannot satisfy activation (task 4)

The stub still **never REJECTs** — kept deliberately; a stub inventing a rejection would be worse. It
simply can no longer approve:

- `KybVerificationService.decide` now takes the whole `ScreeningResult` and, after the two FAIL
  branches, refuses to collapse a `PASS` out of a run where `screeningPerformed()` is false: the run
  lands in `MANUAL_REVIEW` with the provider's caveat as the reason. A test walks four different
  clean-looking subject shapes (including a forced re-run) and asserts none reaches `PASS`.
- `StubKybVerifyClient` requires an authoritative `CLEAR` to say `APPROVED`, so through the stub it
  can only ever return `MANUAL_REVIEW`. The `APPROVED` branch is retained so flipping
  `gmepay.kyb-adapter.client=rest` at a real provider needs no change here.
- **`ActivationGateService`** gained **`SANCTIONS_NOT_SCREENED`**, raised whenever
  `KybEntity.hasAuthoritativeScreening()` is false (no KYB row, no screening, or a non-authoritative
  one). It is **not overridable by the `risk_rationale` note**: a rationale can document why a real
  HIT is acceptable; nothing can substitute for a check that never ran. `SANCTIONS_NOT_CLEAR` keeps
  its documented override for real HIT / NEEDS_REVIEW results.
- **Refusing is the default.** The escape hatch is `gmepay.activation.allow-unscreened-kyb`, **false
  by default**, WARNing at startup when on, and absent from every deployed values file. When on it
  does **not** call the partner screened: the gate returns a non-null
  `ActivationGateResult.unscreenedBasis()` ("ACTIVATED WITHOUT A SANCTIONS SCREENING … this partner's
  sanctions status is UNKNOWN"), and `PartnerLifecycleChangeRequestApplier` writes a
  **`PARTNER_ACTIVATED_UNSCREENED`** audit row in the **same transaction** as the activation — so a
  LIVE partner cannot exist without a permanent record saying no screening backs it.
- **The database enforces it too**: V042's `ck_partner_kyb_clear_requires_authority` rejects a `CLEAR`
  whose `screening_authoritative` is not TRUE. `COALESCE` is load-bearing there — a bare
  `= TRUE` makes the predicate NULL for an unset column, and SQL treats NULL as *not violated*, i.e.
  exactly the row being forbidden would have slipped through. Two existing test fixtures that
  hand-wrote a bare `'CLEAR'` had to start naming a provider, which is the intended friction.

## 6. Migrations

Both verified next-free and engine-neutral (PostgreSQL + H2 PostgreSQL-mode, so they run in the
slices). config-registry has `db/vendor/{h2,postgresql}` dirs but they carry only V004 and V023 — no
vendor variant was needed, and kyb-adapter has a single `db/migration` dir.

| Migration | Does |
|---|---|
| `kyb-adapter V002__screening_provenance.sql` | widens `screening_status` to `VARCHAR(32)`; adds the 3 provenance columns + `reclassified_from` / `reclassification_note`; backfills provenance from the `provider_ref` prefix (`stub-%` → `stub`, else `unknown`); **reclassifies** a non-authoritative `CLEAR` → `NOT_SCREENED_NO_PROVIDER` and `PASS` → `MANUAL_REVIEW`, preserving the prior claim |
| `config-registry V042__partner_kyb_screening_provenance.sql` | same shape on `partner_kyb`; replaces the `screening_status` CHECK with the new roster; reclassifies `CLEAR` → `NOT_SCREENED_NO_PROVIDER` and `verification_decision` `APPROVED`/`PASS` → `MANUAL_REVIEW`; adds `ck_partner_kyb_clear_requires_authority` |

Both are **reclassify, never delete**. Both have a dedicated migration test
(`V002ScreeningProvenanceMigrationTest`, `V042ScreeningProvenanceMigrationTest`) that migrates to the
prior version, seeds the exact legacy row shapes (the dangerous stub `CLEAR`/`PASS`, an honest `HIT`,
a never-screened row, an undeclared producer), migrates, and asserts the reclassification, the
preserved audit trail, the untouched honest rows, and that no row anywhere still claims a screening
that did not happen. V042's test also asserts the new CHECK rejects a fresh unattributed `CLEAR`.
V042's header states plainly that it updates SCD-6 rows in place, once, on purpose — it corrects the
historical record's *label*, and inserting superseding rows would invent screening events that never
occurred; the resulting divergence from the sealed ADR-007 AFTER snapshots is explained by
`reclassification_note` on every touched row.

## 7. Follow-ups for other owners (NOT edited)

| Site | Problem |
|---|---|
| `services/ops-partner-bff/.../web/ComplianceOverviewController.java:96-101` | `kybStatus` switch has no `NOT_SCREENED_NO_PROVIDER` case, so the compliance overview will bucket an unscreened partner into whatever the default arm is. Should be its own visibly-not-done state. |
| `services/ops-partner-bff/.../client/stub/StubConfigRegistryClient.java:1396,1426` | re-implements the keyword rules and still fabricates `"CLEAR"` — the one remaining place that can mint a clean screening from nothing (BFF fallback path only). |
| `services/ops-partner-bff/.../web/dto/ComplianceRow.java:13` | javadoc status union is the old 3-value roster. |
| `apps/admin-ui/.../step-3/KybForm.jsx:516-519`, `store/kybSlice.js:20`, `step-8/ReviewSection.jsx:190` | no chip/copy for `NOT_SCREENED_NO_PROVIDER` (it falls through to printing the raw string — honest but unstyled) and no surface for the caveat. It can no longer render a green "Clear" for a stub run, but it should say why. |
| `scripts/backup/inventory.env` + `scripts/backup/check-inventory.sh` | would need a 16th entry if kyb-adapter is given its own PostgreSQL (see below). |

## 8. Still open / externally gated

1. **The vendor is still unavailable (external gate, ADR-014).** `OctaKybAdapter` still throws
   `notYetAvailable()` and was not touched. Nothing in this change makes screening real. The visible
   consequence — and it is intended — is that **partner activation now refuses in every environment**
   unless the non-prod flag is set, in which case it is recorded as unscreened. That is the honest
   state of the platform, not a regression.
2. **kyb-adapter has no PostgreSQL.** Its own `kyb_screening` run log is in-memory H2 in both compose
   and the chart, so `GET /v1/kyb/result/{ref}` history dies with the container. Deliberately
   deferred: the backup inventory and its drift guard enumerate exactly the 15 databases that exist,
   and adding a 16th belongs with that inventory change (files I do not own). The
   regulator-defensible record — `partner_kyb`, which the wizard and the activation gate read — is on
   PostgreSQL and unaffected.
3. **Business-registration honesty is the same gap one level down.**
   `StubBusinessRegistrationVerifier` still returns `VERIFIED` for any tax id without a trigger token,
   with no provenance of its own, so `biz_reg_status = VERIFIED` on a stub run is as unearned as
   `CLEAR` was. It no longer *matters* for activation (the screening branch blocks first) but it
   should get the same treatment when the KFTC certificate lands.
4. **No compliance-owner-signed manual-KYB SOP.** The CPO's "Done =" for P6 offered "vendor live **or**
   an explicit manual-KYB SOP that the product/compliance owner signs off as the interim control".
   The code side now forces that conversation (activation refuses), but the SOP itself is a document
   an owner must write and sign — it does not exist.
5. **The audit-chain divergence from V042** (§6) is intentional and annotated, but the exit-gate
   tamper check that compares `partner_kyb` rows against their sealed AFTER snapshots will report the
   reclassified rows as drifted. Whoever runs that check needs to know V042 is the cause.
