# Changelog

## Wave-3 — config-registry read-contract wiring (branch `w3/config-registry`)

config-registry as the producer of the Wave-3 read contracts. Edits scoped to
`services/config-registry/` only; libs + other services frozen.

### Added
- **partner_scheme location-resolution read** — `GET /v1/schemes/resolve?country=`
  (new `SchemeResolutionController`) returns `List<PartnerSchemeView>` over the
  CURRENT V022 enablements joined to each partner's `operating_country`. Carries
  `direction`, `countryCode`, derived `supportsCpm`/`supportsMpm` (from
  `approval_method_cpm`/`_mpm` presence), `status` (ACTIVE/SUSPENDED from the
  kill switch) and `priority` (null — no column yet). Filterable by country;
  unknown country → empty list. smart-router consumes this.
- **Rule rate-source fields** — V035 adds `rate_coll_source`/`rate_pay_source`
  (roster IDENTITY|LIVE|MANUAL|PARTNER, DEFAULT 'LIVE') to `partner_rule`; the
  GET `/v1/partners/{id}/rules` `RuleView` now emits them (NULL→LIVE on read).
  rate-fx consumes this.
- **Credit-limit push to prefunding** (IR-pf-2) — gated REST client
  (`PrefundingCreditLimitClient`: `RestPrefundingCreditLimitClient` @
  `gmepay.prefunding.client=rest`, `NoOpPrefundingCreditLimitClient` default).
  `CreditLimitPusher` merges `credit_limit_usd` (V015) + daily/monthly/annual +
  daily-txn-count caps (V020/V034) and PUTs `/internal/v1/prefunding/{partnerId}/credit-limit`
  on every prefunding-config or limits write. MockRestServiceServer-tested.
- **Onboarding → KYB verify** — gated `KybVerifyClient`
  (`RestKybVerifyClient` calls kyb-adapter `POST /v1/kyb/verify`,
  `StubKybVerifyClient` default). `KybService.runVerification(...)` +
  `POST /v1/partners/{id}/kyb/verify` persist provider ref + collapsed decision
  (V036 `verification_decision`/`_reason` columns) on a fresh SCD-6 row.
  MockRestServiceServer-tested.

### Notes
- Migrations V035/V036 are ADR-013 Expand-phase additive ALTERs (nullable /
  DEFAULTed), engine-neutral (PG + H2 PG-mode).
- Credit-limit push fires on cap-SET; activation-time push not yet wired.
