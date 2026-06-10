package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.ConfigRegistryClient;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Phase-1 in-memory stub of {@link ConfigRegistryClient}. Lets the BFF boot and be
 * exercised end-to-end without booting config-registry. A future
 * {@code RestConfigRegistryClient} marked {@code @Primary} will take over without
 * removing this bean.
 *
 * <p>The fixed dataset matches the partners used by other services' tests so the
 * Admin UI shows consistent IDs across the stack during local development.
 */
@Component
public class StubConfigRegistryClient implements ConfigRegistryClient {

    private static final Map<String, PartnerSummary> STORE = Map.of(
            "partner_test_001", new PartnerSummary(
                    "partner_test_001", "OVERSEAS", "USD", RoundingMode.HALF_UP),
            "partner_test_002", new PartnerSummary(
                    "partner_test_002", "DOMESTIC", "KRW", RoundingMode.DOWN),
            "partner_test_003", new PartnerSummary(
                    "partner_test_003", "OVERSEAS", "JPY", RoundingMode.HALF_EVEN));

    @Override
    public PartnerSummary getPartner(String partnerId) {
        return STORE.get(partnerId);
    }

    @Override
    public List<PartnerSummary> listPartners() {
        return List.copyOf(STORE.values());
    }
}
