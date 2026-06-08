# GMEPay+ — Atomic Ticket Authoring Brief (READ FIRST)

You decompose ONE WBS work-package into **atomic developer tickets**. Each ticket must be doable by a
developer in **60 minutes or less**, be **fully self-contained** (a dev with ZERO project context can
complete it from the ticket alone), and have a **concrete deliverable** plus **explicit logic/acceptance
checks**. You are NOT writing prose — you emit structured JSONL.

## Hard rules for every ticket
1. **≤ 60 minutes.** If a unit of work is bigger, split it into multiple tickets. Typical = 20–45 min.
2. **Self-contained.** The `context` field must give enough background (entities, formula, field names,
   file/endpoint) that the assignee needs no other document. Restate the relevant rule/formula inline.
   Never say "see the spec" — quote what's needed.
3. **One clear deliverable.** A specific artifact: a migration file, a function/class, an endpoint handler,
   a validation rule, a unit test, a config schema, a DB index, etc. Name it concretely.
4. **Verifiable logic checks.** 2–5 checks the assignee (or a reviewer) can verify objectively, INCLUDING
   edge cases and invariants (e.g. "rejects when m_a+m_b < 2%", "pool identity holds within 0.01 USD",
   "deduction is atomic under concurrent calls"). These ARE the acceptance criteria.
5. **Concrete, not vague.** Use real field names, types, currencies, and numbers. Prefer test tickets with
   exact input→expected output.
6. **Include test tickets.** For logic-bearing work-packages, add explicit unit-test tickets with sample
   vectors. Tests are tickets too.

## Output: write a JSONL file
Write your tickets to the EXACT path you are given (one file per work-package), using the Write tool.
**One JSON object per line. No surrounding array. No markdown.** Each line MUST be valid JSON with these keys:

```
{"id":"<WBSREF>-T01","title":"<imperative, <=90 chars>","context":"<1-3 sentences, self-contained>","steps":["step 1","step 2"],"deliverable":"<concrete artifact>","checks":["verifiable check 1","check 2"],"minutes":30,"deps":["<other ticket id or WBS ref>"]}
```
- `id`: `<WBSREF>-T01`, `-T02`, … in execution order (WBSREF is given to you, e.g. `4.2`).
- `steps`: 2–6 short imperative bullets.
- `checks`: 2–5 objective acceptance/logic checks (the "definition of done").
- `minutes`: integer ≤ 60.
- `deps`: array of ticket IDs (this or other work-packages) or `[]` if none.
- Keep strings free of literal newlines and unescaped quotes (use simple ASCII; avoid `"` inside strings — paraphrase).
Do not include workstream/phase/role/source — those are added automatically from the parent work-package.

## How to work
1. Read the relevant Part of `spec_full.txt` (path given) for your work-package's domain — but DISTILL it
   into the ticket text so the ticket stands alone.
2. Decompose the work-package into the full ordered set of ≤1h tickets needed to actually deliver it:
   schema/interface → core logic → edge cases/validation → wiring → unit tests → docs. Be exhaustive but
   non-redundant. A 6 person-day work-package typically yields ~30–50 tickets; a 3 PD one ~15–25.
3. Write the JSONL file. Reply with one line: the file path + ticket count.

## Canonical facts you can rely on (restate the needed bits inside tickets)
**Product.** GMEPay+ is a Global QR Payment Hub: one Partner API reaches many national QR Schemes. GME is a
neutral routing + FX + settlement layer. First scheme = ZeroPay (operator 한결원/KFTC). Partners = payment
apps (e.g. GME Remit = LOCAL/KRW; SendMN, T-Bank = OVERSEAS, prefunded USD).

**Entities.** Scheme, Partner (type LOCAL|OVERSEAS), Rule = (partner × scheme × direction), Direction =
Inbound|Outbound|Domestic|Hub. Payment modes: Fixed MPM (merchant static QR) and CPM (customer dynamic QR).
Adding a scheme/partner/rule is CONFIG, never code.

**Three-currency model.** Collection ccy → Settlement A → USD intermediary (BOK-required pivot) →
Settlement B → Payout ccy. Treasury rate convention: `treasury.usd_{ccy}` = units of {ccy} per 1 USD.

**Rate engine — USD-volume margin, RECEIVE mode (payout-first), 5 steps** (cost_rate_pay =
treasury.usd_{settle_b_ccy}; cost_rate_coll = treasury.usd_{settle_a_ccy}):
1. `payout_usd_cost = target_payout / cost_rate_pay`
2. `collection_usd  = payout_usd_cost / (1 - m_a - m_b)`
3. `collection_margin_usd = collection_usd * m_a` ; `payout_margin_usd = collection_usd * m_b`
4. `send_amount = collection_usd * cost_rate_coll`
5. `collection_amount = send_amount + service_charge`  (service_charge is flat, in Settle A ccy, and NEVER
   enters the USD pool)
- **Pool identity (invariant):** `collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost` (tolerance ≈ 0.01 USD).
- **Derived (BOK, never configured):** `offer_rate_coll = send_amount / (collection_usd - collection_margin_usd)` (BOK FX1015 #14); `cross_rate = target_payout / send_amount`.
- **Identity legs:** Settle A = USD → cost_rate_coll = 1.0; Settle B = USD → cost_rate_pay = 1.0 (or Partner B quote).
- **Same-currency short-circuit:** if collection = settle_A = settle_B = payout, skip the USD pool; `collection_amount = target_payout + service_charge`.
- **Min combined margin:** `m_a + m_b >= 2.0%` for cross-border rules; 0 allowed for same-currency.
- **Partner B authoritative quote:** deviation beyond tolerance (default 1.0%) → error `PARTNER_B_QUOTE_DEVIATION` (do not commit); unreachable → `PARTNER_B_QUOTE_UNAVAILABLE` (no fallback).
- **Rate quote TTL:** default 60s (aggregator-bound) / 300s otherwise; configurable 60–1800s. `validUntil = quote_issued_at + ttl`. On commit, all USD-pool values + derived rates are permanently recorded (rate-lock); later treasury/margin changes never affect committed txns.
- **MPM vs CPM:** one engine; GMEPay+ provides `offer_rate`; the PARTNER computes `collection_amount`; GMEPay+ never validates the partner's collection amount.

**Prefunding (OVERSEAS only).** Prepaid USD balance. Deduct: Fixed MPM at `POST /v1/payments`; CPM at
`POST /v1/payments/cpm/generate`. Deduction is ATOMIC (SELECT ... FOR UPDATE). Scheme is never called
without a prior successful deduction. Insufficient balance → reject before scheme. Low-balance alert per
partner. LOCAL partners (GME Remit) need no prefunding.

**Money handling.** Store currency + amount; respect per-currency scale (KRW = 0 decimals, USD = 2). Apply
rounding only at defined points; never let floating error break the pool identity (use decimal types).

**Audit.** Config changes log actor, timestamp, previous value, and apply only to NEW transactions.
Transactions carry an 8-step event trail.

When your work-package touches areas beyond these notes (e.g. ZeroPay file layouts, security), read the
corresponding Part in `spec_full.txt` and distill the needed specifics into the ticket text.
