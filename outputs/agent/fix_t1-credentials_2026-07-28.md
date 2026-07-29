> 작업: T1-1 credential issuance fix / 출처: agent

# T1-1 — Go-live partner credentials are fabricated and authenticate nothing

Gap source: `Documentation/GAP_REGISTER.md` T1-1 (CPO P1, `outputs/agent/audit_cpo-product_2026-07-28.md`).

---

## 1. What was actually broken

**Missing config, not missing code.** Both REST clients already existed, were correct, and
called endpoints that really exist:

| config-registry client | endpoint called | actually exposed by |
|---|---|---|
| `client/rest/RestAuthIdentityClient.issueKey` | `POST /internal/auth/keys` | `services/auth-identity/.../web/ApiKeyAdminController.java:53` (`@RequestMapping("/internal/auth/keys")` at :44) |
| `RestAuthIdentityClient.revokeKey` | `POST /internal/auth/keys/{keyId}/revoke` | `ApiKeyAdminController.java:74` |
| `client/RestNotificationWebhookClient.registerEndpoint` | `POST /v1/webhooks/endpoints` | `services/notification-webhook/.../api/WebhookEndpointController.java:43` (`@RequestMapping("/v1/webhooks/endpoints")` at :34) |

Request/response field sets matched too (config-registry's mirrored `IssueKeyRequest` ==
auth-identity's `dto/IssueKeyRequest`, all 7 fields; the 3-field response mirror tolerates
auth-identity's extra `prefix`/`environment`/`createdAt` via `@JsonIgnoreProperties`).

**The four defects were all in selection/wiring:**

1. `StubAuthIdentityClient` was an **unconditional `@Component`** and
   `RestAuthIdentityClient` required an explicit `gmepay.auth-identity.client=rest`. That
   env var was set for **ops-partner-bff only** (`docker-compose.yml:815`,
   `deploy/helm/gmepay/values.yaml:288`, `run-fleet.ps1:103`) and **never for
   config-registry** — which is the service that actually issues partner credentials.
2. `StubNotificationWebhookClient` carried `matchIfMissing = true` and
   `GMEPAY_NOTIFICATION_WEBHOOK_CLIENT` was set **nowhere in the repo**.
3. `RestNotificationWebhookClient`'s fallback base-url was `http://notification-webhook:8085`
   — wrong for every deployed environment (compose/K8s put every service on 8080 via
   `SERVER_PORT`; 8085 is only that service's standalone `application.properties` default).
   So even wiring the selector alone would have 502'd.
4. **`auth-identity` was in the compose `full` profile only** while config-registry,
   ops-partner-bff, notification-webhook and kafka are all `core`. A `core` stack physically
   could not issue a verifiable key. Same hole in `run-fleet.ps1 -Subset money`.

Consequence before the fix: activation returned `pk_live_…`/`sk_live_…`/`whsec_…` strings that
existed nowhere. auth-identity's `POST /internal/auth/keys/resolve` answers `found=false`, so
every signed partner request 401s; no `webhook_endpoint` row exists, so no delivery is ever
attempted and no signature could match; and `StubAuthIdentityClient.revokeKey()` is an empty
method, so suspending/terminating a partner revoked nothing.

## 2. Design choice for task 3 — invert the defaults (not fail-fast-on-unset)

Options considered: (a) leave stub default + fail startup when no selector is set outside a
test profile; (b) make the REST client the default and force the stub to be named.

**Chose (b).** Rationale: (a) still leaves the dangerous bean one property away from being the
default, needs a profile-detection heuristic that is itself a silent-failure surface, and would
break every unit slice and single-service local run. With (b) the failure modes become:

- selector absent → REST client → misconfiguration surfaces as a **502 at activation**, loud,
  inside the activation transaction, so the partner never lands SANDBOX/LIVE without credentials;
- selector `stub` → stub, plus a `WARN` at construction naming the consequence;
- selector anything else (`mock`, `http`, empty-but-present) → **no bean at all**, so
  `PartnerCredentialService`'s required constructor arg fails context refresh and the service
  refuses to boot.

There is no remaining path by which a missing or mistyped env var produces a credential.
(`@ConditionalOnProperty` compares `havingValue` case-insensitively, so `REST`/`Rest` in a
hand-edited deployment file still select the real client — pinned by a test.)

## 3. Files changed

**Java — config-registry (behaviour)**
- `client/rest/RestAuthIdentityClient.java` — `matchIfMissing = true`; Javadoc records the T1-1 inversion.
- `client/StubAuthIdentityClient.java` — now `@ConditionalOnProperty(havingValue = "stub")` (was unconditional); WARN in the constructor.
- `client/RestNotificationWebhookClient.java` — `matchIfMissing = true`; default base-url `:8085` → `:8080`.
- `client/StubNotificationWebhookClient.java` — dropped `matchIfMissing = true`; WARN in the constructor.
- `client/AuthIdentityClient.java`, `client/NotificationWebhookClient.java` — port Javadoc corrected (which impl is the default and why).
- `src/main/resources/application.properties` — declares `gmepay.auth-identity.{client,base-url}` + `gmepay.notification-webhook.{client,base-url}`, env-overridable, defaulting to `rest` / the compose 8080 hostnames.

**Deployment**
- `docker-compose.yml` — config-registry gains the 4 env vars; `auth-identity` profile `["full"]` → `["core","full"]`. Deliberately **no** `depends_on: auth-identity` on config-registry: auth-identity already `depends_on: config-registry` and the reverse edge would be a compose cycle. The REST clients are lazy, so boot order is irrelevant.
- `deploy/helm/gmepay/values.yaml` — same 4 env vars under `services.config-registry.env`. The cloud overlays (`values-aws/azure/onprem.yaml`) only override `SPRING_DATASOURCE_URL` and Helm merges `env` maps key-by-key, so they inherit these.
- `run-fleet.ps1` — config-registry entry gains `--gmepay.auth-identity.{client,base-url}` (→ `localhost:18085`) and `--gmepay.notification-webhook.{client,base-url}` (→ `localhost:18086`); `auth-identity` added to `$moneyNames` (ops-partner-bff already pointed at 18085 with `client=rest` while nothing started it — a pre-existing hole).
- `e2e-tests/.../PartnerOnboardingE2ETest.java` — that fleet's config-registry now gets `--gmepay.auth-identity.base-url=http://localhost:18085` and `--gmepay.notification-webhook.client=stub` (notification-webhook is not in that fleet; the funnel saves no step-8 draft). Compiles; not executed (no servers started this round).

**Tests (new)**
- `services/config-registry/.../client/CredentialClientSelectionTest.java` (6)
- `services/config-registry/.../client/rest/RestAuthIdentityClientTest.java` (5)
- `services/auth-identity/.../web/PartnerCredentialIssuanceContractTest.java` (5)
- `services/notification-webhook/.../api/WebhookEndpointRegistrationContractTest.java` (5)
- `services/config-registry/.../webhook/WebhookProvisioningServiceTest.java` (+1 shared SHA-256 vector)

**Docs** — `Documentation/GAP_REGISTER.md` (T1-1 → `[x]` with note), `services/config-registry/CHANGELOG.md`.

Untouched per constraints: `services/ops-partner-bff`, `services/payment-executor`, `services/revenue-ledger`.

## 4. Env matrix (config-registry)

| Surface | `..._AUTH_IDENTITY_CLIENT` | `..._AUTH_IDENTITY_BASE_URL` | `..._NOTIFICATION_WEBHOOK_CLIENT` | `..._NOTIFICATION_WEBHOOK_BASE_URL` |
|---|---|---|---|---|
| image default (`application.properties`) | `rest` | `http://auth-identity:8080` | `rest` | `http://notification-webhook:8080` |
| `docker-compose.yml` | `rest` | `http://auth-identity:8080` | `rest` | `http://notification-webhook:8080` |
| `deploy/helm/gmepay/values.yaml` (+ aws/azure/onprem overlays) | `rest` | `http://auth-identity:8080` | `rest` | `http://notification-webhook:8080` |
| `run-fleet.ps1` (CLI args) | `rest` | `http://localhost:18085` | `rest` | `http://localhost:18086` |
| `PartnerOnboardingE2ETest` fleet | `rest` | `http://localhost:18085` | `stub` (service absent) | — |
| `@DataJpaTest` / unit slices | n/a — the clients are not in those contexts | | | |

Companion secret already in place on all three surfaces: `GMEPAY_AUTH_IDENTITY_INTERNAL_SECRET`
(compose `docker-compose.yml` config-registry block, Helm `envSecretAliases`) matching
auth-identity's `GMEPAY_INTERNAL_AUTH_SECRET` — required because `/internal/auth/**` sits behind
the #90 internal-auth gate in compose/prod.

## 5. Test results

`gradlew.bat :services:config-registry:test :services:auth-identity:test :services:notification-webhook:test`
→ **BUILD SUCCESSFUL** (config-registry 454 tests, 0 failures; auth-identity + notification-webhook green).
One iteration was needed: the first run asserted that `client=REST` (wrong case) would leave no
bean — Spring compares `havingValue` case-insensitively, so that assertion was wrong about
Spring, not about the fix; it was replaced with an explicit case-insensitivity test plus an
unknown-value (`mock`/`http`) fail-fast test.

New-test tallies (from `build/test-results`): `CredentialClientSelectionTest` 6/6,
`RestAuthIdentityClientTest` 5/5, `PartnerCredentialIssuanceContractTest` 5/5,
`WebhookEndpointRegistrationContractTest` 5/5, `WebhookProvisioningServiceTest` 10/10.

What the proof actually asserts:
- **auth-identity can verify what activation issues** — the exact wire JSON config-registry
  sends → `POST /internal/auth/keys` (real controller, real service, real DB) → the returned
  `keyId` resolves `found=true, active=true, partnerId=42` at `POST /internal/auth/keys/resolve`,
  and the one-time plaintext verifies against the stored salted PBKDF2 hash
  (`ApiKeyEntity.secretMatches`). The stub-shaped fabricated key is asserted to resolve
  `found=false` — the pre-fix symptom, now a locked-in negative case.
- **revoke is real** — after `POST /internal/auth/keys/{keyId}/revoke` the key resolves
  `found=true, active=false` and the row is `REVOKED` (contrast the stub's empty method);
  idempotent on retry.
- **the webhook secret returned at activation is the one notification-webhook knows** —
  `POST /v1/webhooks/endpoints` → the returned plaintext hashes to the
  `webhook_endpoint.signing_secret_hash` actually persisted, and both services' independent
  copies of the SHA-256 helper are pinned to the same fixed vector
  (`SHA-256("whsec_test") = 609b97b0…6d73`) on both sides.
- **the real clients are what get selected**, per the matrix above.

Config validation (no servers/docker started): `docker-compose.yml` +
`deploy/helm/gmepay/values{,-aws,-azure,-onprem}.yaml` parse under PyYAML and the parsed values
were asserted programmatically; `run-fleet.ps1` parses clean via
`[System.Management.Automation.Language.Parser]::ParseFile` (1977 tokens, 0 errors).

## 6. Residual risk

1. **No live end-to-end confirmation.** The proof is two-sided contract testing, not one
   process calling another. The `PartnerOnboardingE2ETest` fleet is now wired for the real path
   but was not executed (no servers/docker this round) — and that test still issues keys by
   calling auth-identity directly rather than through a config-registry activation. Recommended
   follow-up: add an activation step there and run `:e2e-tests:e2eTest`.
2. **Mirrored DTOs stay unlinked by the compiler.** `RestAuthIdentityClient.IssueKeyRequest`
   and auth-identity's `dto/IssueKeyRequest` are independent records (MSA rule 5), as are the
   two `sha256Hex` helpers. The new tests are the only thing holding them in step — a
   coordinated rename that edits both records and neither test would still drift.
3. **The internal-auth secret is a dev literal.** `dev-internal-svc-secret-not-for-prod` is the
   compose/Helm default for the token config-registry presents to `/internal/auth/keys`. Real
   credentials are now issued over a link authenticated by a secret checked into the repo — a
   T1-6 / CISO item, not fixed here.
4. **Rotation/expiry unproven at the boundary.** `PartnerCredentialRotationScheduler` proposes
   rotation through 4-eyes, and rotation now genuinely revokes upstream keys — but no test
   drives rotate-then-verify across the two services.
5. **`environment` vocabularies differ by lane and are unenforced across services**:
   credentials use `SANDBOX|PRODUCTION` (config-registry ↔ auth-identity) while webhooks use
   `SANDBOX|LIVE` (config-registry ↔ notification-webhook). Both sides agree today
   (`PartnerCredentialService.ENVIRONMENTS` vs `WebhookProvisioningService.ENVIRONMENTS`), but
   nothing prevents a future lane from crossing them; the mismatch would surface as a 400 at
   activation.
6. **Profile/subset promotion has a cost.** `auth-identity` now boots in the compose `core`
   profile and the fleet `money` subset (+1 JVM ≈320 MB, `postgres-authid` was already `core`).
   Deliberate: a core stack that cannot issue verifiable credentials is the bug.
7. **Fleet base-urls are positional.** `run-fleet.ps1` hardcodes `18085`/`18086`; renumbering
   the fleet without updating the config-registry args re-breaks activation (loudly, as a 502).
