package com.gme.pay.router.resolve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-process {@link PartnerSchemeRegistry} fixture — the resolver's default
 * backing until the config-registry V022 read contract is delivered (see the
 * INTEGRATION REQUEST). Seeded from the real V022 CHECK roster + the corridors
 * GMEPay+ operates today (KR/ZeroPay domestic; KH/VN/SG inbound corridors) so
 * resolution behaves data-drivenly, not from a code allow-list.
 *
 * <p>Marked {@code @Profile("!config-registry")} so wiring the production REST
 * adapter (profile {@code config-registry}) silently displaces this fixture
 * without a code change in the resolver.
 *
 * <p>Thread-safe; {@link #add(PartnerSchemeRecord)} lets tests stage corridors.
 */
@Component
@Profile("!config-registry")
public class InMemoryPartnerSchemeRegistry implements PartnerSchemeRegistry {

    private final Map<String, List<PartnerSchemeRecord>> byCountry = new ConcurrentHashMap<>();

    public InMemoryPartnerSchemeRegistry() {
        // KR domestic: ZeroPay, both presentment modes, the live corridor.
        add(new PartnerSchemeRecord("ZEROPAY", "KR", "BOTH", true, true, 0));
        // KH inbound: KHQR (MPM only) preferred, BAKONG (both) fallback.
        add(new PartnerSchemeRecord("KHQR", "KH", "INBOUND", false, true, 0));
        add(new PartnerSchemeRecord("BAKONG", "KH", "BOTH", true, true, 1));
        // VN inbound: NAPAS_247, MPM only.
        add(new PartnerSchemeRecord("NAPAS_247", "VN", "INBOUND", false, true, 0));
        // SG inbound: PROMPT_PAY (MPM) preferred, FAST_SG (CPM) fallback.
        add(new PartnerSchemeRecord("PROMPT_PAY", "SG", "INBOUND", false, true, 0));
        add(new PartnerSchemeRecord("FAST_SG", "SG", "OUTBOUND", true, false, 1));
    }

    /** Stage / override a corridor row (tests + the seed constructor). */
    public final void add(PartnerSchemeRecord record) {
        String key = record.countryCode();
        byCountry.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
    }

    @Override
    public List<PartnerSchemeRecord> schemesForCountry(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return List.of();
        }
        String key = countryCode.trim().toUpperCase(Locale.ROOT);
        List<PartnerSchemeRecord> rows = byCountry.get(key);
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .sorted(Comparator.comparingInt(PartnerSchemeRecord::priority))
                .toList();
    }
}
