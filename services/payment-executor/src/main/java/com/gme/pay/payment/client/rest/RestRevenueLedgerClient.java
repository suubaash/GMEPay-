package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import java.math.BigDecimal;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Real REST client for revenue-ledger's {@code POST /v1/journals/rounding-residual} endpoint.
 * Annotated {@code @Primary} so it wins over any stub bean in the same context.
 *
 * <p>Failures DO NOT throw to the orchestrator (the commit path must not fail because of a
 * downstream residual posting). 4xx responses are logged at WARN; 5xx are logged at ERROR;
 * the caller continues. The residual remains locked on the transaction (see
 * {@code Transaction.lockSettlementBooking}) so it can be replayed by an out-of-band job.
 */
@Component
@Primary
public class RestRevenueLedgerClient implements RevenueLedgerClient {

    private static final Logger log = LoggerFactory.getLogger(RestRevenueLedgerClient.class);
    private static final String PATH = "/v1/journals/rounding-residual";

    private final RestClient restClient;

    @Autowired
    public RestRevenueLedgerClient(@Value("${gmepay.revenue-ledger.base-url:http://revenue-ledger:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Test-friendly constructor that takes a pre-built {@link RestClient}. */
    public RestRevenueLedgerClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void postRoundingResidual(String reference, BigDecimal residual, String currency) {
        if (residual == null || residual.signum() == 0) {
            return; // zero residual = no-op, by contract
        }
        Map<String, Object> body = Map.of(
                "reference", reference,
                "residual", residual,
                "currency", currency);
        try {
            restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpServerErrorException ex) {
            log.error("revenue-ledger 5xx posting rounding residual ref={} residual={} {}: {}",
                    reference, residual, currency, ex.getStatusCode(), ex);
            // do NOT propagate — residual is locked on the txn for offline retry
        } catch (Exception ex) {
            // Could be 4xx, connection refused, timeout, etc. Same policy: log and continue.
            log.warn("revenue-ledger residual post failed ref={} residual={} {}: {}",
                    reference, residual, currency, ex.toString());
        }
    }
}
