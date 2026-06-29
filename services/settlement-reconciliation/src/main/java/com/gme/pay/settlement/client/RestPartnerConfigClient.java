package com.gme.pay.settlement.client;

import com.gme.pay.contracts.PartnerView;
import com.gme.pay.settlement.port.PartnerConfigPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.RoundingMode;

/**
 * HTTP impl of {@link PartnerConfigPort} — calls config-registry's canonical
 * {@code GET /v1/partners/{partnerCode}} and reads the {@link PartnerView} settle currency + rounding
 * mode. Fail-soft: any HTTP/parse error logs WARN and returns {@link PartnerSettlementConfig#defaults}
 * so a settlement run is never blocked by a config-registry hiccup. Never reads the config-registry DB.
 *
 * <p>Spring 6 rule (two constructors): the container-wired one carries {@link Autowired}; the other is
 * a package-private test helper taking a pre-built {@link RestTemplate}. Mirrors RestTransactionQueryClient.
 */
@Primary
@Component
public class RestPartnerConfigClient implements PartnerConfigPort {

    private static final Logger log = LoggerFactory.getLogger(RestPartnerConfigClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    @Autowired
    public RestPartnerConfigClient(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${gmepay.clients.config-registry.base-url:http://config-registry:8081}") String baseUrl) {
        this.restTemplate = restTemplateBuilder.build();
        this.baseUrl = baseUrl;
    }

    /** Test helper — pre-built RestTemplate (e.g. backed by MockRestServiceServer). */
    RestPartnerConfigClient(RestTemplate restTemplate, String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    @Override
    public PartnerSettlementConfig resolve(String partnerCode) {
        try {
            PartnerView v = restTemplate.getForObject(
                    baseUrl + "/v1/partners/{code}", PartnerView.class, partnerCode);
            if (v == null) {
                return PartnerSettlementConfig.defaults(partnerCode);
            }
            String ccy = firstNonBlank(v.settleACcy(), v.settlementCurrency(), "KRW");
            RoundingMode mode = v.settlementRoundingMode() != null ? v.settlementRoundingMode() : RoundingMode.HALF_UP;
            return new PartnerSettlementConfig(partnerCode, ccy, mode);
        } catch (RestClientException ex) {
            log.warn("config-registry partner '{}' unavailable ({}) — defaulting to HALF_UP/KRW",
                    partnerCode, ex.toString());
            return PartnerSettlementConfig.defaults(partnerCode);
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return "KRW";
    }
}
