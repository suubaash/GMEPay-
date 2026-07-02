package com.gme.sim.gmeremit.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Decodes a Nepal (Fonepay / NepalPay) QR via the Nepal QR partner simulator
 * ({@code sim-nepal-qr}) at {@code POST /qrscan-thirdparty/parse/} with a raw
 * {@code {"qs": "<qr>"}} body.
 *
 * <p>The parse endpoint returns the REAL merchant (name / city) plus currency (NPR) and
 * amount in rupees ({@code trxAmount}; null for a static QR where the user enters the amount).
 * This replaces the "Unknown Merchant" fallback for cross-border Nepal payments.
 */
@Service
public class NepalQrClient {

    private static final Logger log = LoggerFactory.getLogger(NepalQrClient.class);

    private final RestClient nepalRestClient;

    public NepalQrClient(@Qualifier("nepalQrRestClient") RestClient nepalRestClient) {
        this.nepalRestClient = nepalRestClient;
    }

    /** Returns null if the Nepal sim is down or the decode fails. */
    public NepalParse decode(String qrPayload) {
        try {
            return nepalRestClient.post()
                    .uri("/qrscan-thirdparty/parse/")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("qs", qrPayload))
                    .retrieve()
                    .body(NepalParse.class);
        } catch (ResourceAccessException e) {
            log.warn("Nepal QR sim unreachable for decode: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Nepal QR decode failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Response of {@code POST /qrscan-thirdparty/parse/}. {@code trxAmount} is in rupees as a
     * plain string (null for a static QR).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NepalParse(
            @JsonProperty("format")          String format,
            @JsonProperty("initMethod")      String initMethod,   // "static" | "dynamic"
            @JsonProperty("merchantName")    String merchantName,
            @JsonProperty("merchantCity")    String merchantCity,
            @JsonProperty("merchantCountry") String merchantCountry,
            @JsonProperty("trxCurrency")     String trxCurrency,   // "NPR"
            @JsonProperty("trxAmount")       String trxAmount      // rupees, null=static
    ) {}
}
