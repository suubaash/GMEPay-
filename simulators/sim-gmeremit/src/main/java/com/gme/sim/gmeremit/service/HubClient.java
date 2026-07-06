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

    /**
     * Asks GMEPay+ to classify a scanned QR — GMEPay+ is the authority for the corridor + currency.
     * Returns {@code null} if the hub is unreachable, so the wallet can degrade gracefully.
     */
    public HubClassification classify(String qrPayload) {
        try {
            return restClient.post()
                    .uri("/v1/pay/classify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("qrPayload", qrPayload))
                    .retrieve()
                    .body(HubClassification.class);
        } catch (ResourceAccessException e) {
            log.warn("Hub unreachable for QR classify: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("QR classify failed: {}", e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Payment execution
    // -------------------------------------------------------------------------

    /**
     * Executes a payment at the hub.
     *
     * @param qrPayload raw scanned QR
     * @param currency  merchant currency — {@code "KRW"} for domestic ZeroPay, {@code "NPR"} for Nepal
     * @param amount    amount in the merchant currency (KRW for domestic, NPR for Nepal)
     * @param userRef   wallet user id
     */
    public HubPayResult pay(String qrPayload, String currency, String amount, String userRef) {
        try {
            HubPayResponse resp = restClient.post()
                    .uri("/v1/pay")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new HubPayRequest(qrPayload, amount, currency, "GMEREMIT", userRef))
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

    /**
     * Hub payment request. {@code amount} is in the merchant currency ({@code currency}); for a
     * domestic KRW payment {@code amountKrw} is also populated for backward compatibility with the
     * existing hub contract. For a Nepal payment {@code amount} is the NPR amount and
     * {@code currency} is {@code "NPR"}.
     */
    public record HubPayRequest(
            @JsonProperty("qrPayload") String qrPayload,
            @JsonProperty("amount")    String amount,
            @JsonProperty("amountKrw") String amountKrw,
            @JsonProperty("currency")  String currency,
            @JsonProperty("partner")   String partner,
            @JsonProperty("userRef")   String userRef
    ) {
        HubPayRequest(String qrPayload, String amount, String currency,
                      String partner, String userRef) {
            // The hub's WalletPaymentRequest REQUIRES amountKrw and interprets it as "the amount in
            // `currency`" (the field name is legacy — it holds NPR for a Nepal scan, not only KRW).
            // Always populate it; nulling it for non-KRW made the hub 400 → wallet HUB_ERROR.
            this(qrPayload, amount, amount, currency, partner, userRef);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HubPayResponse(
            @JsonProperty("status")        String status,
            @JsonProperty("schemeTxnRef")  String schemeTxnRef,
            @JsonProperty("merchantName")  String merchantName,
            @JsonProperty("currency")      String currency,      // merchant currency; may be null (domestic)
            @JsonProperty("payAmount")     String payAmount,     // merchant-currency amount; may be null
            @JsonProperty("payAmountKrw")  String payAmountKrw,
            @JsonProperty("feeKrw")        String feeKrw,
            @JsonProperty("chargedKrw")    String chargedKrw,
            @JsonProperty("committedAt")   String committedAt,
            @JsonProperty("declineReason") String declineReason
    ) {}

    /** GMEPay+'s authoritative classification of a scanned QR (from {@code POST /v1/pay/classify}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HubClassification(
            @JsonProperty("supported") boolean supported,
            @JsonProperty("network")   String network,   // e.g. "fonepay.com"
            @JsonProperty("country")   String country,   // e.g. "NP"
            @JsonProperty("currency")  String currency,  // GME-authoritative, e.g. "NPR"
            @JsonProperty("mode")      String mode,       // "MPM" / "CPM"
            @JsonProperty("scheme")    String scheme      // e.g. "NEPAL"
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
            String currency,
            String payAmount,
            String payAmountKrw,
            String feeKrw,
            String chargedKrw,
            String committedAt,
            String declineReason
    ) {
        public static HubPayResult hubDown() {
            return new HubPayResult(false, true, null, null, null, null, null, null, null, null, "HUB_UNAVAILABLE");
        }

        /**
         * The hub was reachable but returned an error (e.g. HTTP 5xx) rather than a clean
         * wallet decline. Distinct from {@link #hubDown()} so the wallet does not falsely
         * report "unavailable" when the service is actually up.
         */
        public static HubPayResult hubError(String reason) {
            return new HubPayResult(false, false, null, null, null, null, null, null, null, null, reason);
        }

        public static HubPayResult fromResponse(HubPayResponse r) {
            boolean ok = "APPROVED".equals(r.status());
            return new HubPayResult(ok, false,
                    r.schemeTxnRef(), r.merchantName(),
                    r.currency(), r.payAmount(),
                    r.payAmountKrw(), r.feeKrw(), r.chargedKrw(),
                    r.committedAt(), r.declineReason());
        }
    }
}
