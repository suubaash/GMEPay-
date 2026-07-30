> 작업: T0-6 JWT key rotation + versioning / 출처: agent

# T0-6 — key rotation and key versioning for the platform JWT

Closes the residual `Documentation/GAP_REGISTER.md` T0-6 recorded as *"no rotation and no key
versioning — grep for `kid`/`keyVersion`/`activeKeyId` is still zero hits"*, left deliberately
un-half-built by `outputs/agent/fix_t0-partner-keys-and-secrets_2026-07-28.md` (which removed the
published default and added the fail-closed boot gate).

Nothing was started: no Docker, no Keycloak, no fleet, no server. Static + unit evidence only.

---

## 1. Survey — every signing/verification secret, and which of them this covers

| Secret | Minted by | Verified by | Rotation story **before** | **After this change** |
|---|---|---|---|---|
| **Platform JWT** `GME_AUTH_JWT_SIGNING_SECRET` (HS256, `gme.auth.jwt.signing-secret`) | `auth-identity` only — `JwtHelper`, `POST /internal/auth/token/issue` | `auth-identity` only — `POST /internal/auth/token/verify`. Grep-confirmed: `JwtHelper` exists in **no other module** | one static value. Replacing it invalidated every live token instantly. No scheduled rotation expressible at all | **COVERED.** Versioned key set, `kid` in the header, verify-by-`kid`, accept-old window, boot-time retire safety, runbook |
| **Keycloak / OIDC operator tokens** | Keycloak (RS256) | `api-gateway` + `ops-partner-bff` via `spring-security-oauth2-resource-server` (`OIDC_ISSUER_URI` → JWKS) | **already rotates itself** — asymmetric, `kid`-versioned at the IdP, resource servers refetch JWKS on an unknown `kid` | **NOT COVERED — and not a gap.** A different trust path; conflating it with the platform JWT is the mistake the brief warned about. No GME-side config change is involved in a Keycloak key roll |
| **Internal-auth token** `GMEPAY_INTERNAL_AUTH_SECRET` (`X-Gme-Internal`) | nobody — a static shared token | 11 services, via `libs/lib-errors` `com.gme.pay.internalauth.InternalAuthFilter` | one static value, held by every participant at once | **NOT COVERED — scoped out, see §6** |
| **Webhook signing root** `GMEPAY_WEBHOOK_SIGNING_SECRET` (HKDF derivation root, `WebhookSecretDeriver`) | `notification-webhook` derives a per-endpoint `whsec_` from it | **partners**, outside our infrastructure | one static root; rotating it re-derives *every* partner secret simultaneously | **NOT COVERED — scoped out, see §6** |
| **RBAC claim-stamp key** `GMEPAY_RBAC_SECRET` | `api-gateway` `RbacClaimStampingFilter` | downstream services via `lib-errors` `RbacAutoConfiguration` | one static value, shared both sides | **NOT COVERED** — same shape as the internal-auth token |
| **Partner HMAC secrets** (`sk_…`) | `auth-identity` issuance, PBKDF2 at rest | `api-gateway` from operator config (T0-7) | per-partner; `ApiKeyAdminController` already has issue/rotate/revoke | out of scope — already has a lifecycle |
| **Scheme RSA pairs** (9Pay, Nepal) | partner-coordinated | partner | manual, partner-coordinated | out of scope |

Only the first row changed. The bottom four are named here so the boundary is explicit rather than
implied.

---

## 2. What was built

### `domain/JwtKeySet` — the key set

One **ACTIVE** key that signs, plus zero or more previously active keys accepted for
**verification only** until the tokens they signed expire.

**The `kid` is derived, not configured** — `gmek_<16 hex>`, a truncated SHA-256 over a
domain-separated copy of the secret. This is the one design decision worth arguing, so the reasoning
is in the class javadoc and repeated here:

- an operator-chosen `kid` adds a failure mode with no upside — a label pointing at the wrong secret.
  Every token then names a key that cannot verify it, and the symptom (mass `INVALID_TOKEN`) is
  indistinguishable from a forgery wave. A derived id *is* a function of the material;
- it shrinks rotation config to secrets and dates, with no second list of names to keep in sync;
- it discloses nothing — one-way, truncated, and already published in every token header;
- the same key produces the same `kid` in every replica with no coordination, which is what makes
  "did the rotation reach every pod" answerable by comparing a short string.

The cost — a `kid` is not human-meaningful — is paid back by `report(maxTtl, now)`, which pairs each
id with its demotion date and the instant it becomes safe to remove.

Config format for the accepted set: `<secret>@<ISO-8601 instant it stopped signing>`, `;`-separated.
The timestamp is **mandatory**: it is the only input from which "safe to delete" can be computed, so
an undated entry fails the boot rather than being guessed at. A malformed entry fails the whole
parse — silently dropping one would drop a key that live tokens still name, which is exactly the
outcome the overlap window exists to prevent.

### `domain/JwtHelper` — verify by `kid`

Header is now `{"alg":"HS256","typ":"JWT","kid":"gmek_…"}`. Verification parses the header and picks
the key **before** computing any HMAC:

| Case | Outcome |
|---|---|
| `kid` names the active key | verified against that key only |
| `kid` names an accepted predecessor | verified against that key only |
| `kid` unknown | **`UNKNOWN_KID`** — rejected, no other key tried |
| **no `kid`** | **`INVALID`** — pinned, see below |
| `alg` != `HS256` | `INVALID` at parse time |

No fallback, for two reasons: it would make the `kid` advisory (a retired key honoured through a
token naming a live one), and it would let an unauthenticated caller cost the service one HMAC per
configured key per bad token.

**Pinned decision — a token with no `kid` is rejected.** Everything the platform mints carries one,
so an un-kidded token is either from a build older than this change or was not minted here. The
first case is bounded and self-clearing: those tokens are at most one `max-token-ttl-seconds` old, so
deploying this build costs exactly one max-TTL window in which pre-upgrade tokens are refused —
identical to a rotation with no overlap, and the runbook says to plan the T0-6 rollout as a hard
cutover. Falling back to the active key to be kind to that window would leave an un-versioned
verification path permanently open, which is what T0-6 exists to close; a compatibility switch would
be the switch nobody turns off, so there deliberately is not one.

`UNKNOWN_KID` is a distinct outcome rather than folded into `INVALID` because the two mean opposite
things operationally: a burst of `UNKNOWN_KID` after a rotation means **a key was retired while its
tokens were still live** (or a replica has a stale set), while a burst of `INVALID_TOKEN` means
somebody is forging. `JwtTokenService` records them as different audit `reason`s and answers **both
as `INVALID_TOKEN` on the wire** — which keys the service holds is not something a caller enumerates
one probe at a time.

### `config/JwtSigningKeyEnforcedConfig` — extended, not weakened

The four original T0-6 rejections are unchanged and now run over **every key in the set**, accepted
ones included, via the extracted `validateKeyMaterial`. That is the load-bearing part: an accepted
key is live signing material for as long as it is accepted, so a published or placeholder-shaped
value parked in `previous-keys` would otherwise have been a hole punched straight through the T0-6
gate in the name of rotation. The single-key constructor still exists and behaves identically, so
the original test class passes untouched.

Four rotation-specific refusals added:

| Refusal | Why a refusal and not a warning |
|---|---|
| undated / unparseable `previous-keys` entry | without a demotion date the retirement step is guesswork |
| demotion timestamp in the future | the safe-to-remove arithmetic would run backwards |
| the same secret twice in the set | the operator believes they configured two keys and one demotion date is being silently ignored |
| **undeclared hard cutover** | see below |

**The retire-safety check.** If `GME_AUTH_JWT_ACTIVE_KEY_ACTIVATED_AT` says the active key started
signing less than one max token TTL ago **and** the accepted set is empty, then whatever was signing
before was dropped in the same step — every token minted before the switch is dead. The service
refuses to start and the message names both fixes: restore the outgoing key as
`<old-secret>@<activated-at>`, or set `GME_AUTH_JWT_ALLOW_HARD_CUTOVER=true`. That flag is not an
escape hatch bolted on; it is the *declared* form of the two situations where losing every token is
correct — a first deployment and a compromise response — which is why the compromise runbook step
uses it by name.

**Retiring too late is a WARN, not a refusal.** A key kept a full extra TTL past safe is an
unnecessary extra copy of live signing material, but taking the service down over a forgotten config
line would be worse than the risk, and an operator may be holding the key deliberately mid-incident.

### `GET /internal/auth/token/keys`

Rotation is only an operation if it can be verified, and "restart the pods and hope" is not
verification. Returns the active `kid`, each predecessor, and each `safeToRemoveAfter` — read from
the **live `JwtHelper`**, not from configuration, so a replica that missed the rollout is visible as
a different `activeKid`. No key material; behind `X-Gme-Internal` like every other route here.

---

## 3. What an operator now does

`docs/runbooks/JWT_KEY_ROTATION.md`, summarised:

1. **Promote** — new `GME_AUTH_JWT_SIGNING_SECRET`, old value appended to
   `GME_AUTH_JWT_PREVIOUS_KEYS` as `<old>@<now>`, `GME_AUTH_JWT_ACTIVE_KEY_ACTIVATED_AT=<now>`,
   `ALLOW_HARD_CUTOVER=false`. Roll `auth-identity` — **and nothing else**, since no other service
   reads the key.
2. **Wait** ≥ `max-token-ttl-seconds` (default 1 h) from when the rollout *completes*.
3. **Retire** — remove the entry, roll again, delete the value from the secret store.

Verification: startup log → `GET /internal/auth/token/keys` on **every replica** → mint-and-decode +
present a pre-rotation token to `/verify`. Watch `TOKEN_VERIFY_FAILED` reasons: `UNKNOWN_KID` means
the rotation is going wrong, `INVALID_TOKEN` means somebody is attacking.

**Compromise response is deliberately the opposite of a graceful rotation.** The runbook states it
plainly: a graceful rotation keeps the leaked key accepted for an hour, so forged tokens keep working
for an hour. If you believe the key leaked, do the hard cutover — the cost is one hour of pain you
were going to pay anyway. Plus: never re-add the leaked key, add it to `PUBLISHED_KEYS` if it ever
touched the repo, sweep `TOKEN_ISSUED` for unaccountable subjects, and rotate the internal-auth
secret too if the same store was breached (holding *that* means being able to *mint* rather than
merely forge).

---

## 4. Configuration wired

| Variable | Secret? | compose | Helm base | 3 overlays | run-fleet |
|---|---|---|---|---|---|
| `GME_AUTH_JWT_PREVIOUS_KEYS` | yes | `x-auth-jwt-previous-keys`, defaults **empty** | `secrets.data` + `envSecretKeys`, **`""`** | **`""`** | `''` |
| `GME_AUTH_JWT_ACTIVE_KEY_ACTIVATED_AT` | no | `x-auth-jwt-activated-at`, empty | service `env`, `""` | inherit | — (unset ⇒ check inactive) |
| `GME_AUTH_JWT_ALLOW_HARD_CUTOVER` | no | `x-auth-jwt-allow-hard-cutover`, `true` (a throwaway `compose up` is always a first activation) | service `env`, `"true"` for first install | inherit | `'true'` |

`GME_AUTH_JWT_PREVIOUS_KEYS` **ships empty rather than as a `CHANGE_ME_` placeholder**, and that is
deliberate: a placeholder here would be a *rejected key value*, so it would fail the service closed
for the situation that is normal. Empty is not a credential, so the "placeholders only, never a
working credential" rule is kept. The single-anchor idiom is preserved — three new top-level
anchors, no inline literals, and the guard asserts the accepted list can never gain a checked-in
default.

---

## 5. Files changed

**auth-identity** — new `domain/JwtKeySet.java`, `dto/JwtKeySetStatusResponse.java`; changed
`domain/JwtHelper.java`, `config/JwtSigningKeyEnforcedConfig.java`, `config/AuthConfig.java`,
`service/JwtTokenService.java`, `web/JwtTokenController.java`, `src/main/resources/application.yml`,
`CHANGELOG.md`. Tests: new `domain/JwtKeySetTest`, `domain/JwtHelperKeyRotationTest`,
`config/JwtKeySetEnforcedConfigTest`, `service/JwtTokenServiceRotationTest`.

**Deployment / docs** — `docker-compose.yml`, `deploy/helm/gmepay/values{,-aws,-azure,-onprem}.yaml`,
`run-fleet.ps1`, `scripts/check_internal_auth_wiring.py`, `.gitleaks.toml` (see §7.6),
new `docs/runbooks/JWT_KEY_ROTATION.md`, `docs/COMPOSE.md`, `Documentation/GAP_REGISTER.md`.

**Not touched**, per the coordination constraint: `.smoke/**`, Helm replica/HPA settings, and
service-level stub selectors belong to the other agent. Only the files above were staged.

---

## 6. Rotation for the internal-auth token and the webhook root — NOT attempted, and why

Both are materially different problems, and half-rotating three secret types would be worse than
rotating one properly.

**`GMEPAY_INTERNAL_AUTH_SECRET`.** The platform JWT works because there is a `kid` *inside each
token* — a per-message field the verifier can key on. `X-Gme-Internal` is a bare shared token with no
envelope, so there is nothing to version: an overlap window means every **verifier** accepting a
*set* of tokens while every **caller** migrates. That is an accepted-set on `InternalAuthFilter`
(`libs/lib-errors`), plus a defined switchover order across **11 services** that each hold both roles,
plus a way to tell when the last caller has moved. It is a fleet-wide coordinated change, not a
change to one service.

**`GMEPAY_WEBHOOK_SIGNING_SECRET`.** It is an HKDF *derivation root*, so rotating it re-derives
**every partner's** endpoint secret simultaneously, and the verifiers are third parties outside our
infrastructure. The overlap window is measured in partner release cycles, and the mechanism is
dual-signature delivery (both old and new signature on each webhook) plus per-partner migration
tracking. `WebhookEndpointProvisioningService` already rotates a *single* endpoint's secret; the root
is the unsolved part, and it is a partner-facing programme rather than a build.

`GMEPAY_RBAC_SECRET` is the same shape as the internal-auth token. Both are recorded as scoped
register items with the above sizing, and in `docs/runbooks/JWT_KEY_ROTATION.md` §7 so an operator
reading the runbook cannot mistake them as covered.

---

## 7. Verification

| Check | Result |
|---|---|
| `gradlew :services:auth-identity:test` | ✅ **318** tests, 0 failures / 0 errors |
| `gradlew testClasses` (whole repo) | ✅ BUILD SUCCESSFUL |
| `python scripts/check_internal_auth_wiring.py` | ✅ **121/121** (was 90 + 6 pre-existing from other work; 25 new) |
| `python scripts/check_monitoring_wiring.py` | ✅ 37/37 |
| `python scripts/check_helm_chart_wiring.py` | ✅ 299/299 |
| `python scripts/check_gitleaks_config.py` | ✅ 0 findings, 15/15 negative controls caught (see §7.6) |
| `python scripts/check_load_harness_wiring.py` | ✅ OK |
| `node docker/keycloak/check-topology.mjs` | ✅ 101/101 |
| PyYAML parse: compose + 4 Helm values, values asserted programmatically | ✅ all parse |
| PowerShell `Parser::ParseFile` on `run-fleet.ps1` | ✅ 0 errors (2194 tokens) |

What the new tests actually assert, against the brief's list:

1. **a token signed with the active key verifies** — `activeKeyVerifies`;
2. **a token signed with a still-accepted previous key verifies** — `previousKeyStillVerifies`
   (minted by a helper whose active key was V1, presented to one now signing with V2), plus
   `twoPreviousKeysBothVerify` for an overlapping second rotation, plus the same at the service
   boundary in `JwtTokenServiceRotationTest`;
3. **an unknown `kid` is rejected and does not silently try other keys** —
   `unknownKidIsRejectedWithoutFallback` (distinct `UNKNOWN_KID` outcome, no claims returned) and
   `kidMustMatchTheSignature`, which splices a V1 header/body onto a V3 signature: if any fallback
   existed it would have to try the other key, and the assertion that it lands on `INVALID` is what
   proves only the selected key was used;
4. **no-`kid` behaviour is pinned** — `unkiddedTokenIsRejected` builds the exact pre-T0-6 header
   `{"alg":"HS256","typ":"JWT"}` with a **genuinely correct signature for the active key** and
   asserts it is still rejected;
5. **retiring a key that could still have live tokens is refused** — `prematureRetirementIsRefused`,
   with `gracefulRotationBoots` / `declaredHardCutoverBoots` / `steadyStateAfterTheWindowBoots` as
   the three legitimate neighbours, and `overdueKeyIsWarnedNotRefused` for the other direction;
6. **a key set violating the T0-6 rules refuses to boot** — published / placeholder / too-short in
   the *accepted* slot, no active key, duplicate secret, future demotion date, malformed entry;
7. **a config test reads the SHIPPED config** — `shippedConfigWiresRotation` loads
   `application.yml` off the classpath and pins all four property lines including
   `allow-hard-cutover: ${…:false}`, mirroring how `JwtSigningKeyEnforcedConfigTest` pinned the
   original gate. The wiring guard additionally pins compose, all four Helm files, the empty
   accepted list, and the runbook's existence and content.

Also asserted: `EXPIRED` beats `UNKNOWN_KID` for an expired token signed by an accepted predecessor
(the two must not be conflated), the audit row for `UNKNOWN_KID` uses the shared unknown-subject
chain and never the token's self-asserted subject, and the key-set response contains no key material.

**§7.6 — one guard was already red at `HEAD`, and is not mine.** `check_gitleaks_config.py` failed
before I touched anything, on `gmepay-test-platform/src/engine/credentials.ts:341`
(`return { 'X-Gme-Internal': CREDENTIALS.internalToken! };`, introduced by commit `bac6541`) — a
variable *reference*, not a value. The other agent has since fixed it properly in `.gitleaks.toml`
with a **value-scoped** `[rules.allowlist]` on the `gmepay-internal-platform-token` rule (tighter
than the line-scoped entry I first tried, which I removed in favour of theirs — a line-scoped pattern
would silence a real secret sharing the line). **`.gitleaks.toml` is deliberately NOT in my commit**;
it is theirs. Guard now: 0 findings, 15/15 negative controls caught.

---

## 8. Residual risk / not done

1. **Premature retirement is only detectable from what configuration declares.** A rotation that
   swaps the signing secret and leaves `GME_AUTH_JWT_ACTIVE_KEY_ACTIVATED_AT` stale is
   indistinguishable, to a stateless process, from one that has run on the same key for months — the
   process has no memory of which key it used yesterday. Closing that needs a **persisted key
   registry** (kid / first-seen / last-active, reconciled at boot), which is a Flyway migration and a
   larger build. Recorded as a scoped residual rather than approximated; the runbook compensates by
   making the timestamp part of step 1 and the guard states its own limitation in javadoc.
2. **Rotation for the internal-auth token, the RBAC stamp key and the webhook derivation root is not
   built** — §6. Deliberate.
3. **`ALLOW_HARD_CUTOVER` ships `true`** in `values.yaml`, `docker-compose.yml` and `run-fleet.ps1`,
   because a first install must come up and a dev fleet is always a first activation. A live
   environment that never flips it to `false` has the premature-retirement check disabled. The
   runbook's §4 makes flipping it the first post-install action; nothing mechanically enforces it,
   because nothing in the repo can tell a first install from the hundredth.
4. **Deploying this build is itself a hard cutover** for tokens minted by the previous build (they
   carry no `kid`). Bounded at one max TTL, documented, and pinned by test — but it is real, and a
   rollout during peak will refuse in-flight tokens for up to an hour.
5. **No live end-to-end confirmation.** No server, no Docker, no fleet, per the constraint. Every
   accept/reject claim is asserted at the helper and service boundary with real HMACs over real
   tokens; the compose/Helm/fleet claims are from PyYAML and PowerShell parses plus the wiring
   checker. `GET /internal/auth/token/keys` is asserted at the service layer
   (`keySetStatusIsTheRotationEvidence`), not over HTTP.
6. **The `kid` is derived from the secret, so a `kid` is a stable oracle for "is this the same key as
   before".** Anyone who can read token headers across a rotation learns that the key changed — and
   that a key came *back* if one ever did. That is intended (it is what makes verification possible)
   and discloses nothing about the material, but it is worth stating rather than discovering.
7. **The accepted set is unbounded.** Nothing stops an operator accumulating ten predecessors; they
   would all be WARNed as overdue, but the service still boots. A cap was considered and rejected as
   arbitrary — the WARN plus the runbook is the control.
