# GMEPay+ — Acceptance Test-Case Catalog

_What the platform must accomplish, as testable behaviors. Grounded in the current build (ZeroPay inbound + Nepal outbound, MPM/CPM, FX, prefunding, settlement, ADR-016 failover routing). Legend: **[E]** externally/cert-gated (KFTC/BOK/Hometax/KoFIU/vendor) — behavior specifiable, live run blocked by a third party._

## 1. QR scan & decode
- **TC-QR-01** Scan a static MPM merchant QR → decode returns merchant identity, **no amount** → customer must enter amount.
- **TC-QR-02** Scan a dynamic MPM QR → decode returns the **amount embedded in the QR** (customer cannot override).
- **TC-QR-03** Scan a ZeroPay QR (`com.zeropay`, country KR) → classified as ZeroPay.
- **TC-QR-04** Scan a Nepal Fonepay QR (`fonepay.com`, country NP) → classified as fonepay network.
- **TC-QR-05** Scan Khalti/mobank JSON QR → classified by JSON shape (khalti/mobank).
- **TC-QR-06** Malformed / unsupported QR → `INVALID_QR` / `UNSUPPORTED_QR`, no payment attempted.
- **TC-QR-07** CPM: customer-presented token generated with a scheme-issued prepare token + prefunding reservation (not a fabricated local token).

## 2. Routing & failover (ADR-016)
- **TC-ROUTE-01** QR classified to a network GUID → resolved to the owning partner via `partner_scheme.network_identifier` (not by country alone).
- **TC-ROUTE-02** One country, multiple partners on the same network → resolver returns **ordered candidate list** by `priority`.
- **TC-ROUTE-03** Adding a partner is a **config row** (network_identifier + priority) — no code change — and it appears in routing.
- **TC-ROUTE-04** Primary partner returns a **technical failure** (timeout/5xx/unavailable) → fails over to the next candidate.
- **TC-ROUTE-05** Primary returns a **business decline** (invalid QR / receiver-not-eligible / insufficient) → **terminal, no failover**.
- **TC-ROUTE-06 (money-critical)** Primary **times out but actually paid** → status-lookup returns APPROVED/PENDING → router returns that result and **does NOT submit to a second partner** (no double-charge).
- **TC-ROUTE-07** All candidates exhausted → `SCHEME_UNAVAILABLE` (not a generic error).
- **TC-ROUTE-08** Failover is bounded by `max-hops`; each attempt (partner, outcome, reason) is recorded.
- **TC-ROUTE-09** Unknown network / no candidate for location → `NO_SCHEME_FOR_LOCATION`.
- **TC-ROUTE-10** Payment mode not supported / direction not enabled for the resolved scheme → `PAYMENT_MODE_NOT_SUPPORTED` / `DIRECTION_NOT_ENABLED`.

## 3. Payment execution
- **TC-PAY-01** GMEREMIT domestic (KRW): pay → charge = amount + flat service fee (₩500), APPROVED, txn ref returned.
- **TC-PAY-02** SENDMN overseas inbound (KRW→foreign): FX applied, prefunding deducted, margin booked, APPROVED.
- **TC-PAY-03** Nepal outbound: scan Fonepay QR → enter amount → routed to Nepal adapter → partner `pay` → APPROVED with scheme `idx`.
- **TC-PAY-04** MPM static: amount the customer enters is the amount authorized.
- **TC-PAY-05** MPM dynamic: amount from the QR is authorized regardless of a caller-supplied value.
- **TC-PAY-06** Idempotent pay: same idempotency key replays the original result, no second charge.
- **TC-PAY-07** Merchant not found / suspended / deactivated (strict mode) → hard decline (`MERCHANT_NOT_FOUND` / `MERCHANT_SUSPENDED` / `MERCHANT_DEACTIVATED`), not a synthesized merchant.
- **TC-PAY-08** Scheme adapter unreachable → the wallet sees `SCHEME_UNAVAILABLE`, not a generic `HUB_ERROR`.

## 4. FX & rate (USD-intermediary)
- **TC-FX-01** Cross-border quote uses USD-intermediary two-leg calc (collection + payout) with market±buffer cost rates.
- **TC-FX-02** SEND vs RECEIVE mode produce the correct directional amounts (pool-identity holds).
- **TC-FX-03** Margin applied on the USD amount over `send_usd_cost` base; revenue split into Phase-1 locked margin vs Phase-2 trading gain.
- **TC-FX-04** `offer_rate_coll` (BOK FX1015 #14) computed from the **real** collection margin, not a zero-margin approximation.
- **TC-FX-05** Same-currency payment short-circuits FX (no rate applied).
- **TC-FX-06** Quote has a TTL; expired quote is rejected at commit; TTL survives restart (durable store).
- **TC-FX-07** Partner-B quote source: a rule marked `PARTNER` routes that leg to the Partner-B quote; deviation beyond tolerance → `PARTNER_B_QUOTE_DEVIATION`.
- **TC-FX-08** New corridor (e.g. KRW/USD→NPR) resolves a rate and books margin.

## 5. Prefunding
- **TC-PF-01** Atomic deduct under concurrency never drives balance negative (SELECT FOR UPDATE).
- **TC-PF-02** Deduct/reverse are idempotent on the key; reverse restores balance.
- **TC-PF-03** CPM reserve holds funds at token issuance; release frees them on expiry/decline; reserved funds are excluded from available.
- **TC-PF-04** Low-balance tier alerts fire at 95/85/70%; BREACH triggers config-registry auto-suspend.
- **TC-PF-05** Credit-limit push from config-registry raises available headroom; AML caps enforced.
- **TC-PF-06** Deduction-history endpoint returns recent deductions (drives wallet `?include_history`).
- **TC-PF-07** GME↔partner and GME↔scheme (e.g. Khalti prefund/postfund) balances tracked independently.

## 6. Transaction lifecycle
- **TC-TXN-01** A committed payment is recorded with a txn ref and is retrievable by ref.
- **TC-TXN-02** FSM transitions valid only along allowed edges; SCHEME_SENT / UNCERTAIN / REVERSED / REFUNDED mapped (PATCH performs a real transition, not a no-op).
- **TC-TXN-03** Committed-FX projection (`/v1/transactions/fx-committed`) exposes rate-locked fields (offer_rate_coll, cross_rate, margins) for reporting.
- **TC-TXN-04** Refund query by refund date (`/refunded`) returns refund legs with original-payment ref (canonical `RefundedTransactionView`).
- **TC-TXN-05** UNCERTAIN transactions are excluded from the expiry sweeper and flagged for reconciliation.
- **TC-TXN-06** Idempotency key enforced on the orchestrated payments path.

## 7. Settlement & reconciliation
- **TC-SET-01** Net/gross settlement computed per partner under the partner's rounding mode; residual posted to `REVENUE_ROUNDING`.
- **TC-SET-02** Recon ties GME booked net against the scheme file (ZP0062/0064); a real mismatch raises an exception, a NET batch does not falsely mismatch.
- **TC-SET-03** Recon is idempotent — a re-run does not double-post or duplicate lines.
- **TC-SET-04** Rounding residual posted **exactly once per batch** (guarded), even on retry.
- **TC-SET-05** Cross-date refund clawback reduces the batch net; negative net clamped per scheme rules.
- **TC-SET-06** Settlement exceptions listable/resolvable/re-runnable with operator audit.
- **TC-SET-07 [E]** ZeroPay ZP00xx batch files generated with real (non-zero-record) data over committed txns; SFTP+PGP transport.
- **TC-SET-08** Nepal partner settlement recorded; GME↔Khalti prefund/postfund reconciled; partner→GME SWIFT credit posts to the partner balance.

## 8. Revenue & ledger
- **TC-REV-01** `payment.approved` consumed (canonical typed event) → revenue-capture journal posted with partner/scheme/margins.
- **TC-REV-02** Double-entry postings balance; fee-share split is configurable (not a fixed 70/30).
- **TC-REV-03** `GET /v1/revenue` returns `total_rounding_usd` and per-range aggregates.
- **TC-REV-04** Rounding-residual post is idempotent on reference (settlement retry safe; per-txn and per-batch refs coexist).

## 9. Refund / cancel
- **TC-RF-01** Same-day cancel reaches the scheme and marks the txn REVERSED with a real FSM transition.
- **TC-RF-02** Cancel books the actual reversed prefunding amount (not hardcoded zero) and a structured revenue reversal.
- **TC-RF-03** Admin-portal refund flow drives cancel end-to-end.

## 10. Events & webhooks
- **TC-EVT-01** `payment.approved` / `transaction.committed` emitted on commit via the outbox (topic `gmepay.<eventType>`).
- **TC-EVT-02** Producer and consumers agree on the canonical payload type (camelCase, money as decimal strings).
- **TC-WH-01** Partner webhook: PENDING delivery row created, dispatched, signed (HMAC), retried with backoff, DLQ on exhaustion.
- **TC-WH-02** Webhook delivery is idempotent; re-delivery does not duplicate.

## 11. Partner onboarding & config
- **TC-ONB-01** 8-slice onboarding wizard: draft → 4-eyes approval → activation → credential issuance.
- **TC-ONB-02** All config changes go through 4-eyes change-request; DB CHECK + state machine enforce it.
- **TC-ONB-03** Audit log is hash-chained and verifiable (chainValid=true), written in the same transaction.
- **TC-ONB-04** KYB verification called at the KYB step; providerRef + decision persisted; PASS/FAIL/MANUAL_REVIEW.
- **TC-ONB-05** Pricing rule: cross-border margin invariant (≥2%) enforced; rate-source per leg (IDENTITY/LIVE/MANUAL/PARTNER) honored.
- **TC-ONB-06** Scheme catalog (`GET /v1/schemes`) equals the enablement roster equals the DB CHECK (no drift).

## 12. Auth & security
- **TC-SEC-01** Gateway HMAC-SHA256: valid signature accepted, invalid/tampered rejected.
- **TC-SEC-02** Replay-protection filter rejects a re-used nonce/timestamp outside the window.
- **TC-SEC-03** Per-partner rate limiting returns 429 + `Retry-After` beyond the quota.
- **TC-SEC-04** Partner credentials resolved from a real store (config-registry/auth-identity), not hardcoded test keys.
- **TC-SEC-05** mTLS / IP-allowlist enforced where configured.
- **TC-SEC-06 [E]** Operator login via real JWT/OIDC (Keycloak), not the `password=demo` mock; RBAC permission matrix enforced; partner-scoped reads blocked across partners.
- **TC-SEC-07** No cloud-provider SDK on any runtime classpath (portability guard fails the build if added).

## 13. Merchant / QR data
- **TC-MER-01** `GET /v1/merchants/{qr}` resolves identity/status/active; 404 on unknown.
- **TC-MER-02** Strict mode rejects SUSPENDED/DEACTIVATED with the precise 422 code.
- **TC-MER-03 [E]** Merchant/QR sync from the scheme (ZeroPay files) upserts + deactivates-on-receipt; orphan reconciliation.

## 14. Compliance & reporting
- **TC-CMP-01 [E]** BOK FX1014/FX1015 daily report generated; field #14 `offer_rate_coll` populated from committed-FX data.
- **TC-CMP-02 [E]** Domestic vs cross-border classified with correct legal-basis/FX-exemption mapping.
- **TC-CMP-03 [E]** KoFIU CTR/STR thresholds detected; filings idempotent and persisted.
- **TC-CMP-04 [E]** Hometax monthly merchant-fee tax invoice (overseas) generated.
- **TC-CMP-05** Filing records idempotent (no duplicate submission per lane/type/date).

## 15. Admin & partner UIs
- **TC-UI-01** Transactions/settlement/revenue/dashboard pages show **live** data when BFF rest clients are enabled (not stub fixtures).
- **TC-UI-02** Onboarding wizard + approvals queue + audit trail render real partner data.
- **TC-UI-03** Sandbox "Simulation Console" tabs (Merchant, Wallet, FX, **Nepal QR**, Service Trace) load; the Nepal QR console decodes/pays and shows the stored request/response.
- **TC-UI-04** Nepal QR console works **remotely** (over the tunnel) via the same-origin server-side proxy — not a client-side localhost iframe.
- **TC-UI-05** Partner portal: balance, transaction history, statements reflect the partner's real data (scoped).

## 16. Simulators / sandbox
- **TC-SIM-01** sim-scheme authorizes+commits a ZeroPay MPM/CPM payment.
- **TC-SIM-02** sim-nepal-qr validate/parse/pay/status behave per the Khalti/Fonepay contracts; `pay` dedups reference.
- **TC-SIM-03** sim-nepal-qr stores every request/response (with base64-decoded payload) and exposes them via `/sim/nepal-qr/records`.

## 17. Cross-cutting / NFR
- **TC-NFR-01** Every backing dependency (DB/Redis/Kafka/object-store/OIDC/OTLP) is env-injected; no hardcoded provider endpoint.
- **TC-NFR-02** Same container images deploy to on-prem / AWS / Azure via a values overlay only (cloud-agnostic).
- **TC-NFR-03** Services degrade gracefully when a downstream is gated off (fixture/stub fallback) without crashing.
- **TC-NFR-04** Errors use the canonical `ApiError` envelope with stable codes + correct HTTP status.
- **TC-NFR-05 [E]** Money-path E2E on a real stack (PostgreSQL/Kafka/Redis) green in CI; performance meets NFR targets; pen-test passed.

## 18. Certification-gated (external parties)
- **TC-CERT-01 [E]** ZeroPay/KFTC certification passed; live scheme connectivity.
- **TC-CERT-02 [E]** Real Nepal partner (Khalti) live endpoint + RSA-signed requests + IP-allowlist.
- **TC-CERT-03 [E]** BOK / Hometax / KoFIU real submission channels (mTLS/SFTP) verified.

---
_Grounded in the 2026-06/07 build. **[E]** = specifiable now, live-verifiable only once the external party / real infra is available. Owning services per case live in `docs/SERVICE_MAP.md` + `Documentation/services_backlog/`._
