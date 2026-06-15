package com.gme.sim.merchant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gme.sim.merchant.dto.ChargeRequest;
import com.gme.sim.merchant.dto.ChargeResponse;
import com.gme.sim.merchant.dto.RegisterShopRequest;
import com.gme.sim.merchant.model.ShopRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client that calls sim-scheme (:9102) endpoints.
 * All scheme paths match the frozen contract exactly.
 * When the scheme is down, throws SchemeUnavailableException.
 */
@Service
public class SchemeClient {

    private static final Logger log = LoggerFactory.getLogger(SchemeClient.class);

    private final RestClient restClient;
    private final ObjectMapper mapper;

    public SchemeClient(RestClient schemeRestClient, ObjectMapper mapper) {
        this.restClient = schemeRestClient;
        this.mapper     = mapper;
    }

    /**
     * POST /v1/scheme/merchants
     * Registers a merchant in the scheme and returns a ShopRecord with the assigned merchantId.
     */
    public ShopRecord registerMerchant(RegisterShopRequest req) {
        // Build a unique merchantId locally (scheme accepts a client-supplied id)
        String merchantId = "ZP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();

        ObjectNode body = mapper.createObjectNode();
        body.put("merchantId", merchantId);
        body.put("name",       req.name());
        body.put("city",       req.city());
        body.put("mcc",        req.mcc());
        if (req.businessRegNo()       != null) body.put("businessRegNo",       req.businessRegNo());
        if (req.subMerchantId()       != null) body.put("subMerchantId",       req.subMerchantId());
        if (req.kftcInstitutionCode() != null) body.put("kftcInstitutionCode", req.kftcInstitutionCode());
        if (req.merchantType()        != null) body.put("merchantType",        req.merchantType());

        try {
            JsonNode resp = restClient.post()
                    .uri("/v1/scheme/merchants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            String assignedId = resp.path("merchantId").asText(merchantId);
            String feeRate    = resp.path("feeRate").isNull() ? null : resp.path("feeRate").asText(null);

            return new ShopRecord(
                    assignedId,
                    req.name(),
                    req.city(),
                    req.mcc(),
                    req.businessRegNo(),
                    req.subMerchantId(),
                    req.kftcInstitutionCode(),
                    req.merchantType(),
                    feeRate
            );
        } catch (ResourceAccessException e) {
            log.warn("sim-scheme unreachable during registerMerchant: {}", e.getMessage());
            throw new SchemeUnavailableException("sim-scheme is not available", e);
        } catch (RestClientResponseException e) {
            log.warn("sim-scheme returned {} during registerMerchant: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new SchemeUnavailableException("sim-scheme returned " + e.getStatusCode(), e);
        }
    }

    /**
     * GET /v1/scheme/merchants/{merchantId}/store-qr
     * Returns the raw JSON node from the scheme (proxy passthrough).
     */
    public JsonNode getStoreQr(String merchantId) {
        try {
            return restClient.get()
                    .uri("/v1/scheme/merchants/{merchantId}/store-qr", merchantId)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {
            log.warn("sim-scheme unreachable during getStoreQr: {}", e.getMessage());
            throw new SchemeUnavailableException("sim-scheme is not available", e);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return null; // merchant not found in scheme
            }
            log.warn("sim-scheme returned {} during getStoreQr: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new SchemeUnavailableException("sim-scheme returned " + e.getStatusCode(), e);
        }
    }

    /**
     * POST /v1/scheme/qr/dynamic
     * Mints a one-time amount-embedded QR for a POS charge.
     */
    public ChargeResponse mintDynamicQr(String merchantId, BigDecimal amount, String currency) {
        String resolvedCurrency = (currency != null && !currency.isBlank()) ? currency : "KRW";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", merchantId);
        body.put("amount",     amount.toPlainString());
        body.put("currency",   resolvedCurrency);

        try {
            JsonNode resp = restClient.post()
                    .uri("/v1/scheme/qr/dynamic")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            return new ChargeResponse(
                    "MPM_DYNAMIC",
                    resp.path("qrPayload").asText(),
                    amount,
                    resolvedCurrency
            );
        } catch (ResourceAccessException e) {
            log.warn("sim-scheme unreachable during mintDynamicQr: {}", e.getMessage());
            throw new SchemeUnavailableException("sim-scheme is not available", e);
        } catch (RestClientResponseException e) {
            log.warn("sim-scheme returned {} during mintDynamicQr: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new SchemeUnavailableException("sim-scheme returned " + e.getStatusCode(), e);
        }
    }

    /**
     * GET /v1/scheme/merchants/{merchantId}/payments?since={since}
     * Returns the raw JSON node from the scheme (proxy passthrough).
     */
    public JsonNode getPaymentFeed(String merchantId, long since) {
        try {
            return restClient.get()
                    .uri("/v1/scheme/merchants/{merchantId}/payments?since={since}", merchantId, since)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {
            log.warn("sim-scheme unreachable during getPaymentFeed: {}", e.getMessage());
            throw new SchemeUnavailableException("sim-scheme is not available", e);
        } catch (RestClientResponseException e) {
            log.warn("sim-scheme returned {} during getPaymentFeed: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new SchemeUnavailableException("sim-scheme returned " + e.getStatusCode(), e);
        }
    }
}
