> 작업: harden payment.reversed contract / 출처: agent

## Type added

`com.gme.pay.contracts.events.PaymentReversedPayload`

Path: `libs/lib-api-contracts/src/main/java/com/gme/pay/contracts/events/PaymentReversedPayload.java`

New Java `record`, purely additive. No existing types touched. Mirrors `PaymentApprovedPayload` style: camelCase fields, money as decimal strings, `@JsonInclude(ALWAYS)`, and an `EVENT_TYPE` constant driving topic `gmepay.<eventType>`.

### Purpose
Emitted when a payment's terminal outcome becomes `REVERSED`, including an operator force-resolve of an `UNCERTAIN` txn to `REVERSED`. Today that transition emits no domain event, so held prefund float is released and no reversing journal is booked (money moves with no ledger impact — the bug this fixes). This contract lets revenue-ledger book a reversing journal and prefunding release the held float on the same signal.

### Fields
| Field | Type | Notes |
|---|---|---|
| `eventType` | String | `EVENT_TYPE = "payment.reversed"` → topic `gmepay.payment.reversed` |
| `txnRef` | String | transaction reference reversed |
| `partnerId` | String | owning partner |
| `schemeId` | String | owning scheme |
| `reversedAmount` | String | decimal string |
| `currency` | String | reversed amount currency |
| `reversedUsd` | String | prefund USD to release; nullable |
| `reason` | String | reversal reason |
| `source` | String | e.g. `OPERATOR`, `SCHEME`, `TIMEOUT` |
| `occurredAt` | String | ISO-8601 instant |

Constant: `public static final String EVENT_TYPE = "payment.reversed";`

## Compile status
`./gradlew compileJava compileTestJava --parallel --console=plain` → **BUILD SUCCESSFUL** across all services. Only pre-existing deprecation warnings (`PartnerSummary`/`PartnerCreateRequest` in ops-partner-bff tests); no errors introduced.
