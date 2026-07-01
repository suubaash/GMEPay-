package com.gme.pay.scheme.nepal.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.scheme.nepal.sign.NepalRequestSigner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST client for the Nepal QR scheme simulator (sim-nepal-qr, :9103) — Khalti/Fonepay contracts.
 *
 * <p>Operations (see {@code API-DOCS/validate.txt} + {@code API-DOCS/issuance-extension.txt}):
 * <ul>
 *   <li>{@link #parse}    — POST {@code /qrscan-thirdparty/parse/} (unsigned) → merchant fields.</li>
 *   <li>{@link #validate} — POST {@code /api/qr/validate/} with {@code Authorization: Token <t>}
 *       → network + receiver (an alternate decode surface).</li>
 *   <li>{@link #pay}      — POST {@code /qrscan-thirdparty/pay/} signed envelope
 *       (headers {@code Authorization: Key <k>} + {@code X-KhaltiNonce}) → {@code idx} + state.</li>
 *   <li>{@link #status}   — POST {@code /qrscan-thirdparty/status/} signed envelope → state.</li>
 * </ul>
 *
 * <p>Configured by {@code gmepay.scheme.nepal.base-url} (default {@code http://localhost:9103}),
 * {@code gmepay.scheme.nepal.token} (validate) and {@code gmepay.scheme.nepal.key} (pay/status).
 *
 * <p>Error mapping — the Nepal partner returns HTTP 400 with a stable {@code error_key} /
 * {@code detail} / {@code code} for business errors (nonce, duplicate reference, invalid QR,
 * khalti_error), which are mapped to canonical {@link ErrorCode}s by {@link #mapPayError}.
 */
@Component
public class NepalSchemeApiClient {

    private final RestClient restClient;
    private final NepalRequestSigner signer;
    private final ObjectMapper mapper;
    private final String token;
    private final String key;

    /** Primary constructor — wired by Spring. {@code @Autowired} required (2+ ctors). */
    @Autowired
    public NepalSchemeApiClient(
            RestClient.Builder builder,
            NepalRequestSigner signer,
            ObjectMapper mapper,
            @Value("${gmepay.scheme.nepal.base-url:http://localhost:9103}") String baseUrl,
            @Value("${gmepay.scheme.nepal.token:sim-token}") String token,
            @Value("${gmepay.scheme.nepal.key:sim-key}") String key) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.signer = signer;
        this.mapper = mapper;
        this.token = token;
        this.key = key;
    }

    /** Package-private test constructor — accepts a pre-built RestClient + collaborators. */
    NepalSchemeApiClient(RestClient restClient, NepalRequestSigner signer, ObjectMapper mapper,
                         String token, String key) {
        this.restClient = restClient;
        this.signer = signer;
        this.mapper = mapper;
        this.token = token;
        this.key = key;
    }

    // -------------------------------------------------------------------------
    // parse (unsigned decode) — POST /qrscan-thirdparty/parse/
    // -------------------------------------------------------------------------

    /**
     * Decodes a scanned QR via the unsigned {@code /parse/} surface.
     *
     * @param qs the raw scanned QR string
     * @return parsed {@link ParseResponse} (merchant name/city/mcc, currency, rupee amount)
     */
    public ParseResponse parse(String qs) {
        try {
            ParseResponse resp = restClient.post()
                    .uri("/qrscan-thirdparty/parse/")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("qs", qs))
                    .retrieve()
                    .body(ParseResponse.class);
            if (resp == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "sim-nepal-qr empty parse response");
            }
            return resp;
        } catch (RestClientResponseException ex) {
            throw mapPayError(ex, "parse");
        } catch (ResourceAccessException ex) {
            throw unreachable("parse", ex);
        }
    }

    // -------------------------------------------------------------------------
    // validate (Token-auth decode) — POST /api/qr/validate/
    // -------------------------------------------------------------------------

    /**
     * Decodes a scanned QR via the Token-authenticated {@code /api/qr/validate/} surface.
     *
     * @param qr the raw scanned QR string
     * @return parsed {@link ValidateResponse} (network + receiver/merchant fields)
     */
    public ValidateResponse validate(String qr) {
        try {
            ValidateResponse resp = restClient.post()
                    .uri("/api/qr/validate/")
                    .header(HttpHeaders.AUTHORIZATION, "Token " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("qr", qr))
                    .retrieve()
                    .body(ValidateResponse.class);
            if (resp == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "sim-nepal-qr empty validate response");
            }
            return resp;
        } catch (RestClientResponseException ex) {
            throw mapPayError(ex, "validate");
        } catch (ResourceAccessException ex) {
            throw unreachable("validate", ex);
        }
    }

    // -------------------------------------------------------------------------
    // pay (signed, synchronous single-shot) — POST /qrscan-thirdparty/pay/
    // -------------------------------------------------------------------------

    /**
     * Submits a payment. This is authorize+commit combined: Nepal {@code pay} is synchronous
     * and single-shot (contrast ZeroPay's two-phase authorize/commit).
     *
     * @return parsed {@link PayResponse} ({@code idx} = scheme txn ref, amount in paisa)
     */
    public PayResponse pay(String qs, long amountPaisa, String reference,
                           String mobile, String purpose, String remarks) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("qs", qs);
        payload.put("amount", Long.toString(amountPaisa));
        payload.put("reference", reference);
        payload.put("mobile", mobile);
        payload.put("purpose", purpose == null ? "Remittance" : purpose);
        payload.put("remarks", remarks == null ? "" : remarks);

        NepalRequestSigner.SignedEnvelope env = signer.sign(toJson(payload));
        try {
            PayResponse resp = restClient.post()
                    .uri("/qrscan-thirdparty/pay/")
                    .header(HttpHeaders.AUTHORIZATION, "Key " + key)
                    .header("X-KhaltiNonce", Long.toString(env.nonce()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("data", env.data(), "signature", env.signature()))
                    .retrieve()
                    .body(PayResponse.class);
            if (resp == null) {
                throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE, "sim-nepal-qr empty pay response");
            }
            return resp;
        } catch (RestClientResponseException ex) {
            throw mapPayError(ex, "pay");
        } catch (ResourceAccessException ex) {
            throw unreachable("pay", ex);
        }
    }

    // -------------------------------------------------------------------------
    // status (signed) — POST /qrscan-thirdparty/status/
    // -------------------------------------------------------------------------

    /**
     * Looks up the state of a previously-submitted payment by its unique {@code reference}.
     *
     * @return parsed {@link StatusResponse} ({@code state}: APPROVED/PENDING/REJECTED/REVERSED/Error)
     */
    public StatusResponse status(String reference, Long amountPaisa) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reference", reference);
        if (amountPaisa != null) payload.put("amount", amountPaisa);

        NepalRequestSigner.SignedEnvelope env = signer.sign(toJson(payload));
        try {
            StatusResponse resp = restClient.post()
                    .uri("/qrscan-thirdparty/status/")
                    .header(HttpHeaders.AUTHORIZATION, "Key " + key)
                    .header("X-KhaltiNonce", Long.toString(env.nonce()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("data", env.data(), "signature", env.signature()))
                    .retrieve()
                    .body(StatusResponse.class);
            if (resp == null) {
                throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE, "sim-nepal-qr empty status response");
            }
            return resp;
        } catch (RestClientResponseException ex) {
            throw mapPayError(ex, "status");
        } catch (ResourceAccessException ex) {
            throw unreachable("status", ex);
        }
    }

    // -------------------------------------------------------------------------
    // Error mapping
    // -------------------------------------------------------------------------

    private ApiException unreachable(String op, ResourceAccessException ex) {
        return new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                "sim-nepal-qr unreachable during " + op + ": " + ex.getMessage());
    }

    /**
     * Maps a Nepal partner non-2xx response to a canonical {@link ErrorCode}.
     *
     * <p>Business errors surface as HTTP 400 with a stable body:
     * <ul>
     *   <li>{@code error_key=validation_error} with a {@code reference: "Duplicate reference..."}
     *       → {@link ErrorCode#IDEMPOTENCY_CONFLICT}</li>
     *   <li>{@code detail} containing "Nonce" (expired / not matched) → {@link ErrorCode#VALIDATION_ERROR}</li>
     *   <li>{@code error_key=khalti_error} ("Invalid QR" / payment failed) → {@link ErrorCode#VALIDATION_ERROR}</li>
     *   <li>any other 400 / 422 → {@link ErrorCode#VALIDATION_ERROR}</li>
     * </ul>
     * Transport-level 401/403 (bad token/key/IP) → {@link ErrorCode#SCHEME_UNAVAILABLE}
     * (a misconfiguration, not a caller-fixable input); 404 → {@link ErrorCode#MERCHANT_NOT_FOUND};
     * 5xx / other → {@link ErrorCode#SCHEME_UNAVAILABLE}.
     */
    ApiException mapPayError(RestClientResponseException ex, String operation) {
        HttpStatusCode status = ex.getStatusCode();
        String body = ex.getResponseBodyAsString();
        String prefix = "sim-nepal-qr " + operation + " " + status.value() + ": " + body;

        if (status.value() == HttpStatus.BAD_REQUEST.value()
                || status.value() == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
            JsonNode n = tryParse(body);
            String errorKey = n == null ? "" : n.path("error_key").asText("");
            String detail = n == null ? "" : n.path("detail").asText("");
            boolean duplicateRef = n != null && n.hasNonNull("reference")
                    && n.get("reference").asText("").startsWith("Duplicate reference");

            if (duplicateRef) {
                return new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, prefix);
            }
            if (detail.toLowerCase().contains("nonce")) {
                return new ApiException(ErrorCode.VALIDATION_ERROR, prefix);
            }
            // khalti_error (Invalid QR / Payment failed) + validation_error field errors
            return new ApiException(ErrorCode.VALIDATION_ERROR, prefix);
        }
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return new ApiException(ErrorCode.MERCHANT_NOT_FOUND, prefix);
        }
        // 401/403 (invalid token/key/IP) and 5xx are all a scheme-side / config problem.
        return new ApiException(ErrorCode.SCHEME_UNAVAILABLE, prefix);
    }

    private JsonNode tryParse(String body) {
        try {
            return body == null || body.isBlank() ? null : mapper.readTree(body);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String toJson(Map<String, Object> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "failed to encode Nepal payload: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Wire DTOs (match sim-nepal-qr responses)
    // -------------------------------------------------------------------------

    /** POST /qrscan-thirdparty/parse/ success body. {@code trxAmount} is a rupee string, null if static. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParseResponse(
            String format,
            String initMethod,
            String merchantInfoExtra,
            String merchantCategoryCode,
            String trxCurrency,
            String trxAmount,
            String merchantCountry,
            String merchantName,
            String merchantCity
    ) {}

    /** POST /api/qr/validate/ success body (merchant networks). {@code amount} is paisa or null. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ValidateResponse(
            String network,
            String name,
            String merchant_id,
            Long amount,
            String currency,
            String purpose,
            Map<String, Object> extra
    ) {}

    /** POST /qrscan-thirdparty/pay/ success body. {@code idx} is the scheme transaction ref. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PayResponse(
            String idx,
            String amount,
            String type,
            String detail,
            Map<String, Object> meta
    ) {}

    /** POST /qrscan-thirdparty/status/ body. {@code state}: APPROVED/PENDING/REJECTED/REVERSED/Error. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatusResponse(
            String detail,
            String state
    ) {}
}
