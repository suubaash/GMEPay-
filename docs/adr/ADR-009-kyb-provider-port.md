# ADR-009 — KYB / sanctions screening: `KybProvider` port abstraction

**Status:** Accepted (user decision, 2026-06-11)
**Slice:** Partner Setup Slice 3 (KYB)
**Vendor:** see ADR-014

## Context
KoFIU + FSS expect daily sanctions rescreening (OFAC, EU, UN, MOFA-KR, KoFIU lists) and risk-rated CDD/EDD per FATF R.10/R.24. Maintaining consolidated sanctions lists in-house for 20 years is operationally untenable. A vendor adapter is required. The platform must not bind to a specific vendor at the code level — vendor swap (or future multi-vendor consensus screening for high-risk partners) must be a configuration change, not a refactor.

## Decision
A **`KybProvider` port** in a new `lib-kyb` module:

```java
public interface KybProvider {
  ScreeningResult screen(KybSubject subject);          // sanctions / PEP / adverse media
  KybRunResult runFullKyb(KybSubject subject);          // license, UBO, registry checks
  OngoingMonitoringHandle subscribe(PartnerId, ScreeningPolicy);
}
```

Adapters: `RestKybAdapter` (vendor-specific, in `services/auth-identity` or a new `services/kyb-adapter` per ADR boundary), `MockKybAdapter` (deterministic for tests), `StubKybAdapter` (returns "needs review" for dev). Every screening result is published to Kafka topic `gmepay.kyb.screening` (ADR-001) and persisted to the partner's KYB sub-resource (ADR-006 vault for the raw vendor response, audit log per ADR-007 for the decision).

## Consequences
- Vendor is swappable. ADR-014 records the current choice.
- Multi-vendor consensus screening (e.g. for high-risk partners, run two vendors and require both to clear) is a `CompositeKybProvider` that fans out to two underlying adapters — zero domain code change.
- "Can this partner transact" is **never** determined by the screening result alone; the partner's status (ADR-008 4-eyes operator decision after reviewing the screening + risk rating) is the source of truth.
