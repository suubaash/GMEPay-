> 작업: ADR-016 networkIdentifier contract / 출처: agent

# ADR-016 Step 1 — `networkIdentifier` shared-contract field

## Field added
- **`String networkIdentifier`** appended as the **18th (last) component** of the
  `PartnerSchemeView` record in
  `libs/lib-api-contracts/src/main/java/com/gme/pay/contracts/PartnerSchemeView.java`.
- Carries the QR network's globally-unique reverse-domain/AID GUID
  (e.g. `com.zeropay`, `fonepay.com`) that a scanned QR classifies to — the
  deterministic QR-network → partner routing key (ADR-016 §2).
- Nullable/additive; `@JsonInclude(ALWAYS)` unchanged so `null` stays on the wire.
- No other fields touched.

## Compat approach (mirrors existing wave-2/3 pattern)
Two backwards-compatible secondary constructors delegate the trailing field(s) to `null`:
- Existing **12-arg** (pre-Wave-3) ctor — extended its delegation to pass `null` for
  `networkIdentifier` as well.
- **New 17-arg** (Wave-3, pre-ADR-016) ctor — added, delegates `networkIdentifier=null`.

Net effect: every existing call site in config-registry (`PartnerSchemeEntity.toView`)
and smart-router that constructs the 12- or 17-arg shape compiles unchanged.

## No new DTO
smart-router's `resolve` returns `List<PartnerSchemeView>` ordered by `priority` — the
ordered candidate list for failover is just that view list. Only the field was added.

## Compile status
`./gradlew compileJava compileTestJava --parallel --console=plain` → **BUILD SUCCESSFUL**
across all 17 services (only pre-existing `PartnerSummary` deprecation warnings; no errors).

Committed to branch `fo/contracts`.
