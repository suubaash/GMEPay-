package com.gme.pay.qr.persistence;

import com.gme.pay.qr.domain.cpm.MerchantQrDataPort.MerchantResolution;
import com.gme.pay.qr.domain.emvco.ParsedQRPayload;
import com.gme.pay.qr.exception.MerchantNotFoundException;
import com.gme.pay.qr.exception.QRErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit slice for the cache persistence layer on H2 in PostgreSQL mode (17.2-G04).
 *
 * <p>Runs everywhere without Docker. The same repositories/migrations are exercised
 * against real PostgreSQL 16 in {@link QrPersistencePostgresIT} (CI only, @Tag("docker")).
 * Flyway applies V001/V002 against the H2-PG-mode datasource from application.properties
 * ({@code replace = NONE} keeps that datasource instead of a plain-H2 embedded one).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaQrParseCacheAdapter.class, CachedMerchantQrDataAdapter.class})
class QrCachePersistenceH2SliceTest {

    @Autowired
    private JpaQrParseCacheAdapter parseCache;

    @Autowired
    private QrParseCacheRepository parseCacheRepository;

    @Autowired
    private CachedMerchantQrDataAdapter merchantAdapter;

    @Test
    void storeThenFindReturnsCachedPayload() {
        ParsedQRPayload original = samplePayload("RAW-PAYLOAD-1", new BigDecimal("1500"));

        parseCache.store(original);
        Optional<ParsedQRPayload> found = parseCache.findCached("RAW-PAYLOAD-1");

        assertTrue(found.isPresent());
        ParsedQRPayload cached = found.get();
        assertEquals(original.rawPayload(),    cached.rawPayload());
        assertEquals(original.merchantId(),    cached.merchantId());
        assertEquals(original.qrCodeId(),      cached.qrCodeId());
        assertEquals(original.currencyCode(),  cached.currencyCode());
        assertEquals(original.merchantName(),  cached.merchantName());
        assertEquals(original.merchantCity(),  cached.merchantCity());
        assertEquals(original.mcc(),           cached.mcc());
        assertEquals(original.countryCode(),   cached.countryCode());
        assertEquals(original.maiTag(),        cached.maiTag());
        assertEquals(original.formatIndicator(), cached.formatIndicator());
        assertTrue(cached.crcVerified());
        // NUMERIC(20,8) round-trip: numerically identical (scale may normalise to 8)
        assertEquals(0, cached.encodedAmount().compareTo(new BigDecimal("1500")));
    }

    @Test
    void nullAmountRoundTripsToEqualRecord() {
        ParsedQRPayload original = samplePayload("RAW-PAYLOAD-NULL-AMOUNT", null);

        parseCache.store(original);

        assertEquals(Optional.of(original), parseCache.findCached("RAW-PAYLOAD-NULL-AMOUNT"));
    }

    @Test
    void findUnknownPayloadReturnsEmpty() {
        assertTrue(parseCache.findCached("NEVER-SEEN-BEFORE").isEmpty());
    }

    @Test
    void storingSamePayloadTwiceUpsertsSingleRow() {
        parseCache.store(samplePayload("RAW-PAYLOAD-DUP", new BigDecimal("10")));
        parseCache.store(samplePayload("RAW-PAYLOAD-DUP", new BigDecimal("20")));

        assertEquals(1, parseCacheRepository.count());
        assertEquals(0, parseCache.findCached("RAW-PAYLOAD-DUP").orElseThrow()
                .encodedAmount().compareTo(new BigDecimal("20")));
    }

    @Test
    void resolveReturnsCachedMerchant() {
        merchantAdapter.cache("QR999",
                new MerchantResolution("M999", "Seoul Mart", "ZEROPAY", true));

        MerchantResolution resolved = merchantAdapter.resolve("QR999");

        assertEquals("M999",       resolved.merchantId());
        assertEquals("Seoul Mart", resolved.merchantName());
        assertEquals("ZEROPAY",    resolved.schemeId());
        assertTrue(resolved.active());
    }

    @Test
    void resolveMissThrowsMerchantNotFound() {
        MerchantNotFoundException ex = assertThrows(MerchantNotFoundException.class,
                () -> merchantAdapter.resolve("QR-ABSENT"));

        assertEquals(QRErrorCode.MERCHANT_NOT_FOUND, ex.getErrorCode());
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private static ParsedQRPayload samplePayload(String rawPayload, BigDecimal amount) {
        return new ParsedQRPayload(rawPayload, 1, "410", "TestMerchant", "Seoul",
                "5411", "KR", 26, "M123456789", "QR00000000000000000001", amount, true);
    }
}
