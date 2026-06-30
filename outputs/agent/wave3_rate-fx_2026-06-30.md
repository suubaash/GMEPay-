> 작업: Wave3 rate-fx wiring / 출처: agent

# Wave-3 — rate-fx: consume config-registry rule rate-source (IR-1)

## Build status
`./gradlew :services:rate-fx:test` → **BUILD SUCCESSFUL**, 35 tests (7 new), 0 failures.
Edited ONLY `services/rate-fx/`. libs + other services untouched.

## Rate-source is now data-driven (closes IR-1)
The Wave-3 contract (a36997e) added `RuleView.rateCollSource`/`ratePaySource`. rate-fx now consumes
them end-to-end:
- `RestConfigRegistryClient` deserializes the two wire strings off `GET /v1/partners/{code}/rules`
  onto `PartnerRule` (its local `RuleResponse` record's field names already match the contract).
- `RateSource.fromNullable` resolves each leg; `QuoteIssueService.buildRateInput` dispatches a
  `PARTNER` leg to `PartnerBQuotePort`, `LIVE`/`MANUAL` to the treasury snapshot store, `IDENTITY`
  /USD legs to null (engine forces 1.0), and null/absent ⇒ LIVE (back-compat).

NOTE: the Phase-1/2 field-mapping code (client → `PartnerRule` → `RateSource` → Partner-B dispatch)
was already present from commit b2b4114; what was MISSING and is the substance of this commit is the
HTTP-level proof/regression guard that the *wire* fields actually flow through. Added
`ConfigRegistryRuleSourceWiringTest` (MockRestServiceServer, config-registry NOT running): proves
`getRules` deserializes the source strings; and end-to-end that PARTNER routes through Partner B
(treasury never queried), LIVE/MANUAL read the snapshot store, IDENTITY yields null, absent ⇒ LIVE.

One production-surface change: widened `RestConfigRegistryClient(RestClient)` (already a test-only
seam ctor, mirroring `XeRateClient`) from package-private to `public` so the test in the `issue`
package can drive the real client. CHANGELOG updated.

## Remaining (≤3)
1. **Producer still emits null sources.** config-registry's `RuleEntity.toView()` calls the 9-arg
   compat ctor, so `rateCollSource`/`ratePaySource` are always null on the wire today → every leg
   resolves LIVE in practice until config-registry is wired to populate them (frozen this wave).
2. **Real Partner-B feed** — PARTNER legs still resolve via the in-process `SnapshotPartnerBQuotePort`
   (manually-seeded `source='PARTNER'` snapshots); no live Partner-B HTTP client yet.
3. None blocking; suite green.
