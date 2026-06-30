package com.gme.pay.settlement.client;

import com.gme.pay.settlement.port.RefundedTransactionPort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * In-process fallback for {@link RefundedTransactionPort}, active whenever the gated
 * {@link RestRefundedTransactionClient} is NOT enabled
 * ({@code gmepay.clients.transaction-mgmt.refunded.enabled} unset/false) — i.e. dev/test where
 * transaction-mgmt is not running. Returns an empty refund set: no cross-date claw-back is netted,
 * which is the safe no-op (the per-creation-date refund path in {@link com.gme.pay.settlement.port.TransactionQueryPort}
 * still applies). A test may subclass / override to inject deterministic fixtures.
 *
 * <p>Not {@code @Primary}, so the REST client wins when present; this is the default bean only when the
 * REST client's condition is unmet.
 */
@Component
public class FixtureRefundedTransactionAdapter implements RefundedTransactionPort {

    @Override
    public List<RefundLeg> findRefundedOn(LocalDate refundedOn) {
        return List.of();
    }
}
