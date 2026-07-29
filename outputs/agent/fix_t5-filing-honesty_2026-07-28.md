> 작업: T5-2 filing status honesty / 출처: agent

# T5-2 — Regulatory lanes can no longer report a filing that never happened

**Scope:** `services/reporting-compliance` only. Build: `gradlew :services:reporting-compliance:test` → **BUILD SUCCESSFUL, 109 tests, 0 failures** (baseline before the change: same task green).

The gap was not that the lanes are gated — it is that the code returned success anyway. `StubHometaxClient` produced literal status `"ACCEPTED"` with a spec-shaped fake 24-char `ntsConfirmation` and `STUB-INV-<seq>` invoice id; `StubKofiuFeedClient` produced a `STUB-<uuid>` receipt; `report_filing.submission_status` could therefore reach `SUBMITTED`/`CONFIRMED` with `external_receipt_id` populated for a report that never left the JVM. That is what has been made structurally impossible.

---

## 1. Per-lane: what is real vs what was fabricated

| Lane | Real (kept, untouched) | Was fabricated | Now |
|---|---|---|---|
| **KOFIU** (CTR/STR daily feed) | CTR/STR threshold computation incl. the statutory KRW 10M fallback; daily feed-file generation `KOFIU_YYYYMMDD.dat`; per-type idempotent filing rows | `StubKofiuFeedClient` returned receipt `STUB-<uuid>`; scheduler logged "feed submitted" | `FilingTransmissionResult.notTransmitted(reason)` — **no receipt id can be attached**; filings settle at `NOT_FILED_CHANNEL_UNAVAILABLE`; log is `GENERATED BUT NOT FILED` at WARN |
| **BOK** (FX1014/FX1015) | FX1014/FX1015 direction mapping, fixed-width padding, `offer_rate_coll` (#14) carried verbatim, file writing, `bok_report_record` persistence | `submitStub()` logged `[STUB] Would SFTP-submit…` at INFO, reading as a submission step | renamed `logTransmissionNotAttempted()`, WARN "GENERATED BUT NOT TRANSMITTED… Nothing has been filed"; filings settle at `NOT_FILED_CHANNEL_UNAVAILABLE`. `TODO_OI03` in the two mandatory code columns now makes the artifact **fail local validation**, so BOK cannot even reach `VALIDATED` |
| **HOMETAX** (monthly e-tax invoice) | Monthly VAT aggregation net of GME's 2% spread and the KRW 500 levy; `vat_treatment` handling (STANDARD / ZERO_RATED_EXPORT / EXEMPT) | status `"ACCEPTED"`, fake `ntsConfirmation`, `STUB-INV-<seq>` — the worst of the three | `HometaxInvoiceResponse.notFiled(reason)`: status `NOT_FILED_CHANNEL_UNAVAILABLE`, **both NTS identifiers null**, reason attached. Lane now also writes an honest `report_filing` row (`ETAX`), which it previously never did |

`gmepay.hometax.cert-id: stub-cert-id` is now explicitly treated as **not a credential** (this was the audit's `hometaxSet == true` on a fully stubbed lane).

---

## 2. States before / after

**Before:** `PENDING → GENERATED → SUBMITTED → CONFIRMED` (+`FAILED`), `VARCHAR(16)`, any caller could write any value.

**After** (`ReportFiling.Status`, `VARCHAR(32)`):

| State | Meaning | Reachable today |
|---|---|---|
| `PENDING` | filing row opened | yes |
| `GENERATED` | aggregated + artifact produced locally | yes — real capability |
| `VALIDATED` | passed **our own** format checks (non-empty, no `TODO_` placeholder). Explicitly not an authority confirmation | yes for KoFIU; **no** for BOK (`TODO_OI03`) |
| `NOT_FILED_CHANNEL_UNAVAILABLE` | generated, never sent, reason recorded | **yes — terminal for all 3 lanes** |
| `TRANSMITTED` | bytes accepted by a real channel | **no** |
| `ACKNOWLEDGED` | authority returned a receipt | **no** |
| `FAILED` | run failed | yes |

**Why the last two are unreachable rather than merely unused** — three independent barriers:

1. `ReportFilingService.recordTransmission/recordAcknowledgement` throw `VALIDATION_ERROR` unless `FilingChannelRegistry.isLive(lane)`. The registry is **configuration-only** (blank `bok.channel.endpoint` / `kofiu.channel.endpoint`, placeholder NTS cert id) and has no constructor that can be talked into `true` by a stub bean.
2. `FilingTransmissionResult` rejects at construction a not-transmitted result carrying a receipt id, and a transmitted result without one. The no-channel clients cannot express a fake ack.
3. The V003 `CHECK` constraint rejects `SUBMITTED`/`CONFIRMED`/`ACCEPTED`/`FILED` at the database.

Plus: `recordTransmission` refuses a blank receipt id, and `ACKNOWLEDGED` requires the filing to already be `TRANSMITTED`.

---

## 3. Gate visibility

- **Startup:** `FilingChannelRegistry` logs one line per lane on `ApplicationReadyEvent` — `WARN Regulatory filing channel NOT CONFIGURED: lane=BOK — filings terminate at NOT_FILED_CHANNEL_UNAVAILABLE. <reason naming the missing key>`.
- **API:** `GET /v1/reports` envelope gained `filing_status`, `filing_channel_unavailable_reason`, `filing_channels[]` (additive — the BFF reads `generated_at`/`records`, unaffected). New `GET /v1/reports/filing-channels` returns the board (`lane`, `channel_live`, `reachable_status`, `reason`).
- **Register:** `report_filing.channel_unavailable_reason` states why each row is not filed.
- **Config:** `application.yml` documents the three channel keys and that all are intentionally blank.

---

## 4. Migration

`src/main/resources/db/migration/V003__filing_status_honesty.sql` — verified next free version (only V001, V002 existed); module uses a single `db/migration` dir, no vendor-specific dirs (checked against all 16 service migration dirs). H2-PostgreSQL-mode-safe (`ALTER COLUMN … SET DATA TYPE`), so it runs in tests too.

- widens `submission_status` to `VARCHAR(32)`, replaces the CHECK with the new vocabulary (both `report_filing` and `bok_report_record`);
- adds `channel_unavailable_reason`, `reclassified_from`, `reclassification_note`;
- **reclassifies, never deletes:** `SUBMITTED`/`CONFIRMED` → `NOT_FILED_CHANNEL_UNAVAILABLE`, prior status kept in `reclassified_from`, and the discarded fabricated `external_receipt_id` + `submitted_at` preserved verbatim inside `reclassification_note`. `external_receipt_id`/`submitted_at` are then nulled because those columns must only hold facts produced by a real channel. Rows that never claimed acceptance (`PENDING`, `GENERATED`) are untouched;
- a ~30-line header comment explains why, naming the exact fabricated values.

---

## 5. Tests (all green)

- `channel/FilingChannelRegistryTest` (6) — shipped defaults leave every lane unavailable with a reason; `stub-cert-id` does not count as a credential; lanes independent; a real cert id does make Hometax live.
- `persistence/ReportFilingServiceTest` (+9) — `recordTransmission` refused for **every** lane and the filing left untouched; `recordAcknowledgement` refused; `settleAgainstChannel` records the honest terminal state with the reason and no receipt/`submitted_at`; generation+validation still succeed and keep counts/paths; and (with a channel injected only in-test) transmit→ack works, double-transmit conflicts, blank receipt refused, ack-before-transmit refused.
- `persistence/V003FilingStatusReclassificationTest` (3) — seeds the exact legacy fabricated rows, migrates V002→V003, asserts reclassification + audit trail + untouched honest rows + `bok_report_record` + the CHECK rejecting the old vocabulary.
- `hometax/StubHometaxClientTest` (5, rewritten) — status is `NOT_FILED_CHANNEL_UNAVAILABLE` never `ACCEPTED`, both NTS ids null, reason surfaced; fails loudly if a channel is configured but no production client is wired.
- `kofiu/StubKofiuFeedClientTest` (4, rewritten) — not transmitted, null receipt, reason names the missing key; the result type itself rejects a fabricated receipt.
- `validation/FilingArtifactValidatorTest` (3) — `TODO_OI03` artifact fails; clean artifact passes.
- `BokReportServiceTest` (+3) — envelope carries the honest `filing_status`, the reason, and the 3-lane board, with record counts unchanged.
- `persistence/BokRecordPersistenceServiceTest` — updated: run now settles to `NOT_FILED_CHANNEL_UNAVAILABLE` with a reason, no receipt, no `submitted_at`; record counts still asserted.

---

## 6. Consumers needing a follow-up (NOT edited — other agents own them)

| Call site | Problem |
|---|---|
| `apps/admin-ui/src/api/reportsApi.js:23, 76, 103, 130` | mock/fallback report rows hardcode `status: 'SUBMITTED'` and the JSDoc status union is the old vocabulary — the Reports page renders a submitted filing that never happened. Should be `NOT_FILED_CHANNEL_UNAVAILABLE`. |
| `services/ops-partner-bff/src/main/java/com/gme/pay/bff/client/rest/RestReportingClient.java:88` | hardcodes `"GENERATED"` for every run. Not a false success, but it now drops the available truth — should carry through `filing_status` / `filing_channel_unavailable_reason` / `filing_channels` from the new envelope. |
| `services/ops-partner-bff/src/main/java/com/gme/pay/bff/web/dto/ReportRun.java:14` | javadoc status union still `PENDING \| GENERATED \| SUBMITTED \| FAILED`; needs the new states (and ideally a reason field). |
| `services/ops-partner-bff/src/main/java/com/gme/pay/bff/web/dto/RegulatoryConfigSummary.java:32` | `hometaxSet = v.hometaxIssuerCertId() != null` — true on `"stub-cert-id"`, i.e. true on a fully stubbed lane. Should apply the same placeholder rule, or consume `GET /v1/reports/filing-channels`. |
| `apps/admin-ui/src/app/compliance/page.jsx:341–347` + `src/api/complianceApi.js:22–24,128–152` | `SetBadge` renders `bokSet`/`hometaxSet`/`kofiuSet` as green ticks — reads as "lane live" when it only means "config entered". Should show channel-live vs config-entered separately. |

## 7. Still externally gated (unchanged by this work — T5-2 stays `[~]`)

BOK SFTP endpoint/credentials and the real `bok_txn_code` / `bok_fx_reporting_category` codes (OI-03, `TODO_OI03` still present); NTS mTLS certificate + XML signing (OI-02); KoFIU endpoint and the unconfirmed file layout (5 in-body TODOs in `KofiuFeedFileBuilder`); `StubKofiuTransactionPort` still returns `List.of()`, so no CTR/STR has been computed from real data; `RestCommittedFxTransactionPort` still gated off. None of these can now be misread as filed.
