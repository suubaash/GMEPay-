> 작업: Ops operational gate (payment-executor) / 출처: agent

# Ops operational gate — payment-executor

## What was built
Operations **operational gate** that refuses NEW payment authorizations when the platform is
paused / in maintenance, or when the resolved partner / scheme / route is suspended. In-flight
(already-authorized) payments are never blocked.

### New components (`services/payment-executor/`)
- `domain/client/OperationalStatusClient` — reads config-registry
  `GET /v1/ops/operational-status` → `OperationalStatusView` (lib-api-contracts, from `ops/contracts`).
- `client/rest/RestOperationalStatusClient` — `@ConditionalOnProperty(gmepay.config-registry.base-url)`;
  short in-memory cache (`gmepay.ops.status.cache-ttl-millis`, default 3s); configurable fail-open.
- `client/rest/FixtureOperationalStatusClient` — `@ConditionalOnMissingBean` fallback returning
  `OperationalStatusView.allClear()` (tests + no-config-registry runs proceed).
- `domain/OperationalGate` — precedence systemPaused → maintenance → partner → scheme → route;
  case-insensitive trimmed matching; null refs skip.
- `domain/OperationalGateException` + `PaymentExceptionHandler` mapping → HTTP 503 retryable.

## Where the gate hooks in
- **Wallet `POST /v1/pay`** (`WalletPayController.pay`, at method START): gates the GMEREMIT/SENDMN
  inbound branches AND the FailoverPaymentRouter outbound branch in one place — before any merchant
  lookup / scheme submit. Gated by partner alias (`req.partner()`) + classified QR network (route).
- **Orchestrated `POST /v1/payments/authorize`** (`PaymentController`): gated by partner code +
  scheme id + direction, placed AFTER the idempotent-replay lookup so an in-flight replay is NOT gated.
- **NOT gated:** confirm/capture, cancel, refund, `GET /v1/payments/{id}` — in-flight completes mid-pause.

## Fail-open policy
Default **fail-OPEN** (`gmepay.ops.status.fail-open=true`): unreachable config-registry with no cached
value → `allClear()` (allow). Set `false` to fail-CLOSED (synthetic `systemPaused`). A last-known-good
cached value is preferred over either policy, so a brief blip does not flip behaviour.

## Error codes
Emitted via the `ApiError(String code, …)` ctor (lib-errors frozen): `SYSTEM_PAUSED`,
`PARTNER_SUSPENDED`, `SCHEME_SUSPENDED`, `ROUTE_SUSPENDED` — all 503, retryable.

## Test status
`./gradlew :services:payment-executor:test` → **BUILD SUCCESSFUL** (green). New: `OperationalGateTest`
(7), `RestOperationalStatusClientTest` (4, MockRestServiceServer: parse / cache / fail-open / fail-closed),
`WalletPayControllerTest` (+3: systemPaused→SYSTEM_PAUSED, partner suspended→PARTNER_SUSPENDED, in-flight
refund NOT gated), `PaymentControllerIdempotencyTest` (+2: authorize paused→503 no side-effect, idempotent
replay not gated). All-clear "proceeds" covered by existing happy-path tests (Mockito no-op gate).

## Integration requests
1. **lib-errors**: promote `SYSTEM_PAUSED` / `PARTNER_SUSPENDED` / `SCHEME_SUSPENDED` / `ROUTE_SUSPENDED`
   to `ErrorCode` enum members (503, retryable) when it next unfreezes; emitted as literal codes for now.

## Remaining (≤3)
1. Wallet-path gate keys on the partner **alias** (GMEREMIT/SENDMN) + QR network, not the resolved
   config-registry partner id/scheme code — align suspension-list keys with config-registry's ops model.
2. Set `gmepay.config-registry.base-url` (+ optional `fail-open`/`cache-ttl`) in the deployed profile
   to activate the REST client (fixture all-clear until then).
3. Confirm the config-registry ops endpoint field names/casing match `OperationalStatusView` on the wire
   once that service is live (contract is shared, but not yet integration-tested end-to-end).
