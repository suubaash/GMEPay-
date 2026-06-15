package com.gme.pay.payment.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.SchemeDeclinedException;
import com.gme.pay.payment.domain.SchemeTimeoutException;
import com.gme.pay.payment.domain.client.SchemeClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * REST adapter that submits payments to the scheme-adapter-zeropay service via
 * {@code POST /internal/scheme/zeropay/submit} (and analogous cancel/cpm endpoints).
 *
 * <p>Base URL is read from {@code gmepay.scheme-adapter-zeropay.base-url} (default
 * {@code http://scheme-adapter-zeropay:8080}). HTTP semantics:
 * <ul>
 *   <li>422 → {@link SchemeDeclinedException}
 *   <li>503 / 504 / network read timeout → {@link SchemeTimeoutException}
 *   <li>other non-2xx → {@link PaymentException}
 * </ul>
 */
@Component
@Primary
public class RestSchemeClient implements SchemeClient {

    private final RestClient restClient;
    private final String schemeId = "zeropay";

    @Autowired
    public RestSchemeClient(
            RestClient.Builder builder,
            @Value("${gmepay.scheme-adapter-zeropay.base-url:http://scheme-adapter-zeropay:8080}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    RestSchemeClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public MpmSubmitResponse submitMpm(MpmSubmitRequest request) {
        try {
            SchemeApprovalResponse body = restClient.post()
                    .uri("/internal/scheme/zeropay/submit")
                    .body(new SchemeMpmSubmitRequest(
                            request.txnRef(),
                            request.merchantId(),
                            request.payoutAmount(),
                            request.payoutCurrency(),
                            request.schemeId(),
                            request.qrPayload()))
                    .retrieve()
                    .body(SchemeApprovalResponse.class);

            if (body == null) {
                throw new PaymentException("scheme-adapter returned empty MPM response");
            }
            return new MpmSubmitResponse(
                    body.schemeApprovalCode(),
                    body.schemeTxnRef(),
                    body.approvedAt());
        } catch (RestClientResponseException ex) {
            throw mapSchemeFailure(ex);
        } catch (ResourceAccessException ex) {
            // I/O error / read timeout
            throw new SchemeTimeoutException(schemeId);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException("scheme-adapter submit failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void cancelPayment(String schemeTxnRef, String reason) {
        try {
            restClient.post()
                    .uri("/internal/scheme/zeropay/cancel")
                    .body(new SchemeCancelRequest(schemeTxnRef, reason))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw mapSchemeFailure(ex);
        } catch (ResourceAccessException ex) {
            throw new SchemeTimeoutException(schemeId);
        } catch (RuntimeException ex) {
            throw new PaymentException("scheme-adapter cancel failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public CpmSubmitResponse submitCpm(CpmSubmitRequest request) {
        try {
            SchemeApprovalResponse body = restClient.post()
                    .uri("/internal/scheme/zeropay/cpm")
                    .body(new SchemeCpmSubmitRequest(
                            request.txnRef(),
                            request.qrToken(),
                            request.payoutAmount(),
                            request.payoutCurrency(),
                            request.schemeId()))
                    .retrieve()
                    .body(SchemeApprovalResponse.class);

            if (body == null) {
                throw new PaymentException("scheme-adapter returned empty CPM response");
            }
            return new CpmSubmitResponse(
                    body.schemeApprovalCode(),
                    body.schemeTxnRef(),
                    body.approvedAt());
        } catch (RestClientResponseException ex) {
            throw mapSchemeFailure(ex);
        } catch (ResourceAccessException ex) {
            throw new SchemeTimeoutException(schemeId);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException("scheme-adapter CPM submit failed: " + ex.getMessage(), ex);
        }
    }

    /** Maps a non-2xx scheme response onto the right domain exception. */
    private RuntimeException mapSchemeFailure(RestClientResponseException ex) {
        HttpStatusCode status = ex.getStatusCode();
        String body = ex.getResponseBodyAsString();
        if (status.value() == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
            return new SchemeDeclinedException(extractCode(body), extractMessage(body));
        }
        if (status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()
                || status.value() == HttpStatus.GATEWAY_TIMEOUT.value()) {
            return new SchemeTimeoutException(schemeId);
        }
        return new PaymentException(
                "scheme-adapter call failed: " + status + " " + body, ex);
    }

    private static String extractCode(String body) {
        return extractField(body, "code", "SCHEME_ERROR");
    }

    private static String extractMessage(String body) {
        return extractField(body, "message", "Scheme declined");
    }

    private static String extractField(String body, String name, String fallback) {
        if (body == null) return fallback;
        String key = "\"" + name + "\"";
        int idx = body.indexOf(key);
        if (idx < 0) return fallback;
        int colon = body.indexOf(':', idx);
        if (colon < 0) return fallback;
        int firstQuote = body.indexOf('"', colon);
        if (firstQuote < 0) return fallback;
        int secondQuote = body.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) return fallback;
        return body.substring(firstQuote + 1, secondQuote);
    }

    // ---- wire formats ----

    // Field names MUST match scheme-adapter-zeropay's SubmitPaymentRequest JSON keys
    // (Jackson binds by name): partnerTxnRef / amountKrw / currency — not txnRef/payout*.
    record SchemeMpmSubmitRequest(
            String partnerTxnRef,
            String merchantId,
            BigDecimal amountKrw,
            String currency,
            String schemeId,
            String qrPayload
    ) {}

    record SchemeCpmSubmitRequest(
            String txnRef,
            String qrToken,
            BigDecimal payoutAmount,
            String payoutCurrency,
            String schemeId
    ) {}

    record SchemeCancelRequest(String schemeTxnRef, String reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SchemeApprovalResponse(
            String schemeApprovalCode,
            String schemeTxnRef,
            Instant approvedAt
    ) {}
}
