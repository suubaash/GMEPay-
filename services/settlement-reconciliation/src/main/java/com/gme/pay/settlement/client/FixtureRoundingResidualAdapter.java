package com.gme.pay.settlement.client;

import com.gme.pay.settlement.port.RoundingResidualPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * In-process fallback for {@link RoundingResidualPort}, active whenever the gated
 * {@link RestRoundingResidualClient} is NOT enabled ({@code gmepay.clients.revenue-ledger.enabled}
 * unset/false) — i.e. dev/test where revenue-ledger is not running.
 *
 * <p>Logs the residual and reports success so the recon path can mark the batch posted (the
 * once-per-batch guard still advances), without coupling dev/test to a live revenue-ledger. Not
 * {@code @Primary}, so the REST client wins when its condition is met.
 */
@Component
public class FixtureRoundingResidualAdapter implements RoundingResidualPort {

    private static final Logger log = LoggerFactory.getLogger(FixtureRoundingResidualAdapter.class);

    @Override
    public boolean postResidual(String batchId, BigDecimal residual, String currency) {
        log.info("[fixture] rounding residual reference={} residual={} {} (revenue-ledger client disabled)",
                batchId, residual == null ? "0" : residual.toPlainString(), currency);
        return true;
    }
}
