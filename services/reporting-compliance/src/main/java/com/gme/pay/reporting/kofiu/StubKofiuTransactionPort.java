package com.gme.pay.reporting.kofiu;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Default (stub) implementation of {@link KofiuTransactionPort}.
 *
 * <p>Active whenever no other bean of type {@link KofiuTransactionPort} is
 * present in the context (e.g. a production HTTP client). Returns an empty
 * list so the service boots and the scheduler runs harmlessly without real
 * transaction-mgmt credentials.
 *
 * <p>Replace this with a real HTTP client pointing at
 * {@code GET /v1/transactions/kofiu?fromKst=...&toKst=...} once the
 * transaction-mgmt team exposes that endpoint.
 */
@Component
@ConditionalOnMissingBean(value = KofiuTransactionPort.class,
        ignored = StubKofiuTransactionPort.class)
public class StubKofiuTransactionPort implements KofiuTransactionPort {

    @Override
    public List<KofiuTransaction> fetchForKofiu(LocalDate fromKst, LocalDate toKst) {
        // No real transaction-mgmt endpoint wired yet — return empty.
        return List.of();
    }
}
