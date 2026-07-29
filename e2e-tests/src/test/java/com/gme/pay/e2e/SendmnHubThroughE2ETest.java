package com.gme.pay.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Hub-through E2E for the <b>SENDMN corridor</b> (QR scheme plan Phase 4): a wallet scans a
 * QPay (Mongolia) static QR and pays a <b>KRW</b> amount through the REAL hub money path —
 *
 * <pre>
 *   wallet ──POST /v1/pay (partner=SENDMN, KRW)──▶ payment-executor
 *      → GET  /v1/rates (KRW→MNT, USD→KRW)         → sim-rate-provider   (live FX + margin)
 *      → POST /v1/prefunding/2/deduct              → prefunding          (USD, atomic)
 *      → POST /internal/scheme/sendmn/verify-qr    → scheme-adapter-sendmn
 *      → POST /internal/scheme/sendmn/submit-mpm   →   └─▶ sim-sendmn    (VerifyQr/Confirm)
 * </pre>
 *
 * <p>Unlike {@link SendmnAdapterSimE2ETest} (adapter ↔ sim pair only), this test proves the
 * corridor end to end from the wallet entry point: classification of the QPay QR, the
 * KRW→MNT FX offer (mid × 0.98), the fixed ₩500 fee, the USD prefunding deduction, the
 * SendMN-registered-rate settlement at the scheme, and the hub's recorded attempt.
 *
 * <p><b>Rate tolerance.</b> sim-rate-provider random-walks its mid-rates ±0.3% every 60 s,
 * so rate-derived expectations are asserted against rates fetched immediately before the
 * pay with a small relative tolerance; every internal relationship (fee, charged, MNT ↔
 * offer rate, settlement ↔ registered rate, deduction ↔ balance delta) is asserted exactly.
 *
 * <p><b>Negative control.</b> A payment whose USD equivalent exceeds the remaining prefund
 * balance must decline with INSUFFICIENT_PREFUNDING <i>before any scheme call</i> — proven
 * by the adapter's durable by-reference index 404ing the declined attempt's reference
 * (verify-qr persists the reference BEFORE Confirm, so a 404 = the adapter was never called).
 *
 * <p>Also exercises the restart-fallback probe leg: the adapter's
 * {@code GET /internal/scheme/sendmn/status/by-reference/{reference}} resolves the hub's
 * partner reference to APPROVED after the payment — the durable leg the hub falls back to
 * when its in-process reference→txTokenNo map is lost.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("E2E: SENDMN corridor hub-through — wallet -> payment-executor -> scheme-adapter-sendmn -> sim-sendmn")
class SendmnHubThroughE2ETest {

    // ---- fleet ports (deliberately clear of every service/sim default so a dev fleet or a
    //      zombie from another E2E class can never collide; assertPortsFree guards anyway) ----
    private static final int PORT_CONFIG_REGISTRY   = 18181;
    private static final int PORT_PREFUNDING        = 18185;
    private static final int PORT_SIM_RATE          = 19101;
    private static final int PORT_SIM_SENDMN        = 19106;
    private static final int PORT_ADAPTER           = 18093;
    private static final int PORT_PAYMENT_EXECUTOR  = 18084;

    private static final String PAY   = "http://localhost:" + PORT_PAYMENT_EXECUTOR;
    private static final String PFND  = "http://localhost:" + PORT_PREFUNDING;
    private static final String RATES = "http://localhost:" + PORT_SIM_RATE;
    private static final String SIM   = "http://localhost:" + PORT_SIM_SENDMN;
    private static final String ADPT  = "http://localhost:" + PORT_ADAPTER;

    /** WalletPayController's SENDMN sandbox partner id — prefunding is keyed on it. */
    private static final String SENDMN_PARTNER = "2";

    /** Registered MNT/USD buy rate — deliberately NOT the sim default (3373.00). */
    private static final BigDecimal REGISTERED_RATE = new BigDecimal("3391.50");

    private static final BigDecimal OPENING_USD = new BigDecimal("100.00");
    private static final BigDecimal AMOUNT_KRW  = new BigDecimal("50000");
    private static final BigDecimal FEE_KRW     = new BigDecimal("500");
    /** Big enough that its USD cost always exceeds the remaining prefund (~63 USD). */
    private static final BigDecimal TOO_BIG_KRW = new BigDecimal("200000");

    /** Relative tolerance for rate-derived figures (one ±0.3% walk step per leg + rounding). */
    private static final BigDecimal REL_TOL = new BigDecimal("0.015");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static SchemeFleet fleet;

    // Captured by the happy path, used by later scenarios.
    private static String qrPayload;
    private static String simMerchantId;
    private static String approvedPartnerTxnRef;
    private static String txTokenNo;
    private static String paymentNo;

    // -------------------------------------------------------------------------
    // Fleet lifecycle
    // -------------------------------------------------------------------------

    @BeforeAll
    static void bootFleet() throws Exception {
        fleet = new SchemeFleet("e2e-logs-sendmn-hub");
        fleet.assertPortsFree(PORT_CONFIG_REGISTRY, PORT_PREFUNDING, PORT_SIM_RATE,
                PORT_SIM_SENDMN, PORT_ADAPTER, PORT_PAYMENT_EXECUTOR);

        // Downstream first; payment-executor last. config-registry is REQUIRED (fail-closed
        // operational kill-switch — without it every payment declines SYSTEM_PAUSED).
        fleet.launchService("config-registry", PORT_CONFIG_REGISTRY, Map.of());
        fleet.launchService("prefunding", PORT_PREFUNDING, Map.of());
        fleet.launchSim("sim-rate-provider", PORT_SIM_RATE, Map.of());
        fleet.launchSim("sim-sendmn", PORT_SIM_SENDMN, Map.of(),
                // The sim pushes its registered FX rate to the adapter (SendMN→partner, doc §9).
                "--gmepay.sim.sendmn.fx-push.url=http://localhost:" + PORT_ADAPTER
                        + "/partner-hosted/fx-rate");
        fleet.launchService("scheme-adapter-sendmn", PORT_ADAPTER, Map.of(),
                "--sendmn.base-url=http://localhost:" + PORT_SIM_SENDMN);
        fleet.launchService("payment-executor", PORT_PAYMENT_EXECUTOR, Map.of(),
                "--gmepay.config-registry.base-url=http://localhost:" + PORT_CONFIG_REGISTRY,
                "--gmepay.prefunding.base-url=http://localhost:" + PORT_PREFUNDING,
                "--gmepay.sim-rate-provider.base-url=http://localhost:" + PORT_SIM_RATE,
                "--gmepay.scheme-adapters.SENDMN.base-url=http://localhost:" + PORT_ADAPTER,
                // merchant-qr-data is not booted: Mongolian merchants are resolved by the
                // SendMN scheme at verify-qr, not by GME's domestic registry (lenient mode).
                "--gmepay.payment.merchant-validation=lenient",
                // read-only H2 introspection: the harness asserts the recorded attempts.
                "--gmepay.devtools.enabled=true");
        fleet.awaitUp(Duration.ofSeconds(180));

        // Seed the SENDMN partner's USD prefunding balance (partner id 2 — the controller's
        // sandbox constant; the seed runner only seeds the code "SENDMN", not "2").
        HttpResponse<String> provision = SchemeFleet.post(PFND + "/v1/prefunding/provision", """
                { "partnerCode": "%s", "openingBalanceUsd": "%s", "lowBalanceThresholdUsd": "10.00" }
                """.formatted(SENDMN_PARTNER, OPENING_USD.toPlainString()));
        if (provision.statusCode() != 201) {
            fail("could not provision prefunding for partner " + SENDMN_PARTNER
                    + ": HTTP " + provision.statusCode() + " " + provision.body());
        }
    }

    @AfterAll
    static void stopFleet() {
        if (fleet != null) {
            fleet.shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // 1) FX rate registration (SendMN -> partner direction) at the adapter
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("FX registration: sim registers a fresh MNT/USD buy rate with the adapter")
    void fxRateRegisteredAtAdapter() throws Exception {
        HttpResponse<String> set = SchemeFleet.post(SIM + "/sim/fx-rate",
                "{\"rate\": \"" + REGISTERED_RATE.toPlainString() + "\"}");
        assertEquals(200, set.statusCode(), "sim fx-rate set. Body: " + set.body());

        HttpResponse<String> push = SchemeFleet.post(SIM + "/sim/fx-rate/push", "{}");
        assertEquals(200, push.statusCode(), "sim fx-rate push to adapter. Body: " + push.body());

        HttpResponse<String> latest = SchemeFleet.get(ADPT + "/internal/scheme/sendmn/fx-rate/latest");
        assertEquals(200, latest.statusCode(), "adapter latest fx-rate. Body: " + latest.body());
        assertEquals(0, REGISTERED_RATE.compareTo(
                        new BigDecimal(JSON.readTree(latest.body()).path("rate").asText())),
                "adapter must settle at the registered rate. Body: " + latest.body());
    }

    // -------------------------------------------------------------------------
    // 2) Corridor discovery: the hub classifies the QPay QR
    // -------------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("classify: hub resolves a seeded QPay QR to network=qpay / MN / MNT / scheme SENDMN")
    void hubClassifiesQpayQr() throws Exception {
        // Seeded amount-less merchant + its static QPay QR from the sim console.
        HttpResponse<String> merchants = SchemeFleet.get(SIM + "/sim/merchants");
        assertEquals(200, merchants.statusCode());
        JsonNode chosen = null;
        for (JsonNode m : JSON.readTree(merchants.body())) {
            if (m.path("fixedAmount").isNull() || m.path("fixedAmount").isMissingNode()) {
                chosen = m; // amount-less static QR — the wallet keys the amount in
                break;
            }
        }
        assertNotNull(chosen, "sim-sendmn seeds no amount-less merchant: " + merchants.body());
        simMerchantId = chosen.path("merchantId").asText();

        HttpResponse<String> qr = SchemeFleet.get(
                SIM + "/sim/qr/" + URLEncoder.encode(simMerchantId, StandardCharsets.UTF_8));
        assertEquals(200, qr.statusCode(), "sim QR lookup for " + simMerchantId);
        qrPayload = qr.body();
        assertTrue(qrPayload.startsWith("000201"), "seeded QR must be EMVCo MPM: " + qrPayload);

        // GMEPay+ is the authority for what the scanned QR is: the wallet calls classify
        // BEFORE amount entry and learns the corridor (SENDMN) and merchant currency (MNT).
        // The QPay EMVCo AID (GUID A000000843000101) must resolve — this exact journey
        // declined unsupported_qr until the classifier learned the QPay RID.
        HttpResponse<String> classify = SchemeFleet.post(PAY + "/v1/pay/classify",
                JSON.writeValueAsString(Map.of("qrPayload", qrPayload)));
        assertEquals(200, classify.statusCode(), "classify. Body: " + classify.body());
        JsonNode c = JSON.readTree(classify.body());
        assertTrue(c.path("supported").asBoolean(false), "QPay QR must be supported. Body: " + classify.body());
        assertEquals("qpay", c.path("network").asText(), "Body: " + classify.body());
        assertEquals("MN", c.path("country").asText(), "Body: " + classify.body());
        assertEquals("MNT", c.path("currency").asText(), "Body: " + classify.body());
        assertEquals("SENDMN", c.path("scheme").asText(), "Body: " + classify.body());
    }

    // -------------------------------------------------------------------------
    // 3) Happy path: KRW pay through the full corridor
    // -------------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("happy path: KRW 50,000 -> APPROVED with FX fields; sim Approved@registered-rate; prefund deducted once (USD)")
    void walletPaysKrwThroughSendmnCorridor() throws Exception {
        // Live mid-rates the hub will price against (fetched immediately before the pay;
        // the sim random-walks ±0.3%/60s, hence REL_TOL on rate-derived assertions).
        BigDecimal midKrwMnt = fetchRate("KRW", "MNT");
        BigDecimal krwPerUsd = fetchRate("USD", "KRW");
        BigDecimal balanceBefore = prefundBalance();
        assertEquals(0, OPENING_USD.compareTo(balanceBefore), "untouched opening balance");

        // --- PAY: explicit partner=SENDMN selects the KRW→MNT FX corridor. ---
        HttpResponse<String> pay = SchemeFleet.post(PAY + "/v1/pay", """
                { "qrPayload": "%s", "amountKrw": "%s", "partner": "SENDMN", "userRef": "e2e-user-mn-1" }
                """.formatted(qrPayload, AMOUNT_KRW.toPlainString()));
        assertEquals(201, pay.statusCode(),
                "POST /v1/pay must APPROVE a QPay QR payment through the SENDMN adapter. Body: " + pay.body());
        JsonNode receipt = JSON.readTree(pay.body());
        assertEquals("APPROVED", receipt.path("status").asText(), "Body: " + pay.body());
        assertTrue(receipt.path("fxApplied").asBoolean(false), "FX corridor receipt. Body: " + pay.body());

        // Exact KRW money: fee is fixed ₩500, charged = amount + fee.
        assertEquals(0, AMOUNT_KRW.compareTo(bd(receipt, "payAmountKrw")), "pay amount");
        assertEquals(0, FEE_KRW.compareTo(bd(receipt, "feeKrw")), "fixed ₩500 service fee");
        assertEquals(0, AMOUNT_KRW.add(FEE_KRW).compareTo(bd(receipt, "chargedKrw")), "charged = amount + fee");

        // Offer rate = mid × (1 − 2% margin); MNT payout = amount × offer rate (whole MNT).
        BigDecimal offerRate = bd(receipt, "fxRate");
        assertClose(midKrwMnt.multiply(new BigDecimal("0.98")), offerRate, "offer rate = mid × 0.98");
        BigDecimal payAmountMnt = bd(receipt, "payAmountMnt");
        BigDecimal expectedMnt = AMOUNT_KRW.multiply(offerRate).setScale(0, RoundingMode.HALF_UP);
        assertTrue(payAmountMnt.subtract(expectedMnt).abs().compareTo(BigDecimal.ONE) <= 0,
                "MNT payout must equal amountKrw × offerRate (±1 for the receipt's 6dp rate): "
                        + payAmountMnt + " vs " + expectedMnt);

        String schemeTxnRef = receipt.path("schemeTxnRef").asText("");
        assertTrue(!schemeTxnRef.isBlank(), "scheme txn ref proves the adapter round-trip. Body: " + pay.body());

        // --- hub-side attempt trail: exactly one APPROVED sendmn attempt was recorded. ---
        List<Map<String, Object>> attempts = sendmnAttempts();
        assertEquals(1, attempts.size(), "exactly one sendmn execution attempt: " + attempts);
        Map<String, Object> attempt = attempts.get(0);
        assertEquals("APPROVED", str(attempt, "outcome"), "attempt outcome: " + attempt);
        assertEquals(schemeTxnRef, str(attempt, "scheme_txn_ref"), "attempt carries the scheme ref");
        approvedPartnerTxnRef = str(attempt, "partner_txn_ref");
        assertTrue(approvedPartnerTxnRef != null && approvedPartnerTxnRef.startsWith("SENDMN-"),
                "hub-minted partner reference: " + attempt);

        // --- restart-fallback probe leg: the adapter's durable by-reference index resolves
        //     the hub's reference to APPROVED (what hub lookupStatus uses on a map miss). ---
        HttpResponse<String> byRef = SchemeFleet.get(
                ADPT + "/internal/scheme/sendmn/status/by-reference/" + approvedPartnerTxnRef);
        assertEquals(200, byRef.statusCode(), "by-reference probe. Body: " + byRef.body());
        JsonNode probe = JSON.readTree(byRef.body());
        assertEquals("APPROVED", probe.path("status").asText(), "Body: " + byRef.body());
        txTokenNo = probe.path("txTokenNo").asText();
        paymentNo = probe.path("paymentNo").asText();
        assertTrue(txTokenNo.startsWith("SMN"), "scheme token minted at verify-qr: " + byRef.body());
        assertEquals(schemeTxnRef, paymentNo, "receipt schemeTxnRef is the SendMN PAYMENT_NO");

        // --- sim-side truth: exactly one Approved payment, settled at the REGISTERED rate. ---
        JsonNode simPayment = simPayment(txTokenNo);
        assertEquals("Approved", simPayment.path("status").asText(), "sim ledger: " + simPayment);
        assertEquals(paymentNo, simPayment.path("paymentNo").asText(), "one payment, same PAYMENT_NO");
        assertEquals(simMerchantId, simPayment.path("merchantId").asText(), "paid the scanned merchant");
        assertEquals(0, payAmountMnt.setScale(2, RoundingMode.HALF_UP)
                        .compareTo(new BigDecimal(simPayment.path("localAmount").asText())),
                "scheme received the hub's REAL MNT payout: " + simPayment);
        BigDecimal expectedSettlement = payAmountMnt.setScale(2, RoundingMode.HALF_UP)
                .divide(REGISTERED_RATE, 4, RoundingMode.HALF_UP);
        assertEquals(0, expectedSettlement.compareTo(new BigDecimal(simPayment.path("settlementAmount").asText())),
                "SETTLEMENT_AMOUNT consistent with the registered rate: " + simPayment);

        // --- prefunding: deducted exactly ONCE, in USD, for chargedKrw at the live rate. ---
        BigDecimal balanceAfter = prefundBalance();
        BigDecimal deducted = balanceBefore.subtract(balanceAfter);
        assertTrue(deducted.signum() > 0, "prefunding must have been deducted");
        assertClose(AMOUNT_KRW.add(FEE_KRW).divide(krwPerUsd, 8, RoundingMode.HALF_UP), deducted,
                "USD deduction = chargedKrw / (USD/KRW)");
        JsonNode deductions = JSON.readTree(SchemeFleet.get(
                PFND + "/v1/prefunding/" + SENDMN_PARTNER + "/deductions").body());
        assertEquals(1, deductions.path("entries").size(),
                "exactly ONE DEBIT ledger entry: " + deductions);
        assertEquals(approvedPartnerTxnRef, deductions.path("entries").get(0).path("txnRef").asText(),
                "the DEBIT is keyed by the payment's partner reference");
        assertEquals(0, deducted.compareTo(
                        new BigDecimal(deductions.path("entries").get(0).path("amountUsd").asText())),
                "ledger DEBIT equals the balance delta");
    }

    // -------------------------------------------------------------------------
    // 4) Negative: insufficient prefunding declines BEFORE any scheme call
    // -------------------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("negative: insufficient prefunding -> 422 DECLINED, prefund untouched, NO scheme call")
    void insufficientPrefundingDeclinesWithoutSchemeCall() throws Exception {
        BigDecimal balanceBefore = prefundBalance();

        HttpResponse<String> pay = SchemeFleet.post(PAY + "/v1/pay", """
                { "qrPayload": "%s", "amountKrw": "%s", "partner": "SENDMN", "userRef": "e2e-user-mn-2" }
                """.formatted(qrPayload, TOO_BIG_KRW.toPlainString()));
        assertEquals(422, pay.statusCode(), "must decline. Body: " + pay.body());
        JsonNode body = JSON.readTree(pay.body());
        assertEquals("DECLINED", body.path("status").asText(), "Body: " + pay.body());
        assertEquals("INSUFFICIENT_PREFUNDING", body.path("declineReason").asText(), "Body: " + pay.body());

        // Prefund untouched (the 402 decline writes nothing).
        assertEquals(0, balanceBefore.compareTo(prefundBalance()), "balance unchanged");
        JsonNode deductions = JSON.readTree(SchemeFleet.get(
                PFND + "/v1/prefunding/" + SENDMN_PARTNER + "/deductions").body());
        assertEquals(1, deductions.path("entries").size(), "STILL exactly one DEBIT: " + deductions);

        // Hub recorded the FAILED attempt with no scheme ref...
        List<Map<String, Object>> attempts = sendmnAttempts();
        assertEquals(2, attempts.size(), "approved + failed attempts: " + attempts);
        Map<String, Object> failed = attempts.stream()
                .filter(a -> "FAILED".equals(str(a, "outcome")))
                .findFirst().orElseGet(() -> fail("no FAILED sendmn attempt recorded: " + attempts));
        assertNull(failed.get("scheme_txn_ref"), "no scheme ref on a pre-scheme decline: " + failed);
        String failedRef = str(failed, "partner_txn_ref");

        // ...and the adapter NEVER saw that reference: verify-qr persists the reference
        // BEFORE Confirm, so a by-reference 404 proves zero scheme calls were made.
        HttpResponse<String> byRef = SchemeFleet.get(
                ADPT + "/internal/scheme/sendmn/status/by-reference/" + failedRef);
        assertEquals(404, byRef.statusCode(),
                "adapter must have no record of the declined attempt. Body: " + byRef.body());

        // The scheme still holds exactly the one approved payment, unchanged.
        JsonNode simPayment = simPayment(txTokenNo);
        assertEquals("Approved", simPayment.path("status").asText());
        assertEquals(paymentNo, simPayment.path("paymentNo").asText(), "no new/changed scheme payment");
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static BigDecimal fetchRate(String base, String quote) throws Exception {
        HttpResponse<String> resp = SchemeFleet.get(
                RATES + "/v1/rates?base=" + base + "&quote=" + quote);
        assertEquals(200, resp.statusCode(), "sim-rate-provider " + base + "/" + quote);
        return new BigDecimal(JSON.readTree(resp.body()).path("rate").asText());
    }

    private static BigDecimal prefundBalance() throws Exception {
        HttpResponse<String> resp = SchemeFleet.get(
                PFND + "/v1/prefunding/" + SENDMN_PARTNER + "/balance");
        assertEquals(200, resp.statusCode(), "prefunding balance. Body: " + resp.body());
        JsonNode view = JSON.readTree(resp.body());
        assertEquals("USD", view.path("currency").asText(), "prefunding is USD-denominated");
        return new BigDecimal(view.path("balance").asText());
    }

    /** All rows of payment-executor's execution_attempts trail for scheme sendmn, as maps. */
    private static List<Map<String, Object>> sendmnAttempts() throws Exception {
        HttpResponse<String> resp = SchemeFleet.get(PAY + "/__data/tables/execution_attempts");
        assertEquals(200, resp.statusCode(), "devtools attempt dump. Body: " + resp.body());
        JsonNode table = JSON.readTree(resp.body());
        List<String> columns = new ArrayList<>();
        table.path("columns").forEach(c -> columns.add(c.asText().toLowerCase()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode row : table.path("rows")) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                JsonNode v = row.get(i);
                m.put(columns.get(i), v == null || v.isNull() ? null : v.asText());
            }
            if ("sendmn".equalsIgnoreCase(str(m, "scheme_id"))) {
                rows.add(m);
            }
        }
        return rows;
    }

    private static JsonNode simPayment(String token) throws Exception {
        HttpResponse<String> resp = SchemeFleet.get(SIM + "/sim/payments/" + token);
        assertEquals(200, resp.statusCode(), "sim payment record for " + token);
        return JSON.readTree(resp.body());
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** Reads a JSON field that may be a number or quoted string into a BigDecimal. */
    private static BigDecimal bd(JsonNode node, String field) {
        return new BigDecimal(node.path(field).asText());
    }

    /** Asserts {@code actual} is within {@link #REL_TOL} of {@code expected} (rate walk guard). */
    private static void assertClose(BigDecimal expected, BigDecimal actual, String what) {
        BigDecimal diff = expected.subtract(actual).abs();
        BigDecimal allowed = expected.abs().multiply(REL_TOL);
        assertTrue(diff.compareTo(allowed) <= 0,
                what + ": expected ≈" + expected + " but was " + actual
                        + " (diff " + diff + " > allowed " + allowed + ")");
    }
}
