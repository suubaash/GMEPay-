package com.gme.pay.scheme.ninepay.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.scheme.ninepay.sign.NinepaySigner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST client for the 9Pay disbursement ("Pay-Out to Banks") API, integration spec ver 3.13.
 *
 * <p>Endpoints (all JSON POST unless noted; auth = {@code partner_id} + RSA pipe-string
 * {@code signature} + IP whitelist — no OAuth/token):</p>
 * <ul>
 *   <li>{@link #verifyAccount}  — POST {@code /service/account/verify} (beneficiary name resolve)</li>
 *   <li>{@link #transfer}      — POST {@code /service/transfer} (create payout; NO cancel after submit)</li>
 *   <li>{@link #transferInfoByRequestId} / {@link #transferInfoByTransactionId}
 *        — POST {@code /service/transfer/info} (status lookup / timeout resolution)</li>
 *   <li>{@link #balance}       — POST {@code /service/account/balance} (prefunded balance)</li>
 *   <li>{@link #bankList}      — GET {@code /transfer-bank/bank-list} (no signature documented)</li>
 *   <li>{@link #exchangeRate}  — POST {@code /service/exchange-rate-v2} (reference-only rates)</li>
 *   <li>{@link #decodeQr}      — POST {@code /service/v2/decode-qr} (VIETQR/VNPAY payloads)</li>
 * </ul>
 *
 * <p>Base URLs: test {@code https://stg-api-console.9pay.mobi}, prod
 * {@code https://api-console.9pay.vn} — configured via {@code gmepay.scheme.ninepay.base-url}
 * (local default targets the planned sim-ninepay :9107). The {@code hl=en} header requests
 * English error messages (default Vietnamese).</p>
 *
 * <p><b>Failure taxonomy</b> — callers rely on the distinction:
 * {@link NinepayErrorException} = 9Pay answered with a business error ({@code error.code});
 * {@link NinepayTransportException} = ambiguous (timeout/IO/5xx/unparseable or, when
 * {@code verify-responses=true}, a response whose RSA signature does not verify) —
 * the transfer may still have executed, so poll before any retry.</p>
 */
@Component
public class NinepayApiClient {

    /** Times in the 9Pay API are GMT+7 ({@code Y-m-d H:i:s}). */
    static final ZoneId NINEPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** transfer/info lookup by the 9Pay transaction id. */
    public static final String LOOKUP_BY_TRANSACTION_ID = "TRANSACTION_ID";
    /** transfer/info lookup by OUR original request_id (the timeout-resolution path). */
    public static final String LOOKUP_BY_REQUEST_ID = "TRANSACTION_REQUEST_ID";

    private final RestClient restClient;
    private final NinepaySigner signer;
    private final ObjectMapper mapper;
    private final String partnerId;
    private final boolean verifyResponses;

    /** Primary constructor — wired by Spring. {@code @Autowired} required (2+ ctors). */
    @Autowired
    public NinepayApiClient(
            RestClient.Builder builder,
            NinepaySigner signer,
            ObjectMapper mapper,
            @Value("${gmepay.scheme.ninepay.base-url:http://localhost:9107}") String baseUrl,
            @Value("${gmepay.scheme.ninepay.partner-id:GMEPAY}") String partnerId,
            // T5-4: ON by default. When 9Pay's public key is absent the verification path
            // fails closed (unverifiable = ambiguous), it does not fall back to trust.
            @Value("${gmepay.scheme.ninepay.verify-responses:true}") boolean verifyResponses) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.signer = signer;
        this.mapper = mapper;
        this.partnerId = partnerId;
        this.verifyResponses = verifyResponses;
    }

    /** Package-private test constructor — accepts a pre-built RestClient + collaborators. */
    NinepayApiClient(RestClient restClient, NinepaySigner signer, ObjectMapper mapper,
                     String partnerId, boolean verifyResponses) {
        this.restClient = restClient;
        this.signer = signer;
        this.mapper = mapper;
        this.partnerId = partnerId;
        this.verifyResponses = verifyResponses;
    }

    public String partnerId() {
        return partnerId;
    }

    // -------------------------------------------------------------------------
    // 3.1 account verify — POST /service/account/verify
    // -------------------------------------------------------------------------

    /**
     * Resolves the beneficiary account name before paying (recommended pre-flight;
     * VNPAY QRs skip this). Signs {@code request_id|partner_id|bank_no|account_no|account_type}.
     */
    public VerifyResult verifyAccount(String requestId, String bankNo, String accountNo, int accountType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("partner_id", partnerId);
        body.put("bank_no", bankNo);
        body.put("account_no", accountNo);
        body.put("account_type", accountType);
        body.put("signature", signer.sign(
                NinepaySigner.canonical(requestId, partnerId, bankNo, accountNo, accountType)));

        JsonNode data = post("/service/account/verify", body);
        verifyResponseSignature("verify", data, NinepaySigner.canonical(
                text(data, "request_id"), text(data, "partner_id"), text(data, "bank_no"),
                text(data, "account_no"), text(data, "account_type"), text(data, "account_name")));
        return new VerifyResult(
                text(data, "bank_no"), text(data, "account_no"),
                data.path("account_type").asInt(accountType), text(data, "account_name"));
    }

    // -------------------------------------------------------------------------
    // 3.2 money transfer — POST /service/transfer
    // -------------------------------------------------------------------------

    /**
     * Creates the payout. <b>Cannot be cancelled after submission.</b> The signature covers
     * {@code request_id|partner_id|bank_no|account_no|account_type|account_name|amount|content}
     * — the optional AML fields / {@code qr_str} / {@code sender_uid} are sent but NOT signed
     * (per spec).
     */
    public TransferResult transfer(TransferCommand cmd) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", cmd.requestId());
        body.put("partner_id", partnerId);
        body.put("bank_no", cmd.bankNo());
        body.put("account_no", cmd.accountNo());
        body.put("account_type", cmd.accountType());
        body.put("account_name", cmd.accountName());
        body.put("amount", cmd.amountVnd());
        body.put("content", cmd.content());
        putIfPresent(body, "sender_name", cmd.senderName());
        putIfPresent(body, "sender_id", cmd.senderId());
        putIfPresent(body, "recipient_id", cmd.recipientId());
        putIfPresent(body, "transfer_purpose", cmd.transferPurpose());
        putIfPresent(body, "qr_str", cmd.qrStr());
        putIfPresent(body, "u_country", cmd.uCountry());
        putIfPresent(body, "sender_uid", cmd.senderUid());
        body.put("signature", signer.sign(NinepaySigner.canonical(
                cmd.requestId(), partnerId, cmd.bankNo(), cmd.accountNo(), cmd.accountType(),
                cmd.accountName(), cmd.amountVnd(), cmd.content())));

        JsonNode data = post("/service/transfer", body);
        verifyResponseSignature("transfer", data, NinepaySigner.canonical(
                text(data, "request_id"), text(data, "partner_id"), text(data, "transaction_id"),
                text(data, "bank_no"), text(data, "account_no"), text(data, "account_type"),
                text(data, "account_name"), text(data, "request_amount"), text(data, "transfer_amount"),
                text(data, "status"), text(data, "created_at")));
        return new TransferResult(
                text(data, "request_id"), text(data, "transaction_id"),
                longOrNull(data, "request_amount"), longOrNull(data, "transfer_amount"),
                longOrNull(data, "fee"), text(data, "status"), text(data, "created_at"),
                text(data, "message"));
    }

    // -------------------------------------------------------------------------
    // 3.3 transaction lookup — POST /service/transfer/info
    // -------------------------------------------------------------------------

    /**
     * Looks up a transfer by OUR original {@code request_id}
     * ({@code content_type=TRANSACTION_REQUEST_ID}) — the safe-retry / timeout-resolution
     * path. {@code lookupRequestId} must be a NEW unique request id for this lookup call.
     */
    public TransferInfo transferInfoByRequestId(String lookupRequestId, String originalRequestId) {
        return transferInfo(lookupRequestId, LOOKUP_BY_REQUEST_ID, originalRequestId);
    }

    /** Looks up a transfer by the 9Pay {@code transaction_id}. */
    public TransferInfo transferInfoByTransactionId(String lookupRequestId, String transactionId) {
        return transferInfo(lookupRequestId, LOOKUP_BY_TRANSACTION_ID, transactionId);
    }

    private TransferInfo transferInfo(String lookupRequestId, String contentType, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", lookupRequestId);
        body.put("partner_id", partnerId);
        body.put("content_type", contentType);
        // The spec overloads `transaction_id` to carry either the 9Pay txn id or our
        // original request_id, switched by content_type.
        body.put("transaction_id", key);
        body.put("signature", signer.sign(NinepaySigner.canonical(lookupRequestId, partnerId, key)));

        JsonNode data = post("/service/transfer/info", body);
        verifyResponseSignature("transfer-info", data, NinepaySigner.canonical(
                text(data, "request_id"), text(data, "partner_id"), text(data, "transaction_id"),
                text(data, "status"), text(data, "created_at")));
        return new TransferInfo(
                text(data, "request_id"), text(data, "transaction_id"),
                longOrNull(data, "request_amount"), longOrNull(data, "transfer_amount"),
                text(data, "status"), text(data, "created_at"));
    }

    // -------------------------------------------------------------------------
    // 3.4 balance inquiry — POST /service/account/balance
    // -------------------------------------------------------------------------

    /**
     * Reads the prefunded balance. {@code request_time} is GMT+7 {@code Y-m-d H:i:s};
     * signs {@code request_id|partner_id|request_time}.
     */
    public BalanceResult balance(String requestId) {
        String requestTime = TIME_FORMAT.format(ZonedDateTime.now(NINEPAY_ZONE));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("partner_id", partnerId);
        body.put("request_time", requestTime);
        body.put("signature", signer.sign(NinepaySigner.canonical(requestId, partnerId, requestTime)));

        JsonNode data = post("/service/account/balance", body);
        verifyResponseSignature("balance", data, NinepaySigner.canonical(
                text(data, "request_id"), text(data, "partner_id"), text(data, "response_time")));
        List<BalanceAmount> available = new ArrayList<>();
        for (JsonNode n : data.path("balance_available")) {
            available.add(new BalanceAmount(text(n, "unit"), longOrNull(n, "value")));
        }
        return new BalanceResult(text(data, "response_time"), available, data.path("balance_info"));
    }

    // -------------------------------------------------------------------------
    // 3.6 bank list — GET /transfer-bank/bank-list
    // -------------------------------------------------------------------------

    /** Fetches the ~74-entry 9Pay bank/wallet roster. No signature is documented (open issue O7). */
    public JsonNode bankList() {
        try {
            JsonNode node = restClient.get()
                    .uri("/transfer-bank/bank-list")
                    .header("hl", "en")
                    .retrieve()
                    .body(JsonNode.class);
            if (node == null) {
                throw new NinepayTransportException("9Pay bank-list: empty response");
            }
            return node.path("data").path("banks");
        } catch (RestClientResponseException ex) {
            throw mapHttpError("bank-list", ex);
        } catch (ResourceAccessException ex) {
            throw new NinepayTransportException("9Pay unreachable during bank-list: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // 3.7 exchange rate — POST /service/exchange-rate-v2
    // -------------------------------------------------------------------------

    /**
     * Reference-only FX rates. GOTCHA (digest 3.7): the documented signature string includes
     * {@code amount|currency_from|currency_to} even though the request-field table omits
     * them — we send them in the body AND sign them; confirm the real contract with 9Pay.
     */
    public JsonNode exchangeRate(String requestId, long amount, String currencyFrom, String currencyTo) {
        String requestTime = TIME_FORMAT.format(ZonedDateTime.now(NINEPAY_ZONE));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("partner_id", partnerId);
        body.put("request_time", requestTime);
        body.put("amount", amount);
        body.put("currency_from", currencyFrom);
        body.put("currency_to", currencyTo);
        body.put("signature", signer.sign(NinepaySigner.canonical(
                requestId, partnerId, requestTime, amount, currencyFrom, currencyTo)));

        JsonNode data = post("/service/exchange-rate-v2", body);
        verifyResponseSignature("exchange-rate-v2", data, NinepaySigner.canonical(
                text(data, "request_id"), text(data, "partner_id"), text(data, "response_time")));
        return data.path("rate_info");
    }

    // -------------------------------------------------------------------------
    // 3.8 QR decode — POST /service/v2/decode-qr
    // -------------------------------------------------------------------------

    /**
     * Decodes a VIETQR/VNPAY QR payload. Signature covers ONLY {@code request_id|partner_id}
     * (not {@code str_qr}) per spec. If the decoded {@code type} is VNPAY, account verify is
     * skipped and the transfer must carry {@code qr_str}.
     */
    public DecodedQr decodeQr(String requestId, String strQr) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("partner_id", partnerId);
        body.put("str_qr", strQr);
        body.put("signature", signer.sign(NinepaySigner.canonical(requestId, partnerId)));

        JsonNode data = post("/service/v2/decode-qr", body);
        return new DecodedQr(
                text(data, "type"), text(data, "bank_no"), text(data, "account_number"),
                longOrNull(data, "amount"), text(data, "account_name"), text(data, "city"),
                text(data, "description"), text(data, "service"));
    }

    // -------------------------------------------------------------------------
    // Transport plumbing
    // -------------------------------------------------------------------------

    /**
     * POSTs the signed body and unwraps the 9Pay envelope
     * {@code {success, data, error:{code,message,errors}}}.
     */
    private JsonNode post(String path, Map<String, Object> body) {
        JsonNode node;
        try {
            node = restClient.post()
                    .uri(path)
                    .header("hl", "en") // English error messages (default Vietnamese)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            throw mapHttpError(path, ex);
        } catch (ResourceAccessException ex) {
            // Timeout / connection failure — AMBIGUOUS: 9Pay may have executed the request.
            throw new NinepayTransportException(
                    "9Pay unreachable/timeout during " + path + ": " + ex.getMessage(), ex);
        }
        if (node == null) {
            throw new NinepayTransportException("9Pay empty response from " + path);
        }
        return unwrap(path, node);
    }

    /**
     * HTTP-level errors: the spec defines only body-level success/error semantics (open
     * issue O7), so a 4xx whose body carries a parseable {@code error.code} is treated as
     * a definitive business error; anything else (5xx, opaque body) stays ambiguous.
     */
    private RuntimeException mapHttpError(String path, RestClientResponseException ex) {
        if (ex.getStatusCode().is4xxClientError()) {
            try {
                JsonNode body = mapper.readTree(ex.getResponseBodyAsString());
                JsonNode error = body.path("error");
                if (error.hasNonNull("code")) {
                    return new NinepayErrorException(error.path("code").asText(),
                            error.path("message").asText(""));
                }
            } catch (Exception ignored) {
                // fall through to ambiguous
            }
        }
        return new NinepayTransportException(
                "9Pay HTTP " + ex.getStatusCode().value() + " from " + path + ": "
                        + ex.getResponseBodyAsString(), ex);
    }

    private JsonNode unwrap(String path, JsonNode envelope) {
        boolean failed = envelope.has("success") && !envelope.path("success").asBoolean(false);
        if (failed || envelope.has("error")) {
            JsonNode error = envelope.path("error");
            String code = error.path("code").asText("");
            if (code.isBlank()) {
                throw new NinepayTransportException(
                        "9Pay unparseable error envelope from " + path + ": " + envelope);
            }
            throw new NinepayErrorException(code, error.path("message").asText(""));
        }
        JsonNode data = envelope.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new NinepayTransportException("9Pay envelope missing data from " + path + ": " + envelope);
        }
        return data;
    }

    /**
     * Verifies 9Pay's RSA signature over the documented response fields — gap <b>T5-4</b>.
     *
     * <p>Gated by {@code gmepay.scheme.ninepay.verify-responses}, which now defaults to
     * <b>true</b>: these responses drive payout state, so accepting them unverified meant
     * anything able to answer as 9Pay could move that state.
     *
     * <p><b>Fail closed.</b> Three outcomes, none of which is "trust it anyway":
     * <ul>
     *   <li>signature verifies → proceed;</li>
     *   <li>signature does not verify → {@link NinepayTransportException} (AMBIGUOUS: the
     *       operation may still have executed at 9Pay, so the caller polls rather than
     *       declaring failure);</li>
     *   <li><b>no usable trust anchor</b> ({@code ninepay-public-key-pem} blank or
     *       unparseable) → also {@link NinepayTransportException}, explicitly labelled as
     *       unverifiable. Previously this branch silently returned and the response was
     *       accepted on trust whenever the flag was off, which was the default.</li>
     * </ul>
     */
    private void verifyResponseSignature(String operation, JsonNode data, String canonical) {
        if (!verifyResponses) {
            return;
        }
        if (!signer.canVerify()) {
            // Fail closed: no key ⇒ no verdict ⇒ we do not trust the response.
            throw new NinepayTransportException("9Pay " + operation + " response cannot be verified: "
                    + "gmepay.scheme.ninepay.ninepay-public-key-pem is not configured (or is "
                    + "unparseable). Refusing to trust an unverified 9Pay response; provision "
                    + "9Pay's public key, or set gmepay.scheme.ninepay.verify-responses=false "
                    + "ONLY in an isolated non-production environment.");
        }
        String signature = text(data, "signature");
        if (!signer.verify(canonical, signature)) {
            throw new NinepayTransportException(
                    "9Pay " + operation + " response signature failed verification");
        }
    }

    private static void putIfPresent(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asLong();
    }

    // -------------------------------------------------------------------------
    // Wire DTOs
    // -------------------------------------------------------------------------

    /** Input to {@link #transfer}. Amounts are integer VND (min 2000, validated upstream). */
    public record TransferCommand(
            String requestId,
            String bankNo,
            String accountNo,
            int accountType,
            String accountName,
            long amountVnd,
            String content,
            String senderName,
            String senderId,
            String recipientId,
            String transferPurpose,
            String qrStr,
            String uCountry,
            String senderUid
    ) {}

    /** POST /service/account/verify response ({@code account_name} = resolved beneficiary). */
    public record VerifyResult(String bankNo, String accountNo, int accountType, String accountName) {}

    /**
     * POST /service/transfer response. NOTE the {@code transfer_amount} fee-inclusion
     * contradiction between spec 4.2 and 4.3 (open issue O6) — do not build fee recon on it
     * until 9Pay confirms.
     */
    public record TransferResult(
            String requestId,
            String transactionId,
            Long requestAmount,
            Long transferAmount,
            Long fee,
            String status,
            String createdAt,
            String message
    ) {}

    /** POST /service/transfer/info response (lookup shape: no message/fee). */
    public record TransferInfo(
            String requestId,
            String transactionId,
            Long requestAmount,
            Long transferAmount,
            String status,
            String createdAt
    ) {}

    /** One {@code balance_available} entry (unit VND/USD). */
    public record BalanceAmount(String unit, Long value) {}

    /** POST /service/account/balance response ({@code balanceInfo} kept raw — per-service breakdown). */
    public record BalanceResult(String responseTime, List<BalanceAmount> available, JsonNode balanceInfo) {}

    /** POST /service/v2/decode-qr response. {@code type} VNPAY → skip verify, send qr_str on transfer. */
    public record DecodedQr(
            String type,
            String bankNo,
            String accountNumber,
            Long amountVnd,
            String accountName,
            String city,
            String description,
            String service
    ) {}
}
