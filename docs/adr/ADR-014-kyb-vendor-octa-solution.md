# ADR-014 — KYB / sanctions vendor: Octa Solution

**Status:** Accepted (user decision, 2026-06-11)
**Implements:** [ADR-009](./ADR-009-kyb-provider-port.md)
**Slice:** Partner Setup Slice 3 (KYB)

## Context
ADR-009 defines a vendor-agnostic `KybProvider` port; this ADR records the concrete vendor chosen. Selection criteria: (a) Korean regulatory coverage (KoFIU consolidated lists, MOFA-KR sanctions, FSS adverse-media feeds), (b) REST API for sandbox + production integration, (c) PIPA-compliant data residency, (d) ongoing-monitoring subscription model, (e) survivable as a vendor relationship for 20 years.

## Decision
**Octa Solution** ([octasolution.co.kr](https://www.octasolution.co.kr)). Local Korean KYB provider with KoFIU-list integration, used by the user's existing operations.

The concrete adapter implementing ADR-009's `KybProvider` port is `OctaKybAdapter` in `services/kyb-adapter` (new service). The user will provide API specification and sandbox credentials when Slice 3 begins; until then the `KybProvider` is wired to `StubKybAdapter` so Slices 1–2 are not blocked.

## Consequences
- Vendor-agnostic port (ADR-009) preserved: a future swap (or composite/consensus screening for high-risk partners per ADR-009) is a configuration change, not a refactor.
- Procurement track: contract + DPA + sandbox creds + production allowlist need to be sequenced ahead of Slice 3's go-live. Pre-Slice-3 calendar dependency: Octa Solution sandbox access.
- Until sandbox access lands, `StubKybAdapter` returns deterministic "needs review" decisions so the rest of the platform can be exercised end-to-end without external dependency.
- If Octa Solution coverage gaps appear during corridor expansion (e.g. when a new country goes live and Octa's adverse-media feed is weak in that jurisdiction), the `CompositeKybProvider` path in ADR-009 allows a second vendor to be layered in without code change to the partner aggregate.
