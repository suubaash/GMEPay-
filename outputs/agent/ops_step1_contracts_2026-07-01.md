> 작업: Ops wave shared contracts / 출처: agent

# Ops wave — shared contracts (step 1, additive)

Branch `ops/contracts`, worktree `wt3/ops-contracts`, edits confined to `libs/lib-api-contracts/`.

## Types added

### 1. `com.gme.pay.contracts.OperationalStatusView`
Platform kill-switch / operational state read model.
- File: `libs/lib-api-contracts/src/main/java/com/gme/pay/contracts/OperationalStatusView.java`
- Fields: `boolean systemPaused`, `boolean maintenanceMode`, `List<String> suspendedPartners`, `List<String> suspendedSchemes`, `List<String> suspendedRoutes`, `String reason`, `String since` (ISO instant, nullable).
- `@JsonInclude(ALWAYS)` (mirrors PartnerSchemeView/RuleView).
- Static default: `public static final OperationalStatusView ALL_CLEAR` (nothing paused/suspended, empty lists, null reason/since) + `allClear()` factory.

### 2. `com.gme.pay.contracts.events.OpsAlertPayload`
Operations alert domain event; mirrors `PaymentApprovedPayload` camelCase + EVENT_TYPE pattern.
- File: `libs/lib-api-contracts/src/main/java/com/gme/pay/contracts/events/OpsAlertPayload.java`
- Fields: `String eventType`, `String alertType`, `String severity`, `String subjectRef`, `String detail`, `String occurredAt` (ISO string).
- `public static final String EVENT_TYPE = "ops.alert"` → topic `gmepay.ops.alert`.
- alertType roster (doc): STUCK_TXN, UNCERTAIN_AGED, FLOAT_LOW, WEBHOOK_BACKLOG, RECON_BREAK, DECLINE_SPIKE; severity INFO|WARN|CRITICAL. Money/nums as strings per MONEY_CONVENTION.

## Constraints honored
- Additive only: two NEW files; no existing type/field changed. No secondary ctor needed (both are new records).

## Compile status
`./gradlew compileJava compileTestJava --parallel --console=plain` → **BUILD SUCCESSFUL** across all services (only pre-existing deprecation warnings). No server/docker.
