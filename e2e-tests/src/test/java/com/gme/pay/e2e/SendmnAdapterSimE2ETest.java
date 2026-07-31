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
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live E2E: <b>scheme-adapter-sendmn (:8093) ↔ sim-sendmn (:9106)</b> — Phase 4 of
 * {@code Documentation/schemes/QR_SCHEME_ACCOMMODATION_PLAN.md}
 * ("wallet scans SendMN static QR → verify → confirm → approved, via sim").
 *
 * <p>Both processes are real boot jars talking real HTTP with the plain-JSON
 * {@code encryptedData} envelope (adapter default). The test drives the adapter's
 * hub-facing API ({@code /internal/scheme/sendmn/...}) exactly the way payment-executor's
 * {@code SendmnRestSchemeClient} would, plus the SendMN→partner FX-rate registration
 * direction ({@code sim → POST /partner-hosted/fx-rate}):</p>
 *
 * <ol>
 *   <li>FX rate registration: sim registers a fresh MNT/USD buy rate with the adapter.</li>
 *   <li>Happy path: seeded QR → verify-qr → submit-mpm → APPROVED with PAYMENT_NO and a
 *       SETTLEMENT_AMOUNT consistent with the registered rate.</li>
 *   <li>Duplicate submit replay: same txTokenNo → same result, no second payment.</li>
 *   <li>Forced wire-304 (duplicate TX_TOKEN_NO at the scheme): adapter resolves via
 *       PaymentStatus poll — no payment is double-executed.</li>
 *   <li>Forced wire-307 (settlement mismatch): a clean 4xx decline, not a crash.</li>
 * </ol>
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("E2E: SendMN QR pay-in — scheme-adapter-sendmn <-> sim-sendmn")
class SendmnAdapterSimE2ETest {

    private static final int PORT_SIM = 9106;
    private static final int PORT_ADAPTER = 8093;
    private static final String SIM = "http://localhost:" + PORT_SIM;
    private static final String ADAPTER = "http://localhost:" + PORT_ADAPTER;

    /** Deliberately NOT the sim's default (3373.00) so we prove registration actually flowed. */
    private static final BigDecimal RATE = new BigDecimal("3391.50");
    private static final BigDecimal AMOUNT_MNT = new BigDecimal("150000.00");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static SchemeFleet fleet;

    // Captured by the happy path, reused by the duplicate-submit test.
    private static String merchantId;
    private static String merchantName;
    private static String qrPayload;
    private static String txTokenNo;
    private static String paymentNo;

    @BeforeAll
    static void bootFleet() throws Exception {
        fleet = new SchemeFleet("e2e-logs-sendmn");
        fleet.assertPortsFree(PORT_SIM, PORT_ADAPTER);
        fleet.launchSim("sim-sendmn", PORT_SIM, java.util.Map.of());
        fleet.launchService("scheme-adapter-sendmn", PORT_ADAPTER, java.util.Map.of(),
                "--sendmn.base-url=http://localhost:" + PORT_SIM);
        fleet.awaitUp(Duration.ofSeconds(150));
    }

    @AfterAll
    static void stopFleet() {
        if (fleet != null) {
            fleet.shutdown();
        }
    }

    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("FX registration: sim pushes a fresh buy rate to the adapter's /partner-hosted/fx-rate")
    void fxRateRegistrationFlows() throws Exception {
        // Register a NEW rate at the sim (mints a fresh FX_TICKER_NO)...
        HttpResponse<String> set = SchemeFleet.post(SIM + "/sim/fx-rate",
                "{\"rate\": \"" + RATE.toPlainString() + "\"}");
        assertEquals(200, set.statusCode(), "sim fx-rate set. Body: " + set.body());
        String fxTickerNo = JSON.readTree(set.body()).path("fxTickerNo").asText();

        // ...then push it in the real SendMN→partner direction (doc §9).
        HttpResponse<String> push = SchemeFleet.post(SIM + "/sim/fx-rate/push", "{}");
        assertEquals(200, push.statusCode(),
                "sim must be able to register the rate with the adapter. Body: " + push.body());

        // Adapter now settles at exactly that rate.
        HttpResponse<String> latest = SchemeFleet.get(ADAPTER + "/internal/scheme/sendmn/fx-rate/latest");
        assertEquals(200, latest.statusCode(), "adapter latest fx-rate. Body: " + latest.body());
        JsonNode rate = JSON.readTree(latest.body());
        assertEquals(0, RATE.compareTo(new BigDecimal(rate.path("rate").asText())),
                "adapter must settle at the registered rate. Body: " + latest.body());
        assertEquals(fxTickerNo, rate.path("fxTickerNo").asText(), "registered ticker id");
    }

    @Test
    @Order(2)
    @DisplayName("happy path: seeded QR -> verify-qr -> submit-mpm APPROVED (PAYMENT_NO + settlement@rate)")
    void walletScansQrVerifiesConfirmsAndApproves() throws Exception {
        // --- seeded merchant + QR from the sim console ---
        HttpResponse<String> merchants = SchemeFleet.get(SIM + "/sim/merchants");
        assertEquals(200, merchants.statusCode());
        JsonNode chosen = null;
        for (JsonNode m : JSON.readTree(merchants.body())) {
            if (m.path("fixedAmount").isNull() || m.path("fixedAmount").isMissingNode()) {
                chosen = m; // amount-less static QR — the wallet keys the amount in
                break;
            }
        }
        if (chosen == null) {
            fail("sim-sendmn seeds no amount-less merchant. Body: " + merchants.body());
        }
        merchantId = chosen.path("merchantId").asText();
        merchantName = chosen.path("name").asText();

        HttpResponse<String> qr = SchemeFleet.get(
                SIM + "/sim/qr/" + URLEncoder.encode(merchantId, StandardCharsets.UTF_8));
        assertEquals(200, qr.statusCode(), "sim QR lookup for " + merchantId);
        qrPayload = qr.body();
        assertTrue(qrPayload.startsWith("000201"), "seeded QR must be EMVCo MPM: " + qrPayload);

        // --- verify-qr (adapter mints TX_TOKEN_NO, resolves the merchant via the sim) ---
        HttpResponse<String> verify = SchemeFleet.post(ADAPTER + "/internal/scheme/sendmn/verify-qr",
                JSON.writeValueAsString(java.util.Map.of("qrPayload", qrPayload)));
        assertEquals(200, verify.statusCode(), "verify-qr. Body: " + verify.body());
        JsonNode v = JSON.readTree(verify.body());
        txTokenNo = v.path("txTokenNo").asText();
        assertTrue(txTokenNo.startsWith("SMN"), "TX_TOKEN_NO minted by adapter. Body: " + verify.body());
        assertEquals(merchantId, v.path("merchantId").asText(), "merchant GUID round-trips");
        assertEquals(merchantName, v.path("merchantName").asText(), "merchant name round-trips");
        assertEquals("11", v.path("qrType").asText(), "MPM static QR type");
        assertEquals("MNT", v.path("currency").asText());
        assertTrue(v.path("localAmountMnt").isNull(), "amount-less static QR carries no amount");

        // --- submit-mpm (SendMN Confirm) with the keyed-in MNT amount ---
        HttpResponse<String> submit = SchemeFleet.post(ADAPTER + "/internal/scheme/sendmn/submit-mpm",
                JSON.writeValueAsString(java.util.Map.of(
                        "txTokenNo", txTokenNo,
                        "localAmountMnt", AMOUNT_MNT.toPlainString())));
        assertEquals(200, submit.statusCode(), "submit-mpm. Body: " + submit.body());
        JsonNode s = JSON.readTree(submit.body());
        assertEquals("APPROVED", s.path("status").asText(), "Body: " + submit.body());
        paymentNo = s.path("paymentNo").asText("");
        assertTrue(!paymentNo.isBlank(), "PAYMENT_NO must be present. Body: " + submit.body());
        assertTrue(!s.path("paymentReceiptNo").asText("").isBlank(), "receipt no present");

        // Settlement math: LOCAL / registered-rate at scale 4 HALF_UP (the 307 contract).
        BigDecimal expectedSettlement = AMOUNT_MNT.divide(RATE, 4, RoundingMode.HALF_UP);
        assertEquals(0, RATE.compareTo(new BigDecimal(s.path("fxUsdBuyRate").asText())),
                "Confirm must use the registered buy rate. Body: " + submit.body());
        assertEquals(0, expectedSettlement.compareTo(new BigDecimal(s.path("settlementAmountUsd").asText())),
                "SETTLEMENT_AMOUNT consistent with the registered rate. Body: " + submit.body());

        // --- status poll until Approved (sim: Processing -> Approved on the Nth poll) ---
        JsonNode finalStatus = pollAdapterStatusUntil(txTokenNo, "APPROVED", Duration.ofSeconds(20));
        assertEquals(paymentNo, finalStatus.path("paymentNo").asText(), "PAYMENT_NO stable across status");

        // --- sim-side truth: the payment really executed once, with our settlement amount ---
        JsonNode simPayment = simPayment(txTokenNo);
        assertEquals("Approved", simPayment.path("status").asText(), "sim ledger: " + simPayment);
        assertEquals(paymentNo, simPayment.path("paymentNo").asText());
        assertEquals(0, expectedSettlement.compareTo(new BigDecimal(simPayment.path("settlementAmount").asText())),
                "sim recorded the settlement we sent");
        assertEquals(merchantId, simPayment.path("merchantId").asText());
    }

    @Test
    @Order(3)
    @DisplayName("duplicate submit: replaying the same txTokenNo is a no-op — no double payment")
    void duplicateSubmitDoesNotDoublePay() throws Exception {
        HttpResponse<String> replay = SchemeFleet.post(ADAPTER + "/internal/scheme/sendmn/submit-mpm",
                JSON.writeValueAsString(java.util.Map.of(
                        "txTokenNo", txTokenNo,
                        "localAmountMnt", AMOUNT_MNT.toPlainString())));
        assertEquals(200, replay.statusCode(), "replay submit. Body: " + replay.body());
        JsonNode r = JSON.readTree(replay.body());
        assertEquals("APPROVED", r.path("status").asText());
        assertEquals(paymentNo, r.path("paymentNo").asText(),
                "replay must return the ORIGINAL payment, not a new one. Body: " + replay.body());

        // Sim-side: still exactly the one payment, same PAYMENT_NO.
        JsonNode simPayment = simPayment(txTokenNo);
        assertEquals("Approved", simPayment.path("status").asText());
        assertEquals(paymentNo, simPayment.path("paymentNo").asText(), "no second payment at the scheme");
    }

    @Test
    @Order(4)
    @DisplayName("wire 304 (duplicate TX_TOKEN_NO at SendMN): adapter polls status — payment NOT executed twice")
    void forced304ResolvesByPollingNotResubmitting() throws Exception {
        setScenario("{\"forceError304\": true}");
        try {
            String token = verifyFreshToken();
            HttpResponse<String> submit = SchemeFleet.post(ADAPTER + "/internal/scheme/sendmn/submit-mpm",
                    JSON.writeValueAsString(java.util.Map.of(
                            "txTokenNo", token,
                            "localAmountMnt", "50000.00")));
            assertEquals(200, submit.statusCode(),
                    "304 must resolve via PaymentStatus, not blow up. Body: " + submit.body());
            JsonNode s = JSON.readTree(submit.body());
            String status = s.path("status").asText();
            assertTrue(status.equals("PENDING") || status.equals("UNKNOWN"),
                    "304 -> poll -> non-executed payment stays PENDING/UNKNOWN (never APPROVED, "
                            + "never auto-failed). Body: " + submit.body());
            assertTrue(s.path("paymentNo").isNull() || s.path("paymentNo").asText().isBlank(),
                    "no PAYMENT_NO — nothing was paid. Body: " + submit.body());

            // Sim-side truth: the token was verified but never confirmed — zero money moved.
            JsonNode simPayment = simPayment(token);
            assertEquals("Decrypted", simPayment.path("status").asText(),
                    "sim must still hold the record pre-Confirm: " + simPayment);
            // sim serializes non_null — an unset PAYMENT_NO is an absent field, not a JSON null
            assertTrue(simPayment.path("paymentNo").isNull() || simPayment.path("paymentNo").isMissingNode(),
                    "no scheme payment created: " + simPayment);
        } finally {
            resetScenario();
        }
    }

    @Test
    @Order(5)
    @DisplayName("wire 307 (settlement mismatch): clean 4xx decline surfaced — no crash, no payment")
    void forced307SurfacesCleanDecline() throws Exception {
        setScenario("{\"forceError307\": true}");
        String token;
        try {
            token = verifyFreshToken();
            HttpResponse<String> submit = SchemeFleet.post(ADAPTER + "/internal/scheme/sendmn/submit-mpm",
                    JSON.writeValueAsString(java.util.Map.of(
                            "txTokenNo", token,
                            "localAmountMnt", "70000.00")));
            assertTrue(submit.statusCode() >= 400 && submit.statusCode() < 500,
                    "307 is a loud VALIDATION decline (4xx), never a 5xx crash. Got "
                            + submit.statusCode() + " body: " + submit.body());
            JsonNode err = JSON.readTree(submit.body()); // must be the structured ApiError envelope
            assertTrue(err.path("message").asText("").contains("307"),
                    "error must surface the SendMN 307 semantics. Body: " + submit.body());
        } finally {
            resetScenario();
        }

        // Adapter is still healthy and the payment did not go through.
        HttpResponse<String> status = SchemeFleet.get(
                ADAPTER + "/internal/scheme/sendmn/status/" + token);
        assertEquals(200, status.statusCode(), "adapter must stay serviceable after a 307");
        assertNotEquals("APPROVED", JSON.readTree(status.body()).path("status").asText(),
                "the mismatched Confirm must not have paid. Body: " + status.body());
        JsonNode simPayment = simPayment(token);
        assertTrue(simPayment.path("paymentNo").isNull() || simPayment.path("paymentNo").isMissingNode(),
                "no scheme payment created on 307: " + simPayment);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static String verifyFreshToken() throws Exception {
        HttpResponse<String> verify = SchemeFleet.post(ADAPTER + "/internal/scheme/sendmn/verify-qr",
                JSON.writeValueAsString(java.util.Map.of("qrPayload", qrPayload)));
        assertEquals(200, verify.statusCode(), "verify-qr. Body: " + verify.body());
        return JSON.readTree(verify.body()).path("txTokenNo").asText();
    }

    private static JsonNode pollAdapterStatusUntil(String token, String want, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        String lastBody = "";
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> resp = SchemeFleet.get(ADAPTER + "/internal/scheme/sendmn/status/" + token);
            if (resp.statusCode() == 200) {
                JsonNode node = JSON.readTree(resp.body());
                if (want.equals(node.path("status").asText())) {
                    return node;
                }
                lastBody = resp.body();
            } else {
                lastBody = "HTTP " + resp.statusCode() + " " + resp.body();
            }
            SchemeFleet.sleep(1000);
        }
        fail("adapter status for " + token + " never reached " + want + ". Last: " + lastBody);
        return null;
    }

    private static JsonNode simPayment(String token) throws Exception {
        HttpResponse<String> resp = SchemeFleet.get(SIM + "/sim/payments/" + token);
        assertEquals(200, resp.statusCode(), "sim payment record for " + token);
        return JSON.readTree(resp.body());
    }

    private static void setScenario(String json) throws Exception {
        assertEquals(200, SchemeFleet.post(SIM + "/sim/scenario", json).statusCode());
    }

    private static void resetScenario() throws Exception {
        assertEquals(200, SchemeFleet.post(SIM + "/sim/scenario/reset", "{}").statusCode());
    }
}
