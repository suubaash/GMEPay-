package com.gme.pay.scheme.sendmn.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.scheme.sendmn.crypto.SendmnEnvelopeCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * REST client for the SendMN partner API (VerifyQr / Confirm / PaymentStatus — all POST
 * JSON, {@code Authorization: <raw token>} + {@code Username}/{@code AgentCode} headers,
 * bodies wrapped in the {@code {"encryptedData": ...}} envelope via
 * {@link SendmnEnvelopeCodec}).
 *
 * <p>Error model: SendMN business results ride inside the decrypted body as
 * {@code RES_CODE} ("0" success; 304 duplicate TX_TOKEN_NO; 307 settlement mismatch; …)
 * and are returned to the adapter <b>as data</b> — the adapter owns the 304/ambiguity
 * policy (poll PaymentStatus, never blind-retry). Transport-level failures map to
 * {@link ErrorCode#SCHEME_UNAVAILABLE}; token rejection ({@code S102}/{@code S104}) is
 * transparently re-authenticated once via {@link SendmnAuthClient#invalidate()}.</p>
 */
@Component
public class SendmnSchemeApiClient {

    /** Token-level rejections that warrant one re-auth + replay. */
    private static final Set<String> TOKEN_ERRORS = Set.of("S102", "S104");

    private final RestClient restClient;
    private final SendmnAuthClient authClient;
    private final SendmnEnvelopeCodec codec;
    private final ObjectMapper mapper;

    /** Primary constructor — wired by Spring. {@code @Autowired} required (2+ ctors). */
    @Autowired
    public SendmnSchemeApiClient(
            RestClient.Builder builder,
            SendmnAuthClient authClient,
            SendmnEnvelopeCodec codec,
            ObjectMapper mapper,
            @Value("${sendmn.base-url:http://localhost:9106}") String baseUrl) {
        this(builder.baseUrl(baseUrl).build(), authClient, codec, mapper);
    }

    /** Package-private test constructor — accepts a pre-built RestClient. */
    SendmnSchemeApiClient(RestClient restClient, SendmnAuthClient authClient,
                          SendmnEnvelopeCodec codec, ObjectMapper mapper) {
        this.restClient = restClient;
        this.authClient = authClient;
        this.codec = codec;
        this.mapper = mapper;
    }

    // -------------------------------------------------------------------------
    // VerifyQr — POST /api/Partner/VerifyQr
    // -------------------------------------------------------------------------

    /** Decodes a scanned merchant QR (SendMN forwards to QPay). */
    public VerifyQrApiResponse verifyQr(String qrCode, String txTokenNo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("QR_CODE", qrCode);
        payload.put("TX_TOKEN_NO", txTokenNo);
        return post("/api/Partner/VerifyQr", payload, VerifyQrApiResponse.class);
    }

    // -------------------------------------------------------------------------
    // Confirm — POST /api/Partner/Confirm (the money-moving call)
    // -------------------------------------------------------------------------

    /**
     * Executes the payment to the merchant. NOTE (open issue O3): the spec table names
     * the FX currency field {@code FX_CUR_CODE} while the example JSON uses
     * {@code FX_CUR_CD} — we send BOTH with the same value until SendMN confirms.
     * The undocumented example-only fields are sent empty except {@code FX_TICKER_NO},
     * which references the registered rate we settled against.
     */
    public ConfirmApiResponse confirm(ConfirmCommand cmd) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("TX_TOKEN_NO", cmd.txTokenNo());
        payload.put("MERCHANT_ID", cmd.merchantId());
        payload.put("LOCAL_CUR_CODE", cmd.localCurCode());
        payload.put("LOCAL_PAYMENT_AMOUNT", plain(cmd.localPaymentAmount()));
        payload.put("FX_CUR_CODE", cmd.fxCurCode());
        payload.put("FX_CUR_CD", cmd.fxCurCode());
        payload.put("FX_USD_BUY_RATE", plain(cmd.fxUsdBuyRate()));
        payload.put("FX_USD_SELL_RATE", "");
        payload.put("FX_BASIC_RATE", "");
        payload.put("FX_TICKER_NO", cmd.fxTickerNo() == null ? "" : cmd.fxTickerNo());
        payload.put("SETTLEMENT_CUR_CODE", cmd.settlementCurCode());
        payload.put("SETTLEMENT_AMOUNT", plain(cmd.settlementAmount()));
        payload.put("SETTLEMENT_DATE", "");
        payload.put("RECONCILE_DATE", "");
        payload.put("PAYMENT_DATETIME", cmd.paymentDatetimeUtc());
        return post("/api/Partner/Confirm", payload, ConfirmApiResponse.class);
    }

    // -------------------------------------------------------------------------
    // PaymentStatus — POST /api/Partner/PaymentStatus
    // -------------------------------------------------------------------------

    /** Looks up the payment state by {@code TX_TOKEN_NO} (poll — SendMN has no callback). */
    public PaymentStatusApiResponse paymentStatus(String txTokenNo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("TX_TOKEN_NO", txTokenNo);
        return post("/api/Partner/PaymentStatus", payload, PaymentStatusApiResponse.class);
    }

    // -------------------------------------------------------------------------
    // Envelope exchange + auth-retry plumbing
    // -------------------------------------------------------------------------

    private <T> T post(String uri, Map<String, Object> payload, Class<T> type) {
        T first = exchange(uri, payload, type);
        String resCode = resCodeOf(first);
        if (resCode != null && TOKEN_ERRORS.contains(resCode)) {
            authClient.invalidate();
            return exchange(uri, payload, type);
        }
        return first;
    }

    private <T> T exchange(String uri, Map<String, Object> payload, Class<T> type) {
        String body;
        try {
            body = restClient.post()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, authClient.getToken())
                    .header("Username", authClient.username())
                    .header("AgentCode", authClient.agentCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("encryptedData", codec.encrypt(toJson(payload))))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            // Any non-2xx from SendMN is ambiguous at the transport level (the request may
            // or may not have been processed) — surface as retryable SCHEME_UNAVAILABLE and
            // let the adapter resolve via PaymentStatus polling (never auto-fail).
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                    "sendmn " + uri + " HTTP " + ex.getStatusCode().value() + ": "
                            + ex.getResponseBodyAsString());
        } catch (ResourceAccessException ex) {
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                    "sendmn unreachable during " + uri + ": " + ex.getMessage());
        }
        if (body == null || body.isBlank()) {
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE, "sendmn empty response from " + uri);
        }
        return parseEnveloped(uri, body, type);
    }

    /** Unwraps {@code {"encryptedData": ...}}; tolerates a plain (unenveloped) error body. */
    private <T> T parseEnveloped(String uri, String body, Class<T> type) {
        try {
            JsonNode root = mapper.readTree(body);
            String json = root.hasNonNull("encryptedData")
                    ? codec.decrypt(root.get("encryptedData").asText())
                    : body;
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                    "sendmn unparseable response from " + uri + ": " + e.getMessage());
        }
    }

    private String resCodeOf(Object dto) {
        if (dto instanceof VerifyQrApiResponse r) return r.resCode();
        if (dto instanceof ConfirmApiResponse r) return r.resCode();
        if (dto instanceof PaymentStatusApiResponse r) return r.resCode();
        return null;
    }

    private String toJson(Map<String, Object> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "failed to encode sendmn payload: " + e.getMessage());
        }
    }

    private static String plain(BigDecimal d) {
        return d == null ? "" : d.toPlainString();
    }

    // -------------------------------------------------------------------------
    // Commands + wire DTOs (field names follow the doc EXAMPLES, not the tables —
    // digest gotcha #2; amounts are strings with fixed scale: 18,2 MNT / 18,4 USD)
    // -------------------------------------------------------------------------

    /** All fields the Confirm call needs; built by the adapter from the payment + registered rate. */
    public record ConfirmCommand(
            String txTokenNo,
            String merchantId,
            String localCurCode,
            BigDecimal localPaymentAmount,
            String fxCurCode,
            BigDecimal fxUsdBuyRate,
            String fxTickerNo,
            String settlementCurCode,
            BigDecimal settlementAmount,
            String paymentDatetimeUtc   // UTC yyyyMMddHHmmss (digest gotcha #11)
    ) {}

    /** POST /api/Partner/VerifyQr decrypted response body. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerifyQrApiResponse(
            @JsonProperty("RES_CODE") String resCode,
            @JsonProperty("RES_MSG") String resMsg,
            @JsonProperty("ADDITIVE_MSG") String additiveMsg,
            @JsonProperty("QR_TYPE") String qrType,
            @JsonProperty("MERCHANT_ID") String merchantId,
            @JsonProperty("MERCHANT_NAME") String merchantName,
            @JsonProperty("MERCHANT_ADDRESS") String merchantAddress,
            @JsonProperty("TERMINAL_ID") String terminalId,
            @JsonProperty("TX_TOKEN_NO") String txTokenNo,
            @JsonProperty("LOCAL_PAYMENT_AMOUNT") String localPaymentAmount
    ) {}

    /** POST /api/Partner/Confirm decrypted response body ("RECIPT" [sic] per the doc). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfirmApiResponse(
            @JsonProperty("RES_CODE") String resCode,
            @JsonProperty("RES_MSG") String resMsg,
            @JsonProperty("ADDITIVE_MSG") String additiveMsg,
            @JsonProperty("PAYMENT_NO") String paymentNo,
            @JsonProperty("PAYMENT_RECIPT_NO") String paymentReceiptNo,
            @JsonProperty("MERCHANT_ID") String merchantId,
            @JsonProperty("MERCHANT_NAME") String merchantName
    ) {}

    /** POST /api/Partner/PaymentStatus decrypted response body. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentStatusApiResponse(
            @JsonProperty("RES_CODE") String resCode,
            @JsonProperty("RES_MSG") String resMsg,
            @JsonProperty("ADDITIVE_MSG") String additiveMsg,
            @JsonProperty("RECONCILE_DATE") String reconcileDate,
            @JsonProperty("SETTLEMENT_DATE") String settlementDate,
            @JsonProperty("TX_TOKEN_NO") String txTokenNo,
            @JsonProperty("PAYMENT_STATUS") String paymentStatus,
            @JsonProperty("PAYMENT_NO") String paymentNo,
            @JsonProperty("PAYMENT_RECIPT_NO") String paymentReceiptNo,
            @JsonProperty("MERCHANT_ID") String merchantId,
            @JsonProperty("MERCHANT_NAME") String merchantName
    ) {}
}
