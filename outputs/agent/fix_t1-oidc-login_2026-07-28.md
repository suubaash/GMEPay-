> 작업: T1-2 OIDC login fix / 출처: agent

# T1-2 — partner portal login made reachable, and both SPAs restored against the secured BFF

Scope owned and touched: `apps/**`, `docker/keycloak/**`, `docker-compose.yml`, `deploy/helm/**`
(+ the two authorized doc edits: `Documentation/GAP_REGISTER.md` T1-2, and the factually-wrong
Keycloak table in `docs/COMPOSE.md`). **No Java changed anywhere** — `services/ops-partner-bff`,
`payment-executor` and `prefunding` are untouched (`git status` confirms), and no server, docker,
Keycloak or fleet was started.

The defect was never "OIDC is missing". Both SPAs already had a complete PKCE module and a
`/auth/callback` page. What was missing was **agreement**: four files named four different
realms/clients/ports, the clients were confidential while the exchange (correctly) sent no
secret, and the realm emitted none of the claims the just-landed resource server authorizes
from. Everything below is that disagreement removed.

---

## 1. Canonical identity topology

| | local docker (compose) | local host fleet (`run-fleet.ps1`) | k8s base / on-prem | AWS / Azure overlay |
|---|---|---|---|---|
| realm | `gmepay` | `gmepay` | `gmepay` | `gmepay` |
| admin-ui client | `admin-ui` (public, PKCE S256) | same | same | same |
| portal client | `partner-portal-ui` (public, PKCE S256) | same | same | same |
| browser issuer = token `iss` | `http://localhost:8097/realms/gmepay` | `http://localhost:8097/realms/gmepay` | `http://keycloak.gmepay.local/realms/gmepay` | `https://auth.gmepay.example.com/realms/gmepay` |
| resource-server JWKS | `http://keycloak:8080/realms/gmepay/protocol/openid-connect/certs` | discovery via the issuer (host can reach it) | `http://keycloak:8080/...certs` | discovery via the issuer (commented override available) |
| Keycloak port | host **8097** → container 8080 | 8097 (Keycloak in docker) | ingress (external to the chart) | ingress |
| BFF reached by the SPA | `BFF_PROXY_TARGET=http://127.0.0.1:8095` | `…:18095` | `http://ops-partner-bff:8080` | `http://ops-partner-bff:8080` |
| one knob | `KC_PUBLIC_URL` (drives `KC_HOSTNAME_URL` **and** both `OIDC_ISSUER_URI`s) | `OIDC_ISSUER_URI` export | `abi.*` | `abi.*` |

Three decisions worth stating:

1. **Realm `gmepay`, not `gmepay-partners`.** The partners realm existed in no seed file, no
   compose file and no chart — it was an aspiration in a JSDoc default. Partners who bring their
   own IdP federate as an *identity provider inside* `gmepay`; a second realm would need its own
   client, mappers, issuer and provisioning, none of which exists.
2. **Public clients + PKCE, not "ship the dev secret".** `exchangeCode` sending no
   `client_secret` was correct; the realm was wrong. Both `*-dev-secret` values are deleted.
3. **Port 8097, not 8090.** 8090 is `scheme-adapter-zeropay` (`docs/COMPOSE.md`). The Java
   defaults still say `:8090`, which I cannot change (no Java edits), so `OIDC_ISSUER_URI` is set
   explicitly everywhere it matters — and that stale default is exactly why a **host-run** BFF
   still needs one env var (see §5).
4. **Issuer ≠ JWKS URL.** Keycloak stamps `iss` with the URL the *browser* used, so the resource
   server must expect the browser-facing URL; a container cannot resolve `localhost:8097`, so the
   key fetch is pinned in-network. Spring builds the decoder from `jwk-set-uri` and still
   validates `iss` against `issuer-uri` — the split is deliberate, not a workaround.

## 2. Claim mappers added to the realm seed

`TokenClaims` / `OpsRbacGuard` / `AdminSurfaceRbacInterceptor` read **only** these (verified by
reading the Java, not guessing):

| claim | seed mechanism | consumed by |
|---|---|---|
| `permissions` (array of `resource.action`; CSV also parsed) | user attribute `permissions` + inline mapper `gmepay-permissions` (`multivalued=true`, access+id+userinfo) | `requireOps` / `requireTxnView` / `requireAdminRead` / `requireAdminWrite`, and the interceptor over **all** `/v1/admin/**` |
| `partner_id` (single string) | user attribute `partner_id` + inline mapper `gmepay-partner-id` | `requirePartnerScope` for `/v1/portal/{partnerId}/**` |
| `realm_access.roles` | built-in `roles` client scope (already in `defaultClientScopes`) | `ROLE_*` authorities |

Mappers are **inline on each client** (not a shared client scope) so a partial import cannot
half-apply them. Seeded users:

| user / password | role | `partner_id` | `permissions` |
|---|---|---|---|
| `admin` / `demo` | OPERATOR | — | full hub set (`ops:operate`, `partner.activate`, `rbac.manage`, `approval.*`, `settlement.resolve_exception`, `inspector.view`, …) |
| `operator-readonly` / `demo` | OPERATOR | — | `partner.view`, `txn.view`, `report.generate` — proves admin **writes** 403 |
| `partner-demo` / `demo` | PARTNER_USER | **`GMEREMIT`** | none (a partner needs none for its own portal) |
| `partner-sendmn` / `demo` | PARTNER_USER | **`SENDMN`** | none — lets an operator verify the cross-partner 403 |

`GMEREMIT`/`SENDMN` are asserted against `PartnerSeeder.java` by the topology check, so a
renamed partner code breaks the check instead of the login.

## 3. Files changed

**Identity seed / infra**
- `docker/keycloak/realm-gmepay.json` — both clients public + PKCE S256, secrets removed, explicit
  `/auth/callback` redirect URIs + web origins (incl. `127.0.0.1`), 4 protocol mappers, 4 users
  with the attributes above.
- `docker/keycloak/README.md` **(new)** — the topology table, the issuer-vs-JWKS explanation, the
  claim contract, the **Keycloak 25 unmanaged-attribute caveat** (import writes the attributes, but
  the Admin Console hides them until `unmanagedAttributePolicy=ENABLED`; the `kcadm` command is
  given — deliberately *not* in the seed JSON, because a malformed user-profile component fails the
  whole realm import), and browser-free curl verification.
- `docker/keycloak/check-topology.mjs` **(new)** — 99 assertions, zero dependencies, no servers.
- `docker-compose.yml` — `KC_HOSTNAME_URL=${KC_PUBLIC_URL:-http://localhost:8097}`; realm mounted
  as a **file** (so the README cannot be handed to `--import-realm`); `OIDC_ISSUER_URI` +
  `..._JWK_SET_URI` for **both** `ops-partner-bff` and `api-gateway`; comments explaining why.
- `deploy/helm/gmepay/values.yaml`, `values-onprem.yaml`, `values-aws.yaml`, `values-azure.yaml` —
  browser-facing issuers (on-prem/base were in-cluster URLs that can never match a browser token),
  in-cluster JWKS, `NEXT_PUBLIC_KEYCLOAK_CLIENT_ID` per UI, `NEXT_PUBLIC_BFF_BASE_URL` →
  `BFF_PROXY_TARGET=http://ops-partner-bff:8080`, and a note that Keycloak is external to the chart.

**partner-portal-ui**
- `src/api/oidc.js` — defaults `gmepay` / `partner-portal-ui` / `:8097`.
- `src/api/auth.js` — `login()` (POST `/v1/auth/login`) **deleted**; new `partnerIdFromTokens()`
  (access token then id_token, **no** username/email fallback — that only yields a misleading
  "scoped to a different partner" 403), `isPartnerScopeMissing()`, `refreshSession()`; a stale
  partner id is now cleared on login as someone else.
- `src/api/client.js` — `X-Partner-Id` no longer sent (both JSON and blob paths); new `authedFetch`
  attaches the bearer and does one refresh+replay on 401 then clears the session;
  `NEXT_PUBLIC_PARTNER_ID` fallback removed; `portalApi.login` gone, `refreshToken` now real.
- `src/app/login/page.jsx` — rewritten: one "Sign in with Keycloak" button; the password form and
  the on-screen "Phase 1 demo credentials … password `demo`" text are gone.
- `src/store/authSlice.js` — `loginThunk` removed; partner id from the claim.
- `src/components/AuthGate.jsx` — new explicit state: signed in but **no `partner_id` claim** →
  names the missing Keycloak attribute/mapper instead of rendering 8 pages that all 403.
- 8 pages: the "set `NEXT_PUBLIC_PARTNER_ID`" copy replaced with the claim-based explanation.
- `.env.example`, `.env.local`, `README.md`.

**admin-ui**
- `src/api/oidc.js` — default issuer `:8097`; `CLIENT_ID = 'gmepay-admin-ui'` (a client that exists
  nowhere) replaced by env-overridable `keycloakClientId()` → `admin-ui`; new `refreshTokens()`.
- `src/api/auth.js` — `login()` deleted (and with it the `auth → client` import cycle); dead
  `setToken` removed; `getRefreshToken()` + `refreshSession()` added.
- `src/api/client.js` — `adminApi.login` / `adminApi.refreshToken` deleted; 401 → one refresh+replay.
- `src/api/opsApi.js` — stops sending `X-Gme-Permissions: ops:operate`. `init.operate` is now only a
  documentation marker; the BFF authorizes from the token.
- `src/store/authSlice.js` — `loginThunk` deleted. `src/app/login/page.jsx` — SSO only.
  `src/schemas/loginSchema.js` — **deleted**. `next.config.mjs` — `BFF_PROXY_TARGET` first.
- `.env.example`, `.env.local`, `README.md`.

**Tests** (all updated, none weakened): portal `api/auth`, `api/oidc`, `store/authSlice`,
`components/AuthGate` (+2 new partner-scope cases), `app/login/page` (rewritten: asserts *no*
input element exists at all); admin `api/oidc` (+`keycloakClientId`, + "token request carries no
`client_secret`"), `store/authSlice`.

## 4. Build / test results

| check | result |
|---|---|
| `npx next build` partner-portal-ui | ✅ compiled + linted, 13 routes |
| `npx next build` admin-ui | ✅ compiled + linted, 41 routes |
| `vitest run` partner-portal-ui | ✅ **101/101** (13 files) |
| `vitest run` admin-ui | **777/785** — 8 failures in 3 files (`step-1 IdentityForm`, `step-2 ContactsForm`, `step-3 KybForm`), all `Test timed out in 5000ms` in heavy `userEvent` typing. Pre-existing: the same files fail the same way on a **HEAD baseline copy**, and all 34 tests pass when the 3 files run in isolation. Nothing they import was touched. |
| `gradlew :services:ops-partner-bff:test` | ✅ **317 tests, 0 failures** (64 classes) — the T0 contract is intact |
| `node docker/keycloak/check-topology.mjs` | ✅ **99/99** |
| PyYAML parse: compose + 4 values files + Chart.yaml | ✅ all parse; resolved keys printed and cross-checked |
| realm JSON parse | ✅ (by the topology check) |

Vitest cannot run in `D:\GMEPay+` (vite URL-decodes the `+`, and a junction does not help because
vite realpaths its root). Method used: real-directory copies at `D:\gmepay-vitest\<app>` with
`node_modules` junctioned back, plus a HEAD-restored `admin-ui-base` copy purely to establish the
pre-existing-failure baseline. Those copies are scratch and outside the repo.

## 5. Operator verification (the exact steps for a real login)

```bash
# 0. one-time: the compose Keycloak must re-import the realm, so drop its volume if it
#    already booted with the OLD (confidential-client, no-mapper) realm:
docker compose down keycloak && docker volume rm code_pg-keycloak

# 1. bring up Keycloak + the BFF (core profile includes both)
docker compose --profile core up -d keycloak ops-partner-bff
#    remote/tunnel host instead:  KC_PUBLIC_URL=https://auth.example.com docker compose ... up -d

# 2. confirm the realm imported with public clients + mappers
curl -s http://localhost:8097/realms/gmepay/.well-known/openid-configuration | head -c 200
#    -> "issuer":"http://localhost:8097/realms/gmepay"   (must match OIDC_ISSUER_URI exactly)

# 3. token without a browser (dev realm only; directAccessGrants is on for these clients)
TOKEN=$(curl -s -X POST http://localhost:8097/realms/gmepay/protocol/openid-connect/token \
  -d grant_type=password -d client_id=partner-portal-ui \
  -d username=partner-demo -d password=demo -d scope=openid | jq -r .access_token)
echo "$TOKEN" | cut -d. -f2 | base64 -d | jq '{iss, partner_id, permissions, realm_access}'
#    MUST show partner_id:"GMEREMIT". If it is absent the mappers/attributes did not import —
#    see docker/keycloak/README.md §3.

# 4. the four decisive calls
curl -o /dev/null -s -w '%{http_code} own\n'    -H "Authorization: Bearer $TOKEN" http://localhost:8095/v1/portal/GMEREMIT/overview   # 200
curl -o /dev/null -s -w '%{http_code} other\n'  -H "Authorization: Bearer $TOKEN" http://localhost:8095/v1/portal/SENDMN/overview     # 403
curl -o /dev/null -s -w '%{http_code} admin\n'  -H "Authorization: Bearer $TOKEN" http://localhost:8095/v1/admin/partners             # 403
curl -o /dev/null -s -w '%{http_code} anon\n'                                     http://localhost:8095/v1/portal/GMEREMIT/overview   # 401

# 5. the browser flow
cd apps/partner-portal-ui && cp .env.example .env.local   # BFF_PROXY_TARGET=…:8095 for compose
npm run dev    # http://localhost:3001 -> Sign in with Keycloak -> partner-demo / demo
#    admin-ui: same, port 3000, user admin / demo (operator-readonly to see writes 403)

# host fleet (run-fleet.ps1) instead of compose — REQUIRED extra step, see below:
#    $env:OIDC_ISSUER_URI = 'http://localhost:8097/realms/gmepay'   # then start ops-partner-bff
#    and set BFF_PROXY_TARGET=http://127.0.0.1:18095 in both .env.local files (already done)
```

## 6. Unproven / follow-ups (nothing here was faked)

1. **No live run.** Static validation + builds only, per the constraint. Three things are reasoned,
   not observed: Keycloak 25's realm import persisting the *unmanaged* `permissions`/`partner_id`
   user attributes (import goes through `RepresentationToModel`, which writes them directly — the
   Admin Console still hides them until `unmanagedAttributePolicy=ENABLED`); `KC_HOSTNAME_URL`
   pinning `iss`; and the actual code→token exchange. Step 3 above falsifies all three in ~30s.
2. **`run-fleet.ps1` is not owned by this task** and does not set `OIDC_ISSUER_URI`, so a
   host-run BFF still falls back to the stale `:8090` Java default and 401s everything. Follow-up:
   add `OIDC_ISSUER_URI=http://localhost:8097/realms/gmepay` to the `ops-partner-bff` (and
   `api-gateway`) entries there. Documented in both `.env.local` files meanwhile.
3. **Existing Keycloak volumes must be dropped** (step 0) — `--import-realm` skips a realm that
   already exists, so an operator who booted the old realm keeps confidential clients and no
   mappers, and will see `invalid_client` even after this change.
4. **Reaching *data* is still T1-3, not T1-2.** Login and scoping are fixed; several portal panels
   (webhooks, profile, statements, API keys) remain controller fixtures/stubs, and a fresh
   `GMEREMIT` has no prefunding row until one is created — so "logs in and sees their own data
   end-to-end" is true for the auth path and only partly true for the payload.
5. **Would need a backend change (deliberately NOT made):** nothing was required. Two things stay
   worth doing in Java later, by whoever owns those files: the `:8090` defaults in
   `ops-partner-bff/application.properties` + `api-gateway/application.yml` should become `:8097`
   so a bare local run is correct without env, and the BFF has no CORS config — which is why the
   SPAs must proxy same-origin rather than call it directly.
6. **Residual stale references outside my scope:** `gmepay-test-platform/src/usecases/features.ts`
   still asserts `POST /v1/auth/login` with `password=demo` (that use case now describes a deleted
   endpoint and should be rewritten as a 401/404 assertion), and
   `Documentation/services_backlog/partner-portal-ui.md` still specifies a native
   username+password+TOTP portal login that contradicts ADR-011.
7. `X-Gme-Permissions` is gone from admin-ui, but the platform-wide RBAC pipeline
   (`gmepay.rbac.enabled`, gateway-stamped signed claims) is still inert — T0-3, unchanged here.
