> 작업: ADR-016 smart-router candidate resolve / 출처: agent

# ADR-016 — smart-router QR-classified failover candidate resolve

## Endpoint
`GET /v1/route/resolve?network=&country=&mode=&direction=`

- `network` is **optional**. Present → ADR-016 failover path: returns an ORDERED
  `List<PartnerSchemeView>`. Absent/blank → the pre-existing country-based
  `ResolveResponse` (chosen scheme + candidate set + ambiguity), unchanged.
- Controller method returns `Object` (one endpoint, two response shapes selected by
  the `network` param). `LocationResolveController.resolve(...)`.

## Ordered-candidate logic
`LocationSchemeResolver.resolveCandidates(network, LocationSchemeQuery)`:
1. Validate base query (country/mode/direction) + require non-blank `network`.
2. `registry.schemesForCountry(country)` — already ACTIVE/enabled rows in ascending
   `priority`.
3. Filter: `servesNetwork(network)` (CSV membership) → `enabledFor(direction)` →
   `supports(mode)`.
4. `map(PartnerSchemeRecord::toView)` preserving priority order = the **failover
   order** (element 0 primary; rest failover).
5. Empty → `NO_SCHEME_FOR_LOCATION` (404); blank network → `VALIDATION_ERROR` (400).
   Canonical `ApiException`/`ErrorCode` envelope via existing `RouterApiExceptionHandler`.

## CSV-membership match
`PartnerSchemeRecord.networkIdentifier` is a comma-separated GUID list. `servesNetwork`
splits on `,`, trims each token, case-insensitive equals against the requested network.
A row with null `networkIdentifier` serves no network. A partner fronting several
networks (`fonepay.com,nepalpay,com.f1soft,connectips`) is a candidate for any one of
them. Mapped through both backings: `RestPartnerSchemeRegistry.toRecord` now carries
`view.networkIdentifier()` + `view.partnerId()`; `InMemoryPartnerSchemeRegistry` seeds
`com.zeropay`→ZEROPAY (KR) and the Nepal GUIDs→NEPAL (NP).

## Multi-candidate (failover) proof
Fixture adds a SECOND NP partner `NEPAL_FONEPAY_DIRECT` (priority 1) also serving
`fonepay.com`. `network=fonepay.com + NP + MPM` returns `[NEPAL(pri0),
NEPAL_FONEPAY_DIRECT(pri1)]` in priority order — proven at the resolver level and over
the wire (MockRestServiceServer, two partner directories + scheme reads).

## Test status
`./gradlew :services:smart-router:test` GREEN — **49 tests**.
New: 6 resolver candidate tests (two-partner-ordered, interior-CSV-token, com.zeropay,
unknown-network 404, mode-filtered 404, blank-network 400), 3 controller network-path
tests (ordered JSON array, 404, blank→country-fallback), 1 REST wire test proving
`networkIdentifier`+`partnerId` flow through. Existing country-based tests unchanged
(one NP fixture assertion updated for the added failover row).

## Remaining (≤3)
1. `RestPartnerSchemeRegistry` still fans out per-country (directory + per-partner
   scheme reads); no network-scoped config-registry query — fine while frozen.
2. Failover EXECUTION (walk candidates, business-decline TERMINAL, anti-double-charge
   lookup) is payment-executor's `FailoverPaymentRouter` — out of smart-router scope.
3. Circuit-breaker skip (ADR-016 phase 2) not modelled in the candidate ordering.
