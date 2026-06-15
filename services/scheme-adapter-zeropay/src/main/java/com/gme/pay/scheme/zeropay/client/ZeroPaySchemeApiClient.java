package com.gme.pay.scheme.zeropay.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;

/**
 * REST client for the ZeroPay scheme simulator (sim-scheme, :9102).
 *
 * <p>Three operations are exposed:
 * <ul>
 *   <li>{@link #authorize} — POST /v1/scheme/payments/authorize</li>
 *   <li>{@link #commit}    — POST /v1/scheme/payments/{authId}/commit</li>
 *   <li>{@link #decodeQr}  — POST /v1/scheme/qr/decode</li>
 * </ul>
 *
 * <p>Configured by {@code gmepay.scheme.zeropay.base-url}
 * (default {@code http://localhost:9102/v1/scheme}).
 *
 * <p>Error mapping:
 * <ul>
 *   <li>422 AMOUNT_MISMATCH / INVALID_QR → {@link ApiException}({@link ErrorCode#VALIDATION_ERROR})</li>
 *   <li>404 MERCHANT_NOT_FOUND → {@link ApiException}({@link ErrorCode#MERCHANT_NOT_FOUND})</li>
 *   <li>503 / 504 / network error → {@link ApiException}({@link ErrorCode#SCHEME_UNAVAILABLE})</li>
 *   <li>other non-2xx → {@link ApiException}({@link ErrorCode#SCHEME_UNAVAILABLE})</li>
 * </ul>
 */
@Component
public class ZeroPaySchemeApiClient {

    private final RestClient restClient;

    /**
     * Primary constructor — wired by Spring with the configured base URL.
     * {@code @Autowired} is required because there is also a package-private
     * test constructor (Spring 6 rule: @Autowired on @Value ctor if 2+ ctors).
     */
    @Autowired
    public ZeroPaySchemeApiClient(
            RestClient.Builder builder,
            @Value("${gmepay.scheme.zeropay.base-url:http://localhost:9102/v1/scheme}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /** Package-private test constructor — accepts a pre-built RestClient. */
    ZeroPaySchemeApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    // -------------------------------------------------------------------------
    // Authorize
    // -------------------------------------------------------------------------

    /**
     * POST /payments/authorize
     *
     * @param mode        "MPM_STATIC" | "MPM_DYNAMIC" | "CPM"
     * @param qrPayload   required for MPM modes; null for CPM
     * @param cpmToken    required for CPM; null for MPM
     * @param amount      payment amount (as BigDecimal; the JSON wire sends as string)
     * @param currency    ISO currency code (e.g. "KRW")
     * @param payerRef    partner transaction reference (idempotency key)
     * @return parsed {@link AuthorizeResponse}
     */
    public AuthorizeResponse authorize(String mode, String qrPayload, String cpmToken,
                                       BigDecimal amount, String currency, String payerRef) {
        AuthorizeRequest req = new AuthorizeRequest(mode, qrPayload, cpmToken, amount, currency, payerRef);
        try {
            AuthorizeResponse resp = restClient.post()
                    .uri("/payments/authorize")
                    .body(req)
                    .retrieve()
                    .body(AuthorizeResponse.class);
            if (resp == null) {
                throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                        "sim-scheme returned empty authorize response");
            }
            return resp;
        } catch (RestClientResponseException ex) {
            throw mapError(ex, "authorize");
        } catch (ResourceAccessException ex) {
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                    "sim-scheme unreachable during authorize: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Commit
    // -------------------------------------------------------------------------

    /**
     * POST /payments/{authId}/commit
     *
     * @param authId the authId returned by {@link #authorize}
     * @return parsed {@link CommitResponse}
     */
    public CommitResponse commit(String authId) {
        try {
            CommitResponse resp = restClient.post()
                    .uri("/payments/{authId}/commit", authId)
                    .retrieve()
                    .body(CommitResponse.class);
            if (resp == null) {
                throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                        "sim-scheme returned empty commit response");
            }
            return resp;
        } catch (RestClientResponseException ex) {
            throw mapError(ex, "commit");
        } catch (ResourceAccessException ex) {
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                    "sim-scheme unreachable during commit: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Decode QR
    // -------------------------------------------------------------------------

    /**
     * POST /qr/decode
     *
     * @param qrPayload the raw EMVCo QR string
     * @return parsed {@link DecodeQrResponse}
     */
    public DecodeQrResponse decodeQr(String qrPayload) {
        try {
            DecodeQrResponse resp = restClient.post()
                    .uri("/qr/decode")
                    .body(new DecodeQrRequest(qrPayload))
                    .retrieve()
                    .body(DecodeQrResponse.class);
            if (resp == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "sim-scheme returned empty QR decode response");
            }
            return resp;
        } catch (RestClientResponseException ex) {
            throw mapError(ex, "qr/decode");
        } catch (ResourceAccessException ex) {
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                    "sim-scheme unreachable during qr/decode: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // CPM token issuance
    // -------------------------------------------------------------------------

    /**
     * POST /cpm/token — issues a CPM token for the given customer and funding source.
     *
     * @param customerId  the wallet customer ID
     * @param fundingRef  the funding source reference (e.g. "WALLET")
     * @return parsed {@link CpmTokenResponse}
     */
    public CpmTokenResponse fetchCpmToken(String customerId, String fundingRef) {
        try {
            CpmTokenResponse resp = restClient.post()
                    .uri("/cpm/token")
                    .body(new CpmTokenRequest(customerId, fundingRef))
                    .retrieve()
                    .body(CpmTokenResponse.class);
            if (resp == null) {
                throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                        "sim-scheme returned empty CPM token response");
            }
            return resp;
        } catch (RestClientResponseException ex) {
            throw mapError(ex, "cpm/token");
        } catch (ResourceAccessException ex) {
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                    "sim-scheme unreachable during cpm/token: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Refund
    // -------------------------------------------------------------------------

    /**
     * POST /payments/{authId}/refund — refunds a previously committed payment.
     *
     * @param authId  the authId from the original authorize call
     * @param amount  the amount to refund (null for full refund, but sim-scheme requires it)
     * @return parsed {@link RefundResponse}
     */
    public RefundResponse refund(String authId, BigDecimal amount) {
        try {
            RefundResponse resp = restClient.post()
                    .uri("/payments/{authId}/refund", authId)
                    .body(new RefundRequest(amount))
                    .retrieve()
                    .body(RefundResponse.class);
            if (resp == null) {
                throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                        "sim-scheme returned empty refund response");
            }
            return resp;
        } catch (RestClientResponseException ex) {
            throw mapError(ex, "refund");
        } catch (ResourceAccessException ex) {
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                    "sim-scheme unreachable during refund: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Error mapping
    // -------------------------------------------------------------------------

    private ApiException mapError(RestClientResponseException ex, String operation) {
        HttpStatusCode status = ex.getStatusCode();
        String body = ex.getResponseBodyAsString();

        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return new ApiException(ErrorCode.MERCHANT_NOT_FOUND,
                    "sim-scheme " + operation + " 404: " + body);
        }
        if (status.value() == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
            // AMOUNT_MISMATCH or INVALID_QR
            return new ApiException(ErrorCode.VALIDATION_ERROR,
                    "sim-scheme " + operation + " 422: " + body);
        }
        if (status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()
                || status.value() == HttpStatus.GATEWAY_TIMEOUT.value()) {
            return new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                    "sim-scheme " + operation + " " + status + ": " + body);
        }
        return new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                "sim-scheme " + operation + " " + status + ": " + body);
    }

    // -------------------------------------------------------------------------
    // Wire DTOs
    // -------------------------------------------------------------------------

    record AuthorizeRequest(
            String mode,
            String qrPayload,
            String cpmToken,
            BigDecimal amount,
            String currency,
            String payerRef
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthorizeResponse(
            String authId,
            String status,
            String schemeRef,
            String merchantId,
            BigDecimal amount,
            String currency,
            String asOf
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommitResponse(
            String authId,
            String status,
            String schemeTxnRef,
            String committedAt
    ) {}

    record DecodeQrRequest(String qrPayload) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DecodeQrResponse(
            String merchantId,
            String merchantName,
            String mode,
            String amount,   // null for static; plain string for dynamic
            String currency
    ) {}

    record CpmTokenRequest(String customerId, String fundingRef) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CpmTokenResponse(
            String mode,
            String cpmToken,
            String expiresAt
    ) {}

    record RefundRequest(BigDecimal amount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefundResponse(
            String refundId,
            String status
    ) {}
}
