> 작업: scheme adapter architecture map / 출처: agent

# GMEPay+ scheme adapter architecture map (2026-07-27)

## 1. Where adapters live / ZeroPay key classes
- Adapters are standalone Spring Boot services under `D:/GMEPay+/code/services/`: `scheme-adapter-zeropay`, `scheme-adapter-nepal`. Package root `com.gme.pay.scheme.<code>`.
- ZeroPay classes:
  - ACL interface: `.../zeropay/adapter/SchemeAdapter.java`; impl `ZeroPaySchemeAdapter.java`; props `ZeroPayAdapterProperties.java`.
  - HTTP client: `.../zeropay/client/ZeroPaySchemeApiClient.java`; REST API in `.../zeropay/api/ZeroPaySchemeController.java`, `RegistrationStatusController.java`.
  - Codec (KFTC 전문/jeonmun): `.../zeropay/jeonmun/{JeonmunCodec,FieldSpec,FieldType,ZeroPayFrame,ZeroPayMessages,ZeroPayMpm420000}.java`.
  - Transport: `.../zeropay/transport/ZeroPayTcpTransport.java`; SFTP `.../zeropay/sftp/{SftpTransport,LocalDirSftpTransport}.java`.
  - Status/enums: `.../zeropay/status/{ZeroPayResultCode,ZeroPayErrorCodeMapper,UnknownZeroPayResultCodeException}.java`; models under `.../adapter/model/*` incl. `BatchType.java` (ZP0011/0012/0021/0022/0061/0063/0065/0066), `SchemeConfig.java`.
  - Batch/settlement formatters+parsers: `.../zeropay/batch/Zp00*` + `ZpSettlementRequest/ResultFormatter/Parser`, `ZeroPayBatchScheduler`.
  - Persistence + Flyway: `.../zeropay/persistence/*` and `src/main/resources/db/migration/V001–V003` (zp_batch_files, zp_staged_records, zp_committed_txns).
  - Config: `.../zeropay/config/OpenApiConfig.java`, `application.yml`/`application.properties`.

## 2. Scheme abstraction / SPI — there are TWO seams (not one)
- **Adapter-internal ACL**: `SchemeAdapter` (in scheme-adapter-zeropay only; NOT shared lib). Methods: `parseMerchantQR`, `prepareCPM`, `authoriseCpm`, `submitMpm`, `commitPayment`, `cancelPayment`, batch gen (`generatePaymentResultFile/generateRefundResultFile/generateSettlementRequestFile`), `parseInboundFile/validateInboundFile`, SFTP `transferOutbound/fetchInbound`, `getSupportedFiletypes/getSchemeConfig/healthCheck/processMerchantSync`. Nepal does NOT implement this interface (its own `NepalSchemeAdapter`) — the ACL is copied per adapter, not a shared SPI module.
- **Hub-core client seam**: `com.gme.pay.payment.domain.client.SchemeClient` (payment-executor). Methods: `submitMpm`, `submitCpm`, `cancelPayment`, `checkBalance` (default), `lookupStatus` (default, ADR-016 anti-double-charge). Implementations: `RestSchemeClient` (ZeroPay, default), `NepalRestSchemeClient` (SCHEME_CODE="NEPAL"), decorated by `ResilientSchemeClient` (circuit breaker/bulkhead, `@Primary`), dispatched by `SchemeClientRouter` (routes by `request.schemeId()` upper-cased; unknown→ZeroPay default).

## 3. Registration / configuration
- Roster SSOT (code-level, not DB): `config-registry` `SchemeCatalogService` — ZEROPAY/NEPAL=ACTIVE, BAKONG/KHQR/NAPAS_247/PROMPT_PAY/FAST_SG/QRIS=PLANNED. Exposed `GET /v1/schemes`.
- Numeric id map: `payment-executor` `SchemeId.java` (ZEROPAY=1…NEPAL=8) — must be kept in sync with catalog.
- DB: `config-registry` `partner_scheme` table (migration `V022`) with CHECK `ck_partner_scheme_scheme` listing the closed roster; `V037` adds `network_identifier` (CSV of EMVCo AIDs/GUIDs, back-fills com.zeropay / fonepay,nepalpay,khalti,...). Entities `PartnerSchemeEntity` (+`onPersist` mirrors network map), services `PartnerSchemeService.replaceDraftSchemes`, `SchemeCommissionShareService`, `MerchantFeeScheduleService`, `SchemeOperatingHoursEntity`.
- Per-adapter base URLs: `gmepay.scheme-adapters.<CODE>.base-url` (ZeroPay keeps legacy `gmepay.scheme-adapter-zeropay.base-url`).

## 4. Simulators (each own settings.gradle)
- `simulators/sim-scheme` (generic scheme sim: `SchemeProfile` enum KHQR/ZEROPAY, `SchemeConfig` picks via `gmepay.sim.scheme.profile`, EMVCo encoder `emvco/*`, `SchemeController`), `sim-merchant`, `sim-nepal-qr`, `sim-gmeremit`, `sim-wallet`, `sim-rate-provider`.
- Codec seam: `sim-merchant` `com.gme.sim.merchant.scheme.SchemeWireCodec` interface (`schemeId/displayName/payoutCurrency/buildStaticQr/buildDynamicCharge/buildStaticResult`), first impl `ZeroPaySchemeWireCodec`. New sim = new `SchemeWireCodec` impl + register; or add a `SchemeProfile` enum value in sim-scheme.

## 5. Payment flow / MPM vs CPM
- Entry `payment-executor` `WalletPayController` → `PaymentOrchestrator`. MPM = strict two-phase (`authorizeMpm` resolves quote/merchant/fee, `checkBalance`, then irreversible `schemeClient.submitMpm` last, carrying raw `qrPayload`); CPM path uses `submitCpm`. QR routing key from `QrSchemeClassifier` (EMVCo tag 26-51 sub-tag00 network id + tag58 country; JSON khalti/mobank) → `smart-router` (`SchemeRouter`, `PartnerSchemeRegistry`, `network→candidate` filtering). Per-corridor services `NepalPaymentService`, `GmeremitPaymentService`, `SendmnPaymentService`.

## 6. Webhooks/callbacks
- No inbound scheme→hub HTTP callback path found; schemes are polled/batch. `notification-webhook` is OUTBOUND only (hub→partner, Kafka `payment.approved` → `WebhookDispatcher`). Scheme results arrive via ZeroPay batch/SFTP inbound files (ZP0012/0022) parsed by the adapter, not webhooks. **Open issue: a push-callback scheme (e.g. 9Pay) has no existing inbound endpoint seam.**

## 7. Settlement — NOT scheme-pluggable
- `settlement-reconciliation` is ZeroPay-hardcoded: `AbstractZeroPayFileBuilder`, `ZP0061RequestBuilder`, `ZP0065PaymentDetailBuilder`, `ZP0066RefundDetailBuilder`; ZeroPay adapter also has ZP0061/0063/0065/0066 formatters. No scheme abstraction over settlement file format. **Open issue: new scheme with different settlement = new builders + generalizing the batch factory.**

## 8. Checklist — what a new scheme (e.g. SMN QR / 9Pay) must touch
1. New service `services/scheme-adapter-<code>/` (copy `scheme-adapter-nepal`): own `*SchemeAdapter`, API controller, `*SchemeApiClient`, codec/signer, `dto/*`, Flyway `db/migration/*`, `application.yml`.
2. payment-executor: new `<Code>RestSchemeClient` (define `SCHEME_CODE`), register in `SchemeClientRouter.byScheme`; add numeric id in `SchemeId.CODE_TO_ID`.
3. `QrSchemeClassifier`: add the QR network identifier/AID + JSON marker so scans classify to the new network.
4. config-registry: add row to `SchemeCatalogService.CATALOG`; add code to `partner_scheme` CHECK via new migration; back-fill `network_identifier` (new `V0xx`); smart-router registry picks it up.
5. Config: `gmepay.scheme-adapters.<CODE>.base-url` in payment-executor yml; docker-compose / helm values / run-fleet.ps1 service entry + port.
6. Settlement (if scheme settles differently): new file builders in `settlement-reconciliation` + generalize `SettlementBatchFactory` (currently ZeroPay-only).
7. Simulator: new `SchemeWireCodec` impl in sim-merchant and/or `SchemeProfile` enum value + EMVCo profile in sim-scheme (or a new `sim-<code>-qr` module with its own settings.gradle registered in root `simulators`).
8. Inbound results: if push-callback (9Pay), build a new inbound callback controller/endpoint (no existing seam); if file-based, add batch parsers like ZeroPay ZP0012/0022.

## Unresolved / architectural gaps
- (a) `SchemeAdapter` ACL is duplicated per adapter, not a shared SPI — no single interface to implement.
- (b) No inbound webhook/callback seam for push-notification schemes.
- (c) Settlement layer is ZeroPay-specific, not pluggable.
- (d) `SchemeCatalogService` roster, `SchemeId` map, and `partner_scheme` CHECK are three parallel lists that must be kept in sync manually.
