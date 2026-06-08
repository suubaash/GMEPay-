package com.gme.pay.payment.domain.settlement;

import com.gme.pay.payment.domain.client.PartnerConfigClient;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Phase-1 stub for {@link PartnerConfigClient} until config-registry integration. Demonstrates a
 * partner (1002) that books round-DOWN to 2dp vs the default HALF_UP. Replaced by a REST client in Phase 2.
 */
public class StubPartnerConfigClient implements PartnerConfigClient {

    private final Map<Long, SettlementConfig> configs = Map.of(
            1001L, new SettlementConfig("KRW", RoundingMode.HALF_UP),
            1002L, new SettlementConfig("USD", RoundingMode.DOWN)
    );

    @Override
    public SettlementConfig getSettlementConfig(long partnerId) {
        return configs.getOrDefault(partnerId, new SettlementConfig("USD", RoundingMode.HALF_UP));
    }
}
