> 작업: T5-4 webhook/IPN integrity / 출처: agent

# T5-4 — Webhook/IPN integrity: per-endpoint secrets, 9Pay verification on by default, IPN replay guard

Gap source: `Documentation/GAP_REGISTER.md` T5-4 · `outputs/agent/audit_ciso-security_2026-07-28.md` §13 (MAJOR).
Modules touched: **`services/notification-webhook`**, **`services/scheme-adapter-ninepay`** only.

---

## 0. Summary

| Defect | Before | After |
|---|---|---|
| (a) one global webhook signing secret | `DefaultWebhookTargetResolver` returned `gmepay.webhook.signing-secret` for **every** partner | per-endpoint HKDF-derived secret, **hash-verified before signing**, fail-closed; rotation with a dual-signature overlap window |
| (b) 9Pay `verify-responses=false` by default | API responses that drive payout state accepted unverified | defaults **`true`**; **no public key ⇒ reject as unverifiable/ambiguous**, never trust |
| (c) no IPN replay protection | `handleIpn` applied `recordIpn` unconditionally | UNIQUE `event_key` on the `(request_id, trans_id, code)` identity + signed-timestamp staleness + monotonic-status rules |

Tests: **198 green, 0 failures** (`notification-webhook` 116, `scheme-adapter-ninepay` 82) on a forced `--rerun-tasks`.

---

## 1. Webhook secret model — before / after

### Before (two problems, not one)

`DefaultWebhookTargetResolver.java:46,79-81` resolved the URL per partner but returned the single
configured `gmepay.webhook.signing-secret` as the signing key for all of them. So:

1. **Cross-partner forgery.** Every partner that verifies signatures needs the key they are
   verifying with. One global key means each partner holds the key that signs *everyone's*
   webhooks — one leak (or one curious partner) forges events to all of them.
2. **It never actually worked.** Registration minted a random `whsec_…` (`SigningSecrets.newSecret()`),
   stored its SHA-256 and revealed the plaintext once — and then the dispatcher signed with the
   *global* value instead. A partner following the API doc could never get a match. The hash at
   rest was of a secret nothing ever used.

### After — derive, don't store

`provisioning/WebhookSecretDeriver.java` (new). RFC 5869 HKDF-SHA256:

```
PRK    = HMAC-SHA256(salt="GMEPAY-WEBHOOK-SECRET-V1", ikm=rootKey)
OKM    = HMAC-SHA256(PRK, info || 0x01)                       (L = 32)
info   = "webhook-endpoint|partner=<id>|env=<ENV>|gen=<n>"
secret = "whsec_" + base64url-nopad(OKM)                      (43 chars — same shape as before)
```

The root key **reuses the existing `gmepay.webhook.signing-secret` / `GMEPAY_WEBHOOK_SIGNING_SECRET`
slot** (already templated in `values.yaml`), so **no new deployment variable is required** — but its
meaning changed: it is a derivation root that is *never disclosed to any partner*, where before it was
the shared HMAC key every partner needed a copy of.

Why derivation rather than encrypting the plaintext at rest:

- it keeps the project-wide rule "plaintext secret material is NEVER persisted" (SEC-09 §4, V004's own
  comment) **literally true** — no new secret-bearing column, no KEK, no IV/AEAD to get wrong;
- per-endpoint isolation is one-way: partner 7's secret reveals nothing about partner 8's;
- rotation is a counter, not a re-keying exercise;
- and it stays verifiable — see fail-closed below.

**The T1-1 contract is intact.** `WebhookEndpointRegistrationContractTest` passes unmodified in its
assertions (5/5): the secret returned at activation still hashes to the row this service stored, the
digest is still unsalted SHA-256 lowercase hex (fixed vector `whsec_test` → `609b97b0…` unchanged),
the idempotent replay still returns no second secret. The only edit to that file is a
`@TestConfiguration` supplying the new collaborator bean — no assertion changed.

## 2. Fail-closed proof

`DefaultWebhookTargetResolver` now re-derives the endpoint's secret and **checks it against
`signing_secret_hash` before handing it to the sender**. There is no branch left that substitutes a
shared or placeholder key:

| Situation | Outcome |
|---|---|
| root key blank | `Optional.empty()` → row stays PENDING, WARN naming the missing property |
| root key **wrong** (env drift) | derived ≠ stored digest → `Optional.empty()`, ERROR — we refuse to sign with a secret the partner does not hold |
| legacy V003 row, `signing_secret_hash IS NULL` | `Optional.empty()` (nothing to verify against) |
| rotation overlap names an un-derivable previous secret | previous silently dropped, current still signs (never a delivery failure) |

Pinned by `DefaultWebhookTargetResolverTest` (10 tests), including the cross-forgery assertion:
partner 700's secret **cannot** verify a payload signed for partner 800
(`verifySignature(body, secret700, signedFor800)` is `false`, `secret800` is `true`).

Note the useful side effect: a wrong root key can now only cause *non-delivery*, never a silently
wrong signature — the failure is loud and safe rather than quiet and wrong.

## 3. Rotation design (and why this shape)

`POST /v1/webhooks/endpoints/{endpointId}/rotate-secret?overlapMinutes=N` →
`WebhookSecretRotationView(endpointId, signingSecretPlaintext, secretGeneration, previousSecretExpiresAt)`,
plaintext revealed **once**, like registration. Default overlap 1440 min (24 h), max 30 days, `0` =
immediate cutover. Refuses (500) when no root key is configured — we never issue a secret the
dispatcher could not reproduce.

Flyway **`V007__webhook_endpoint_secret_rotation.sql`** (next free version; module has a single
`db/migration` dir, **no vendor subdirs** — V001–V006 existed): `secret_generation INTEGER NOT NULL
DEFAULT 1` (+ `CHECK >= 1`), `previous_secret_hash VARCHAR(64)`, `previous_secret_expires_at TIMESTAMP`.

**Overlap semantics = both signatures on the wire, not both secrets accepted.** Chosen deliberately:
this service *produces* signatures, it does not verify partner-inbound ones. An inbound verifier can
simply try two keys; an outbound signer must decide what to put in the header. So during the window
`X-GME-Webhook-Signature` carries two comma-separated values (`sha256=<new>,sha256=<old>`,
Stripe's convention) and the partner cuts over whenever they redeploy. Outside the window, the header
is byte-for-byte what it was before this change — single value, no consumer impact.
`WebhookSigningService.verifySignature` was widened to accept either value (constant-time, no early
exit); `signatureHeader(...)` builds it.

## 4. 9Pay response verification — on by default, fails closed

- `application.yml`: `verify-responses: ${GMEPAY_SCHEME_NINEPAY_VERIFY_RESPONSES:true}` (was hard
  `false`); the PEM properties also became env-overridable
  (`GMEPAY_SCHEME_NINEPAY_{PRIVATE,PUBLIC}_KEY_PEM`) — they previously had **no** env hook at all.
- `NinepayApiClient`: `@Value("${…verify-responses:true}")`.
- `NinepaySigner.canVerify()` (new) distinguishes "9Pay sent a bad signature" (attack / spec drift)
  from "we have no key to check it with" (our misconfiguration). `verifyResponseSignature` now throws
  `NinepayTransportException` for **both** — deliberately the *ambiguous* exception, so the caller
  polls `transfer/info` and the payout lands `UNKNOWN` rather than being declared failed. Fail-closed
  without inventing a false verdict about money.

**E2E untouched and unaffected.** `e2e-tests/…/NinepayPayoutE2ETest` already boots the adapter with
explicit `gmepay.scheme.ninepay.verify-responses=true` + `ninepay-public-key-pem` from
`GET /sim/public-key` (`e2e_qr-schemes_2026-07-27.md:41`), so flipping the *default* changes nothing
for it. No file under `e2e-tests/` or `simulators/` was modified.
(There is no `e2e_qr-schemes_2026-07-28.md` in `outputs/agent/` — only the 07-27 report.)

Proof: `NinepayVerifyResponsesDefaultTest` (new, 2 tests) — (1) the **shipped YAML** resolves to
`true` with the env var absent; (2) a real Spring context with **no property source at all** (so only
`@Value` defaults apply) rejects a well-formed "SUCCESS" response with
`"…cannot be verified…ninepay-public-key-pem"`. Plus `NinepayApiClientTest` gains the same fail-closed
case alongside its existing good/forged pair.

## 5. IPN replay rule — and how the real 009 path stays intact

`adapter/NinepayIpnReplayGuard.java` (new), gate applied **before any payout mutation**:

1. **Identity dedupe.** 9Pay ships no IPN id, so identity = the audit's prescribed
   `(request_id, trans_id, code)` triple, SHA-256'd into UNIQUE `np_ipn_events.event_key`. Replay ⇒
   `DUPLICATE`: recorded, **not** applied, still 2xx-ACKed (an error would just provoke more retries).
   The UNIQUE constraint is the backstop under concurrency — a racing redelivery loses the
   `saveAndFlush` and the payout is left untouched.
2. **Staleness by the SIGNED `created_at`** (not arrival time — an attacker picks arrival). Older than
   the newest already-applied event ⇒ `STALE_ORDER`. **Equal passes** (critical, see below).
3. **Monotonic status.** Rank `SUBMITTED/UNKNOWN 0 < PENDING 1 < PROCESSING 2 < HELD 3 <
   SUCCESS/FAILED 4 < REVERSED 5`; a lower-ranked incoming status ⇒ `STATUS_REGRESSION`. Nothing
   overwrites `REVERSED`; a late `PENDING` cannot undo a `SUCCESS`.
4. **Age window** `gmepay.scheme.ninepay.ipn.max-age-minutes`, default **10080 (7 days)**, `0`
   disables ⇒ `EXPIRED`. Deliberately generous: genuine bank reversals arrive days late and silently
   dropping one would leave money paid out that the bank took back.

Flyway **`V002__np_ipn_events_replay_guard.sql`** (next free; single `db/migration` dir, no vendor
subdirs): `event_key VARCHAR(64)` + UNIQUE, `applied BOOLEAN NOT NULL DEFAULT FALSE`,
`reject_reason VARCHAR(40)`, `scheme_created_at VARCHAR(20)` (the signed stamp, promoted out of
`raw_payload` because rule 2 orders by it). `event_key` is **NULL for non-applied rows** — signature
failures and audited replays — and both PostgreSQL and H2 allow repeated NULLs under UNIQUE, giving
"unique only when applied" without a partial index. That is what stops a rejected delivery from
blocking the genuine redelivery behind it (proved by a test).

### Why the genuine 000 → 009 reversal still works

This was the trap. Checked against the sim, which mirrors the spec: `TransferLifecycle` sends the
reversal as `ipnSender.send(record, partnerId, "SUCCESS", "009", …)` — **wire status stays SUCCESS and
`created_at` is reused**. Since 9Pay signs
`request_id|partner_id|trans_id|request_amount|fee|transfer_amount|type|status|created_at` and **not
`code`**, a genuine 009 is byte-identical to the 000 before it *except for the unsigned `code`*.
Hence, all three deliberate:

- dedupe keys on the **triple including `code`**, not on the signed body — keying on signed content
  would have swallowed the real reversal as a duplicate;
- staleness accepts **equal** timestamps;
- `SUCCESS → REVERSED` is **not** a regression.

Pinned end-to-end in `NinepaySchemeAdapterTest`: `ipn_genuine000Then009StillReverses` walks
000 → applied → (persisted identity on file, same `created_at`) → 009 → `REVERSED` with `reversed_at`
stamped and **two** `saveAndFlush` claims.

### Replay attempts that are now blocked

| Attempt | Result |
|---|---|
| duplicate 000 | `DUPLICATE`, ACK with current status, `payouts.save` never called |
| **replayed 009** | `DUPLICATE`; `reversed_at` unchanged (asserted identical) — **no double-reverse** |
| stale 000 resent after the 009 | `STALE_ORDER`; payout stays `REVERSED` |
| late 004/PENDING onto a SUCCESS | `STATUS_REGRESSION` |
| IPN older than the window | `EXPIRED` |
| forged signature | `SIGNATURE_INVALID`, 400 (unchanged), and **no `event_key` claimed** so the genuine redelivery still applies |

## 6. Files changed

**`services/notification-webhook`** — new: `provisioning/WebhookSecretDeriver.java`,
`provisioning/WebhookSecretRotationView.java`, `db/migration/V007__…sql`,
`test/provisioning/WebhookSecretDeriverTest.java`. Modified: `dispatcher/DefaultWebhookTargetResolver.java`
(rewritten), `dispatcher/WebhookTargetResolver.java` (`ResolvedTarget.secondarySecret`, 2-arg
convenience ctor kept), `dispatcher/WebhookDispatcher.java` (passes the secondary),
`domain/WebhookSender.java` (7-arg overload; 6-arg delegates), `signing/WebhookSigningService.java`
(`signatureHeader`, multi-value verify), `provisioning/WebhookEndpointProvisioningService.java`
(derive + `rotateSecret`), `api/WebhookEndpointController.java` (rotate endpoint, 500 handler),
`persistence/WebhookEndpointEntity.java` (V007 fields), `application.properties` (root-key semantics),
tests: resolver (rewritten), provisioning (+6 T5-4 tests), dispatcher (2 stubs → 7-arg), contract test
(`@TestConfiguration` only), `PaymentApprovedWebhookDeliveryIT` (endpoint row now carries the derived
digest — it is `@Tag("docker")`, so **not exercised locally**; it compiles and its wiring was updated).

**`services/scheme-adapter-ninepay`** — new: `adapter/NinepayIpnReplayGuard.java`,
`persistence/IpnRejectReason.java`, `db/migration/V002__…sql`,
`test/client/NinepayVerifyResponsesDefaultTest.java`. Modified: `adapter/NinepaySchemeAdapter.java`
(guard wired into `handleIpn`, 6-arg ctor), `client/NinepayApiClient.java` (default + fail-closed),
`sign/NinepaySigner.java` (`canVerify()`), `persistence/NpIpnEventEntity.java` +
`NpIpnEventRepository.java`, `application.yml`, tests: adapter (+7), api client (+1), persistence
slice (+3).

## 7. Test results

`gradlew.bat :services:notification-webhook:test :services:scheme-adapter-ninepay:test --rerun-tasks`
→ **BUILD SUCCESSFUL**, `tests=116 failures=0 errors=0` + `tests=82 failures=0 errors=0`.

Every behaviour the task asked to prove is pinned: two endpoints get distinct signatures and one's
secret cannot verify the other's payload; rotation window works (inside → both, expired → current
only, `0` → immediate, repeatable); a bad-signature 9Pay response is rejected; a missing public key
fails closed (twice — unit and default-config level); duplicate IPN ignored idempotently while
`000 → 009` still reverses; replayed `009` does not double-reverse.

## 8. Required config / deployment (NOT changed here — out of scope)

1. **`GMEPAY_WEBHOOK_SIGNING_SECRET` is now a derivation root, not a shared HMAC key.** Same variable
   name, so no manifest edit is needed, but: it must be set wherever webhooks must be delivered
   (blank ⇒ nothing delivers, by design), it must be **identical across notification-webhook
   replicas**, and it must **never be given to a partner** again. `docker-compose.yml:714`'s
   `dev-webhook-secret-not-for-prod` works unchanged for local dev.
2. **Existing `webhook_endpoint` rows are undeliverable until rotated.** Rows minted before this
   change hold digests of CSPRNG secrets that cannot be derived, so the resolver fails closed on them
   (loud ERROR per row). Remediation per endpoint: `POST /v1/webhooks/endpoints/{id}/rotate-secret`
   and hand the partner the new secret. No data migration can fix this — the old plaintext was never
   stored anywhere (by design). Currently a demo-scale concern only.
3. **No caller for the rotate endpoint yet.** config-registry / ops-partner-bff / admin-ui would need
   an operator-facing "rotate webhook secret" action (4-eyes, mirroring partner-credential rotation).
   Not added — those modules are out of scope for this task.
4. **9Pay keys** need real values in production: `GMEPAY_SCHEME_NINEPAY_PRIVATE_KEY_PEM` (PKCS#8) and
   `GMEPAY_SCHEME_NINEPAY_PUBLIC_KEY_PEM` (X.509) — neither is in `values.yaml`'s `secrets.data`
   (pre-existing gap, CISO §11). With `verify-responses` now on by default, **an environment with no
   9Pay public key will refuse every 9Pay response** — that is the intended fail-closed behaviour, and
   it makes the missing key a startup-visible operational fact rather than a silent trust hole.
   `GMEPAY_SCHEME_NINEPAY_IPN_MAX_AGE_MINUTES` optionally tunes the IPN window (default 7 days).

## 9. Still open (external / out of these two modules)

- **9Pay IPN-edge IP allowlist** — the `application.yml` comment claims mutual IP whitelisting is
  "enforced at the network layer", and it is enforced nowhere: no NetworkPolicy, no ingress allowlist.
  Requires `deploy/helm/**` (excluded here).
- **9Pay must sign `code`** (external ask, O-item). Because `code` is outside the signed string and a
  genuine 009 is otherwise identical to the 000 it reverses, a captured 000 with `code` rewritten to
  `009` still verifies cryptographically — **no local rule can distinguish it from a real reversal**.
  Compensating controls today: the dedupe key bounds it to one reversal per identity, `REVERSED` is
  absorbing, every attempt is persisted with its `reject_reason`, and the IP allowlist (above) is the
  real perimeter. This is the one part of T5-4 that cannot be closed in code we own.
- **Vault** — the resolver's derivation root is still an env var. Sourcing it from Vault is now a
  one-line change (swap how `WebhookSecretDeriver` gets its root key) rather than the architectural
  hole the audit described.
- **A `kid`/key-version for the root key** — rotating the *root* still re-keys every endpoint
  (per-endpoint `secret_generation` is versioned; the root is not). Related to CISO §11's
  "no rotation, no `kid`" finding.
