package com.gme.sim.wallet.service;

import com.gme.sim.wallet.config.WalletProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

/**
 * REST client for sim-rate-provider (port 9101).
 * Returns mid-rate for a currency pair.  Only called for SENDMN flows.
 */
@Service
public class RateClient {

    private static final Logger log = LoggerFactory.getLogger(RateClient.class);

    private final String baseUrl;
    private final RestTemplate rest;

    @Autowired
    public RateClient(WalletProperties props, RestTemplate rest) {
        this.baseUrl = props.getRateBaseUrl();
        this.rest = rest;
    }

    /**
     * GET /v1/rates?base={from}&quote={to} → mid-rate as BigDecimal string.
     * e.g. /v1/rates?base=KRW&quote=MNT → { base, quote, rate, asOf, source }.
     */
    @SuppressWarnings("unchecked")
    public BigDecimal getMidRate(String from, String to) {
        String url = baseUrl + "/v1/rates?base=" + from + "&quote=" + to;
        try {
            var resp = rest.getForObject(url, Map.class);
            if (resp == null || resp.get("rate") == null) {
                throw new SimDownException("rate sim returned empty response for " + from + "/" + to);
            }
            return new BigDecimal(resp.get("rate").toString());
        } catch (RestClientException ex) {
            log.warn("rate sim unreachable at {}: {}", url, ex.getMessage());
            throw new SimDownException("rate sim is not reachable (" + baseUrl + "): " + ex.getMessage());
        }
    }
}
