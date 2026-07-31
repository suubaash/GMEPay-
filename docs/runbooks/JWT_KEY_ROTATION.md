# Runbook — rotating the platform JWT signing key

**Scope: one secret.** `GME_AUTH_JWT_SIGNING_SECRET`, the HS256 key `auth-identity` signs platform
capability tokens with. Every other secret in the fleet has a different lifecycle and is **not**
covered here — see [§7](#7-what-this-runbook-does-not-cover).

**Why this key needs a procedure at all.** HS256 is symmetric: the signing key *is* the verification
key, so whoever holds it can mint a token for any subject with any claims. There is no asymmetric
split to fall back on and no external authority to revoke against. Until gap-register item **T0-6**
was closed, this key was also un-versioned, which meant replacing it was instantaneous and total —
every live token died the moment the new value reached the process. So the cost of reacting to a
*suspicion* was an outage, and in practice suspicions do not get acted on.

---

## 1. The model

`auth-identity` holds a **key set**, not a key:

| Role | Property | What it does |
|---|---|---|
| **ACTIVE** | `gme.auth.jwt.signing-secret` (`GME_AUTH_JWT_SIGNING_SECRET`) | signs new tokens, and verifies its own |
| **ACCEPTED** | `gme.auth.jwt.previous-keys` (`GME_AUTH_JWT_PREVIOUS_KEYS`) | verifies only — previously active keys, kept until their tokens expire |

Every token header carries a **`kid`**, and verification selects the key by it:

```json
{"alg":"HS256","typ":"JWT","kid":"gmek_4c1f0b7a92d8e653"}
```

Three properties follow, and each one is load-bearing:

- **The `kid` is derived, never configured.** It is a truncated, domain-separated SHA-256 of the
  secret (`JwtKeySet.kidFor`). You cannot set it, so it cannot point at the wrong key, and the same
  secret produces the same `kid` in every replica and environment. It carries no key material — it
  is already published in the header of every token.
- **An unknown `kid` is rejected outright.** Verification never falls back to trying the other keys.
  A fallback would make the `kid` decorative (a retired key could still be honoured by a token
  naming a live one) and would let an unauthenticated caller cost the service one HMAC per
  configured key per bad token.
- **A token with *no* `kid` is rejected.** Pinned decision, not an oversight — see
  [§6](#6-pinned-decisions).

**Every key in the set — accepted ones included — must clear the same T0-6 bar**: at least 32 bytes,
not placeholder-shaped, and never a value that has been published in this repository. An accepted
key is live signing material for as long as it is accepted, so a weak key parked in
`GME_AUTH_JWT_PREVIOUS_KEYS` is exactly as dangerous as one in the active slot. The service refuses
to start on any violation.

### Configuration format

`GME_AUTH_JWT_PREVIOUS_KEYS` is a list of `<secret>@<ISO-8601 instant it stopped signing>`, entries
separated by `;` or newlines:

```
GME_AUTH_JWT_PREVIOUS_KEYS="e1f0…c3@2026-07-28T09:00:00Z;9ab7…21@2026-06-14T09:00:00Z"
```

The timestamp is **mandatory**. It is the only input from which "when is this key safe to delete"
can be computed (`demoted-at + gme.auth.jwt.max-token-ttl-seconds`), so an undated entry fails the
boot rather than being guessed at.

Two more, neither of them secret:

| Variable | Meaning |
|---|---|
| `GME_AUTH_JWT_ACTIVE_KEY_ACTIVATED_AT` | when the **active** key started signing (ISO-8601 instant) |
| `GME_AUTH_JWT_ALLOW_HARD_CUTOVER` | declares that losing every live token is intended |

If `ACTIVATED_AT` is inside the last max-TTL **and** `PREVIOUS_KEYS` is empty, the service **refuses
to start**: that combination is a rotation that dropped the outgoing key in the same step it
promoted the new one, and every token minted before the switch has just died.
`ALLOW_HARD_CUTOVER=true` is how you say you meant it — correct for a first deployment and for a
compromise response, and for nothing else.

---

## 2. Scheduled rotation — the sequence

Rotate on a schedule (quarterly is a reasonable default), and always in **three separate steps**.
Doing steps 1 and 3 in one change is precisely the mistake the startup check exists to catch.

### Step 1 — generate and promote

```bash
NEW_KEY="$(openssl rand -hex 32)"
OLD_KEY="<the value GME_AUTH_JWT_SIGNING_SECRET has right now>"
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
```

Set, in one deployment change:

```
GME_AUTH_JWT_SIGNING_SECRET=$NEW_KEY
GME_AUTH_JWT_PREVIOUS_KEYS="$OLD_KEY@$NOW"        # append if entries already exist
GME_AUTH_JWT_ACTIVE_KEY_ACTIVATED_AT=$NOW
GME_AUTH_JWT_ALLOW_HARD_CUTOVER=false
```

Roll `auth-identity`. From this instant new tokens are signed with `$NEW_KEY`; tokens already in
flight keep verifying against `$OLD_KEY`.

> **Only `auth-identity` needs the key.** Nothing else in the fleet reads it — the platform JWT is
> minted and verified in one service, behind `POST /internal/auth/token/{issue,verify}`. No other
> deployment has to be touched, and no other service has to be restarted in step with it.

### Step 2 — wait out the overlap

Wait **at least `gme.auth.jwt.max-token-ttl-seconds`** (default `3600`, i.e. one hour) measured from
the last pod that was still signing with the old key. In practice: wait one hour after the rollout
*completes*, not after it starts.

If any consumer caches tokens beyond their `exp` — none does today — the wait is the longer of the
two. Adding margin is free; the only cost of waiting is that one extra copy of retired signing
material stays in the manifest.

### Step 3 — retire

Remove the entry from `GME_AUTH_JWT_PREVIOUS_KEYS` (back to `""` if it was the only one) and roll
again. Then delete the old value from the secret store.

`auth-identity` **WARNs** on every start about any accepted key kept a full extra TTL past its safe
point:

```
T0-6 JWT key gmek_… is OVERDUE FOR RETIREMENT: it stopped signing at … and no token it signed
can have been live since …. Every extra day it stays in GME_AUTH_JWT_PREVIOUS_KEYS is another
day a leaked copy of it still mints valid tokens. Remove the entry.
```

This is a warning and not a refusal on purpose: taking the service down over a forgotten
configuration line would be worse than the risk it flags, and an operator may be holding the key
deliberately during an incident.

---

## 3. Verifying the rotation took

Rotation is only an operation if it can be checked. Three levels, cheapest first.

**a. The startup log.** Every boot prints the set:

```
T0-6 JWT key set: active kid=gmek_4c1f0b7a92d8e653 (1 accepted predecessor(s), max token TTL 3600s)
T0-6 JWT key gmek_9d2e… (demoted 2026-07-28T09:00:00Z) is accepted for verification only;
     safe to remove after 2026-07-28T10:00:00Z.
```

**b. The key-set endpoint** — `GET /internal/auth/token/keys` (behind `X-Gme-Internal` like
everything else this service exposes):

```bash
curl -sS -H "X-Gme-Internal: $GMEPAY_INTERNAL_AUTH_SECRET" \
     http://auth-identity:8080/internal/auth/token/keys
```

```json
{
  "activeKid": "gmek_4c1f0b7a92d8e653",
  "maxTokenTtlSeconds": 3600,
  "keys": [
    {"kid":"gmek_4c1f0b7a92d8e653","role":"ACTIVE","demotedAt":null,"safeToRemoveAfter":null,
     "safeToRemoveNow":false,"overdueForRemoval":false},
    {"kid":"gmek_9d2e1f0a3b4c5d6e","role":"ACCEPTED","demotedAt":"2026-07-28T09:00:00Z",
     "safeToRemoveAfter":"2026-07-28T10:00:00Z","safeToRemoveNow":false,"overdueForRemoval":false}
  ]
}
```

It reports what the **process** is signing with, read from the live helper — not what a manifest
says it should be. **Query every replica.** A pod that missed the rollout shows a different
`activeKid`, which is the failure this check exists to find. Do not proceed to step 3 until every
replica agrees.

**c. End to end.** Mint a token and decode its header; the `kid` must be the new `activeKid`. Then
present a token minted *before* the rotation to `POST /internal/auth/token/verify` — it must still
answer `valid: true` for the whole overlap window. If it answers `INVALID_TOKEN`, the old key is not
in the accepted set and step 1 was done wrong: put it back immediately.

**Watch the audit trail.** `TOKEN_VERIFY_FAILED` rows carry a `reason`, and the two failures mean
opposite things:

| `reason` | Diagnosis |
|---|---|
| `UNKNOWN_KID` | the token names a key this process does not hold — **a key was retired while its tokens were still live**, or a replica has a stale key set |
| `INVALID_TOKEN` | the signature did not verify — a **forgery attempt**, or corruption |
| `EXPIRED_TOKEN` | routine |

A burst of `UNKNOWN_KID` right after a rotation is the rotation going wrong. A burst of
`INVALID_TOKEN` is somebody attacking. On the wire both `UNKNOWN_KID` and `INVALID_TOKEN` are
answered `INVALID_TOKEN` — which keys the service holds is not something a caller enumerates one
probe at a time — so the audit trail is where the distinction lives.

---

## 4. First deployment

There is no outgoing key, so there is nothing to overlap with:

```
GME_AUTH_JWT_SIGNING_SECRET=$(openssl rand -hex 32)
GME_AUTH_JWT_PREVIOUS_KEYS=""
GME_AUTH_JWT_ACTIVE_KEY_ACTIVATED_AT=<now>
GME_AUTH_JWT_ALLOW_HARD_CUTOVER=true
```

**Set `ALLOW_HARD_CUTOVER=false` in the next change**, once the platform is live. It ships `true` in
`values.yaml` and `docker-compose.yml` so a first install comes up; leaving it `true` in a live
environment disables the one check that catches a botched rotation.

---

## 5. Suspected compromise

The key is a bearer credential for *every identity on the platform*. If you believe a copy has
leaked — a secret store breach, a key in a log or a ticket, a laptop, an ex-operator with the value
— assume tokens are already being forged.

**Decide first: graceful or hard?**

| | Graceful (§2) | Hard cutover |
|---|---|---|
| Old key | still accepted for up to one hour | rejected immediately |
| Live sessions | survive | all die |
| Forged tokens | **also survive for up to an hour** | die immediately |

A graceful rotation does not contain a compromise; it just changes what new tokens are signed with.
**If you actually believe the key leaked, do the hard cutover.** The cost is one hour of pain you
were going to pay anyway.

```
GME_AUTH_JWT_SIGNING_SECRET=$(openssl rand -hex 32)   # a NEW key, from a clean machine
GME_AUTH_JWT_PREVIOUS_KEYS=""                          # the leaked key is NOT kept
GME_AUTH_JWT_ACTIVE_KEY_ACTIVATED_AT=<now>
GME_AUTH_JWT_ALLOW_HARD_CUTOVER=true                   # you mean it
```

Roll `auth-identity`. Every token minted under the old key is dead the moment the last pod restarts.

Then:

1. **Set `ALLOW_HARD_CUTOVER=false` again** in the following change. It is an incident flag, not a
   setting.
2. **Never put the leaked key back** into `GME_AUTH_JWT_PREVIOUS_KEYS`, whatever breaks. That would
   re-arm every forged token.
3. Add the leaked value to `JwtSigningKeyEnforcedConfig.PUBLISHED_KEYS` **if it ever touched the
   repository**, so re-introducing it is a boot failure rather than a review comment.
4. Sweep the audit trail for `TOKEN_ISSUED` rows with subjects or claim-key sets you cannot account
   for, and for `TOKEN_VERIFY_FAILED` / `UNKNOWN_KID` rows after the cutover — those are the forged
   tokens still being presented, and their `tokenFingerprint` ties them to whether this service ever
   minted them.
5. Rotate the **internal-auth secret** (`GMEPAY_INTERNAL_AUTH_SECRET`) too if the same store was
   breached: it gates `POST /internal/auth/token/issue`, so holding it means being able to *mint*
   tokens rather than merely forge them. That rotation is a different problem — see §7.

---

## 6. Pinned decisions

**A token with no `kid` is rejected.** Everything this platform mints carries one, so an un-kidded
token is either issued by a build older than T0-6 or not minted here at all. The first case is
bounded and self-clearing — those tokens are at most one max-TTL old — so upgrading to the versioned
build costs exactly one max-TTL window in which pre-upgrade tokens are refused, identical to a
rotation with no overlap. **Plan the T0-6 upgrade rollout as a hard cutover.** The alternative,
falling back to the active key when no `kid` is present, would leave an un-versioned verification
path permanently open, and a compatibility switch for it would be the switch nobody ever turns off.

**The `kid` is derived, not chosen.** See §1. An operator-chosen label buys readability and costs a
whole class of misconfiguration in which the label and the material disagree.

**Retiring too early is a refusal; retiring too late is a warning.** The first destroys live
sessions and is always a mistake. The second is untidy and sometimes deliberate.

---

## 7. What this runbook does NOT cover

Three other secrets are commonly confused with this one. None of them rotates by this procedure.

| Secret | Minted by | Verified by | Why it is a different problem |
|---|---|---|---|
| **Keycloak / OIDC operator tokens** | Keycloak | api-gateway, ops-partner-bff (`spring-security-oauth2-resource-server`) | A completely separate trust path: RS256, **asymmetric**, keys published at the realm JWKS endpoint and already `kid`-versioned by Keycloak. Resource servers refresh JWKS on an unknown `kid`, so rotation is a Keycloak-side operation with no GME-side config change at all. Nothing in this runbook applies. |
| **`GMEPAY_INTERNAL_AUTH_SECRET`** (`X-Gme-Internal`) | nobody — a shared static token | 11 services, all of them | **Symmetric and shared by every participant simultaneously.** There is no per-message `kid` to version, so an overlap window means every verifier accepting a *set* of tokens while every caller migrates — a fleet-wide coordinated change across 11 deployments, not one. Doing it properly needs an accepted-set on the verifier side of `InternalAuthFilter` plus a caller-side switchover order. Scoped separately. |
| **`GMEPAY_WEBHOOK_SIGNING_SECRET`** (HKDF derivation root) | `notification-webhook` derives a per-endpoint secret from it | **partners**, outside our infrastructure | Rotating the root re-derives **every partner's** endpoint secret at once. The verifiers are third parties who must each redeploy, so the overlap window is measured in partner release cycles and needs dual-signature delivery (both the old and new signature on each webhook) plus per-partner migration tracking. `WebhookEndpointProvisioningService` already rotates a *single* endpoint's secret; the root is the unsolved part. Scoped separately. |

Also out of scope: the RBAC claim-stamping secret (`GMEPAY_RBAC_SECRET`, shared gateway↔services,
same shape as the internal-auth token) and the two scheme RSA key pairs (partner-coordinated).

Half-rotating several secret types would be worse than rotating one properly. Each of the above is
recorded as its own item in `Documentation/GAP_REGISTER.md` under T0-6.
