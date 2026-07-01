package com.gme.pay.merchant.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.merchant.domain.InMemoryMerchantRepository;
import com.gme.pay.merchant.domain.Merchant;
import com.gme.pay.merchant.domain.MerchantLookupService;
import com.gme.pay.merchant.domain.MerchantRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UC-07-03 real-time validation tests for {@link MerchantController}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Happy-path lookup returns 200 with all expected fields</li>
 *   <li>Deactivated merchant returns 200 with {@code active=false} (payment-executor declines)</li>
 *   <li>Deactivated QR (status=DEACTIVATED) returns 200 with {@code active=false}</li>
 *   <li>Unknown QR returns 404 with canonical error envelope</li>
 *   <li>Response JSON field names match RestQrClient.MerchantResponse exactly</li>
 *   <li>Suspended merchant (active=false, status!=ACTIVE) — payment-executor fallback check</li>
 * </ul>
 *
 * <p>Plain JUnit 5 — no Spring context, no Docker, deterministic and fast.
 * The controller's {@link ApiException} handler is exercised by calling
 * {@link MerchantController#handleApiException} directly where 404 semantics are asserted.
 */
class MerchantValidationTest {

    private static final String ACTIVE_QR      = "QR00000000000000VAL1";
    private static final String INACTIVE_QR    = "QR00000000000000VAL2";
    private static final String DEACTIVATED_QR = "QR00000000000000VAL3";
    private static final String SUSPENDED_QR   = "QR00000000000000VAL4";
    private static final String UNKNOWN_QR     = "QR_DOES_NOT_EXIST____";

    private InMemoryMerchantRepository repository;
    private MerchantController controller;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repository = new InMemoryMerchantRepository();
        // Remove default seeds so tests control the fixture state exactly
        repository.remove("QR00000000000000001A");
        repository.remove("QR00000000000000002B");
        repository.remove("QR00000000000000003C");
        repository.remove("QR00000000000000004D");

        // Fully active merchant with all UC-07-03 fields
        repository.put(new Merchant("M0000000V01", ACTIVE_QR, "Valid Merchant",
                "RETAIL", "DOMESTIC", "ACTIVE", true,
                "KRW", "ZEROPAY", "Seoul", "5411"));

        // active=false, status=INACTIVE — payment-executor should decline
        repository.put(new Merchant("M0000000V02", INACTIVE_QR, "Inactive Merchant",
                "RETAIL", "DOMESTIC", "INACTIVE", false,
                "KRW", "ZEROPAY", "Busan", "5411"));

        // Deactivated QR — both active=false and status=DEACTIVATED
        repository.put(new Merchant("M0000000V03", DEACTIVATED_QR, "Deactivated QR Shop",
                "RETAIL", "DOMESTIC", "DEACTIVATED", false,
                "KRW", "ZEROPAY", "Incheon", "5411"));

        // Suspended — active=false, status=SUSPENDED
        repository.put(new Merchant("M0000000V04", SUSPENDED_QR, "Suspended Shop",
                "FOREX", "CROSSBORDER", "SUSPENDED", false,
                "USD", "ZEROPAY", "Daegu", "6211"));

        MerchantLookupService service = new MerchantLookupService(repository);
        MerchantRegistrationService registrationService = new MerchantRegistrationService(repository);
        controller = new MerchantController(service, registrationService);
    }

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    void lookup_activeMerchant_returns200WithAllFields() {
        ResponseEntity<MerchantResponse> resp = controller.getByQr(ACTIVE_QR);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        MerchantResponse body = resp.getBody();
        assertNotNull(body);

        assertEquals("M0000000V01", body.merchantId());
        assertEquals(ACTIVE_QR, body.qrCodeId());
        assertEquals("Valid Merchant", body.merchantName());   // field name = merchantName
        assertEquals("RETAIL", body.merchantType());
        assertEquals("DOMESTIC", body.feeType());
        assertEquals("ACTIVE", body.status());
        assertTrue(body.active());
        assertEquals("KRW", body.payoutCurrency());
        assertEquals("ZEROPAY", body.schemeId());
        assertEquals("Seoul", body.city());
        assertEquals("5411", body.mcc());
    }

    // ------------------------------------------------------------------
    // Deactivated / inactive merchant — active=false (payment-executor declines)
    // ------------------------------------------------------------------

    @Test
    void lookup_inactiveMerchant_returns200WithActiveFalse() {
        ResponseEntity<MerchantResponse> resp = controller.getByQr(INACTIVE_QR);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        MerchantResponse body = resp.getBody();
        assertNotNull(body);
        assertFalse(body.active(), "Inactive merchant must have active=false");
        assertEquals("INACTIVE", body.status());
        assertEquals("M0000000V02", body.merchantId());
    }

    @Test
    void lookup_deactivatedMerchant_returns200WithActiveFalseAndDeactivatedStatus() {
        ResponseEntity<MerchantResponse> resp = controller.getByQr(DEACTIVATED_QR);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        MerchantResponse body = resp.getBody();
        assertNotNull(body);
        assertFalse(body.active(), "Deactivated merchant QR must have active=false");
        assertEquals("DEACTIVATED", body.status(),
                "status must be DEACTIVATED so RestQrClient fallback also returns false");
        assertEquals("M0000000V03", body.merchantId());
    }

    @Test
    void lookup_suspendedMerchant_returns200WithActiveFalse() {
        ResponseEntity<MerchantResponse> resp = controller.getByQr(SUSPENDED_QR);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        MerchantResponse body = resp.getBody();
        assertNotNull(body);
        assertFalse(body.active());
        assertEquals("SUSPENDED", body.status());
    }

    // ------------------------------------------------------------------
    // Unknown QR -> 404
    // ------------------------------------------------------------------

    @Test
    void lookup_unknownQr_throws404ApiException() {
        ApiException ex = assertThrows(ApiException.class,
                () -> controller.getByQr(UNKNOWN_QR));

        assertEquals(ErrorCode.MERCHANT_NOT_FOUND, ex.errorCode());
        assertEquals(404, ex.errorCode().httpStatus());
        assertFalse(ex.errorCode().retryable());
        assertTrue(ex.getMessage().contains(UNKNOWN_QR));
    }

    @Test
    void handleApiException_merchantNotFound_returns404WithCanonicalEnvelope() {
        ApiException ex = assertThrows(ApiException.class,
                () -> controller.getByQr(UNKNOWN_QR));

        // The controller exception handler converts it to an ApiError body
        var errorResponse = controller.handleApiException(ex);
        assertEquals(404, errorResponse.getStatusCode().value());
        assertNotNull(errorResponse.getBody());
        assertEquals(ErrorCode.MERCHANT_NOT_FOUND.name(), errorResponse.getBody().code());
        assertFalse(errorResponse.getBody().retryable());
        assertNotNull(errorResponse.getBody().requestId());
        assertTrue(errorResponse.getBody().message().contains(UNKNOWN_QR));
    }

    // ------------------------------------------------------------------
    // JSON field-name contract: serialised field names must match
    // RestQrClient.MerchantResponse exactly so Jackson binds them.
    // ------------------------------------------------------------------

    @Test
    void responseFieldNames_matchRestQrClientContract() throws Exception {
        ResponseEntity<MerchantResponse> resp = controller.getByQr(ACTIVE_QR);
        MerchantResponse body = resp.getBody();
        assertNotNull(body);

        // Serialise to JSON and check the exact field names present
        String json = objectMapper.writeValueAsString(body);
        JsonNode node = objectMapper.readTree(json);

        // Fields RestQrClient.MerchantResponse reads (must all be present):
        assertTrue(node.has("merchantId"),       "JSON must have 'merchantId'");
        assertTrue(node.has("merchantName"),     "JSON must have 'merchantName' (not 'name')");
        assertTrue(node.has("payoutCurrency"),   "JSON must have 'payoutCurrency'");
        assertTrue(node.has("schemeId"),         "JSON must have 'schemeId'");
        assertTrue(node.has("active"),           "JSON must have 'active'");
        assertTrue(node.has("status"),           "JSON must have 'status'");

        // Confirm the old field name 'name' is NOT present (would shadow merchantName)
        assertFalse(node.has("name"),
                "JSON must NOT have a bare 'name' field — RestQrClient reads 'merchantName'");

        // Verify values round-trip correctly
        assertEquals("M0000000V01", node.get("merchantId").asText());
        assertEquals("Valid Merchant", node.get("merchantName").asText());
        assertEquals("KRW", node.get("payoutCurrency").asText());
        assertEquals("ZEROPAY", node.get("schemeId").asText());
        assertTrue(node.get("active").asBoolean());
        assertEquals("ACTIVE", node.get("status").asText());
    }

    // ------------------------------------------------------------------
    // RestQrClient isActive() fallback logic verification
    // ------------------------------------------------------------------

    @Test
    void deactivatedMerchant_restQrClientFallback_returnsNotActive() throws Exception {
        // RestQrClient.isActive() = active || "ACTIVE".equalsIgnoreCase(status)
        // When active=false AND status=DEACTIVATED -> isActive() must return false
        ResponseEntity<MerchantResponse> resp = controller.getByQr(DEACTIVATED_QR);
        MerchantResponse body = resp.getBody();
        assertNotNull(body);

        String json = objectMapper.writeValueAsString(body);
        JsonNode node = objectMapper.readTree(json);

        boolean active = node.get("active").asBoolean();
        String status = node.get("status").asText();

        // Simulate RestQrClient.isActive() logic
        boolean restQrClientIsActive = active || "ACTIVE".equalsIgnoreCase(status);
        assertFalse(restQrClientIsActive,
                "RestQrClient.isActive() must return false for DEACTIVATED merchant");
    }

    @Test
    void activeMerchant_restQrClientFallback_returnsActive() throws Exception {
        ResponseEntity<MerchantResponse> resp = controller.getByQr(ACTIVE_QR);
        MerchantResponse body = resp.getBody();
        assertNotNull(body);

        String json = objectMapper.writeValueAsString(body);
        JsonNode node = objectMapper.readTree(json);

        boolean active = node.get("active").asBoolean();
        String status = node.get("status").asText();

        boolean restQrClientIsActive = active || "ACTIVE".equalsIgnoreCase(status);
        assertTrue(restQrClientIsActive,
                "RestQrClient.isActive() must return true for ACTIVE merchant");
    }

    // ------------------------------------------------------------------
    // Merchant type present for fee-rate classification
    // ------------------------------------------------------------------

    @Test
    void merchantType_presentInResponse_enablesFeeRateClassification() {
        ResponseEntity<MerchantResponse> resp = controller.getByQr(ACTIVE_QR);
        MerchantResponse body = resp.getBody();
        assertNotNull(body);
        assertEquals("RETAIL", body.merchantType(),
                "merchantType must be present for fee-rate classification (UC-07-03)");
        assertEquals("DOMESTIC", body.feeType());
    }
}
