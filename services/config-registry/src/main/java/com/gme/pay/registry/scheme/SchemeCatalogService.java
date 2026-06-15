package com.gme.pay.registry.scheme;

import com.gme.pay.registry.web.SchemeCatalogResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Supplies the platform's supported-scheme catalog for {@code GET /v1/schemes}.
 *
 * <p>The catalog is static reference data: the set of QR payment schemes GMEPay+
 * can integrate, with {@code status} reflecting integration truth. Today only
 * {@code ZEROPAY} has a live scheme adapter ({@code ACTIVE}); the Phase-2 corridor
 * schemes are {@code PLANNED} (roadmap, no adapter yet) so the Admin UI shows the
 * honest roster rather than implying schemes are routable when they are not.
 *
 * <p>Kept as a code-level constant (not a Flyway-seeded table) because the roster
 * changes only when a new adapter ships — a code change anyway. When dynamic
 * scheme onboarding is needed it can move to a {@code scheme_catalog} table without
 * changing this method's contract.
 */
@Service
public class SchemeCatalogService {

    private static final List<SchemeCatalogResponse> CATALOG = List.of(
            new SchemeCatalogResponse("ZEROPAY", "ZeroPay (Korea)", "KR", "KRW", "LIVE", "ACTIVE"),
            new SchemeCatalogResponse("KHQR", "KHQR / Bakong (Cambodia)", "KH", "KHR", "LIVE", "PLANNED"),
            new SchemeCatalogResponse("QPAY", "QPay (Mongolia)", "MN", "MNT", "LIVE", "PLANNED"),
            new SchemeCatalogResponse("QRIS", "QRIS (Indonesia)", "ID", "IDR", "LIVE", "PLANNED"),
            new SchemeCatalogResponse("SBP", "SBP Faster Payments (Russia)", "RU", "RUB", "LIVE", "PLANNED"),
            new SchemeCatalogResponse("PROMPTPAY", "PromptPay (Thailand)", "TH", "THB", "LIVE", "PLANNED"));

    /** All supported schemes, ZEROPAY first. Never null/empty. */
    public List<SchemeCatalogResponse> listSchemes() {
        return CATALOG;
    }
}
