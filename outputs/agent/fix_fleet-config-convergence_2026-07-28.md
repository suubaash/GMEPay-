> 작업: fleet config convergence / 출처: agent

# Fleet config convergence — restoring startup after the fail-closed hardening

The T0-2 / T0-5 hardening made four services **refuse to boot** without
`GMEPAY_INTERNAL_AUTH_SECRET` and made every gated edge answer **401** to a caller that omits it —
but each fix agent was scoped out of the deployment manifests, so **no manifest carried the secret**.
The fleet could not start. This change closes that, plus the T1-2 `OIDC_ISSUER_URI` residual and the
three client-side token propagations that were still missing.

Nothing was started: no Docker, no Keycloak, no fleet, no server. Static validation only.

---

## 1. The env-var matrix — `GMEPAY_INTERNAL_AUTH_SECRET`

**11 services**, derived from the code rather than from the briefing list (see §4). "compose" =
`docker-compose.yml`; "Helm" = `deploy/helm/gmepay/values.yaml` (`envSecretKeys` / `envSecretAliases`
— the three overlays inherit, verified below); "fleet" = `run-fleet.ps1`.

| Service | Role | compose | Helm | fleet | Was | Missing ⇒ |
|---|---|---|---|---|---|---|
| `auth-identity` | **gate, boot-critical** | ✅ | ✅ | ✅ **added** | compose+Helm only | **refuses to start** |
| `prefunding` | **gate, boot-critical** | ✅ **added** | ✅ **added** | ✅ **added** | nowhere | **refuses to start** |
| `scheme-adapter-zeropay` | **gate, boot-critical** | ✅ **added** | ✅ **added** | ✅ **added** | nowhere | **refuses to start** |
| `rate-fx` | **gate, boot-critical** | ✅ **added** | ✅ **added** | ✅ **added** | nowhere | **refuses to start** |
| `payment-executor` | caller (prefunding + ZeroPay) + gates own `/v1/balance` | ✅ **added** | ✅ **added** | ✅ **added** | nowhere | boots, **every payment declines** |
| `config-registry` | caller (auth-identity keys, prefunding credit-limit) | ✅ **added**¹ | ✅ **added**¹ | ✅ **added** | alias only | activation 502 / credit-limit 401 |
| `qr-service` | caller (prefunding CPM reserve/release) | ✅ **added** | ✅ **added** | ✅ **added** | nowhere | **CPM issuance declines** |
| `ops-partner-bff` | caller (auth-identity RBAC/approvals/keys, prefunding balance) | ✅ **added**¹ | ✅ **added**¹ | ✅ **added** | alias only | RBAC + balance panels 401 |
| `settlement-reconciliation` | caller (ZeroPay registration-status) | ✅ **added** | ✅ **added** | ✅ **added** | nowhere | fails CLOSED ⇒ **settlement blocked** |
| `api-gateway` | caller (auth-identity `/v1/rbac/resolve`) | ✅ | ✅ | ✅ **added** | compose+Helm only | RBAC claim resolution fails |
| `transaction-mgmt` | gates introspection; boot-critical **iff** devtools on | ✅ **added** | ✅ **added** | ✅ **added** | nowhere | introspection stays anonymous |

¹ `config-registry` and `ops-partner-bff` already had `GMEPAY_AUTH_IDENTITY_INTERNAL_SECRET` aliased
to the same secret. That alias only satisfies `gmepay.auth-identity.internal-secret` — Spring's
relaxed binding cannot map it onto `gmepay.internal-auth.secret`, which is the property their
*prefunding* clients read. Both keys are now present; the checker asserts the env var appears under
its **own** name, not merely as an alias.

**`OIDC_ISSUER_URI`:** compose ✅ (bff + gateway, via `KC_PUBLIC_URL`), Helm ✅ (all 4 values files),
`run-fleet.ps1` ✅ **added** → `http://localhost:8097/realms/gmepay`. This was the T1-2 residual: the
Java default is still `:8090`, which is `scheme-adapter-zeropay`'s port here, so a host-run
`ops-partner-bff` built a decoder against a non-Keycloak and 401'd everything.

**Not needed** (verified, not assumed): `smart-router`, `merchant-qr-data`, `revenue-ledger`,
`reporting-compliance`, `kyb-adapter`, `notification-webhook`, `scheme-adapter-nepal`,
`scheme-adapter-sendmn`, `scheme-adapter-ninepay`. None gates anything, and none sends the header —
`payment-executor`'s `SendmnRestSchemeClient` / `NepalRestSchemeClient` do not, because those
adapters are still ungated (T0-2 follow-up (c), unchanged here).

---

## 2. The secret-handling decision

**Compose — one anchor, keeping the repo's existing dev-default idiom.** A new top-level
`x-internal-auth-secret: &internal-auth-secret ${GMEPAY_INTERNAL_AUTH_SECRET:-dev-internal-svc-secret-not-for-prod}`,
referenced by all 11 service blocks (the file already uses this anchor idiom for `*tcp-health`).

Why not each of the three obvious alternatives:

- **Repeating the literal 11×** is what the naive reading of "add it per service" implies, and it is
  what makes T0-6 worse: the checked-in dev credential would go from 4 sites to 11. The `:-` fallback
  itself is legitimate — the repo already does exactly this for the sibling secret
  `GMEPAY_RBAC_SECRET` (`${GMEPAY_RBAC_SECRET:-dev-rbac-edge-secret-not-for-prod}`), which is the
  precondition the brief set — but the literal should exist **once**. It now does, and a checker
  assertion fails if anyone re-inlines it (`the dev-default literal appears exactly once`).
- **`${GMEPAY_INTERNAL_AUTH_SECRET:?…}` (required)** was rejected: it silently changes behaviour for
  the 4 services that already worked, breaks `docker compose config` and the CI `compose-smoke` job
  for anyone who has not exported it, and destroys out-of-the-box local dev — a bigger change than
  this gap needs. It is now a **one-line** future change, which is the point of the anchor.
- **Removing the default entirely** has the same effect as the above without the clear error message.

**Helm — the chart's existing placeholder mechanism, untouched.** `GMEPAY_INTERNAL_AUTH_SECRET` was
already declared in `secrets.data` as `CHANGE_ME_INTERNAL_SVC_SECRET` (overlays:
`REPLACE_FROM_SECRETS_MANAGER` / `REPLACE_FROM_KEY_VAULT` / `REPLACE_WITH_INTERNAL_SECRET`). All I did
was list the key in each service's `envSecretKeys`, which is how `_deployment.tpl` surfaces a Secret
key as env. **No working credential is added to Helm at any layer** — leaving the placeholder as-is
makes the whole fleet fail closed, which is correct.

**run-fleet.ps1 — one env export, not per-service CLI args.** `$env:GMEPAY_INTERNAL_AUTH_SECRET` is
resolved once (from the operator's environment, else the same dev literal, with a yellow warning) and
`Start-Process` children inherit it. Deliberate: each service reads a *different property name* off
the same variable (`gmepay.internal-auth.secret`, `gmepay.auth-identity.internal-secret`,
`spring.security.oauth2.resourceserver.jwt.issuer-uri`), so a per-service `--property=value` list
would be three lists to keep in sync and would silently rot as services are added. Same for
`OIDC_ISSUER_URI`.

**e2e-tests — a self-describing test fixture.** `SchemeFleet.INTERNAL_SECRET =
"e2e-fixture-internal-token-not-a-deployment-secret"`, injected as env into every launched component
*before* the per-test env map (so a test can still override it deliberately), and presented on every
`get`/`post`. Test source set only, never packaged. Same convention the T0-2/T0-5 fixes used.

---

## 3. What an operator must export

Documented in **both** places the repo already uses: `docs/COMPOSE.md` (new
§"Internal-auth secret", with the full matrix + the why-it-is-not-optional table) and the
`run-fleet.ps1` `.NOTES` header.

```bash
# compose (any shared or tunnelled host: REQUIRED, not optional)
export GMEPAY_INTERNAL_AUTH_SECRET="$(openssl rand -hex 32)"
export GMEPAY_RBAC_SECRET="$(openssl rand -hex 32)"
export KC_PUBLIC_URL=https://auth.example.com     # only if Keycloak is not on localhost
docker compose --profile core up --build
```

```powershell
# host fleet
$env:GMEPAY_INTERNAL_AUTH_SECRET = '<random 32+ bytes>'
$env:OIDC_ISSUER_URI             = 'http://localhost:8097/realms/gmepay'
.\run-fleet.ps1
```

Helm: set `secrets.data.GMEPAY_INTERNAL_AUTH_SECRET` (or point the overlay's placeholder at Secrets
Manager / Key Vault). Must be the **same value for all 11 services** — they are two sides of the same
edges. Also: never set `GMEPAY_DEVTOOLS_ENABLED` or `GMEPAY_SANDBOX_E2E_ENABLED` anywhere shared, and
note `GMEPAY_INTERNAL_AUTH_ENABLED` is **no longer read by any service** (the gate is pinned on).

---

## 4. Client-side token propagation (the three — actually four — missing)

| Where | Call | Fix |
|---|---|---|
| `ops-partner-bff/client/rest/RestPrefundingClient` | `GET /v1/prefunding/{code}/{balance,alerts}` | `builderFor(baseUrl, secret)` + `defaultHeader`, reading `gmepay.internal-auth.secret` (newly declared in the module's `application.properties`) |
| `ops-partner-bff/client/rest/RestSandboxKeyClient` | `POST/GET /internal/auth/keys` | **not in the brief.** The rollout report asserted this client "already sends the token"; it did not — only `Rest{ApprovalQueue,RbacAdmin,OperatorActionAudit}Client` do. Self-serve SANDBOX key issuance would have 401'd. Now reads `gmepay.auth-identity.internal-secret`, matching its siblings |
| `settlement-reconciliation/client/RestRegistrationStatusClient` | `GET /internal/scheme/zeropay/registration-status` | same pattern, reading `gmepay.internal-auth.secret` (newly declared in `application.yml`) |

All three mirror `payment-executor`'s `RestPrefundingClient`: a package-private static
`builderFor(...)` so a test can bind `MockRestServiceServer` to **the very same builder the production
constructor uses** (rather than trusting a hand-built client), a `defaultHeader` when the secret is
non-blank, and a startup WARN + **no header** when blank — fail-closed, never a fabricated credential.
9 new tests, including `headerDoesNotExist` blank-secret cases.

### Incidental find: the §8.2 settlement registration gate was inert in every environment

While wiring the registration-status token I found `RestRegistrationStatusClient` read
`settlement.clients.scheme-adapter-zeropay.{base-url,enabled}` — a namespace **no config file in the
repo declares**. Every sibling client in that module spells `gmepay.clients.*`, and
`application.yml` declares `gmepay.clients.scheme-adapter-zeropay.*`, fed by the
`SCHEME_ADAPTER_ZEROPAY_URL` / `SCHEME_ADAPTER_ZEROPAY_ENABLED` env vars that compose and Helm set
**specifically to arm this prerequisite**. So the flag resolved a property nothing read,
`PermissiveRegistrationStatusAdapter` won everywhere, and ZP0061/ZP0063 could be generated without
ZP0011 transmitted + ZP0012 received. The class had **no tests at all**. Namespace aligned to
`gmepay.clients.*`; 4 tests added, including the 401-fails-CLOSED case.

---

## 5. e2e-tests

- `SchemeFleet` — `INTERNAL_SECRET` / `INTERNAL_HEADER` / `INTERNAL_AUTH_ENV` constants; the env goes
  into `pb.environment()` before the per-test map; `get`/`post` always send the header.
- `PartnerOnboardingE2ETest` and `WalletScanPayE2ETest` have their **own** launchers and HTTP helpers
  (they predate `SchemeFleet`); both now inject `SchemeFleet.INTERNAL_AUTH_ENV` and send the header.
  Without this they fail at *boot*, not at assert: `PartnerOnboarding` launches `auth-identity` +
  `prefunding` and `WalletScanPay` launches `scheme-adapter-zeropay` — all fail-closed.
- `SendmnHubThroughE2ETest`, `SendmnAdapterSimE2ETest`, `NinepayPayoutE2ETest` go through
  `SchemeFleet` and are covered transitively.

Sending the header unconditionally is safe: an ungated route ignores it. Not executed — these suites
start real JVMs, which the no-servers constraint forbids; they compile (`:e2e-tests:testClasses`).

---

## 6. Verification (static only)

| Check | Result |
|---|---|
| PyYAML parse: `docker-compose.yml` + 4 Helm values + `Chart.yaml` | ✅ all parse |
| PowerShell `Parser::ParseFile` on `run-fleet.ps1` | ✅ 0 errors (2072 tokens) |
| `node docker/keycloak/check-topology.mjs` | ✅ **101/101** (99 + 2 new run-fleet issuer assertions) |
| **`python scripts/check_internal_auth_wiring.py`** (new) | ✅ **67/67** |
| `gradlew :services:ops-partner-bff:test :services:settlement-reconciliation:test` | ✅ **371 / 168**, 0 failures, 0 errors, 0 skipped |
| `gradlew testClasses` (whole repo, incl. `:e2e-tests`) | ✅ BUILD SUCCESSFUL |

`scripts/check_internal_auth_wiring.py` is the programmatic assertion the brief asked for, and it
carries **no hardcoded service list** — that is the point. It derives the requirement from the code:
a service needs the secret if its shipped `application.{properties,yml}` resolves a property from
`${GMEPAY_INTERNAL_AUTH_SECRET…}`; it is BOOT-CRITICAL if its main sources contain a fail-closed
guard (`refuses to start`); and it is a CALLER if its main sources put
`InternalAuthHeaders.INTERNAL_TOKEN` on an outbound request. It then asserts each one is present in
compose, base Helm (under its own name, not only an alias), and that `run-fleet.ps1` exports it; that
the overlays keep a `REPLACE_*` placeholder and never clobber `envSecretKeys` with a list override
(Helm replaces lists, merges maps — a real trap); that the compose dev literal appears exactly once;
that a caller always declares a secret property (otherwise no manifest can fix it); and the e2e
launcher/header wiring. **A future service that arms the gate but is not wired fails this check
automatically.**

Also fixed: two stale statements in `docs/COMPOSE.md` — the profile table still listed
`auth-identity` as `full`-only (T1-1 promoted it to `core`).

---

## 7. Still unwired / out of scope (nothing here is claimed done)

1. **`gmepay-test-platform` does not send the header.** Its `src/usecases/features.ts` calls
   `/internal/scheme/zeropay/{submit,cpm,cancel,health}` and the prefunding API directly; those cases
   will 401. It is a separate Node/TS project (`D:\gmepay-test-platform` + a copy in-repo) and was
   outside this brief. T0-5 follow-up (f) now records e2e-tests done, this one open.
2. **`ops-partner-bff`'s `RestPrefundingClient` is never instantiated in any environment.**
   `gmepay.prefunding.client` / `GMEPAY_PREFUNDING_CLIENT` is set **nowhere in the repo**, so
   `StubPrefundingClient` always wins and the partner-balance panel is fixture-backed — while every
   *other* upstream in the same compose/Helm block is switched to `rest`. My token fix there is
   therefore correct but **latent**. Deliberately not flipped: activating a live data path is a
   product decision (T1-3 territory — a fresh partner has no prefunding row), not config convergence.
   Flagged as a follow-up task.
3. **`run-fleet.ps1` passes `--spring.main.lazy-initialization=true`.** The fail-closed guards are
   `InitializingBean`s, so under lazy init they may never be instantiated and a host-fleet service
   could boot with a blank secret and 401 instead of failing loudly. The secret is now always
   exported there, so this is not currently reachable — but the guard is weaker in the fleet than in
   compose/K8s. Unverified either way (no fleet was started).
4. **`/internal/scheme/**` is still anonymous** on `scheme-adapter-nepal`, `-sendmn`, `-ninepay`
   (T0-2 follow-up (c)), so nothing there needs the secret yet. When they are gated,
   `payment-executor`'s `SendmnRestSchemeClient` / `NepalRestSchemeClient` need the token and the
   checker will start requiring the manifests to carry it for those services.
5. **T0-6 is not closed.** Compose still has a checked-in dev default — now in one place, by design.
6. **Nothing was executed end-to-end.** No login, no payment, no `docker compose up`. All claims above
   are from static parse/compile/unit evidence; the boot-order and 401-path claims are reasoned from
   the code the fix agents shipped, not observed.
7. Unrelated pre-existing junk noticed: a committed file literally named `out.append(c)` at the repo
   root (from commit `bb205a1`, a shell accident). Left alone.
