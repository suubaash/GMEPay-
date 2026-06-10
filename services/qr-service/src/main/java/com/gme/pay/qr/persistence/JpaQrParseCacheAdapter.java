package com.gme.pay.qr.persistence;

import com.gme.pay.qr.domain.cache.QrParseCachePort;
import com.gme.pay.qr.domain.emvco.ParsedQRPayload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * JPA adapter for {@link QrParseCachePort} backed by the {@code qr_parse_cache} table
 * (ticket 17.2-G04). Keys rows by SHA-256 hex of the raw payload so lookups never depend
 * on parsing the payload first.
 */
@Component
public class JpaQrParseCacheAdapter implements QrParseCachePort {

    private final QrParseCacheRepository repository;

    public JpaQrParseCacheAdapter(QrParseCacheRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ParsedQRPayload> findCached(String rawPayload) {
        return repository.findById(sha256Hex(rawPayload)).map(JpaQrParseCacheAdapter::toDomain);
    }

    @Override
    @Transactional
    public void store(ParsedQRPayload parsed) {
        repository.save(new QrParseCacheEntity(
                sha256Hex(parsed.rawPayload()),
                parsed.rawPayload(),
                parsed.formatIndicator(),
                parsed.currencyCode(),
                parsed.merchantName(),
                parsed.merchantCity(),
                parsed.mcc(),
                parsed.countryCode(),
                parsed.maiTag(),
                parsed.merchantId(),
                parsed.qrCodeId(),
                parsed.encodedAmount(),
                parsed.crcVerified(),
                Instant.now()
        ));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ParsedQRPayload toDomain(QrParseCacheEntity e) {
        return new ParsedQRPayload(
                e.getRawPayload(),
                e.getFormatIndicator(),
                e.getCurrencyCode(),
                e.getMerchantName(),
                e.getMerchantCity(),
                e.getMcc(),
                e.getCountryCode(),
                e.getMaiTag(),
                e.getMerchantId(),
                e.getQrCodeId(),
                e.getEncodedAmount(),
                e.isCrcVerified()
        );
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
    }
}
