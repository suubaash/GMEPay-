package com.gme.pay.settlement.client;

import com.gme.pay.settlement.port.RoundingResidualPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP client for revenue-ledger's {@code POST /v1/journals/rounding-residual}
 * ({@code postRoundingResidual(reference, residual, currency)}).
 *
 * <p>Gated by {@code gmepay.clients.revenue-ledger.enabled=true} ({@code @Primary} +
 * {@code @ConditionalOnProperty}), mirroring the fleet's rest-client gating. When disabled — the
 * default in dev/test where revenue-ledger is not running — the in-process
 * {@link FixtureRoundingResidualAdapter} wins. Never reads revenue-ledger's DB directly.
 *
 * <p>{@code reference} is the settlement batch id ({@code ZP00NN-YYYYMMDD-WINDOW}); the caller posts
 * exactly once per batch and guards against re-post on recon re-run.
 *
 * <p>Spring 6 two-constructor rule: the container-wired ctor carries {@link Autowired}; the other is a
 * package-private test helper taking a pre-built {@link RestTemplate} (e.g. MockRestServiceServer).
 */
@Primary
@Component
@ConditionalOnProperty(name = "gmepay.clients.revenue-ledger.enabled", havingValue = "true")
public class RestRoundingResidualClient implements RoundingResidualPort {

    private static final Logger log = LoggerFactory.getLogger(RestRoundingResidualClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    @Autowired
    public RestRoundingResidualClient(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${gmepay.clients.revenue-ledger.base-url:http://revenue-ledger:8084}") String baseUrl) {
        this.restTemplate = restTemplateBuilder.build();
        this.baseUrl = baseUrl;
    }

    /** Test helper — pre-built RestTemplate (e.g. backed by MockRestServiceServer). */
    RestRoundingResidualClient(RestTemplate restTemplate, String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    @Override
    public boolean postResidual(String batchId, BigDecimal residual, String currency) {
        if (residual == null || residual.signum() == 0) {
            // Zero residual is a no-op on revenue-ledger (204); treat as already-handled.
            return true;
        }
        // Money rides as a decimal STRING per docs/MONEY_CONVENTION.md (revenue-ledger binds BigDecimal).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", batchId);
        body.put("residual", residual.toPlainString());
        body.put("currency", currency);
        String url = baseUrl + "/v1/journals/rounding-residual";
        try {
            ResponseEntity<Void> resp = restTemplate.postForEntity(url, body, Void.class);
            boolean ok = resp.getStatusCode().is2xxSuccessful();
            log.info("Posted rounding residual reference={} residual={} {} -> {}",
                    batchId, residual.toPlainString(), currency, resp.getStatusCode());
            return ok;
        } catch (Exception e) {
            log.warn("revenue-ledger rounding-residual POST failed reference={} ({}) — will retry on "
                    + "a later recon run", batchId, e.toString());
            return false;
        }
    }
}
