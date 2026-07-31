package com.gme.pay.payment.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.http.HttpClientTimeouts;
import com.gme.pay.payment.domain.PartialRefundNotSupportedException;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.SchemeDeclinedException;
import com.gme.pay.payment.domain.SchemeTimeoutException;
import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.payment.domain.client.SchemeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p>This adapter is the ZeroPay/default {@link SchemeClient}. It is no longer
 * {@code @Primary}: {@link SchemeClientRouter} is the primary bean and delegates
 * ZeroPay (and any non-NEPAL/unknown scheme) here, so this class's behaviour and
 * base-url default are unchanged.
 *
 * <p><b>Internal auth (T0-2):</b> scheme-adapter-zeropay's whole {@code /internal/scheme/**} surface
 * is now behind the service-to-service internal-auth gate ({@code com.gme.pay.internalauth}), so
 * payment-executor — a trusted in-cluster caller — presents the shared secret from
 * {@code gmepay.internal-auth.secret} in the {@code X-Gme-Internal} header on every call. A blank
 * secret sends no header (local dev against an ungated sim); against a real, gated adapter that
 * yields 401 on every call, which is the intended fail-closed outcome of a missing
 * {@code GMEPAY_INTERNAL_AUTH_SECRET} rather than a silent bypass.
 */
@Component
public class RestSchemeClient implements SchemeClient {

    private static final Logger log = LoggerFactory.getLogger(RestSchemeClient.class);

    private final RestClient restClient;
    private final String schemeId = "zeropay";

    @Autowired
    public RestSchemeClient(
            RestClient.Builder builder,
            @Value("${gmepay.scheme-adapter-zeropay.base-url:http://scheme-adapter-zeropay:8080}") String baseUrl,
            @Value("${gmepay.scheme.connect-timeout-millis:2000}") long connectTimeoutMillis,
            @Value("${gmepay.scheme.read-timeout-millis:5000}") long readTimeoutMillis,
            @Value("${gmepay.internal-auth.secret:}") String internalSecret) {
        // Hard connect + read timeout so a HUNG scheme socket aborts in a few seconds (surfacing as
        // ResourceAccessException → SchemeTimeoutException) instead of hanging the pay path forever.
        // These sync timeouts are the call-timeout leg of the resilience trio (breaker+bulkhead live
        // in ResilientSchemeClient); resilience4j TimeLimiter is intentionally NOT used (sync calls).
        // T3-11: HttpClientTimeouts, not ClientHttpRequestFactories.get(..). The Boot 3.3 helper
        // picks a transport by CLASSPATH SCAN and, with no Apache/Jetty/Reactor client present, falls
        // back to SimpleClientHttpRequestFactory (HttpURLConnection) -- under which a read timeout was
        // observed to surface as a RestClientException from BODY EXTRACTION rather than the
        // ResourceAccessException the catch blocks below expect. That difference is not cosmetic: it
        // meant a hung scheme produced a PaymentException, which PaymentOrchestrator does not catch,
        // so no UNCERTAIN row was written and the payment simply vanished from ops' view. Naming the
        // JDK transport explicitly makes the timeout's exception type deterministic (and keeps PATCH
        // working, which HttpURLConnection rejects outright). Pinned by InternalHttpTimeoutTest.
        RestClient.Builder b = builder.baseUrl(baseUrl)
                .requestFactory(HttpClientTimeouts.requestFactory(connectTimeoutMillis, readTimeoutMillis));
        if (internalSecret != null && !internalSecret.isBlank()) {
            b.defaultHeader(InternalAuthHeaders.INTERNAL_TOKEN, internalSecret);
        } else {
            log.warn("gmepay.internal-auth.secret is blank — calls to scheme-adapter-zeropay will "
                    + "carry no {} header and a gated adapter will refuse them (401). Set "
                    + "GMEPAY_INTERNAL_AUTH_SECRET.", InternalAuthHeaders.INTERNAL_TOKEN);
        }
        this.restClient = b.build();
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

    /**
     * T2-6 fail-closed: the ZeroPay adapter's cancel contract is {@code {schemeTxnRef, reason}} — it carries
     * no refund amount, and the 전문 cancel message it builds has no partial-amount field either. So a
     * PARTIAL refund cannot be expressed here; the only thing we could send is a FULL cancel, which would
     * refund the customer more at the scheme than our books recorded. We refuse instead, with the stable
     * {@code PARTIAL_REFUND_UNSUPPORTED} code, and the orchestrator raises it BEFORE moving any float or
     * writing any status — so nothing is half-applied. A full cancel/refund is byte-for-byte unchanged.
     */
    @Override
    public void cancelPayment(CancelRequest request) {
        if (request != null && request.isPartial()) {
            throw new PartialRefundNotSupportedException(
                    schemeId, request.partialAmount(), request.partialCurrency());
        }
        SchemeClient.super.cancelPayment(request);
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

    @Override
    public BalanceCheckResult checkBalance(String schemeId, BigDecimal amount, String currency) {
        try {
            SchemeBalanceResponse body = restClient.post()
                    .uri("/internal/scheme/zeropay/balance-check")
                    .body(new SchemeBalanceRequest(schemeId, amount, currency))
                    .retrieve()
                    .body(SchemeBalanceResponse.class);
            if (body == null) {
                throw new PaymentException("scheme-adapter returned empty balance-check response");
            }
            return new BalanceCheckResult(body.allowed(), body.available());
        } catch (RestClientResponseException ex) {
            throw mapSchemeFailure(ex);
        } catch (ResourceAccessException ex) {
            throw new SchemeTimeoutException(schemeId);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException("scheme-adapter balance-check failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Anti-double-charge status lookup (ADR-016 §4): {@code GET /internal/scheme/zeropay/status?reference=}.
     * Asks ZeroPay whether our stable reference was paid before the router fails over to another
     * partner. 404 → {@link LookupStatus#NOT_FOUND} (safe to fail over); any other failure is
     * best-effort {@code NOT_FOUND} (the router only calls this after a technical failure, so an
     * unreachable status endpoint degrades to fail-over rather than a hard error).
     */
    @Override
    public LookupStatus lookupStatus(String schemeId, String reference) {
        try {
            SchemeStatusResponse body = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/scheme/zeropay/status")
                            .queryParam("reference", reference)
                            .build())
                    .retrieve()
                    .body(SchemeStatusResponse.class);
            if (body == null || body.status() == null) {
                return LookupStatus.NOT_FOUND;
            }
            return mapLookupStatus(body.status());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return LookupStatus.NOT_FOUND;
            }
            return LookupStatus.NOT_FOUND;
        } catch (RuntimeException ex) {
            return LookupStatus.NOT_FOUND;
        }
    }

    /** Maps ZeroPay's status vocabulary onto the canonical {@link LookupStatus}. */
    private static LookupStatus mapLookupStatus(String status) {
        String s = status.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (s) {
            case "APPROVED", "CAPTURED", "SUCCESS", "PAID", "COMPLETED" -> LookupStatus.APPROVED;
            case "PENDING", "AUTHORIZED", "IN_PROGRESS", "PROCESSING" -> LookupStatus.PENDING;
            case "DECLINED", "FAILED", "REJECTED", "CANCELLED" -> LookupStatus.REJECTED;
            default -> LookupStatus.NOT_FOUND;
        };
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

    record SchemeBalanceRequest(String schemeId, BigDecimal amountKrw, String currency) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SchemeBalanceResponse(boolean allowed, BigDecimal available) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SchemeApprovalResponse(
            String schemeApprovalCode,
            String schemeTxnRef,
            Instant approvedAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SchemeStatusResponse(
            String schemeTxnRef,
            String status,
            String reference
    ) {}
}
