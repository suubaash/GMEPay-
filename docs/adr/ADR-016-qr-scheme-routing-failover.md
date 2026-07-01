# ADR-016 — QR-classified multi-partner routing with failover

- **Status:** Proposed (2026-07-01)
- **Deciders:** GME Product (owner), Platform
- **Supersedes:** the interim country→scheme 1:1 routing + `NepalQrDetector` string-match on `/v1/pay`

## Context

A scanned merchant QR must be routed to the partner/scheme that can process it. The interim
implementation routes by **country** (`NP → NEPAL`) and, on the wallet `/v1/pay` path, by a
hardcoded string detector (`NepalQrDetector`). This assumes **one partner per country**.

Reality: **many partners per country** (e.g. Nepal: Khalti, Fonepay-direct, eSewa, ConnectIPS…),
and some partners front **multiple QR networks**. Two distinct sub-problems:
1. **Different networks → different partners** — deterministic once the QR's network is known.
2. **Same network → multiple partners can serve it** — needs a selection policy.

## Decision

### 1. Route by the QR's own network identifier, not by country
EMVCo QRs carry Merchant Account Information templates (tags 26–51) whose sub-tag `00` is a
**globally-unique identifier** (reverse-domain / AID): `com.zeropay`, `fonepay.com`, NepalPay GUID,
etc. JSON QRs (Khalti/mobank) are classified by structure. This GUID is the **deterministic routing
key**. Country + payment-mode (CPM/MPM) + direction are **filter context**, not the router.

### 2. Resolution is data-driven via `config-registry.partner_scheme`
Add a **`network_identifier`** column to `partner_scheme` mapping a QR network GUID → (partner,
scheme, adapter). `smart-router` resolves `(network, country, mode, direction)` → an **ordered list
of candidate partners** (by `priority` asc, `status=ACTIVE` only). No code change to add a partner —
it's a config row.

### 3. Selection policy = **FAILOVER**
```
candidates = smartRouter.resolve(network, country, mode, direction)   # ordered by priority
for partner in candidates (bounded to MAX_HOPS):
    result = dispatch(partner, payment)          # SchemeClientRouter → adapter
    if result.approved:            return APPROVED
    if result.businessDecline:     return DECLINED        # TERMINAL — do NOT fail over
    if result.technicalFailure:    # timeout / 5xx / circuit-open / SCHEME_UNAVAILABLE
        if lookupSaysPaidOrPending(partner, reference):  return that outcome   # anti-double-charge
        continue                    # fail over to next partner
return SCHEME_UNAVAILABLE            # all candidates exhausted
```

### 4. Failover semantics (money-safety is the whole point)
- **Fails over ON (technical):** connection error, timeout, 5xx, circuit-open, `SCHEME_UNAVAILABLE`.
- **TERMINAL, never fails over (business decline):** `invalid_qr`, `unsupported_qr`,
  `receiver_not_found`, `receiver_not_eligible`, insufficient funds, duplicate reference. A decline
  is authoritative — retrying another partner cannot help and risks a double-charge.
- **Anti-double-charge guard (mandatory):** on a timeout/ambiguous failure the outcome is *unknown*,
  so before failing over we **call the partner's idempotent status/lookup** (by our stable
  `reference`); we only fail over if it shows **no** successful/pending payment. Every payable
  adapter MUST expose such a lookup (Khalti: `POST /qrscan-thirdparty/status/`; ZeroPay: status/recon).
- **Idempotency:** one stable payment idempotency key per payment; per-partner attempt reference is
  derived so each attempt is independently queryable.
- **Bounded:** `MAX_HOPS` + overall time budget (config). Every attempt (partner, outcome, reason) is
  recorded in the payment-executor attempt trail / transaction-mgmt.
- **Circuit breaker (phase 2):** skip partners with an open circuit to save latency; half-open probe.

## What changes (implementation)
1. **`QrSchemeClassifier`** (payment-executor) — parse EMVCo GUID / JSON shape → network id. **Retires `NepalQrDetector`.**
2. **`partner_scheme.network_identifier`** (config-registry) — additive migration; network→partner map.
3. **`smart-router`** — resolve returns the *ordered candidate list* for `(network,country,mode,direction)`.
4. **`payment-executor`** — a **FailoverPaymentRouter** on `/v1/pay`: classify → resolve → walk candidates with the failover rules above → `SchemeClientRouter` dispatch.
5. Adapters expose an **idempotent status/lookup** (contract requirement for any failover-eligible partner).

## Consequences
- Adding a partner = a config row (+ its adapter), not code.
- Routing is deterministic per QR; multi-partner is resilient (survives a partner outage).
- Requires disciplined idempotency + a status endpoint per adapter — a hard requirement, not optional.
- `config-registry` becomes the source of truth for network→partner mapping and failover order.

## Alternatives considered
- **Priority-only (no failover):** simplest, but a single partner outage fails otherwise-servable payments. Rejected for resilience.
- **Least-cost routing:** needs a per-partner cost model + FX-aware comparison. Deferred (future ADR); `priority` can encode cost manually meanwhile.

## Open items
- Cost-based routing (future); circuit-breaker thresholds; cross-partner reference reconciliation in settlement when a failover attempt left an orphan lookup.
