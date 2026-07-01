> 작업: Wave3 prefunding credit-limit / 출처: agent

# Wave-3 prefunding — credit-limit + AML-cap push (IR-pf-2)

## Build
`./gradlew :services:prefunding:test` → **BUILD SUCCESSFUL**, 49 tests, 0 failures.

## Endpoint added + applied
- **Added** `PUT /internal/v1/prefunding/{partnerId}/credit-limit` (internal surface, consumed by
  config-registry's gated client). Body: `creditLimitUsd` + AML caps `amlDailyCapUsd` /
  `amlMonthlyCapUsd` / `amlAnnualCapUsd` / `amlDailyTxnCountCap` (any null = no cap).
  Idempotent upsert; re-PUT overwrites; creates the partner row if the push precedes provisioning.
  Returns stored limits + derived available + balance. Negative → 400.
- **Persistence:** V007 adds 4 cap columns to `partner_balance`; entity extended.
  Service: `pushPartnerLimits` (upsert under row lock) + `getPartnerLimits`.
- **Applied = yes (both gates):**
  - Credit limit: already wired — deduct/reserve use `available = balance + credit_limit − reserved`
    (PrefundingAccount, lib-prefunding untouched), so a pushed limit immediately raises headroom.
  - AML caps: `chargeCumulative` now falls back to the STORED caps when the caller omits them
    per-request (per-request still overrides — additive).
- Scope respected: edited only `services/prefunding/`; did NOT touch the existing
  deduct/reverse/reserve/release endpoints. Note: a public `PUT /v1/prefunding/.../credit-limit`
  (credit-limit only) already existed on `PrefundingController` — left as-is; the new internal one
  is the AML-aware superset config-registry binds.

## Remaining (≤3)
1. config-registry side must BIND this contract (its Wave-3 gated client → this PUT) at integration.
2. AML-cap enforcement is on `/cumulative-charge` (authorize phase); deduct/reserve do not yet
   auto-charge cumulative — wiring that is a separate orchestration decision (transaction-mgmt).
3. No GET to surface stored caps over HTTP yet (`getPartnerLimits` exists in-service only).

Committed to `w3/prefunding`. CHANGELOG updated.
