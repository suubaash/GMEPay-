package com.gme.pay.payment.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.SchemeDeclinedException;
import com.gme.pay.payment.domain.SchemeTimeoutException;
import com.gme.pay.payment.domain.client.SchemeClient;
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
 * REST adapter that submits payments to the {@code scheme-adapter-nepal} service.
 *
 * <p>Nepal pay is <strong>single-phase</strong>: {@code POST /internal/scheme/nepal/submit}
 * is authorize+commit in one call. There is no separate confirm/cancel round-trip like
 * ZeroPay's two-phase flow, so both the orchestrator's MPM and CPM submit paths land on
 * the same Nepal submit endpoint. The response {@code {schemeTxnRef,status,amountPaisa}}
 * is mapped into the platform's {@link MpmSubmitResponse}/{@link CpmSubmitResponse} shape
 * so the orchestrator consumes it unchanged (schemeApprovalCode &larr; status,
 * schemeTxnRef &larr; schemeTxnRef, approvedAt &larr; now).
 *
 * <p>Base URL is read from {@code gmepay.scheme-adapters.NEPAL.base-url} (default
 * {@code http://localhost:18091}). This adapter is NOT {@code @Primary}; the
 * {@link SchemeClientRouter} selects it by scheme code (NEPAL) and delegates everything
 * else to the default ZeroPay {@link RestSchemeClient}. HTTP semantics mirror
 * {@link RestSchemeClient}: 422 &rarr; declined, 503/504/read-timeout &rarr; timeout,
 * other non-2xx &rarr; {@link PaymentException}.
 */
@Component
public class NepalRestSchemeClient implements SchemeClient {

    /** Router key this adapter serves. */
    public static final String SCHEME_CODE = "NEPAL";

    private final RestClient restClient;
    private final String schemeId = "nepal";

    @Autowired
    public NepalRestSchemeClient(
            RestClient.Builder builder,
            @Value("${gmepay.scheme-adapters.NEPAL.base-url:http://localhost:18091}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    NepalRestSchemeClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public MpmSubmitResponse submitMpm(MpmSubmitRequest request) {
        NepalSubmitResponse body = submit(
                request.qrPayload(), request.payoutAmount(), request.txnRef());
        return new MpmSubmitResponse(body.status(), body.schemeTxnRef(), Instant.now());
    }

    @Override
    public CpmSubmitResponse submitCpm(CpmSubmitRequest request) {
        NepalSubmitResponse body = submit(
                request.qrToken(), request.payoutAmount(), request.txnRef());
        return new CpmSubmitResponse(body.status(), body.schemeTxnRef(), Instant.now());
    }

    /** Shared single-phase submit (authorize+commit) against the Nepal adapter. */
    private NepalSubmitResponse submit(String qs, BigDecimal amount, String reference) {
        try {
            NepalSubmitResponse body = restClient.post()
                    .uri("/internal/scheme/nepal/submit")
                    .body(new NepalSubmitRequest(qs, toPaisa(amount), reference, null, null, null))
                    .retrieve()
                    .body(NepalSubmitResponse.class);
            if (body == null) {
                throw new PaymentException("scheme-adapter-nepal returned empty submit response");
            }
            return body;
        } catch (RestClientResponseException ex) {
            throw mapSchemeFailure(ex);
        } catch (ResourceAccessException ex) {
            throw new SchemeTimeoutException(schemeId);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException("scheme-adapter-nepal submit failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void cancelPayment(String schemeTxnRef, String reason) {
        // Nepal pay is single-phase (submit = authorize+commit); the adapter exposes no
        // cancel endpoint. Cancellation is not part of the Nepal contract, so this is a
        // no-op rather than a misrouted call to a non-existent endpoint.
        throw new PaymentException(
                "NEPAL is single-phase (submit=authorize+commit); cancelPayment is not supported");
    }

    /**
     * Anti-double-charge status lookup (ADR-016 §4): {@code GET /internal/scheme/nepal/status?reference=}.
     * The adapter answers whether our stable reference was paid. A 404 → {@link LookupStatus#NOT_FOUND}
     * (safe to fail over). Any transport/other error is treated as {@code NOT_FOUND} best-effort — but
     * note the router only calls this AFTER a technical failure, so an unreachable status endpoint
     * degrades to the pre-guard behaviour (fail over) rather than a hard error.
     */
    @Override
    public LookupStatus lookupStatus(String schemeId, String reference) {
        try {
            NepalStatusResponse body = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/scheme/nepal/status")
                            .queryParam("reference", reference)
                            .build())
                    .retrieve()
                    .body(NepalStatusResponse.class);
            if (body == null || body.status() == null) {
                return LookupStatus.NOT_FOUND;
            }
            return mapStatus(body.status());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return LookupStatus.NOT_FOUND;
            }
            // Ambiguous status-endpoint failure: cannot confirm a payment, so best-effort NOT_FOUND.
            return LookupStatus.NOT_FOUND;
        } catch (RuntimeException ex) {
            return LookupStatus.NOT_FOUND;
        }
    }

    /** Maps the Nepal adapter's status vocabulary onto the canonical {@link LookupStatus}. */
    private static LookupStatus mapStatus(String status) {
        String s = status.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (s) {
            case "SUCCESS", "APPROVED", "PAID", "COMPLETED" -> LookupStatus.APPROVED;
            case "PENDING", "IN_PROGRESS", "PROCESSING" -> LookupStatus.PENDING;
            case "FAILED", "DECLINED", "REJECTED" -> LookupStatus.REJECTED;
            default -> LookupStatus.NOT_FOUND;
        };
    }

    private static BigDecimal toPaisa(BigDecimal amount) {
        // Nepal amounts are minor units (paisa); the orchestrator carries a decimal payout.
        return amount == null ? null : amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP);
    }

    private RuntimeException mapSchemeFailure(RestClientResponseException ex) {
        HttpStatusCode status = ex.getStatusCode();
        String body = ex.getResponseBodyAsString();
        if (status.value() == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
            return new SchemeDeclinedException(extractField(body, "code", "NEPAL_ERROR"),
                    extractField(body, "message", "Nepal scheme declined"));
        }
        if (status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()
                || status.value() == HttpStatus.GATEWAY_TIMEOUT.value()) {
            return new SchemeTimeoutException(schemeId);
        }
        return new PaymentException("scheme-adapter-nepal call failed: " + status + " " + body, ex);
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

    // ---- wire formats (match scheme-adapter-nepal's /submit contract) ----

    record NepalSubmitRequest(
            String qs,
            BigDecimal amountPaisa,
            String reference,
            String mobile,
            String purpose,
            String remarks
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NepalSubmitResponse(
            String schemeTxnRef,
            String status,
            BigDecimal amountPaisa
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NepalStatusResponse(
            String schemeTxnRef,
            String status,
            String reference
    ) {}
}
