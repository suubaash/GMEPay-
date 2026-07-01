package com.gme.pay.registry.scheme;

import com.gme.pay.registry.web.SchemeCatalogResponse;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Supplies the platform's supported-scheme catalog for {@code GET /v1/schemes}.
 *
 * <p>The catalog is static reference data: the set of QR payment schemes GMEPay+
 * can integrate, with {@code status} reflecting integration truth. Today
 * {@code ZEROPAY} and {@code NEPAL} have live scheme adapters ({@code ACTIVE});
 * the Phase-2 corridor schemes are {@code PLANNED} (roadmap, no adapter yet) so
 * the Admin UI shows the honest roster rather than implying schemes are routable
 * when they are not.
 *
 * <p>Kept as a code-level constant (not a Flyway-seeded table) because the roster
 * changes only when a new adapter ships — a code change anyway. When dynamic
 * scheme onboarding is needed it can move to a {@code scheme_catalog} table without
 * changing this method's contract.
 *
 * <h2>Single source of truth for scheme IDs</h2>
 *
 * <p>The {@code schemeId}s here are the ONE roster the whole platform agrees on:
 * the V022 {@code partner_scheme} DB CHECK ({@code ck_partner_scheme_scheme}) and
 * the BFF's {@code StubConfigRegistryClient} both carry the same values. The
 * Admin UI scheme picker is populated from this catalog, so any id offered here MUST
 * be one the Slice-7 enablement endpoint ({@link PartnerSchemeService#replaceDraftSchemes})
 * will accept — otherwise the picker offers schemes the save endpoint rejects with a
 * 400. To enforce that invariant {@link PartnerSchemeService} derives its accepted
 * roster from {@link #schemeIds()} rather than hard-coding a parallel list (which is
 * how the two drifted: the catalog had once advertised {@code QPAY}/{@code SBP}/
 * {@code PROMPTPAY}, none of which the DB CHECK or the enablement endpoint accept).
 */
@Service
public class SchemeCatalogService {

    private static final List<SchemeCatalogResponse> CATALOG = List.of(
            new SchemeCatalogResponse("ZEROPAY", "ZeroPay (Korea)", "KR", "KRW", "LIVE", "ACTIVE"),
            // Nepal QR rail. A live scheme-adapter-nepal ships alongside this wiring, so
            // NEPAL is ACTIVE (a second live adapter beside ZEROPAY). The supported
            // Nepal networks (khalti / mobank / fonepay / nepalpay / unionpay / smartqr)
            // are sub-networks/modes carried by the adapter, not distinct catalog rows —
            // the DTO has no networks field, so they are named descriptively here.
            new SchemeCatalogResponse(
                    "NEPAL",
                    "Nepal QR (khalti/mobank/fonepay/nepalpay/unionpay/smartqr)",
                    "NP", "NPR", "LIVE", "ACTIVE"),
            new SchemeCatalogResponse("BAKONG", "Bakong / KHQR (Cambodia)", "KH", "KHR", "LIVE", "PLANNED"),
            new SchemeCatalogResponse("KHQR", "KHQR (Cambodia)", "KH", "KHR", "LIVE", "PLANNED"),
            new SchemeCatalogResponse("NAPAS_247", "NAPAS 247 (Vietnam)", "VN", "VND", "LIVE", "PLANNED"),
            new SchemeCatalogResponse("PROMPT_PAY", "PromptPay (Thailand)", "TH", "THB", "LIVE", "PLANNED"),
            new SchemeCatalogResponse("FAST_SG", "FAST / PayNow (Singapore)", "SG", "SGD", "LIVE", "PLANNED"),
            new SchemeCatalogResponse("QRIS", "QRIS (Indonesia)", "ID", "IDR", "LIVE", "PLANNED"));

    /**
     * The closed set of scheme IDs in the catalog, insertion-ordered (ZEROPAY first).
     * This is the platform-wide roster — it MUST equal the V022 {@code partner_scheme}
     * DB CHECK set. {@link PartnerSchemeService} consumes it so the picker and the
     * enablement endpoint cannot drift.
     */
    private static final Set<String> SCHEME_IDS;

    static {
        Set<String> ids = new LinkedHashSet<>();
        for (SchemeCatalogResponse s : CATALOG) {
            ids.add(s.schemeId());
        }
        SCHEME_IDS = Set.copyOf(ids);
    }

    /** All supported schemes, ZEROPAY first. Never null/empty. */
    public List<SchemeCatalogResponse> listSchemes() {
        return CATALOG;
    }

    /**
     * The catalog's scheme IDs as a closed set — the single source of truth for which
     * scheme IDs are valid platform-wide. Equal to the V022 {@code partner_scheme} DB
     * CHECK roster.
     */
    public static Set<String> schemeIds() {
        return SCHEME_IDS;
    }
}
