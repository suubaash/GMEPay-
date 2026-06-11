package com.gme.pay.qr.persistence;

import com.gme.pay.qr.domain.cpm.MerchantQrDataPort.MerchantResolution;
import com.gme.pay.qr.domain.emvco.ParsedQRPayload;
import com.gme.pay.qr.exception.MerchantNotFoundException;
import com.gme.pay.qr.exception.QRErrorCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-PostgreSQL integration test for the qr-service persistence layer (17.2-G04).
 *
 * <p>Docker-backed: tagged {@code docker} so the normal `test` task skips it (this dev
 * machine has no Docker) and CI runs it via the `integrationTest` task on ubuntu runners.
 * Boots the full Spring context against a postgres:16 container, letting Flyway apply
 * V001/V002 with PostgreSQL syntax — no H2-mode workarounds.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Transactional
class QrPersistencePostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",               POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",          POSTGRES::getUsername);
        registry.add("spring.datasource.password",          POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private JpaQrParseCacheAdapter parseCache;

    @Autowired
    private QrParseCacheRepository parseCacheRepository;

    @Autowired
    private CachedMerchantQrDataAdapter merchantAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliedBothMigrationsSuccessfully() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertNotNull(applied);
        assertEquals(2, applied);

        // Both cache tables exist and are empty on a fresh database.
        assertEquals(Integer.valueOf(0), jdbcTemplate.queryForObject(
                "SELECT count(*) FROM qr_parse_cache", Integer.class));
        assertEquals(Integer.valueOf(0), jdbcTemplate.queryForObject(
                "SELECT count(*) FROM merchant_resolution_cache", Integer.class));
    }

    @Test
    void parseCacheRoundTripOnPostgres() {
        ParsedQRPayload original = samplePayload("PG-RAW-PAYLOAD-1", new BigDecimal("12345.50000001"));

        parseCache.store(original);
        Optional<ParsedQRPayload> found = parseCache.findCached("PG-RAW-PAYLOAD-1");

        assertTrue(found.isPresent());
        ParsedQRPayload cached = found.get();
        assertEquals("M123456789",            cached.merchantId());
        assertEquals("QR00000000000000000001", cached.qrCodeId());
        assertEquals("410",                   cached.currencyCode());
        assertEquals("KR",                    cached.countryCode());
        assertTrue(cached.crcVerified());
        // NUMERIC(20,8): numerically exact, scale normalised to 8 by the column type.
        assertEquals(0, cached.encodedAmount().compareTo(new BigDecimal("12345.50000001")));
        assertEquals(8, cached.encodedAmount().scale());
    }

    @Test
    void storingSamePayloadTwiceUpsertsSingleRowOnPostgres() {
        parseCache.store(samplePayload("PG-RAW-PAYLOAD-DUP", new BigDecimal("10")));
        parseCache.store(samplePayload("PG-RAW-PAYLOAD-DUP", new BigDecimal("20")));

        assertEquals(1, parseCacheRepository.count());
        assertEquals(0, parseCache.findCached("PG-RAW-PAYLOAD-DUP").orElseThrow()
                .encodedAmount().compareTo(new BigDecimal("20")));
    }

    @Test
    void merchantResolutionHitAndMissOnPostgres() {
        merchantAdapter.cache("QR999",
                new MerchantResolution("M999", "Seoul Mart", "ZEROPAY", true));

        MerchantResolution resolved = merchantAdapter.resolve("QR999");
        assertEquals("M999",    resolved.merchantId());
        assertEquals("ZEROPAY", resolved.schemeId());
        assertTrue(resolved.active());

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
