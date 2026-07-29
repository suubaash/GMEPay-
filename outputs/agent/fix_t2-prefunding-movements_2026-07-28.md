> 작업: T2-8 prefunding movement query / 출처: agent

# T2-8 — a date-ranged float-movement query on prefunding, and a leg (b) that can be trusted

**Gap:** the SENDMN three-way tie-out (T2-2) had to read float movements through
`GET /v1/prefunding/{code}/deductions` — an endpoint built for a "recent activity" widget. It has
**no date filter**, clamps at **500 rows**, and shows **DEBITs only**. So the reconciler asked for the
newest 500 entries, windowed them client-side, and logged a WARN whenever the page came back full:
a finance control that announces it might be wrong. Past ~500 movements a day the oldest of that day
fell off the page and were reported as `MISSING_PREFUNDING` breaks that never happened; and because
reversals were invisible, a fully reversed deduct looked like live consumed float with no APPROVED
transaction behind it — another fabricated break.

**Scope:** `services/prefunding` (new endpoint) + `services/settlement-reconciliation` (consume it).
Nothing else touched — no `libs/**`, no `payment-executor`, no manifests.

---

## 1. The new endpoint

`GET /v1/prefunding/{code}/movements?from=&to=&types=&page=&size=`

| Property | Decision | Why it is this way |
|---|---|---|
| Window | `from` **inclusive**, `to` **exclusive** (half-open `[from, to)`), both required ISO-8601 instants | Consecutive windows tile the timeline exactly once. A movement stamped at 00:00 KST belongs to one day only, so two adjacent recon runs can neither double-count it nor lose it. Instants (not dates) keep the timezone decision with the caller — a KST business day is its own two instants. |
| Completeness | Paged; response carries `page`, `size`, `totalElements`, `totalPages`, `hasNext` | **No implicit cap.** A caller walks `hasNext` and knows it read everything. A truncated read is not representable — which is the entire difference from `/deductions`. `size` bounds one page (default 200, max 1000). |
| Reversals | Every entry type by default, each with raw `entryType`, a `direction`, and a **signed `balanceDeltaUsd`** | `DEBIT`/`CAPTURE` → negative, `CREDIT` (a reversal or a top-up) → positive, holds (`RESERVE`/`RELEASE`) and AML counters (`CUM_CHARGE`/`CUM_REVERSE`) → **zero**, because they never touch the balance. Summing the deltas over a reference is the net float effect without the consumer knowing prefunding's vocabulary. |
| Ordering | `created_at ASC, id ASC` | A ledger range read goes forward. The surrogate-key tie-break is load-bearing: without it, entries sharing an instant could repeat or vanish across a page boundary. |
| Errors | Malformed/inverted window → 400; unknown `types` value → 400 | A typo that returned an empty page would read to a finance control exactly like "nothing moved". Silent-empty is the failure mode being closed, so it is never an acceptable answer. |
| Auth | Same `X-Gme-Internal` gate as everything else in the service | It is a **complete float statement for a partner over a range** — if anything the most disclosive read prefunding publishes. Added to `InternalAuthGateTest`'s guarded-endpoint table, so its 401 is proven through the real filter chain on a real port, not asserted in prose. |

`/deductions` is **byte-for-byte unchanged** (other callers bind it; `PrefundingDeductionHistoryApiTest`
still passes, and a new test re-checks it alongside the replacement).

**Flyway V009** (next free version; module has only `db/migration`, no vendor dirs to mirror) adds
`idx_ledger_entry_partner_type_created (partner_id, entry_type, created_at)`. V002's
`(partner_id, created_at)` already served the unfiltered variant; this one puts `entry_type` between
the equality and range columns for the filtered variant the recon actually issues. Additive — one
index, nothing to back out but a `DROP INDEX`.

## 2. What changed in the reconciliation

- `RestPrefundingMovementClient` rewritten: pages the new endpoint (`RestClient`, 500/page,
  `types=DEBIT,CREDIT,CAPTURE`), window computed in the corridor's settlement zone.
- **The truncation WARN is deleted** — it is unreachable. The window is now applied server-side and
  paging runs to `hasNext=false`, so there is no client-side cap to warn about.
- **Partial reads are refused, not returned.** A failure part-way through paging, an empty body
  mid-sequence, a row count that disagrees with `totalElements`, or paging past 200 pages all
  **discard everything read so far** and log ERROR. Half a day of movements would produce
  `MISSING_PREFUNDING` breaks indistinguishable from real ones while the run looked successful —
  strictly worse than an obviously empty leg.
- **The client now presents `X-Gme-Internal`.** It never did. Prefunding's whole surface has been
  gated since T0-5 and refuses to boot without the secret, so **leg (b) would have 401'd in any
  gated deployment** — a latent break found while doing this, not a new requirement. Blank secret ⇒
  no header + WARN, never a fabricated credential (same discipline as `RestRegistrationStatusClient`).
- `PrefundingMovement.amountUsd` is now **signed float-consumed** (positive = deducted, negative =
  credited back) — the negation of the endpoint's balance-point-of-view delta, so it still compares
  directly against a transaction's `prefundingDeductedUsd`. A 4th `entryType` component was added
  with a 3-arg convenience constructor, so every existing call site stays source-compatible.
- `CorridorThreeWayReconciler`: the per-reference sum is unchanged (movements are still **summed,
  never collapsed**) and now nets a deduct against its reversal for free. One behavioural addition —
  a **prefunding-only reference whose movements net to zero is dropped from the reference union**,
  because no float was consumed and a reversed payment correctly never becomes APPROVED. The filter
  is on the union, not the map: a reference some other leg claims is still classified and still sees
  the zero, so an APPROVED transaction whose float was reversed remains a genuine `DISCREPANCY`.

## 3. Tests

`gradlew :services:prefunding:test :services:settlement-reconciliation:test` → **BUILD SUCCESSFUL**
(prefunding **145**, settlement-reconciliation **182**, 0 failures).

**prefunding — `PrefundingMovementsApiTest` (13 new).** Rows inserted straight into the ledger so
`created_at` is controlled to the instant: boundary entries at `from`, at `to`, and ±1ms either side
prove the window is genuinely half-open, plus a second window proving the entry at `to` lands in the
**next** day (days tile, never overlap); inverted and `from == to` windows and a malformed instant all
400; a DEBIT + its CREDIT reversal sum to exactly zero on `balanceDeltaUsd`; holds and AML counters
report zero delta while `CAPTURE` correctly reports a debit; the type filter narrows and an unknown
type 400s; **640 movements over only 8 distinct timestamps** (heavy collision, so a page boundary
landing mid-instant would repeat or drop rows) are walked in 3 pages with every `txnRef` seen exactly
once and `totalElements` matching; `size` clamps while the total stays honest; unknown partner → empty
page; `/deductions` still works. `InternalAuthGateTest` gains `/movements` to its table → the no-token
and wrong-token 401s and the trusted-caller pass-through are all proven for it.

**settlement-reconciliation — `RestPrefundingMovementClientTest` (9 new).** The window goes on the
wire with the token; a reversal arrives negative and nets its deduct to zero; **1,100 movements page
in full with no loss** (past the old 500 cap); a mid-paging 500 discards everything; a
`totalElements` mismatch discards the day; outage and 401 both degrade to empty; a blank secret sends
no header; un-joinable (`txnRef=null` operator top-up), zero-delta (hold) and unparseable-timestamp
rows are skipped; an unusable `balanceDeltaUsd` degrades to amount + direction rather than dropping a
real movement.

**The regression that matters:** `CorridorThreeWayReconcilerTest`'s pre-existing 9 tests are
**unmodified and still pass** — same fixtures, same classifications, same amounts, same cumulative
variance. 4 were added: a fully reversed deduct with no transaction is **not** a break (the old blind
spot); an APPROVED transaction whose float was reversed **is** a `DISCREPANCY` (netting must not hide
it); a partial reversal nets to the residual; and reversals net **per reference only**, so an
unrelated clean payment still MATCHES with the same +$2.00 retained.

## 4. Open / not done

1. **`gradlew testClasses` repo-wide currently fails in three modules that are not mine** —
   `scheme-adapter-nepal` (its tests reference `StubNepalSigner`, which has been deleted in the
   working tree), `payment-executor` (`NepalPaymentService` constructor/`pay` signature changed), and
   `revenue-ledger`. All three are another agent's in-flight edits. Excluding exactly those three,
   `testClasses` is **BUILD SUCCESSFUL** across the repo, including both modules I touched.
2. **No manifest change was needed** — no new env var, port or service. `settlement-reconciliation`
   already reads `gmepay.internal-auth.secret` from `GMEPAY_INTERNAL_AUTH_SECRET` (T0-2 wired it for
   the zeropay probe); the movement client reuses the same property, so deployments that already set
   it need nothing. Its `application.yml` comment now records that **two** callers depend on it.
3. **`services/prefunding/src/main/resources/application.properties` was deliberately left alone.**
   `/movements` is already covered by the existing `/v1/prefunding/**` gate pattern, so no functional
   change was required, and another agent has uncommitted edits in that file.
4. **The old client-side windowing is gone, but the corridor's date semantics are unchanged** — leg
   (b) still keys the day on the movement's `created_at` in the settlement zone. If the settlement
   day should ever key on something else (e.g. a scheme's own cut-off), that is a `CorridorSpec`
   question, not an endpoint one; the endpoint takes arbitrary instants.
5. **`types` is a filter, not a projection.** A caller wanting balance-moving entries only must pass
   `types=DEBIT,CREDIT,CAPTURE`; the default is deliberately *everything*, so a new entry type added
   to prefunding later shows up in an unfiltered read rather than being silently excluded.
6. Everything T2-2 left open stays open: no partner recon file (**gate O4**), no 9Pay corridor
   (**T4-7**), and the rate-basis variance is still **detected but not booked** (T2-5 / CFO#9).
