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

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live E2E: <b>scheme-adapter-ninepay (:8096) ↔ sim-ninepay (:9107)</b> — Phase 4 of
 * {@code Documentation/schemes/QR_SCHEME_ACCOMMODATION_PLAN.md}
 * ("9Pay payout submit → IPN SUCCESS → (toggle) 009 reversal handled").
 *
 * <p><b>Full mutual RSA</b>: the test generates two RSA-2048 keypairs and provisions them
 * exactly like a production key exchange — the partner (adapter) private key + the sim's
 * public key into the adapter config, and the reverse into the sim — then boots the pair
 * with {@code verify-responses=true}, so every request signature, response signature AND
 * IPN signature on the wire is genuinely signed and verified end-to-end
 * ({@code GET /sim/public-key} is asserted to serve the provisioned trust anchor).</p>
 *
 * <ol>
 *   <li>Happy path: {@code POST /scheme/payout} → PENDING → poll to SUCCESS; the sim's
 *       signed IPN (code 000) is delivered and ACKed by the adapter; balance debited once.</li>
 *   <li>Idempotent replay: same {@code request_id} → stored state, no second transfer.</li>
 *   <li>Wire 1062: the transfer already exists at 9Pay (submitted out-of-band) → the
 *       adapter adopts it via transfer/info instead of double-paying.</li>
 *   <li>Delayed 009 reversal: SUCCESS first, then the bank reverses — adapter flips the
 *       payout to REVERSED off the second IPN; sim balance restored.</li>
 * </ol>
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("E2E: 9Pay VND payout — scheme-adapter-ninepay <-> sim-ninepay")
class NinepayPayoutE2ETest {

    private static final int PORT_SIM = 9107;
    private static final int PORT_ADAPTER = 8096;
    private static final String SIM = "http://localhost:" + PORT_SIM;
    private static final String ADAPTER = "http://localhost:" + PORT_ADAPTER;

    private static final String PARTNER_ID = "GMEPAY";
    private static final String BANK_NO = "VIETCOMBANK";
    private static final String ACCOUNT_NO = "1023020330000";   // seeded OK account
    private static final String ACCOUNT_NAME = "NGUYEN VAN AN"; // its bank-verified name
    private static final long FEE_VND = 4_000L;                 // sim's flat fee

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static SchemeFleet fleet;
    private static KeyPair partnerKeys; // ours (adapter signs requests)
    private static KeyPair simKeys;     // "9Pay's" (sim signs responses + IPNs)

    // Captured by the happy path for the replay test.
    private static String happyRequestId;
    private static String happyTransactionId;

    @BeforeAll
    static void bootFleet() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        partnerKeys = gen.generateKeyPair();
        simKeys = gen.generateKeyPair();
        String partnerPrivPem = pem(partnerKeys.getPrivate().getEncoded(), "PRIVATE KEY");
        String partnerPubPem = pem(partnerKeys.getPublic().getEncoded(), "PUBLIC KEY");
        String simPrivPem = pem(simKeys.getPrivate().getEncoded(), "PRIVATE KEY");
        String simPubPem = pem(simKeys.getPublic().getEncoded(), "PUBLIC KEY");

        fleet = new SchemeFleet("e2e-logs-ninepay");
        fleet.assertPortsFree(PORT_SIM, PORT_ADAPTER);

        // Key material carries newlines -> SPRING_APPLICATION_JSON env (binds for both
        // @Value and @ConfigurationProperties), not command-line args.
        fleet.launchSim("sim-ninepay", PORT_SIM,
                Map.of("SPRING_APPLICATION_JSON", JSON.writeValueAsString(Map.of(
                        "sim.ninepay.private-key-pem", simPrivPem,
                        "sim.ninepay.partner-public-key-pem", partnerPubPem))),
                "--sim.ninepay.lifecycle-step-ms=400",
                "--sim.ninepay.reversal-delay-ms=1200");
        fleet.launchService("scheme-adapter-ninepay", PORT_ADAPTER,
                Map.of("SPRING_APPLICATION_JSON", JSON.writeValueAsString(Map.of(
                        "gmepay.scheme.ninepay.private-key-pem", partnerPrivPem,
                        "gmepay.scheme.ninepay.ninepay-public-key-pem", simPubPem,
                        "gmepay.scheme.ninepay.verify-responses", true))),
                "--gmepay.scheme.ninepay.base-url=http://localhost:" + PORT_SIM);
        fleet.awaitUp(Duration.ofSeconds(150));

        // The documented key-exchange surface must serve the provisioned trust anchor.
        HttpResponse<String> pub = SchemeFleet.get(SIM + "/sim/public-key");
        assertEquals(200, pub.statusCode(), "sim public-key endpoint. Body: " + pub.body());
        String served = JSON.readTree(pub.body()).path("public_key_pem").asText();
        assertEquals(stripPem(simPubPem), stripPem(served),
                "GET /sim/public-key must serve the sim's actual signing key");
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
    @DisplayName("happy path: payout PENDING -> SUCCESS; signed IPN 000 delivered + ACKed; balance debited once")
    void payoutSucceedsWithSignedIpn() throws Exception {
        long balanceBefore = simBalance();
        happyRequestId = newRequestId("A");

        HttpResponse<String> submit = SchemeFleet.post(ADAPTER + "/scheme/payout",
                payoutBody(happyRequestId, 50_000L, "E2E happy payout"));
        assertEquals(200, submit.statusCode(), "POST /scheme/payout. Body: " + submit.body());
        JsonNode s = JSON.readTree(submit.body());
        assertEquals(happyRequestId, s.path("requestId").asText());
        String initial = s.path("status").asText();
        assertTrue(initial.equals("PENDING") || initial.equals("PROCESSING") || initial.equals("SUCCESS"),
                "9Pay accepts asynchronously — initial state is non-final. Body: " + submit.body());
        happyTransactionId = s.path("transactionId").asText("");
        assertTrue(!happyTransactionId.isBlank(), "9Pay transaction id assigned. Body: " + submit.body());

        // Poll the adapter (its lookup poll + the sim lifecycle both advance the transfer).
        JsonNode done = pollPayoutUntil(happyRequestId, "SUCCESS", Duration.ofSeconds(30));
        assertEquals(happyTransactionId, done.path("transactionId").asText());
        assertEquals(50_000L, done.path("amountVnd").asLong());
        assertEquals(FEE_VND, done.path("feeVnd").asLong(), "9Pay fee recorded. Body: " + done);

        // The signed IPN 000 must have been DELIVERED (adapter verified + ACKed with 2xx —
        // the adapter persists the np_ipn_events row before ACKing, and rejects bad
        // signatures with 400, so delivered=true proves the inbound IPN edge end-to-end).
        JsonNode ipn = awaitIpn(happyRequestId, "000", Duration.ofSeconds(15));
        assertTrue(ipn.path("delivered").asBoolean(), "IPN 000 must be ACKed by the adapter: " + ipn);

        // Prefunded balance debited exactly amount + fee.
        assertEquals(balanceBefore - 50_000L - FEE_VND, simBalance(), "balance debited once");
    }

    @Test
    @Order(2)
    @DisplayName("duplicate request_id (adapter replay): stored state returned, no second transfer")
    void duplicateRequestIdIsIdempotentReplay() throws Exception {
        long balanceBefore = simBalance();

        HttpResponse<String> replay = SchemeFleet.post(ADAPTER + "/scheme/payout",
                payoutBody(happyRequestId, 50_000L, "E2E happy payout"));
        assertEquals(200, replay.statusCode(), "replay. Body: " + replay.body());
        JsonNode r = JSON.readTree(replay.body());
        assertEquals("SUCCESS", r.path("status").asText(), "replay returns the finished payout");
        assertEquals(happyTransactionId, r.path("transactionId").asText(), "same 9Pay transaction");

        assertEquals(1, countSimTransfers(happyRequestId), "exactly ONE transfer at the scheme");
        assertEquals(balanceBefore, simBalance(), "no second debit");
    }

    @Test
    @Order(3)
    @DisplayName("wire 1062 (request_id already at 9Pay): adapter adopts the existing transfer, never double-pays")
    void wire1062AdoptsExistingTransfer() throws Exception {
        long balanceBefore = simBalance();
        String requestId = newRequestId("B");
        long amount = 30_000L;
        String content = "E2E dup wire test";

        // Simulate a previous attempt whose response was lost: the transfer ALREADY exists
        // at 9Pay (submitted here out-of-band, signed with the partner key) but the adapter
        // has no local row for it.
        String directTxnId = submitDirectlyToSim(requestId, amount, content);

        // The hub now submits the same request_id through the adapter -> sim answers 1062
        // -> adapter must resolve via transfer/info and adopt, not resubmit.
        HttpResponse<String> submit = SchemeFleet.post(ADAPTER + "/scheme/payout",
                payoutBody(requestId, amount, content));
        assertEquals(200, submit.statusCode(), "1062 path. Body: " + submit.body());
        JsonNode s = JSON.readTree(submit.body());
        assertEquals(directTxnId, s.path("transactionId").asText(),
                "adapter must ADOPT the existing 9Pay transfer. Body: " + submit.body());
        String status = s.path("status").asText();
        assertTrue(!status.equals("FAILED") && !status.equals("UNKNOWN"),
                "1062 = already-submitted, not a failure. Body: " + submit.body());

        assertEquals(1, countSimTransfers(requestId), "still exactly ONE transfer for " + requestId);

        // Drive it to completion and confirm single debit overall.
        pollPayoutUntil(requestId, "SUCCESS", Duration.ofSeconds(30));
        assertEquals(balanceBefore - amount - FEE_VND, simBalance(),
                "the whole 1062 episode debits the prefund exactly once");
    }

    @Test
    @Order(4)
    @DisplayName("delayed 009 reversal: SUCCESS then bank reversal -> adapter marks REVERSED, balance restored")
    void delayed009ReversalFlipsSuccessToReversed() throws Exception {
        long balanceBefore = simBalance();
        setScenario("{\"ipnOutcome\": \"REVERSAL\"}");
        try {
            String requestId = newRequestId("C");
            HttpResponse<String> submit = SchemeFleet.post(ADAPTER + "/scheme/payout",
                    payoutBody(requestId, 40_000L, "E2E reversal payout"));
            assertEquals(200, submit.statusCode(), "reversal payout submit. Body: " + submit.body());

            // First the payout genuinely SUCCEEDs (IPN 000)...
            pollPayoutUntil(requestId, "SUCCESS", Duration.ofSeconds(30));

            // ...then the delayed 009 arrives. SUCCESS is FINAL for polling — only the IPN
            // can flip it, so observing REVERSED proves the adapter recorded the 009 push.
            pollPayoutUntil(requestId, "REVERSED", Duration.ofSeconds(20));
            JsonNode ipn009 = awaitIpn(requestId, "009", Duration.ofSeconds(10));
            assertTrue(ipn009.path("delivered").asBoolean(), "009 IPN ACKed by adapter: " + ipn009);

            // The bank gave the money back.
            assertEquals(balanceBefore, simBalance(), "reversal restores the prefunded balance");
        } finally {
            setScenario("{\"ipnOutcome\": \"SUCCESS\"}");
        }
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static String payoutBody(String requestId, long amountVnd, String content) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("bankNo", BANK_NO);
        body.put("accountNo", ACCOUNT_NO);
        body.put("accountType", 0);
        body.put("accountName", ACCOUNT_NAME);
        body.put("amountVnd", amountVnd);
        body.put("content", content);
        return JSON.writeValueAsString(body);
    }

    /** Recommended 9Pay format PartnerID+9P+YYYYMMDD+UniqueId, unique per run. */
    private static String newRequestId(String tag) {
        String day = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).format(DateTimeFormatter.BASIC_ISO_DATE);
        return PARTNER_ID + "9P" + day + "E2E" + tag + System.currentTimeMillis() % 100_000_000L
                + SEQ.incrementAndGet();
    }

    private static JsonNode pollPayoutUntil(String requestId, String want, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        String lastBody = "";
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> resp = SchemeFleet.get(ADAPTER + "/scheme/payout/" + requestId);
            if (resp.statusCode() == 200) {
                JsonNode node = JSON.readTree(resp.body());
                if (want.equals(node.path("status").asText())) {
                    return node;
                }
                lastBody = resp.body();
            } else {
                lastBody = "HTTP " + resp.statusCode() + " " + resp.body();
            }
            SchemeFleet.sleep(500);
        }
        fail("payout " + requestId + " never reached " + want + ". Last: " + lastBody);
        return null;
    }

    /** Waits for the sim's IPN outbox to hold an entry for request_id with the given code. */
    private static JsonNode awaitIpn(String requestId, String code, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        String lastBody = "";
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> resp = SchemeFleet.get(SIM + "/sim/ipns");
            if (resp.statusCode() == 200) {
                lastBody = resp.body();
                for (JsonNode entry : JSON.readTree(resp.body())) {
                    JsonNode payload = entry.path("payload");
                    if (requestId.equals(payload.path("request_id").asText())
                            && code.equals(payload.path("code").asText())) {
                        return entry;
                    }
                }
            }
            SchemeFleet.sleep(500);
        }
        fail("no IPN code " + code + " for " + requestId + " in the sim outbox. Last: " + lastBody);
        return null;
    }

    private static long simBalance() throws Exception {
        HttpResponse<String> resp = SchemeFleet.get(SIM + "/sim/balance");
        assertEquals(200, resp.statusCode(), "sim balance. Body: " + resp.body());
        return JSON.readTree(resp.body()).path("balance_vnd").asLong();
    }

    private static int countSimTransfers(String requestId) throws Exception {
        HttpResponse<String> resp = SchemeFleet.get(SIM + "/sim/transfers");
        assertEquals(200, resp.statusCode());
        int count = 0;
        for (JsonNode t : JSON.readTree(resp.body())) {
            if (requestId.equals(t.path("request_id").asText())) {
                count++;
            }
        }
        return count;
    }

    private static void setScenario(String json) throws Exception {
        assertEquals(200, SchemeFleet.post(SIM + "/sim/scenario", json).statusCode());
    }

    /** Submits a transfer straight to the sim (signed with the partner key), returning its txn id. */
    private static String submitDirectlyToSim(String requestId, long amountVnd, String content) throws Exception {
        String canonical = canonical(requestId, PARTNER_ID, BANK_NO, ACCOUNT_NO, 0,
                ACCOUNT_NAME, amountVnd, content);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("partner_id", PARTNER_ID);
        body.put("bank_no", BANK_NO);
        body.put("account_no", ACCOUNT_NO);
        body.put("account_type", 0);
        body.put("account_name", ACCOUNT_NAME);
        body.put("amount", amountVnd);
        body.put("content", content);
        body.put("signature", sign(partnerKeys.getPrivate(), canonical));

        HttpResponse<String> resp = SchemeFleet.post(SIM + "/service/transfer", JSON.writeValueAsString(body));
        assertEquals(200, resp.statusCode(), "direct sim transfer. Body: " + resp.body());
        JsonNode envelope = JSON.readTree(resp.body());
        assertTrue(envelope.path("success").asBoolean(false),
                "direct (out-of-band) transfer must be accepted by the sim — the request "
                        + "signature the test built must match the sim's canonical. Body: " + resp.body());
        return envelope.path("data").path("transaction_id").asText();
    }

    // ------------------------------------------------------------------ crypto

    private static String pem(byte[] der, String type) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }

    private static String stripPem(String pemText) {
        return pemText == null ? "" : pemText.replaceAll("-----(BEGIN|END)[^-]*-----", "").replaceAll("\\s", "");
    }

    /** Same canonical rule as NinepaySigner/SimSigner: pipe-joined, null -> empty. */
    private static String canonical(Object... fields) {
        StringJoiner joiner = new StringJoiner("|");
        for (Object f : fields) {
            joiner.add(f == null ? "" : String.valueOf(f));
        }
        return joiner.toString();
    }

    private static String sign(PrivateKey key, String canonical) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(canonical.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }
}
