package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.AuditTrailClient;
import com.gme.pay.contracts.AuditEntryView;
import com.gme.pay.contracts.PageView;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stub implementation of {@link AuditTrailClient}. Active when
 * {@code gmepay.config-registry.client} is absent or set to {@code "stub"} (the
 * default) so the BFF boots and unit tests run without config-registry.
 *
 * <p>Returns an empty page for all queries — the stub exists only to satisfy
 * the Spring context; realistic data requires the REST implementation.
 */
@Component
@ConditionalOnProperty(
        name = "gmepay.config-registry.client",
        havingValue = "stub",
        matchIfMissing = true)
public class StubAuditTrailClient implements AuditTrailClient {

    @Override
    public PageView<AuditEntryView> list(String aggregateType, String aggregateId,
                                         int page, int size) {
        return new PageView<>(List.of(), page, size, 0L);
    }
}
