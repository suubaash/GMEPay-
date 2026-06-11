package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.ConfigRegistryClient;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase-1 in-memory stub of {@link ConfigRegistryClient}. Lets the BFF boot and be
 * exercised end-to-end without booting config-registry. A future
 * {@code RestConfigRegistryClient} marked {@code @Primary} will take over without
 * removing this bean.
 *
 * <p>The seed dataset matches the partners used by other services' tests so the
 * Admin UI shows consistent IDs across the stack during local development. The
 * store is mutable so the Admin UI partner-form happy path can round-trip a
 * create or rounding-mode update without booting config-registry.
 */
/**
 * Default unless {@code gmepay.config-registry.client=rest} (then
 * {@link com.gme.pay.bff.client.rest.RestConfigRegistryClient} wins). Keeping
 * the stub on the classpath lets the BFF and its unit slices boot without
 * config-registry being up.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "gmepay.config-registry.client",
        havingValue = "stub",
        matchIfMissing = true)
public class StubConfigRegistryClient implements ConfigRegistryClient {

    private final Map<String, PartnerSummary> store = new LinkedHashMap<>();
    private final List<SchemeSummary> schemes;

    public StubConfigRegistryClient() {
        store.put("partner_test_001", new PartnerSummary(
                "partner_test_001", "OVERSEAS", "USD", RoundingMode.HALF_UP));
        store.put("partner_test_002", new PartnerSummary(
                "partner_test_002", "LOCAL", "KRW", RoundingMode.DOWN));
        store.put("partner_test_003", new PartnerSummary(
                "partner_test_003", "OVERSEAS", "JPY", RoundingMode.HALF_EVEN));
        schemes = List.of(
                new SchemeSummary("zeropay_kr", "ZeroPay KR", "KR", "KRW", "DOMESTIC", "ACTIVE"),
                new SchemeSummary("paynow_sg",  "PayNow SG",  "SG", "SGD", "OVERSEAS", "ACTIVE"),
                new SchemeSummary("upi_in",     "UPI IN",     "IN", "INR", "OVERSEAS", "PILOT"));
    }

    @Override
    public PartnerSummary getPartner(String partnerId) {
        return store.get(partnerId);
    }

    @Override
    public List<PartnerSummary> listPartners() {
        return List.copyOf(store.values());
    }

    @Override
    public synchronized PartnerSummary createPartner(PartnerCreateRequest request) {
        RoundingMode mode = parseMode(request.settlementRoundingMode());
        PartnerSummary created = new PartnerSummary(
                request.partnerId(), request.type(), request.settlementCurrency(), mode);
        store.put(created.partnerId(), created);
        return created;
    }

    @Override
    public synchronized PartnerSummary updateRoundingMode(String partnerId, String mode) {
        PartnerSummary existing = store.get(partnerId);
        if (existing == null) {
            return null;
        }
        PartnerSummary updated = new PartnerSummary(
                existing.partnerId(),
                existing.type(),
                existing.settlementCurrency(),
                parseMode(mode));
        store.put(partnerId, updated);
        return updated;
    }

    @Override
    public List<SchemeSummary> listSchemes() {
        return new ArrayList<>(schemes);
    }

    private static RoundingMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return RoundingMode.HALF_UP;
        }
        return RoundingMode.valueOf(raw);
    }
}
