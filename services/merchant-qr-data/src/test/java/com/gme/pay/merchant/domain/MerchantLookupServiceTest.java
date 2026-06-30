package com.gme.pay.merchant.domain;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 unit tests for {@link MerchantLookupService}.
 *
 * <p>No Spring context, no Docker, no Testcontainers — fully deterministic.
 */
class MerchantLookupServiceTest {

    private static final String KNOWN_QR    = "QR00000000000000099A";
    private static final String UNKNOWN_QR  = "QR_DOES_NOT_EXIST___";

    private InMemoryMerchantRepository repository;
    private MerchantLookupService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryMerchantRepository();
        // Override the default seeds with a controlled fixture
        // Remove existing seeds and insert a single known merchant
        repository.remove("QR00000000000000001A");
        repository.remove("QR00000000000000002B");
        repository.remove("QR00000000000000003C");
        repository.remove("QR00000000000000004D");

        repository.put(new Merchant(
                "M0000000099",
                KNOWN_QR,
                "Test Merchant",
                "RETAIL",
                "DOMESTIC",
                "ACTIVE",
                true));

        service = new MerchantLookupService(repository);
    }

    @Test
    void getByQrCodeId_found_returnsMerchant() {
        Merchant result = service.getByQrCodeId(KNOWN_QR);

        assertNotNull(result, "Expected a non-null Merchant for a known QR code");
        assertEquals("M0000000099", result.merchantId());
        assertEquals(KNOWN_QR, result.qrCodeId());
        assertEquals("Test Merchant", result.name());
        assertEquals("ACTIVE", result.status());
        assertTrue(result.active());
        assertTrue(result.isOperational());
    }

    @Test
    void getByQrCodeId_notFound_throwsMerchantNotFound() {
        ApiException ex = assertThrows(
                ApiException.class,
                () -> service.getByQrCodeId(UNKNOWN_QR),
                "Expected ApiException for an unknown QR code");

        assertEquals(ErrorCode.MERCHANT_NOT_FOUND, ex.errorCode());
        assertEquals(404, ex.errorCode().httpStatus());
        assertFalse(ex.errorCode().retryable());
        assertTrue(ex.getMessage().contains(UNKNOWN_QR),
                "Error message should reference the unknown QR code");
    }

    @Test
    void isOperational_suspendedMerchant_returnsFalse() {
        repository.put(new Merchant(
                "M0000000050",
                "QR_SUSPENDED_________",
                "Suspended Shop",
                "RETAIL",
                "DOMESTIC",
                "SUSPENDED",
                false));

        Merchant suspended = service.getByQrCodeId("QR_SUSPENDED_________");

        assertFalse(suspended.isOperational(),
                "Suspended merchant must not be operational");
    }

    // ------------------------------------------------------------------
    // Strict mode — reject non-operational merchants (lenient-bypass removal)
    // ------------------------------------------------------------------

    @Test
    void strictMode_deactivatedMerchant_isRejected() {
        repository.put(new Merchant(
                "M0000000060", "QR_DEACTIVATED_______",
                "Closed Shop", "RETAIL", "DOMESTIC", "DEACTIVATED", false));

        MerchantLookupService strict = new MerchantLookupService(repository, true);

        ApiException ex = assertThrows(ApiException.class,
                () -> strict.getByQrCodeId("QR_DEACTIVATED_______"),
                "strict mode must reject a non-operational merchant");
        assertEquals(ErrorCode.MERCHANT_NOT_FOUND, ex.errorCode());
        assertTrue(ex.getMessage().contains("not operational"));
        assertTrue(strict.isStrictMode());
    }

    @Test
    void strictMode_activeMerchant_isReturned() {
        MerchantLookupService strict = new MerchantLookupService(repository, true);

        Merchant result = strict.getByQrCodeId(KNOWN_QR);
        assertEquals("M0000000099", result.merchantId());
        assertTrue(result.isOperational());
    }

    @Test
    void lenientMode_deactivatedMerchant_stillReturned() {
        repository.put(new Merchant(
                "M0000000061", "QR_DEACTIVATED_2_____",
                "Closed Shop", "RETAIL", "DOMESTIC", "DEACTIVATED", false));

        // Default service is lenient (strictMode=false).
        Merchant result = service.getByQrCodeId("QR_DEACTIVATED_2_____");
        assertEquals("M0000000061", result.merchantId());
        assertFalse(result.isOperational(),
                "lenient mode returns the inactive merchant (legacy bypass)");
        assertFalse(service.isStrictMode());
    }

    @Test
    void getByQrCodeId_nullQr_throwsMerchantNotFound() {
        ApiException ex = assertThrows(
                ApiException.class,
                () -> service.getByQrCodeId(null));

        assertEquals(ErrorCode.MERCHANT_NOT_FOUND, ex.errorCode());
    }
}
