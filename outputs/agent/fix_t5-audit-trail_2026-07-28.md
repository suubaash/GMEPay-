> 작업: T5-1 audit trail / 출처: agent

# T5-1 — Making the audit trail regulator-grade: non-spoofable actor, sealed columns, verifiable chain

Gap T5-1 was four separate defects wearing one label. This closes three of them in code, closes the
fourth as far as existing means allow, and states precisely what a regulator would still find
missing.

| CISO §9 finding | Status |
|---|---|
| Spoofable `X-Actor` defaulting to `system` | **closed** — the literal is unmintable; unproven claims are structurally distinguishable |
| No authN / RBAC-grant / credential-lifecycle auditing | **closed in auth-identity** (see coverage table) |
| Digest omits `aggregate_type` / `aggregate_id` / `actor_ip`; zero-filled row hashes | **closed** — `CHAIN_V2`, no zero-fill, per-row version |
| No WORM; append-only is "application discipline only" | **partially closed** — DB-level append-only trigger on PostgreSQL; true WORM is still infrastructure (T1-6) |
| 2 of 21 services instrumented | **improved, not solved** — see the coverage table and what it omits |

Also found and fixed while here: **config-registry was writing every audit row twice**, which meant
no chain could ever have verified. Details in §5.

---

## 1. The actor is no longer client input

### What it was

Every write endpoint took `@RequestHeader(value = "X-Actor", required = false) String actor` — 28
occurrences — and 24 service classes fell back to `private static final String DEFAULT_ACTOR =
"system"`. Two of them were worse than that: `OpsControlService` used `"ops"` and
`PlatformSettingService` used `"admin"`, i.e. they invented a plausible *human role* for an unknown
actor. `PartnerStore` — the class that writes the partner aggregate itself — had

```java
private static String currentActorId() {
    return "system";   // "until Slice 1B.4 wires Keycloak"
}
```

so **every partner creation in the platform was audited as `system`**. And `"system"` is the blanket
carve-out in the V005 4-eyes CHECK (`proposed_by = 'system' AND approved_by = 'system'`), so a
header-less propose plus a header-less approve self-approved legally.

### The vocabulary (`libs/lib-audit/.../AuditActors.java`)

Every actor id now carries its own provenance in its namespace:

| Shape | Meaning |
|---|---|
| `alice@gme.com` (bare) | **attested** — from a verified credential |
| `system:auto-suspend` | **named system principal** — a real platform action; the component is mandatory |
| `svc:internal-caller` | a trusted service authenticated but forwarded no human principal |
| `unverified:alice@gme.com` | a name claimed on the wire with nothing behind it — preserved for forensics, impossible to mistake for a principal |
| `unattributed` | nothing claimed, nothing proven — the honest spelling of the old silent default |

`AuditActors.requireAttributable` is called from `AuditEvent.newEvent`, which is the only way any
writer anywhere in the platform can seal an event. It **rejects a blank actor and rejects the bare
literal `"system"` in any casing**. The rejection is loud rather than a silent upgrade to
`system:unknown`, because quietly minting a plausible principal is the exact failure mode this gap
is about. Enforcing it flushed out four real writers that had been relying on the default
(`PartnerStore`, `PartnerSeeder`, `PartnerCredentialRotationScheduler`, and a `KybServiceTest`
call site) — all now name themselves.

One consequence worth calling out: the rotation scheduler's change requests were proposed by
`"system"`, so the 4-eyes carve-out exempted them. They are now proposed by
`system:credential-rotation`, which the carve-out does **not** exempt — so a credential rotation
now genuinely requires a human approver. That was the control's purpose.

### How the identity is derived (`registry/actor/`)

Being precise here matters, because the honest answer bounds the claim. **config-registry does not
authenticate operators** — its own `application.properties` says the operator surface is
authenticated at the api-gateway / ops BFF and "deliberately NOT gated here". There is no resource
server, no JWKS. It therefore *cannot* validate a JWT subject the way `ops-partner-bff`'s
`OpsRbacGuard.actor()` does.

What it *can* verify is the **caller**: the platform's internal-auth secret (`X-Gme-Internal`,
already configured here). The trust chain is *secret proves the caller is the BFF → the BFF verified
the human → the forwarded name is attested*. A delegated attestation, exactly as strong as the
internal secret, and no stronger.

| Caller proved itself? | `X-Actor` present? | Recorded as |
|---|---|---|
| yes | yes | `alice@gme.com` (attested) |
| yes | no | `svc:internal-caller` |
| no | yes | `unverified:alice@gme.com` |
| no | no | `unattributed` |

Mechanics: `AuditActorResolver` holds the rule; `ActorAttestationFilter` resolves it once per
request (so one request can never produce two rows attributed to two different actors);
`@AuditActorHeader` + `AuditActorArgumentResolver` replaced all 28 raw `@RequestHeader` bindings —
the wire contract is unchanged, so no caller needed editing. A distinct annotation was used rather
than intercepting `@RequestHeader` because Spring registers custom argument resolvers *after* the
built-in ones, so `RequestHeaderMethodArgumentResolver` would always have won and the resolution
could have been silently bypassed.

`PartnerStore` reads the same per-request resolution via `AuditActorResolver.currentRequestActor()`
(threading a parameter through a bitemporal SCD-6 write from six call sites would have been a large
refactor for a value that is constant per request), and gained a `save(Partner, explicitActor)`
overload so off-request callers name themselves.

**`actor_ip` was also client input.** It was `null` at every call site except `OpsControlService`,
which read `X-Forwarded-For` — a free-text field. So the one endpoint family that recorded an IP
recorded whatever the client typed, which is worse than recording none because it looks like
evidence. Now: the transport peer address, with `X-Forwarded-For` honoured only from an attested
caller *and* only with `gmepay.audit.actor.trust-forwarded-ip=true`.

### Fail-visible, not yet fail-closed — and why

`gmepay.audit.actor.require-attestation=true` makes an unattested write a 401. **It is off by
default**, deliberately: `ops-partner-bff`'s `RestConfigRegistryClient` sends `X-Actor` but does
**not** send `X-Gme-Internal` on the partner/scheme endpoints, and that client is owned by another
change in flight. Failing closed today would break every operator write in the platform.

So this lands as fail-visible: nothing is attributed that was not proven, and the shortfall is
**countable** rather than asserted —

```sql
SELECT count(*) FROM audit_log WHERE actor_id LIKE 'unverified:%' OR actor_id = 'unattributed';
```

and `GET /v1/audit/integrity` reports it as `unattributableRows`.

**Follow-up (not mine to make):** have `RestConfigRegistryClient` present the internal token on the
config-registry calls — it already does for auth-identity and prefunding — then flip the flag.
Until then, operator writes through the BFF land as `unverified:<jwt-subject>`: the *name* is real
(the BFF verified it), but config-registry cannot prove that, and it does not pretend to.

---

## 2. Tamper-evidence: what the digest covers, and proving it

### The hole

`HashChain.canonicalise()` hashed `eventType | actorId | recordedAt | before | after`. So
`aggregate_type`, `aggregate_id` and `actor_ip` were **outside the digest** — re-pointing an audit
row from one partner to another, or erasing the IP an operator acted from, left the chain verifying.

### `CHAIN_V2`

New rows seal `"2" | aggregateType | aggregateId | actorIp | eventType | actorId | recordedAt |
before | after`. The version number is *inside* the v2 digest, so a v2 row cannot be laundered into
a v1 row by editing `chain_version` and re-verifying under the weaker digest (there is a test for
exactly that).

**Existing rows are not re-sealed, on purpose.** Re-hashing history under a new algorithm would
destroy the property the chain exists to provide: afterwards nobody could distinguish "an honest
migration recomputed these hashes" from "an attacker rewrote the log and recomputed the hashes". So
each row records the version it was sealed under (`chain_version`, V043, `DEFAULT 1` — the default
applies to exactly the rows that *are* v1), verification canonicalises per-row, and the verifier
**reports** how many rows still carry the weaker digest. The residual exposure is a number in a
report instead of an unstated assumption, and it only ever decreases.

Where the version is unknown, it resolves to **v1**, never v2. That direction is not arbitrary:
verifying a v1 row under the v2 digest fails loudly (a false alarm — annoying, safe), while
verifying a v2 row under the v1 digest **succeeds while ignoring three sealed columns**, i.e.
reports "intact" for a row whose `aggregate_id` was rewritten.

### The zero-fill

`DbAuditPublisher` inserted **32 zero bytes** as `row_hash` when an event arrived unsealed. That row
satisfied the `octet_length = 32` CHECK, looked sealed, and could never be verified — the worst
available outcome, because it is indistinguishable from a row an attacker zero-filled. It now
**seals the event** instead of faking a seal (reads the tail of the chain, computes the digest), and
`doInsert` has no fallback left.

### Verification you can act on

`GET /v1/audit/integrity` (read-only; there is no POST, because a mechanism that can re-seal rows on
request is a mechanism an attacker can use to launder an edit) sweeps every chain and reports:

- `intact`, and per broken chain the **`audit_log.id` of the first row that failed** plus which of
  three failure modes it is — `PREV_HASH_MISMATCH` (a row was inserted/deleted/reordered),
  `ROW_HASH_MISMATCH` (a sealed column was edited in place), `UNVERIFIABLE_ROW` (a version this
  build cannot canonicalise);
- `legacyV1Rows` — rows still on the weaker digest;
- `unattributableRows` — sealed rows that are not attributable to a verified principal. A perfectly
  intact chain of unattributable rows is a sealed record of nothing, which was the pre-T5-1 state;
  the two properties are reported separately because they are independent.

The previous `chainValid` flag on `GET /v1/audit` could verify one aggregate and returned a boolean
with no row id — not actionable, since an investigator cannot `SELECT` on "false".

---

## 3. The V042 KYB reclassification does not read as tampering

The T1-4 report warned: `V042__partner_kyb_screening_provenance.sql` rewrites SCD-6 rows in place
(reclassifying a stub `CLEAR` to `NOT_SCREENED_NO_PROVIDER`), so a check comparing `partner_kyb`
against its sealed AFTER snapshots "will report the reclassified rows as drifted".

Both tempting fixes are wrong. **Re-sealing the old audit rows** is precisely the edit an attacker
would make — do it ourselves and no future examiner can tell our migration from their attack.
**Suppressing the check** silences the one control that would notice an *unauthorised* status
change, in order to hide an authorised one.

`KybReclassificationProvenance` instead **appends** one `PARTNER_KYB_RECLASSIFIED` event per
reclassified partner, attributed to `system:migration-v042`, with the old status as BEFORE, the new
status plus V042's own `reclassification_note` as AFTER. After that the hash chain is untouched
(one row added — the only legitimate way an append-only log changes), and the drift is explained by
the audit trail itself, at the position in the chain where it happened.

`GET /v1/audit/integrity/kyb-reclassification` then reports each reclassified partner as
**EXPLAINED** or **UNEXPLAINED**. An exit gate should assert `allExplained`, **not** "no drift
exists" — drift from a documented correction is expected; drift nothing accounts for is a finding.
Sealing is idempotent (runs as an `ApplicationRunner`, skips partners that already have the event),
and deliberately not a Flyway callback: an append is safe to repeat, a migration is not.

---

## 4. Append-only at the database — and what that is not

`V044` (vendor-split) installs on **PostgreSQL**: `BEFORE UPDATE OR DELETE` row triggers and a
`BEFORE TRUNCATE` statement trigger on `audit_log`, all raising `restrict_violation`, plus
`REVOKE UPDATE, DELETE, TRUNCATE ... FROM PUBLIC`. Before this, a repo-wide grep for
`REVOKE`/`GRANT`/`CREATE ROLE`/`TRIGGER` across every `*.sql` returned **zero hits**, and V006's own
header admitted the append-only property "lives in the application layer alone".

What it does **not** give you, stated so nobody over-claims it:

- The app connects as the schema **owner** (`gmepay` in compose). An owner can
  `ALTER TABLE ... DISABLE TRIGGER` and then edit. This is a deterrent and a forensic marker against
  accidental and casual tampering, **not** write-once storage. Real WORM needs (a) a non-owner
  application role with `INSERT`+`SELECT` only and no `ALTER` — revoking `UPDATE`/`DELETE` is
  meaningless while the app is the owner, since ownership implies the privilege — and (b) an off-box
  copy the DB role cannot reach at all.
- **H2 cannot express the trigger.** Its `CREATE TRIGGER` requires a Java class or a Java source
  block, not SQL. The H2 vendor twin is a documented no-op. H2 is the dev/unit-slice datasource only
  (every deployed environment is PostgreSQL 16), but the consequence is stated rather than left to
  be discovered: **the trigger itself is not covered by any H2 test** — a test can still `UPDATE`
  `audit_log`, which is what makes the tamper tests writable. Verifying the trigger needs a
  Testcontainers PostgreSQL slice.
- **ADR-007 tiers 2 and 3 are still not deployed.** config-registry has no
  `SPRING_KAFKA_BOOTSTRAP_SERVERS`, so there is **no off-box copy of the audit log** and no
  object-locked archive. `AuditConfig` now uses the Kafka publisher the moment it is configured, so
  that is a deployment change rather than a code change — but today, tier 1 is all there is.

---

## 5. The bug that made "adopting lib-audit" mean nothing

Found by the auth-identity work and fixed in `lib-audit`: **`DbAuditPublisher` was never created in
any real application.** Its `@ConditionalOnBean(DataSource.class)` is evaluated when
`AuditPublisherAutoConfiguration` is processed, auto-configurations order by class name unless told
otherwise, and `com.gme.pay.audit.…` sorts *before*
`org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration`. So the condition was
evaluated before any DataSource bean definition existed, found none, and `LogAuditPublisher` silently
took over.

A service could therefore depend on lib-audit, wire a publisher, look audited — and write **log
lines instead of hash-chained rows**, with no error and nothing in `audit_log`. It passed every test
of that class, because a test that declares its own `DataSource` `@Bean` registers it as *user*
configuration, i.e. before auto-configuration: the tests proved the opposite of production.

Fixed with `@AutoConfiguration(after = DataSourceAutoConfiguration.class)`.
`DbAuditPublisherAutoConfigOrderingTest` drives it through the **real**
`DataSourceAutoConfiguration` via properties, precisely so it cannot pass for the reason the old
tests did. Verified both ways: removing the annotation makes the test fail.

## 6. Bug found while here: config-registry would have written every audit row twice

`AuditLogService` persists the audit row itself via JPA and then hands the sealed event to an
injected `AuditPublisher` for fan-out. `AuditConfig` declared `LogAuditPublisher` under
`@ConditionalOnMissingBean(AuditPublisher.class)` — a condition evaluated while *user* configuration
is registered, i.e. **before** auto-configuration — so it always passed, and then
`AuditPublisherAutoConfiguration` additionally registered `DbAuditPublisher` as `@Primary`
(`@ConditionalOnBean(DataSource.class)`, and this service has one). The `@Primary` bean won the
injection point.

`DbAuditPublisher.publish` **INSERTs into `audit_log`**. So every audited write would produce two
rows sharing one `prev_hash` and one `row_hash` — and a chain with two siblings at the same link
cannot verify. **The tamper-evidence mechanism would report the platform's own audit log as tampered,
on every aggregate, for reasons no investigator could distinguish from an attack.** It escaped the
tests because every audit slice test installs a `@Primary` recording publisher and never loads
auto-configurations.

**It had not fired yet — because of §5.** The DB publisher was never created, so the collision never
happened. That is not reassurance: fixing the §5 ordering is mandatory (auth-identity, prefunding and
rate-fx need durable rows, not log lines), and fixing it is exactly what would have **activated** the
double-write here. The two fixes had to land together.

Fixed by stating the wiring instead of leaving it to condition-evaluation order: `AuditConfig` now
owns both definitions — a non-`@Primary` `DbAuditPublisher` (which makes the auto-configured
`@Primary` copy back off; two primaries would have made injection ambiguous and the service would
not start) and a `@Primary` fan-out publisher that is Kafka-or-log and **never** the DB publisher.
`AuditFanoutRoutingTest` pins it with the real auto-configuration and a real DataSource.

Related: 25 of 26 call sites use `auditLogProvider.getIfAvailable(); if (auditLog != null)`, so a
mis-wired audit bean meant every business write succeeding with zero audit rows and zero errors.
`AuditWiringGuard` takes `AuditLogService` as a hard constructor dependency, so the service now
**refuses to start** without an audit pipeline. A config-registry that is up but unaudited is worse
than one that is down: the first silently destroys the evidence of everything done while it runs.

---

## 6. Per-service coverage — what is audited now, and what is not

Coverage was chosen by risk, not by count. The CISO table listed which high-risk operations were
unaudited; the four that were reachable in this change are money movement, pricing,
credential/permission lifecycle, and authentication. Services owned by other changes in flight
(`transaction-mgmt`, `payment-executor`, `ops-partner-bff`, `settlement-reconciliation`,
`scheme-adapter-zeropay`) were out of scope and are **not** covered.

### Now writing hash-chained `audit_log` rows

| Service | Migration | Aggregate types | Events audited | Actor derivation |
|---|---|---|---|---|
| **config-registry** | V043 (`chain_version`), V044 (append-only) | 24 config aggregates (partner, kyb, scheme, fees, commission, limits, corridors, credentials, mTLS, IP allowlist, documents, prefunding config, regulatory, settlement, rules, platform settings, ops control, webhooks, …) | every config write, + `PARTNER_KYB_RECLASSIFIED` | `@AuditActorHeader` → `AuditActorResolver` (internal-auth attestation) |
| **auth-identity** | V007 (`audit_log`) | `auth_session`, `auth_token`, `api_key`, `api_key_principal`, `rbac_permission`, `rbac_role`, `rbac_principal`, `rbac_constraint` | `AUTH_VERIFY_FAILED` (4 branches), `TOKEN_ISSUED`, `TOKEN_ISSUE_REJECTED`, `TOKEN_VERIFY_FAILED`, `API_KEY_ISSUED` / `_REVOKED` / `_ROTATED`, `RBAC_PERMISSION_CREATED` / `_GRANTED` / `_REVOKED`, `RBAC_ROLE_CREATED` / `_ASSIGNED` / `_UNASSIGNED`, `RBAC_CONSTRAINT_CREATED` / `_DEACTIVATED` | `AuthAuditActorResolver` |
| **prefunding** | V010 (`audit_log`) | `partner_balance`, `partner_limit`, `partner_aml_usage` | `BALANCE_PROVISIONED`, `BALANCE_CREDITED` / `_DEBITED` / `DEBIT_REVERSED`, `FUNDS_RESERVED` / `_CAPTURED` / `_RELEASED`, `REVERSED_FLOAT_RELEASED`, `CREDIT_LIMIT_SET`, `PARTNER_LIMITS_PUSHED`, `CUMULATIVE_USAGE_CHARGED` / `_REVERSED`, `BREACH_SUSPENSION_PROPOSED` | `AuditActorFilter` + `AuditActorResolver` |
| **rate-fx** | V003 (`audit_log`) | `rate_snapshot` | `RATE_SNAPSHOT_MANUAL_OVERRIDE`, `RATE_SNAPSHOT_LIVE_FETCHED`, `RATE_SNAPSHOT_PARTNER_RECORDED` | `AuditActorFilter` + `AuditActorResolver` |

Notes on the two new ones:

- **prefunding** covers the finding "`ledger_entry` has no actor, no reason, no IP" — balance and
  limit mutations now carry all three. Scheduled/system-driven movements are attributed to a named
  system principal, so a job-driven debit is distinguishable from an operator-driven one.
- **rate-fx** covers "`rate_snapshots` permits `source='MANUAL'` with no actor column". A manual
  override and an automated provider fetch are now different event types, so "a human entered this
  rate" is answerable from the log rather than inferable.

### Auditing something, but not to the sealed chain

| Service | What it records | Why it is not enough |
|---|---|---|
| `notification-webhook` | `webhook_replay_audit` (V006): who requested an operator replay, reason, outcome | A bespoke table with **no hash chain**, and `requested_by` is a body/header-supplied string with no attestation. Not tamper-evident and not attributable. |
| `auth-identity` approvals | `approval_decisions` (V005): approver, decision, `cfo_override`, reason, `UNIQUE(request_id, approver_id)` | Genuinely durable and the strongest 4-eyes control in the codebase — but **not hash-chained**, so an edit to a decision row is undetectable. Deliberately not duplicated into `audit_log`. |

### Not instrumented at all

`revenue-ledger`, `reporting-compliance`, `smart-router`, `qr-service`, `merchant-qr-data`,
`kyb-adapter`, and the five services owned by other changes. Plus **`api-gateway`**, which depends on
lib-audit but has **no datasource**, so it falls through to `LogAuditPublisher` — its edge security
rejections (`GATEWAY_IP_REJECTED`, spoofed-partner) remain `log.info` lines in no table. That one is
not a wiring oversight to fix here: giving the gateway a datasource is an architecture decision, and
the right answer is more likely tier 2 (publish to Kafka) than a gateway DB.

**Honest count: 4 of 21 services write hash-chained audit rows** (was 1 — config-registry; the
gateway's dependency never produced a row). Not 21, and the gap is not closed on coverage.

---

## 7. What a regulator would still find missing

In rough order of how badly it would be received.

1. **No off-box copy of the audit log exists.** ADR-007 names the object-locked MinIO archive as
   *the* regulator-defensible store and Kafka as the transport to it. Neither is deployed —
   config-registry has no `SPRING_KAFKA_BOOTSTRAP_SERVERS`. The platform is running on the one tier
   the ADR describes as being for query speed. Everything else in this report protects a single copy
   that lives in the same database as the data it audits. Wiring is ready (`AuditConfig` switches to
   the Kafka publisher the moment bootstrap-servers is set), so this is a deployment task.
2. **Not WORM.** The V044 triggers refuse `UPDATE`/`DELETE`/`TRUNCATE`, but the application connects
   as the schema owner, who can disable a trigger. The missing piece is a non-owner application role
   with `INSERT`+`SELECT` and no `ALTER` — an environment/role change, tracked under T1-6.
3. **Tail truncation and whole-aggregate deletion are undetectable.** A per-aggregate chain proves
   nothing about rows that were removed from the *end*, or about an aggregate deleted entirely:
   there is no anchor to compare against. Detecting either requires an external anchor — periodic
   signed checkpoints of the chain heads, or the off-box copy in (1). Middle-row deletion, insertion
   and in-place edits *are* detected. This limit is written into `AuditChainVerifier`'s javadoc so
   nobody infers more from a green sweep than it supports.
4. **Attribution is delegated, not local, and not yet fail-closed.** config-registry trusts the
   internal-auth secret to identify the caller and trusts the BFF's own JWT verification for the
   human. That is one shared secret between "attested operator" and "a claim". Until
   `RestConfigRegistryClient` presents the token and `require-attestation` is turned on (in
   config-registry, auth-identity and the two new services), operator writes land as
   `unverified:<subject>` — honest, but a regulator asking "who changed this fee" gets "someone
   claiming to be alice". Countable today via `actor_id LIKE 'unverified:%'`.
5. **Successful authentications are not recorded by default.** `AUTH_VERIFY_SUCCEEDED` is behind a
   flag that is off, because that path is the gateway's per-request oracle and auditing it would put
   a serialised chain-tail read plus insert on every partner API call, funnelling one partner's whole
   traffic through one ordered chain. So the trail answers "which authentications failed", not "which
   succeeded". A regulator may well ask for the latter; satisfying it needs a different write shape
   (batched, or partitioned per session rather than per partner), not a flag flip.
6. **Legacy rows carry the weaker v1 digest** — for them, `aggregate_type`, `aggregate_id` and
   `actor_ip` are still unsealed. This is reported (`legacyV1Rows`) rather than fixed, deliberately:
   re-sealing would destroy the chain's evidentiary value. The number only decreases.
7. **The append-only trigger is unverified by any test that runs today** — H2 cannot express it, so
   proving it needs a Testcontainers PostgreSQL slice (CI-only, no local Docker here).
8. **Retention.** ADR-007 requires 5–10 years. There is no retention policy, no archival job and no
   partitioning on `audit_log`; nothing deletes it either, which is the correct failure direction but
   is not a retention *control*.
9. **Nothing forwards `X-Actor` to prefunding or rate-fx.** Both resolvers work, but their callers
   (`ops-partner-bff`, `config-registry`) do not send an operator claim on those calls — so the
   realistic residual there is not `unverified:*` (the mandatory internal-auth gate should make that
   unreachable) but `svc:internal-caller`: **a manual FX override is currently attributed to "some
   platform service", not to a person.** Neither service has a fail-closed mode either. Fixing this
   is a one-header change in the callers, both owned elsewhere.
10. **prefunding's `ConfigRegistryClient.SYSTEM_PROPOSER` is still the bare `"system"` literal.** It is
    not an audit actor — it is the `change_request.proposed_by` wire value that config-registry's V005
    4-eyes CHECK exempts, so changing it requires changing the constraint first (gap **T5-10**). The
    audit row for the same event uses `system:prefunding-breach-auto-suspend`, so the two deliberately
    disagree until T5-10 removes the carve-out.
11. `ops-partner-bff`'s `operator-action-audit` still defaults to `stub` (discarded in memory), and its
    `PartnerCredentialController` / `PartnerLifecycleController` still take a raw
    `@RequestHeader("X-Actor")` inbound. Both belong to that service's owner.
12. **`auth-identity`'s `V007__audit_log.sql` is pinned column-for-column** against lib-audit's DDL by
    `AuthAuditEventsSelfCheck`. If lib-audit's table changes, that test fails by design and the fix
    must be a **new** migration (V008+), never an edit to V007.

### Verification run

`:libs:lib-audit:test`, `:services:config-registry:test`, `:services:auth-identity:test`,
`:services:prefunding:test`, `:services:rate-fx:test` — all green; repo-wide
`gradlew testClasses` green. New/changed tests include: the actor vocabulary and the refusal of the
bare `"system"` literal; forged/absent/attested/wrong-token actor resolution and the fail-closed
mode; each of the three previously-unsealed columns being rewritten and caught; chain-version
downgrade being caught; the verifier naming the first broken row id; the V042 drift reported before
and explained after sealing, with the chain intact throughout and sealing idempotent; the
auto-configuration ordering that decides whether an adopting service gets durable rows; and the
fan-out routing that would otherwise double-write.
