package com.gme.pay.scheme.zeropay.batch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.util.Map;

/**
 * In-process fallback {@link ZpBatchEnrichmentPort} that returns empty maps for every query.
 *
 * <p>This is the default bean: with no upstream enrichment configured (the common case for
 * local/CI runs where transaction-management is not running), the data port keeps its
 * pre-enrichment behaviour — refund amounts stay as captured (0) and the ZP0065/ZP0066 value
 * date falls back to the business date.</p>
 *
 * <p>It is registered via {@link ConditionalOnMissingBean} so the REST-backed
 * {@link RestTransactionMgmtEnrichmentPort} (gated by {@code adapter.zeropay.enrichment.enabled})
 * takes precedence whenever it is active.</p>
 */
@Configuration
public class NoOpZpBatchEnrichmentPort {

    @Bean
    @ConditionalOnMissingBean(ZpBatchEnrichmentPort.class)
    ZpBatchEnrichmentPort noOpEnrichmentPort() {
        return new ZpBatchEnrichmentPort() {
            @Override
            public Map<String, RefundEnrichment> refundEnrichment(LocalDate businessDate) {
                return Map.of();
            }

            @Override
            public Map<String, LocalDate> settlementValueDates(LocalDate businessDate) {
                return Map.of();
            }
        };
    }
}
