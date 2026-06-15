package com.gme.sim.gmeremit.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Calls the GMEPay+ payment-executor hub at POST /v1/pay and POST /v1/scheme/qr/decode.
 *
 * <p>If the hub is unreachable, methods return a friendly error result rather than
 * propagating the exception (friendly 503 to the wallet caller).
 */
@Service
public class HubClient {

    private static final Logger log = LoggerFactory.getLogger(HubClient.class);

    private final RestClient restClient;

    public HubClient(RestClient gmepayRestClient) {
        this.restClient = gmepayRestClient;
    }

    // -------------------------------------------------------------------------
    // QR decode preview
    // -------------------------------------------------------------------------

    /** Returns null if the hub is down or the decode fails. */
    public QrPreview decodeQr(String qrPayload) {
        try {
            return restClient.post()
                    .uri("/v1/scheme/qr/decode")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("qrPayload", qrPayload))
                    .retrieve()
                    .body(QrPreview.class);
        } catch (ResourceAccessException e) {
            log.warn("Hub unreachable for QR decode: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("QR decode failed: {}", e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Payment execution
    // -------------------------------------------------------------------------

    public HubPayResult pay(String qrPayload, String amountKrw, String userRef) {
        try {
            HubPayResponse resp = restClient.post()
                    .uri("/v1/pay")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new HubPayRequest(qrPayload, amountKrw, "GMEREMIT", userRef))
                    .retrieve()
                    .onStatus(status -> status.value() == 422, (req, res) -> {
                        /* handled below via exchange — allow non-2xx to flow through */
                    })
                    .body(HubPayResponse.class);

            if (resp == null) {
                return HubPayResult.hubDown();
            }
            return HubPayResult.fromResponse(resp);

        } catch (ResourceAccessException e) {
            log.warn("Hub unreachable for payment: {}", e.getMessage());
            return HubPayResult.hubDown();
        } catch (Exception e) {
            log.warn("Payment hub call failed: {}", e.getMessage());
            return HubPayResult.hubDown();
        }
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    public record HubPayRequest(
            @JsonProperty("qrPayload")  String qrPayload,
            @JsonProperty("amountKrw") String amountKrw,
            @JsonProperty("partner")   String partner,
            @JsonProperty("userRef")   String userRef
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HubPayResponse(
            @JsonProperty("status")        String status,
            @JsonProperty("schemeTxnRef")  String schemeTxnRef,
            @JsonProperty("merchantName")  String merchantName,
            @JsonProperty("payAmountKrw")  String payAmountKrw,
            @JsonProperty("feeKrw")        String feeKrw,
            @JsonProperty("chargedKrw")    String chargedKrw,
            @JsonProperty("committedAt")   String committedAt,
            @JsonProperty("declineReason") String declineReason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QrPreview(
            @JsonProperty("merchantId")   String merchantId,
            @JsonProperty("merchantName") String merchantName,
            @JsonProperty("mode")         String mode,
            @JsonProperty("amount")       String amount,
            @JsonProperty("currency")     String currency
    ) {}

    public record HubPayResult(
            boolean approved,
            boolean isHubDown,
            String schemeTxnRef,
            String merchantName,
            String payAmountKrw,
            String feeKrw,
            String chargedKrw,
            String committedAt,
            String declineReason
    ) {
        public static HubPayResult hubDown() {
            return new HubPayResult(false, true, null, null, null, null, null, null, "HUB_UNAVAILABLE");
        }

        public static HubPayResult fromResponse(HubPayResponse r) {
            boolean ok = "APPROVED".equals(r.status());
            return new HubPayResult(ok, false,
                    r.schemeTxnRef(), r.merchantName(),
                    r.payAmountKrw(), r.feeKrw(), r.chargedKrw(),
                    r.committedAt(), r.declineReason());
        }
    }
}
