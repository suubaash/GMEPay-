> 작업: T0-5 internal endpoint lockdown / 출처: agent

# T0-5 — unauthenticated money-moving internal endpoints + internet-reachable sandbox payment runner

Scope: `services/prefunding` and `services/payment-executor` **only**. No changes to `apps/**`,
`docker-compose.yml`, `deploy/helm/**`, `libs/**`, `ops-partner-bff`, `api-gateway`, or any other
service. Everything that must change outside these two services is listed below rather than edited.

Tests: `gradlew.bat :services:prefunding:test :services:payment-executor:test` →
**BUILD SUCCESSFUL — prefunding 129 tests / payment-executor 256 tests, 0 failures, 0 errors**
(75 of them new; no existing test weakened, skipped or deleted).

---

## 1. The reused mechanism (task 1)

The platform already has exactly one internal-auth scheme, added under issue **#90** and living in
`libs/lib-errors`:

| file | role |
|---|---|
| `libs/lib-errors/src/main/java/com/gme/pay/internalauth/InternalAuthHeaders.java` | the header contract — `X-Gme-Internal` |
| `.../internalauth/InternalAuthFilter.java` | `OncePerRequestFilter`; on configured Ant patterns requires the header to match the secret (constant-time `MessageDigest.isEqual`), else writes the standard `ApiError` envelope as **401** before any controller |
| `.../internalauth/InternalAuthProperties.java` | `gmepay.internal-auth.{enabled,secret,path-patterns}`; default patterns `/v1/rbac/**`, `/v1/approvals/**`, `/internal/**` |
| `.../internalauth/InternalAuthAutoConfiguration.java` | registers the filter at `HIGHEST_PRECEDENCE+10` when `gmepay.internal-auth.enabled=true`; already validates "enabled without a secret ⇒ refuse to start" |

It is auto-configured for every service that depends on `lib-errors`
(`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`), but **opt-in**.
Today only `auth-identity` opts in — and it does so with
`enabled: ${GMEPAY_INTERNAL_AUTH_ENABLED:false}`, i.e. **off** unless the deployment says otherwise.
Callers already present the token: `api-gateway/WebClientRbacClaimResolver`,
`config-registry/RestAuthIdentityClient`, `ops-partner-bff/Rest{ApprovalQueue,RbacAdmin,OperatorActionAudit}Client`.
The shared secret is currently the checked-in dev literal
`dev-internal-svc-secret-not-for-prod` (`docker-compose.yml:375,494,861,907`) / `CHANGE_ME_INTERNAL_SVC_SECRET`
(`deploy/helm/gmepay/values.yaml:93`) — that is **T0-6**, not fixed here.

**Decision: reuse it as-is.** No new header, no new filter, no new secret literal in main source.
Both services now consume `InternalAuthFilter` / `InternalAuthHeaders` directly.

---

## 2. `services/prefunding` — every endpoint gated, fail-closed (task 2)

prefunding has **no public surface at all**. All three controllers either move partner float or read
it, and the only legitimate callers are other GMEPay+ services. Before this change every one of them
answered any anonymous caller who could reach port 8080 — and `/v1/prefunding/**` is additionally
published to the internet by `api-gateway` (`GatewayRoutingConfig.java:118-123`).

**Gated (18 routes):**

| controller | routes |
|---|---|
| `api/internal/PrefundingInternalController` (`/internal/v1/prefunding`) | `POST /{id}/deduct`, `POST /{id}/reverse`, `POST /{id}/reserve`, `POST /{id}/release`, `PUT /{id}/credit-limit` |
| `api/PrefundingController` (`/v1/prefunding`) | `POST /{id}/{deduct,credit,reverse,reserve,capture,release,cumulative-charge,cumulative-reverse}`, `PUT /{id}/credit-limit`, `GET /{code}/deductions` |
| `api/BalanceProvisioningController` (`/v1/prefunding`) | `POST /provision`, `GET /{code}/balance`, `GET /{code}/alerts` |

Also gated: `/actuator/metrics/**`, `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`.
Left anonymous **on purpose**: `/actuator/health/**` and `/actuator/info` (container probes).

**Changed files**

- `src/main/resources/application.properties` — `gmepay.internal-auth.enabled=true` (pinned, not an
  env toggle), `gmepay.internal-auth.secret=${GMEPAY_INTERNAL_AUTH_SECRET:}` (**empty default → boot
  failure**, see below), and the explicit `path-patterns` list above.
- **NEW** `src/main/java/com/gme/pay/prefunding/config/InternalAuthEnforcedConfig.java` — the
  fail-closed guard (see §4).
- `api/internal/PrefundingInternalController.java` — javadoc corrected: it previously asserted
  *"network-level policy is the trust boundary"*, which was false (there is no mesh/mTLS; that claim
  is what let the gap sit).

**Why `enabled` is pinned rather than env-driven:** with the shared auto-configuration, "flag absent"
and "flag false" both mean *no filter at all* — for this service that is a silently ungated money
API, the exact failure mode T0-5 is about. A deployment can still set
`GMEPAY_INTERNAL_AUTH_ENABLED=false`, but that no longer disables the gate: it stops the service
from starting.

---

## 3. `services/payment-executor` — sandbox runner default-OFF, then gated (task 3)

`POST /v1/sandbox/e2e/run` is not a read-only test helper: it drives a **real** authorize+capture
through the real pay path (prefunding debit → scheme call → ledger postings) over loopback HTTP. It
was an always-on, unauthenticated "spend money" button, proxied from the admin portal.

- `web/SandboxE2eController.java` — `+@ConditionalOnProperty(name="gmepay.sandbox.e2e.enabled", havingValue="true")`.
- `sandbox/E2eRunner.java`, `sandbox/SelfPayClient.java` — same condition, so the whole feature is
  absent (not merely unmapped) in the default posture.
- `src/main/resources/application.properties` — `gmepay.sandbox.e2e.enabled=false`,
  `gmepay.devtools.enabled=false`, `gmepay.internal-auth.secret=${GMEPAY_INTERNAL_AUTH_SECRET:}`.
- **NEW** `config/SandboxSurfaceInternalAuthConfig.java` — registers `InternalAuthFilter` over
  whichever dev surface is enabled, and refuses to start if one is enabled without a secret.
- `client/rest/RestPrefundingClient.java` — now presents `X-Gme-Internal` on every prefunding call
  (added `@Value("${gmepay.internal-auth.secret:}")` to the `@Autowired` prod constructor; the
  package-private test constructor is untouched). Without this, gating prefunding would have broken
  the main pay path. Blank secret → no header + a startup WARN, i.e. a gated prefunding answers 401
  (fail-closed), never a bypass.

Off ⇒ `/v1/sandbox/e2e/**` returns **404** (there is nothing to probe or fingerprint).
On ⇒ 401 without the token, 200 with it.

**Verified no production config enables it:** repo-wide grep over `docker-compose.yml`,
`deploy/helm/gmepay/values*.yaml`, `run-fleet.ps1`, Dockerfiles and every `application*.properties`
finds **no** occurrence of `gmepay.sandbox.e2e.enabled` or `GMEPAY_SANDBOX_E2E_ENABLED`, and no
`gmepay.devtools.enabled=true`. The runner was reachable purely because the bean was unconditional.

### Sweep of these two services (task 4)

| surface | service | before | after |
|---|---|---|---|
| `/__data/tables`, `/__data/tables/{t}` (`devtools/DevDataController`) | payment-executor | already `@ConditionalOnProperty(gmepay.devtools.enabled)`, but **no auth when on** | same flag (explicitly `false` in config) **+ token-gated when on + boot failure without a secret** |
| `/actuator/metrics`, `/v3/api-docs`, `/swagger-ui/**` | payment-executor | anonymous | gated whenever a secret is configured (always true in a real deployment, since it needs one to call prefunding); WARN when not |
| `/actuator/metrics`, `/v3/api-docs`, `/swagger-ui/**` | prefunding | anonymous | gated unconditionally |
| `/actuator/env`, `/beans`, `/heapdump`, `/shutdown` | both | already not exposed (`exposure.include=health,info,metrics`) | unchanged |
| `GET /v1/balance` | payment-executor | trusts `X-Partner-Id` / `X-Partner-Code` / `X-Partner-Type` headers with fail-open defaults (`partnerId=1`, `OVERSEAS`) ⇒ any caller reads any partner's float | **left as-is** — this is header-trusted tenancy (a T0-2-class IDOR) on the partner-facing, gateway-fronted surface, not an unauthenticated internal endpoint. Gating it with internal-auth would break legitimate partner API traffic. Reported below. A regression test asserts it is *not* swept into the internal gate. |

---

## 4. Fail-closed proof

`InternalAuthEnforcedConfig.InternalAuthGateAssertion` is an `InitializingBean`, so it runs during
context refresh — throwing means the service does not start. Three failure modes, all fatal:

| condition | outcome |
|---|---|
| `gmepay.internal-auth.secret` absent / blank / whitespace | `IllegalStateException: prefunding refuses to start: … set the GMEPAY_INTERNAL_AUTH_SECRET environment variable … There is intentionally no default` |
| `gmepay.internal-auth.enabled` not `true` (filter would never register) | `IllegalStateException: … gmepay.internal-auth.enabled must be true` |
| `path-patterns` narrowed so `/internal/**` or `/v1/prefunding/**` falls outside the gate | `IllegalStateException: … path-patterns no longer gates [<missing>]` |

`SandboxSurfaceInternalAuthConfig` does the same for payment-executor: a dev surface enabled with a
blank secret is a startup failure. With both surfaces off (the production posture) **no secret is
required**, so ordinary boots are unaffected.

**No new secret literal in main source.** The only literal added anywhere is the test fixture
`test-fixture-internal-token-not-a-deployment-secret` in
`services/prefunding/src/test/resources/application-test.properties` (test source set, never
packaged into the jar, needed because the service now refuses to boot without a secret).

### Tests (task 5)

**`services/prefunding/src/test/java/.../api/InternalAuthGateTest.java`** — `@SpringBootTest(RANDOM_PORT)`
+ `TestRestTemplate`, i.e. real HTTP through the real servlet filter chain (deliberately *not*
MockMvc-with-default-headers, so a pass is evidence about the deployed perimeter). All 18 routes ×
3 credential states:

| case | result |
|---|---|
| no credential → each of the 18 money-moving / float-reading routes | **401** |
| tampered credential → same 18 routes | **401** |
| correct credential → same 18 routes | never 401 (reaches the handler) |
| correct credential → `POST /v1/prefunding/provision` then `GET /{code}/balance` | **201** then **200** with the real balance |
| blank `X-Gme-Internal` header | 401 |
| 401 body | standard `ApiError` envelope, leaks no balance |
| `/actuator/health`, `/actuator/health/readiness` | 200 · `/actuator/metrics`, `/v3/api-docs` **401** |

**`services/prefunding/src/test/java/.../config/InternalAuthEnforcedConfigTest.java`** — the
fail-closed matrix above (null/empty/whitespace secret, `enabled=false`, `enabled=false`+no secret,
each required pattern dropped, `null` patterns) plus the armed-gate happy path.

**`services/payment-executor/src/test/java/.../web/SandboxE2eSurfaceTest.java`** — three
`@SpringBootTest(RANDOM_PORT)` contexts plus a `WebApplicationContextRunner`:

| context | asserted |
|---|---|
| flag absent (production default) | no `SandboxE2eController` / `E2eRunner` / `SelfPayClient` bean; `/v1/sandbox/e2e/{options,runs,runs/1}` → **404** (with *and* without a token); `/__data/tables` → 404; `/v1/payments/**` not 401; `/actuator/health` 200 |
| `gmepay.sandbox.e2e.enabled=true` + secret | controller bean present; no credential → **401**; wrong/blank credential → **401**; correct credential → **200**; `POST /run` gated; `/actuator/metrics` + `/v3/api-docs` gated, `/v3/api-docs` 200 with the token; pay surface + probes unaffected |
| secret set, no dev surface (real deployment posture) | sandbox + `/__data` still 404; introspection 401; `/actuator/health{,/liveness}` 200; **`/v1/payments/**` and `/v1/balance` NOT 401** (regression guard against an over-broad pattern) |
| `WebApplicationContextRunner` | sandbox on + no/blank secret → context **fails** with `refuses to start` + `GMEPAY_INTERNAL_AUTH_SECRET`; devtools on + no secret → fails; everything off + no secret → starts with the filter registered **disabled**; on + secret → filter enabled |

Existing tests: the 5 prefunding MockMvc API test classes (41 request sites) were converted to call
through the live gate via a `call(...)` helper + `testsupport/TestInternalAuth`, so they now exercise
business behaviour *behind* the filter instead of bypassing it. `SandboxE2eControllerTest` and
`E2eRunnerTest` construct their subjects directly and needed no change.

One incidental gotcha worth recording: `TestRestTemplate`'s default
`SimpleClientHttpRequestFactory` (`HttpURLConnection`) throws `HttpRetryException` — *"cannot retry
due to server authentication"* — instead of surfacing a 401 whose request carried a body, which is
precisely the case under test (30 spurious failures on the first run). Both tests switch to
`JdkClientHttpRequestFactory`; same family as the known `RestClient` PATCH gotcha.

---

## 5. Required deployment env vars (NOT applied — other agents own those files)

| var | services | value | consequence if missing |
|---|---|---|---|
| `GMEPAY_INTERNAL_AUTH_SECRET` | **`prefunding`** | the platform's shared internal token (same value as auth-identity's) | **prefunding refuses to start** |
| `GMEPAY_INTERNAL_AUTH_SECRET` | **`payment-executor`** | same value | boots, but every prefunding call is refused 401 ⇒ payments decline; `/actuator/metrics` + `/v3/api-docs` stay anonymous |
| `GMEPAY_INTERNAL_AUTH_SECRET` | `ops-partner-bff`, `config-registry`, `qr-service` | same value | those services' prefunding calls 401 (see §6a) |
| `GMEPAY_SANDBOX_E2E_ENABLED` / `gmepay.sandbox.e2e.enabled` | `payment-executor` | **do not set** in any shared/tunnelled environment | n/a |
| `GMEPAY_DEVTOOLS_ENABLED` | `payment-executor` | **do not set** | n/a |

`docker-compose.yml` and `deploy/helm/gmepay/values.yaml` already define the secret for
`auth-identity`, `api-gateway` and `ops-partner-bff`; it must be added to the `prefunding` and
`payment-executor` service blocks (and to `deploy/helm/gmepay/values.yaml`'s `envSecretKeys` for
both). Note the compose default is the **checked-in dev literal** — T0-6 territory.

## 6. The admin-ui rewrite rule that must change

`apps/admin-ui/next.config.mjs:40-43` (not edited — `apps/**` is another agent's):

```js
      {
        source: '/e2e/:path*',
        destination: `${paymentExecutorUrl}/v1/sandbox/e2e/:path*`,
      },
```

**Delete this rewrite block** (and the `paymentExecutorUrl` const on line 25, which nothing else
uses). It is a same-origin, unauthenticated Next-server proxy from the portal — reachable from
wherever the portal is reachable, including the Cloudflare tunnel — straight to the payment runner,
and it bypasses the BFF's new OIDC boundary entirely by design. The runner is now 404 by default and
token-gated when on, so the rewrite is already dead in a correct deployment; it must still go,
because the Next server would happily forward a client-supplied `X-Gme-Internal` header the moment
someone enables the flag. If the E2E tab must survive, it belongs behind the BFF
(`/api/*` → `ops-partner-bff`, OIDC-authenticated + RBAC-checked), with the BFF holding the internal
token server-side — never as a direct browser-to-payment-executor proxy. The
`/sim-nepal-qr/:path*` rewrite on lines 36-39 deserves the same review.

---

## 7. Follow-ups in OTHER services (not edited)

**(a) Callers that must present the token** — otherwise their prefunding calls now 401:

| caller | file | endpoints |
|---|---|---|
| `qr-service` | `.../qr/prefunding/RestPrefundingReservationClient.java:53,77` | `POST /internal/v1/prefunding/{id}/{reserve,release}` |
| `ops-partner-bff` | `.../bff/client/rest/RestPrefundingClient.java:63,79` | `GET /v1/prefunding/{code}/{balance,alerts}` (the ops partner-balance panel) |
| `config-registry` | `.../registry/prefunding/push/RestPrefundingCreditLimitClient.java:61` | `PUT /internal/v1/prefunding/{id}/credit-limit` |

Pattern to copy: `RestAuthIdentityClient` / this fix's `RestPrefundingClient` — a
`@Value("${gmepay.internal-auth.secret:}")` constructor param + `defaultHeader(InternalAuthHeaders.INTERNAL_TOKEN, …)`.

**(b) `api-gateway` publishes the prefunding money API to the internet** —
`GatewayRoutingConfig.java:118-123` routes `GET|POST /v1/prefunding/**` to prefunding. No partner
should be able to call `deduct`/`credit`/`reverse`/`capture`. **Remove the route** (preferred), or at
minimum restrict it to `GET /v1/prefunding/{code}/balance` and have the gateway inject the internal
token. Until then the route exists but returns 401 — safe, yet it advertises the surface.

**(c) `auth-identity`'s own gate defaults OFF** — `application.yml:62`,
`enabled: ${GMEPAY_INTERNAL_AUTH_ENABLED:false}`. Compose/Helm set it to `true`, so deployed
environments are covered, but a bare or hand-rolled run leaves `/internal/auth/token` (JWT minting),
`/internal/auth/keys` (partner API-key issuance), `/internal/auth/verify`, `/v1/rbac/**` and
`/v1/approvals/**` anonymous. Should be pinned `true` with a fail-closed assertion, exactly as
prefunding now is — this is arguably worse than the prefunding gap (mint a token, become anyone).

**(d) `/internal/scheme/**` on the three scheme adapters is completely anonymous** — no
`gmepay.internal-auth` config in any of them:
- `scheme-adapter-zeropay`: `ZeroPaySchemeController` (`/internal/scheme/zeropay`) + `RegistrationStatusController`
- `scheme-adapter-nepal`: `NepalSchemeController` (`/internal/scheme/nepal`)
- `scheme-adapter-sendmn`: `SendmnSchemeController` (`/internal/scheme/sendmn`) + `FxRateController` (`/internal/scheme/sendmn/fx-rate/latest`)

These drive **outbound scheme calls**, i.e. real money movement at the partner network. Same
treatment applies and is cheap (three property blocks).

**(e) `/__data` table dumps in three more services** — `transaction-mgmt`, `config-registry`,
`scheme-adapter-zeropay`. All correctly `@ConditionalOnProperty(gmepay.devtools.enabled)` (default
off), but **unauthenticated when on**, and `transaction-mgmt`'s dumps the transaction ledger. Add the
same token gate + fail-closed assertion payment-executor now has.

**(f) `rate-fx` `RateSnapshotAdminController`** — its own javadoc (line 20) says the path *"is
intended to sit behind the internal-auth gate (operator-only)"*. It is not. Rate snapshots feed
pricing.

**(g) `payment-executor GET /v1/balance` header-trusted tenancy** — `web/BalanceController.java:52-56`
resolves the partner from `X-Partner-Id` (default `1`), `X-Partner-Code` and `X-Partner-Type`
(default `OVERSEAS`), fail-open. Any caller reaching the gateway route reads any partner's float and
deduction history by changing a header. Belongs with T0-2/T0-3 (claim-scoped tenancy), not with this
fix; deliberately left untouched here, with a regression test pinning that it stays outside the
internal gate.

**(h) Actuator/OpenAPI anonymous on the other 15 services** — every one ships
`management.endpoints.web.exposure.include=health,info,metrics` plus springdoc, with the
copy-pasted comment *"no auth wiring assumed in the sandbox"*. `/actuator/metrics` and
`/v3/api-docs` are anonymous everywhere except `ops-partner-bff` (fixed in T0-1..T0-4) and now these
two. Low severity individually, but it is a free map of the whole internal API.
