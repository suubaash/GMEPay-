> 작업: wallet /v1/pay Nepal routing fix / 출처: agent

# Wallet `/v1/pay` — Nepal Fonepay QR routing fix

Branch `fix/wallet-nepal-routing` (worktree `wt3/wallet-nepal`, off `main`). Edits confined to
`services/payment-executor/`.

## Root cause (1-liner)
`WalletPayController` dispatched `POST /v1/pay` ONLY by `partner`; a Fonepay QR arrives as
`partner=GMEREMIT` → ZeroPay domestic path → ZeroPay merchant-lookup 404 / parse fail →
DECLINED (HUB_ERROR). Nothing set `schemeId=NEPAL`, so the `SchemeClientRouter` never reached
the Nepal adapter.

## What was added / changed

### 1. `NepalQrDetector` (domain) — new
`static boolean isNepal(String qrPayload)`. Any marker → Nepal (case-insensitive): `fonepay.com`,
EMVCo country tag `5802NP` (tag 58 = "NP"), `khalti`, `nepalpay`, `npqr`. Null/blank → false.
Does NOT match ZeroPay QRs (`com.zeropay` / `5802KR`).

### 2. `NepalPaymentService` (domain) — new
`pay(qrPayload, amount, userRef)` mirrors `GmeremitPaymentService` shape and returns
`WalletResult`. Builds `MpmSubmitRequest(txnRef, merchantId=null, amount, "NPR", schemeId="NEPAL",
qrPayload)` and calls the injected `SchemeClient` (the `@Primary SchemeClientRouter`) → routes to
`NepalRestSchemeClient` → sim. Skips ZeroPay `merchant-qr-data` validation (adapter resolves the
merchant). Amount treated as NPR pass-through — the adapter converts to paisa (×100 HALF_UP); a
`TODO(fx)` marks KRW→NPR via rate-fx as the follow-up. Maps adapter `{schemeTxnRef,status}` →
`WalletResult` (APPROVED status → approved, carries schemeTxnRef). Records the txn in
transaction-mgmt (try/catch, resilient). On `SchemeDeclinedException`/`PaymentException` →
DECLINED carrying the **adapter's own reason** (not a generic HUB_ERROR).

### 3. `WalletPayController.pay` — changed
Before the partner branch: `if (nepalPaymentService != null && NepalQrDetector.isNepal(qrPayload))`
→ `nepalPaymentService.pay(...)`. GMEREMIT/SENDMN branches unchanged (partner stays whatever the
wallet sent; Nepal is decided by the QR). `NepalPaymentService` added to the `@Autowired` primary
ctor; the backwards-compat 2-arg test ctor still compiles (passes null).

## Test status
`./gradlew :services:payment-executor:test --console=plain` → **BUILD SUCCESSFUL**, all green.
- `NepalQrDetectorTest`: 5 passed (Fonepay sample, ZeroPay non-misfire, each marker,
  case-insensitivity, null/blank).
- `WalletPayControllerTest`: 9 passed (7 pre-existing + 2 new: Fonepay QR partner=GMEREMIT →
  Nepal service → 201 APPROVED w/ schemeTxnRef, gmeremit untouched; ZeroPay QR → GMEREMIT, Nepal
  untouched). All pre-existing payment-executor tests remain green.

## Runtime requirements (for a LIVE success, not just the mocked tests)
- **payment-executor** up (exposes `/v1/pay`).
- **scheme-adapter-nepal** (or the Nepal sim) reachable at
  `gmepay.scheme-adapters.NEPAL.base-url` (default `http://localhost:18091`), exposing
  `POST /internal/scheme/nepal/submit` returning `{schemeTxnRef,status:APPROVED,amountPaisa}`.
- transaction-mgmt optional (recording is resilient/try-catch — absence only skips the record).
- ZeroPay adapter + merchant-qr-data NOT required on the Nepal path.

## Remaining (≤3)
1. **KRW→NPR FX** — wallet UI is KRW-labeled but amount is sent as NPR (sandbox happy path). Wire
   rate-fx before production.
2. **merchantName** — not surfaced for Nepal (WalletResult.merchantName=null); adapter resolves it
   internally. Carry it through if the wallet UI needs the name.
3. **Refund/cancel** — Nepal is single-phase; `NepalRestSchemeClient.cancelPayment` throws. The
   `/v1/pay/{ref}/refund` path still routes cancels to ZeroPay — no Nepal refund yet.
