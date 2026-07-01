package com.gme.sim.gmeremit.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
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

    private final RestClient restClient;        // payment-executor hub (/v1/pay)
    private final RestClient schemeRestClient;  // scheme sim (/v1/scheme/qr/decode)

    public HubClient(
            @org.springframework.beans.factory.annotation.Qualifier("gmepayRestClient") RestClient gmepayRestClient,
            @org.springframework.beans.factory.annotation.Qualifier("schemeRestClient") RestClient schemeRestClient) {
        this.restClient = gmepayRestClient;
        this.schemeRestClient = schemeRestClient;
    }

    // -------------------------------------------------------------------------
    // QR decode preview
    // -------------------------------------------------------------------------

    /** Returns null if the hub is down or the decode fails. */
    public QrPreview decodeQr(String qrPayload) {
        try {
            return schemeRestClient.post()
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
                    // Let every non-2xx body flow through to deserialization instead of throwing.
                    // Business declines arrive as 422 with a populated declineReason; we want to
                    // surface that REAL reason (e.g. MERCHANT_NOT_FOUND), not mask it.
                    .onStatus(HttpStatusCode::isError, (req, res) -> { /* no-op: read body below */ })
                    .body(HubPayResponse.class);

            if (resp == null) {
                // No body at all — the hub answered but said nothing usable.
                return HubPayResult.hubError("HUB_ERROR");
            }
            if (resp.status() == null) {
                // Non-2xx whose body wasn't a wallet response (e.g. a raw 5xx ApiError envelope).
                // The hub IS reachable but errored — don't pretend it's unavailable.
                log.warn("Hub returned an error response without a wallet status: declineReason={}",
                        resp.declineReason());
                return HubPayResult.hubError(
                        resp.declineReason() != null ? resp.declineReason() : "HUB_ERROR");
            }
            return HubPayResult.fromResponse(resp);

        } catch (ResourceAccessException e) {
            // Connection refused / timeout — the hub is genuinely unreachable.
            log.warn("Hub unreachable for payment: {}", e.getMessage());
            return HubPayResult.hubDown();
        } catch (Exception e) {
            log.warn("Payment hub call failed: {}", e.getMessage());
            return HubPayResult.hubError("HUB_ERROR");
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

        /**
         * The hub was reachable but returned an error (e.g. HTTP 5xx) rather than a clean
         * wallet decline. Distinct from {@link #hubDown()} so the wallet does not falsely
         * report "unavailable" when the service is actually up.
         */
        public static HubPayResult hubError(String reason) {
            return new HubPayResult(false, false, null, null, null, null, null, null, reason);
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
