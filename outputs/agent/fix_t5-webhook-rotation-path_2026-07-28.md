> 작업: T5-8 webhook rotation path / 출처: agent

# T5-8 — Making webhook secret rotation reachable, and the dead endpoints visible

## 0. Summary

T5-4 was right and left a hole. It replaced one shared webhook HMAC key with per-endpoint
HKDF-derived secrets and made the dispatcher **verify the re-derived secret against the row's
`signing_secret_hash` before signing**. Correct — but it silently split the endpoint population:
rows minted after the change sign, rows minted before it can *never* be re-derived (their
plaintext was revealed once at activation and was never stored anywhere), so their deliveries
now refuse to sign. The remedy existed —
`POST /v1/webhooks/endpoints/{id}/rotate-secret` — and had **no caller in any service or SPA**.
The only evidence a partner was receiving nothing was one ERROR line per delivery attempt.

Three things were built:

1. **A read that reports the rule** — `GET /v1/webhooks/endpoints/signing-health`, computed by
   the *same code* the dispatcher enforces, so the report and reality cannot drift.
2. **An operator path to rotation** — `POST /v1/admin/webhooks/endpoints/{id}/rotate-secret` on
   `ops-partner-bff`, RBAC-guarded, audited, one-time reveal, internal-auth on the wire.
3. **A UI that says why** — a `WebhookSecretPanel` on the partner Credentials tab whose copy
   names the cause and the required follow-up (tell the partner), not just "rotate".

Nothing auto-rotates. That is a decision, not an omission: see §5.

## 1. The rule, extracted once

Before this, "may we sign for this endpoint?" lived only inside
`DefaultWebhookTargetResolver.deriveAndVerify`. A separate report implementation would have been
a second copy of a security rule, free to drift from the one that actually gates delivery.

`services/notification-webhook/.../provisioning/WebhookSecretVerifier.java` (new) is now the
single implementation, and both callers use it:

| Caller | Uses it to |
|---|---|
| `DefaultWebhookTargetResolver` (unchanged behaviour) | decide whether to sign |
| `WebhookEndpointProvisioningService.signingHealth` (new) | decide what to *report* |

Static helpers taking the deriver as an argument — no Spring wiring, so neither collaborator's
constructor changed and `DefaultWebhookTargetResolverTest` (10 tests) passes untouched.

### The four states

`WebhookEndpointSigningStatus` (new) carries its own semantics rather than leaving the caller to
interpret a string:

| Status | `deliverable` | `fixableByRotation` | Meaning |
|---|---|---|---|
| `SIGNABLE` | true | false | derived secret matches the stored digest |
| `SECRET_NOT_DERIVABLE` | false | **true** | **the T5-8 population** — pre-derivation row, or minted while the root key was blank, or the root key changed |
| `NO_SECRET_DIGEST` | false | **true** | legacy V003 row, `signing_secret_hash IS NULL` (Vault-only) |
| `ROOT_KEY_MISSING` | false | **false** | no `gmepay.webhook.signing-secret` — nothing derives for *anyone* |

`ROOT_KEY_MISSING` is deliberately **not** "fixable by rotation": rotation is refused in that
state anyway (we never issue a secret the dispatcher could not reproduce), and telling an
operator to rotate would send them to fix the wrong thing. The UI renders it as a deployment
problem instead.

## 2. The read

`GET /v1/webhooks/endpoints/signing-health[?partnerId=]` → `WebhookEndpointSigningHealthView[]`.

- **Secret-free.** No plaintext, and **not the stored digest either** — a digest is a verifier,
  not a display value. Pinned: `reportLeaksNoSecretMaterial` asserts the rendered response
  contains neither `whsec_`, nor the row's digest, nor the root key.
- **Carries `createdAt`**, because the age of a row is what tells an operator "this is a
  pre-T5-4 endpoint" without them knowing the migration history.
- **Omitting `partnerId` is the platform-wide sweep** — that is how you find every endpoint that
  stopped delivering without already knowing which partners to ask about.
- New repository finders `findByActiveTrueOrderByIdAsc` / `findByPartnerIdAndActiveTrueOrderByIdAsc`
  (oldest first, so the broken rows surface at the top).

## 3. The operator path (ops-partner-bff)

`web/WebhookEndpointAdminController.java` (new), `/v1/admin/webhooks/endpoints`:

```
GET  /v1/admin/webhooks/endpoints[?partnerCode=]              — signing health
POST /v1/admin/webhooks/endpoints/{endpointId}/rotate-secret  — rotate, reveal once
```

**Authorization — two layers, both must pass.** `AdminSurfaceRbacInterceptor` already gates the
whole `/v1/admin/**` space (`requireAdminRead()` on GET, `requireAdminWrite()` on POST). The
rotate handler *additionally* calls `OpsRbacGuard.requireOps()`, the same fine-grained check
webhook-replay and platform-pause make — rotation invalidates a secret a live partner
integration is using, so the read-only HUB_OPERATOR grant set (`partner.view`, `txn.view`,
`report.generate`) must not be able to fire it. Pinned: 403 for that exact grant set, 403 for a
permission-less token, and in both cases **no audit row and no upstream call**.

**Audit before action.** `audit.recordDurable("webhook.endpoint.rotate_secret", endpointId,
actor, reason)` runs *before* the rotation; `recordDurable` is fail-closed, so an unwritable
audit trail means no rotation. The actor is `rbac.actor(principal)` — the **token subject wins**
over any caller-supplied `X-Gme-Principal-Id`, so an operator cannot rotate under someone else's
name.

**Partner scoping.** The Admin UI holds a business code; endpoints are keyed by
config-registry's numeric surrogate upstream, so `PartnerDirectory` bridges them. An
unresolvable code returns an **empty list**, never the platform-wide sweep — an id we cannot
resolve must not silently widen a partner-scoped read into a cross-partner one.

**`overlapMinutes`** is accepted as a number or numeric string; anything else is a **400, not a
silent default**. Quietly substituting 24 h for what the operator typed is not a decision the
BFF gets to make. Omitted ⇒ `null` ⇒ notification-webhook's own default applies.

### Internal auth on the wire

`RestWebhookOpsClient` gained `builderFor(baseUrl, internalSecret)` and now sends
`X-Gme-Internal` from `gmepay.internal-auth.secret`, exactly the way `RestSandboxKeyClient` /
`RestPrefundingClient` do after the convergence commit. Blank secret ⇒ **no header** + a WARN
(fail-closed once the upstream gate is armed, never a bypass attempt). The test binds
`MockRestServiceServer` to *the same builder the production constructor uses*, so it asserts the
real default-header wiring rather than a hand-built client.

### The stub refuses

`StubWebhookOpsClient.rotateEndpointSecret` throws **503** and
`endpointSigningHealth` returns an empty list with a WARN. A stub cannot mint a secret the
dispatcher would reproduce, so a fake success would hand the operator a value to give a partner
that could never verify a signature — strictly worse than the un-signable endpoint it claims to
fix. Likewise fabricated health rows would tell an operator "healthy" when nothing was checked,
which is the exact failure mode T5-8 exists to remove.

### Not logging the secret

`IssuedCredentialBundleLogMaskingFilter` now also matches `/rotate-secret`, so the one-time
`whsec_` response body is in the same non-loggable class as a rotated partner credential. The
REST client logs only endpoint id + status on failure paths, never a body.

## 4. Admin UI

`apps/admin-ui/src/app/partners/[id]/`:

- **`WebhookSecretPanel.jsx`** (new) — rendered in the **Credentials** tab beneath
  `CredentialRotationPanel`, because "what secrets does this partner hold" is one question to an
  operator even though the two rotate through different services.
- **`WebhookSecretRevealModal.jsx`** (new) — follows `OneTimeCredentialModal`'s pattern
  (read-only monospace field, copy button, explicit "I've copied this" acknowledgement) and adds
  the two facts specific to this rotation: **the partner must be told out of band**, and **the
  overlap deadline** (or, with no overlap, a warning that the previous secret is already dead).
- `partnerLifecycleSlice` gained `webhookEndpoints` / `rotatingEndpointId` /
  `webhookRotateResult` + `clearWebhookRotateResult`; the plaintext is dropped from the store
  the moment the reveal modal closes and is never persisted.
- `api/client.js` gained `getWebhookEndpointHealth` / `rotateWebhookEndpointSecret`.

**The copy is the point.** The task asked that an operator understand *why* they must act, so
the panel's error alert states the cause rather than the symptom:

> Webhook signing moved from one shared platform key to a per-endpoint secret. Endpoints
> registered **before that change cannot be signed for at all** — their original secret was
> revealed once and never stored, so it can never be reproduced, and GMEPay+ now refuses to sign
> with anything else rather than send a signature the partner cannot verify. Deliveries for these
> endpoints stay queued. **Rotate to fix it, then send the new secret to the partner.**

Two restraints in the UI worth naming: the alert appears **only when something is actually
rotatable** (a healthy partner is not told to go rotate things — pinned by a test), and
`ROOT_KEY_MISSING` renders as a separate deployment-fix warning with the rotate button
**disabled**.

## 5. Why there is no auto-rotation

The obvious "fix" is a migration that rotates every un-signable endpoint. It would be wrong.

Rotation replaces a secret the partner must hold to verify anything. GMEPay+ stores only a
digest and reveals plaintext exactly once, so **nothing in the platform can tell the partner what
their new secret is** — only a human can. An automatic rotation therefore converts "the partner
receives nothing, and we can see that" into "the partner receives events signed with a value
they were never given, and nobody is tracking that they need it". The second state is quieter and
worse.

So the read reports `fixableByRotation` and stops. Rotation stays an explicit operator action,
audited, with a one-time reveal and an overlap window whose deadline the operator is shown.

## 6. Task 4 — config-registry: verified NOT broken

**Newly-activated partners already get a derivable secret.** The activation chain is
config-registry `WebhookProvisioningService.provisionOnActivation` → `NotificationWebhookClient`
→ notification-webhook `POST /v1/webhooks/endpoints` → `register(...)`, and `register` derives
via `WebhookSecretDeriver` when a root key is configured. config-registry never mints webhook
secret material itself — it forwards what notification-webhook returned and stores the digest on
the V030 row. So the T5-4 change already covered the activation path, the T1-1 contract test is
untouched, and **no functional change was needed**. Verified green.

One caveat, which is in notification-webhook rather than config-registry: with a **blank root
key** `register(...)` deliberately falls back to a CSPRNG secret so activation does not
half-complete. That mints a fresh un-signable endpoint. Left as-is (changing it would break the
T1-1 contract's guarantee that activation always yields a secret), but it is no longer silent —
such a row now reports as `ROOT_KEY_MISSING` / `SECRET_NOT_DERIVABLE` in the health read.

The **only** config-registry edit is additive: `RestNotificationWebhookClient` now sends
`X-Gme-Internal` when `gmepay.internal-auth.secret` is set (blank ⇒ no header ⇒ today's exact
behaviour), so arming the gate below is a pure deployment change and can never break partner
activation.

## 7. Internal-auth gate on the provisioning surface — declared, default OFF

`/v1/webhooks/endpoints/**` mints and reveals `whsec_` plaintext, is machine-to-machine only,
and had **no gate of any kind** (notification-webhook has no security config and nothing fronts
it). It now declares the platform gate in `application.properties`:

```properties
gmepay.internal-auth.enabled=${GMEPAY_INTERNAL_AUTH_ENABLED:false}
gmepay.internal-auth.secret=${GMEPAY_INTERNAL_AUTH_SECRET:}
gmepay.internal-auth.path-patterns=/v1/webhooks/endpoints,/v1/webhooks/endpoints/**
```

**Default OFF, so this commit is behaviour-neutral.** Both callers already present the header, so
arming it is a deployment change only — see §9.1. `InternalAuthAutoConfiguration` fails the
service closed if `enabled=true` arrives without a secret, so the gate can never silently no-op.

## 8. Files changed

**`services/notification-webhook`** — new: `provisioning/WebhookSecretVerifier.java`,
`provisioning/WebhookEndpointSigningStatus.java`,
`provisioning/WebhookEndpointSigningHealthView.java`,
`test/provisioning/WebhookEndpointSigningHealthTest.java` (7 tests). Modified:
`provisioning/WebhookEndpointProvisioningService.java` (`signingHealth`),
`api/WebhookEndpointController.java` (`GET /signing-health`),
`persistence/WebhookEndpointRepository.java` (2 finders),
`dispatcher/DefaultWebhookTargetResolver.java` (delegates to the verifier — behaviour identical),
`application.properties` (internal-auth declaration).

**`services/ops-partner-bff`** — new: `web/WebhookEndpointAdminController.java`,
`test/web/WebhookEndpointAdminControllerTest.java` (8 tests),
`test/client/rest/RestWebhookOpsClientEndpointSecretTest.java` (7 tests). Modified:
`client/WebhookOpsClient.java` (+2 methods, +2 records),
`client/rest/RestWebhookOpsClient.java` (`builderFor` + `X-Gme-Internal` + both impls),
`client/stub/StubWebhookOpsClient.java` (refuses to rotate),
`config/IssuedCredentialBundleLogMaskingFilter.java` (`/rotate-secret`),
`application.properties` (docs on the `webhook-ops` selector).

**`services/config-registry`** — modified: `client/RestNotificationWebhookClient.java`
(`builderFor` + outbound `X-Gme-Internal`). No functional change; see §6.

**`apps/admin-ui`** — new: `src/app/partners/[id]/WebhookSecretPanel.jsx`,
`src/app/partners/[id]/WebhookSecretRevealModal.jsx`,
`src/app/partners/[id]/__tests__/WebhookSecretPanel.test.jsx` (7 tests). Modified:
`src/app/partners/[id]/page.jsx` (Credentials tab), `src/store/partnerLifecycleSlice.js`,
`src/api/client.js`.

**`Documentation/GAP_REGISTER.md`** — T5-8 closed; T5-4 and T5-9 cross-referenced.

## 9. Still open

### 9.1 Deployment asks (files owned by another agent — NOT edited here)

1. **`GMEPAY_WEBHOOK_OPS_CLIENT=rest` on every target that has an Admin UI.** Without it the BFF
   wires `StubWebhookOpsClient`: the panel reports **no endpoints** and rotation returns 503. It
   never fabricates — but an operator would also never see the broken rows. Needed in
   `docker-compose.yml` (ops-partner-bff) and `deploy/helm/gmepay/values*.yaml`.
2. **`GMEPAY_INTERNAL_AUTH_SECRET` on notification-webhook**, plus
   `GMEPAY_INTERNAL_AUTH_ENABLED=true`, to actually arm §7's gate. notification-webhook currently
   receives **neither** in `docker-compose.yml` nor in `values.yaml`'s
   `envSecretKeys` (it lists only the datasource pair + `GMEPAY_WEBHOOK_SIGNING_SECRET`). Until
   then its provisioning surface — the one that mints `whsec_` — is reachable by anything with
   in-cluster network access. **This is the most security-significant residual item here.**

### 9.2 The partner-communication task (T5-9, not code)

Per T5-9 the old global secret was never the `whsec_` value handed to partners, so **no partner
has ever verified a GMEPay+ webhook**. The machinery to fix that now exists; the work does not.
Each existing endpoint must be rotated and its new secret delivered to the partner, and until
that has happened for a given partner, "webhooks verified end-to-end" stays unproven for them.
Nothing in this change proves a partner has ever verified one of our signatures — only that we
now sign with a value they *could* verify, and that we can see when we cannot sign at all.

### 9.3 Smaller residuals

- **No bulk rotate.** Deliberate at demo scale (rotation is per-partner communication anyway),
  but a platform with hundreds of pre-T5-4 endpoints would want a worklist rather than a
  per-partner page. The platform-wide `GET /signing-health` already returns the worklist; only
  the UI is per-partner.
- **No alert on `deliverable: false`.** The data is now readable but nothing pages on it — an
  endpoint can still sit dead until someone opens the tab. Wiring it into
  `WebhookBacklogMonitor` / the ops alert lane is the natural follow-up.
- **Blank-root-key registration still mints an un-signable endpoint** (§6) — reported now, not
  prevented.
- **Rotating the *root* key still re-keys every endpoint.** Per-endpoint `secret_generation` is
  versioned; the root is not. Unchanged from T5-4's residual list (CISO §11's "no `kid`").

## 10. Test results

| Module | Result |
|---|---|
| `:services:notification-webhook:test` | **123 tests, 0 failures** (was 116; +7) |
| `:services:ops-partner-bff:test` | **433 tests, 0 failures** (was 418; +15) |
| `:services:config-registry:test` | **BUILD SUCCESSFUL** — T1-1 activation contract green |
| `gradlew testClasses` | **BUILD SUCCESSFUL** |
| `apps/admin-ui` `next build` | **succeeded** |
| `apps/admin-ui` vitest (from a real copy) | **91 files / 799 tests passed, 0 failed** |

The pre-existing wizard `userEvent` timeouts did not reproduce in this run; either way no new
failures were introduced.

Every behaviour the task asked to prove is pinned: rotating through the BFF **requires
`ops:operate`** (403 without it, with no audit row and no upstream call); the rotated secret
**verifies against what notification-webhook stored** (`SigningSecrets.matches(revealed,
row.signing_secret_hash)`) *and* equals the independently derived value for the new generation;
a **pre-existing undeliverable endpoint is reported as such and becomes deliverable after
rotation** (`SECRET_NOT_DERIVABLE` → rotate → `SIGNABLE`, generation 1 → 2); and the T1-1
activation contract test is untouched and green.
