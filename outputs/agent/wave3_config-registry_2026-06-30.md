> 작업: Wave3 config-registry wiring / 출처: agent

# Wave-3 config-registry read-contract wiring

Branch `w3/config-registry` off a36997e. Edits scoped to `services/config-registry/` only.

## Build status
`./gradlew :services:config-registry:test` → **BUILD SUCCESSFUL, 419 tests** (was 414 + 5 new).

## Tasks — all 4 DONE

1. **partner_scheme location-resolution read** ✅
   New `SchemeResolutionController` → `GET /v1/schemes/resolve?country=` returns
   `List<PartnerSchemeView>` over CURRENT V022 rows joined to each partner's
   `operating_country`. Carries direction + countryCode + derived supportsCpm/Mpm
   (approval_method_*  non-null) + status (ACTIVE/SUSPENDED from kill switch);
   priority=null (no column). Filter by country, unknown→[]. (smart-router)

2. **Rule rate-source fields** ✅
   V035 adds `rate_coll_source`/`rate_pay_source` (roster, DEFAULT 'LIVE') to
   partner_rule; `RuleView` on GET `/v1/partners/{id}/rules` emits them (NULL→LIVE).
   RuleCommand frozen → write persists at DB default; entity onPersist stamps LIVE
   (Hibernate INSERTs nulls, ignores DB default). (rate-fx)

3. **Credit-limit push to prefunding (IR-pf-2)** ✅
   Gated `PrefundingCreditLimitClient` (Rest @ `gmepay.prefunding.client=rest`,
   NoOp default). `CreditLimitPusher` merges credit_limit_usd (V015) + AML caps
   (V020/V034 daily/monthly/annual + txn-count) and PUTs
   `/internal/v1/prefunding/{partnerId}/credit-limit` (partnerId = business code,
   matching prefunding's balance key) on every prefunding-config OR limits write.
   MockRestServiceServer-tested (4 cases).

4. **Onboarding → KYB verify** ✅
   Gated `KybVerifyClient` (RestKybVerifyClient → kyb-adapter `POST /v1/kyb/verify`,
   StubKybVerifyClient default, mirrors the screen seam). `KybService.runVerification`
   + `POST /v1/partners/{id}/kyb/verify` persist providerRef + collapsed decision
   (V036 verification_decision/_reason cols) on a fresh SCD-6 row; verdict carries
   forward on step-3 saves. MockRestServiceServer-tested (4 cases).

## Contract notes for consumers
- `KybVerificationRequest`/`Result` are LOCAL mirrors (kyb-adapter's are in its
  service module, not a lib); field names match the wire. Result reads
  ignoreUnknown so the richer adapter payload is tolerated.
- `CreditLimitPushCommand` is a LOCAL record (lib frozen); NON_NULL — absent cap =
  no-change. prefunding must bind the same field names.

## Remaining (≤3)
- Credit-limit push fires on cap-SET only; activation-time push not wired (would
  need `PartnerLifecycleService` change).
- RuleCommand lacks rate-source fields (frozen) → operator-set provenance needs a
  lib-api-contracts extension to flow through the write path; today defaults LIVE.
- PartnerSchemeView.priority always null (no per-row ordering column in V022).
