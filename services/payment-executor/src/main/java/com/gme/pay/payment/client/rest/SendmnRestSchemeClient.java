package com.gme.pay.payment.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.SchemeDeclinedException;
import com.gme.pay.payment.domain.SchemeOperationNotSupportedException;
import com.gme.pay.payment.domain.SchemeTimeoutException;
import com.gme.pay.payment.domain.client.SchemeClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * REST adapter that submits payments to the {@code scheme-adapter-sendmn} service
 * (SendMN / QPay, Mongolia — KRW→MNT corridor).
 *
 * <p>SendMN is <strong>two-step</strong> at the scheme edge: {@code POST
 * /internal/scheme/sendmn/verify-qr} decodes the scanned merchant QR and mints the
 * scheme-side idempotency key ({@code txTokenNo}); {@code POST
 * /internal/scheme/sendmn/submit-mpm} (SendMN Confirm) moves the money. This client folds
 * both into one {@link #submitMpm} so the orchestrator keeps the single-call
 * {@link SchemeClient} contract. The adapter owns the scheme's ambiguity policy (duplicate
 * token 304, ambiguous Confirm → PaymentStatus poll, ADR-016 never-auto-fail), so a
 * submit answer of {@code UNKNOWN}/{@code PENDING} comes back in-body as
 * {@code schemeApprovalCode} rather than as an exception.
 *
 * <p>Amounts are MNT (Decimal(18,2)); {@code payoutAmount} on the request must already be
 * the MNT figure (the KRW→MNT FX is done upstream by {@code SendmnPaymentService}).
 * {@code merchantId} is deliberately NOT forwarded: the adapter captures the SendMN
 * merchant GUID at verify-qr, and the hub-side id is a GME record key, not a SendMN id.
 *
 * <p>Base URL is read from {@code gmepay.scheme-adapters.SENDMN.base-url} (default
 * {@code http://localhost:8093}). Not {@code @Primary}; {@link SchemeClientRouter} selects
 * it by scheme code (SENDMN). HTTP semantics mirror {@link NepalRestSchemeClient}:
 * 400/422 → declined, 503/504/read-timeout → timeout, other non-2xx → {@link PaymentException}.
 *
 * <h2>lookupStatus (ADR-016 §4)</h2>
 * The adapter's primary status endpoint is keyed by the scheme-side {@code txTokenNo}, so
 * this client keeps a bounded in-memory {@code reference → txTokenNo} map recorded at
 * verify-qr time (i.e. BEFORE the irreversible Confirm) as the cheap first hop. On a map
 * miss (process restarted, or evicted) it falls back to the adapter's durable
 * {@code GET /internal/scheme/sendmn/status/by-reference/{reference}} — the adapter
 * persists our reference in {@code smn_payments} at verify time, so the probe survives a
 * hub restart. A true 404 from by-reference means the adapter never saw the reference (no
 * Confirm can have been sent) → {@code NOT_FOUND}, safe to fail over — mirroring
 * {@link NepalRestSchemeClient}. ANY other probe failure (transport, 5xx) returns
 * {@code PENDING}, never {@code NOT_FOUND}: the Confirm may have landed, and falsely
 * reporting it absent would allow a double-charge.
 */
@Component
public class SendmnRestSchemeClient implements SchemeClient {

    /** Router key this adapter serves. */
    public static final String SCHEME_CODE = "SENDMN";

    /** Upper bound on remembered reference→txTokenNo pairs (LRU eviction). */
    private static final int MAX_REMEMBERED_REFERENCES = 10_000;

    private final RestClient restClient;
    private final String schemeId = "sendmn";

    /**
     * reference → txTokenNo, recorded at verify-qr (before Confirm) so the ADR-016 probe
     * can translate our stable reference into the SendMN status key. Bounded LRU.
     */
    private final Map<String, String> txTokenByReference =
            Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_REMEMBERED_REFERENCES;
                }
            });

    @Autowired
    public SendmnRestSchemeClient(
            RestClient.Builder builder,
            @Value("${gmepay.scheme-adapters.SENDMN.base-url:http://localhost:8093}") String baseUrl,
            @Value("${gmepay.scheme.connect-timeout-millis:2000}") long connectTimeoutMillis,
            @Value("${gmepay.scheme.read-timeout-millis:5000}") long readTimeoutMillis) {
        // Hard connect + read timeout (see RestSchemeClient): a hung SendMN adapter socket
        // aborts fast as ResourceAccessException → SchemeTimeoutException.
        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .withReadTimeout(Duration.ofMillis(readTimeoutMillis));
        this.restClient = builder.baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(timeouts))
                .build();
    }

    SendmnRestSchemeClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public MpmSubmitResponse submitMpm(MpmSubmitRequest request) {
        if (request.qrPayload() == null || request.qrPayload().isBlank()) {
            throw new PaymentException("SENDMN submit requires the raw scanned qrPayload");
        }
        // Step 1 — verify-qr: decode the QR, mint the scheme idempotency key (txTokenNo).
        // Our stable reference rides along so the adapter persists reference→payment
        // BEFORE the irreversible Confirm — the durable leg of the ADR-016 probe.
        SendmnVerifyQrResponse verified = call(() -> restClient.post()
                .uri("/internal/scheme/sendmn/verify-qr")
                .body(new SendmnVerifyQrRequest(request.qrPayload(), request.txnRef()))
                .retrieve()
                .body(SendmnVerifyQrResponse.class), "verify-qr");
        if (verified == null || verified.txTokenNo() == null || verified.txTokenNo().isBlank()) {
            throw new PaymentException("scheme-adapter-sendmn returned no txTokenNo from verify-qr");
        }
        // Record reference→token BEFORE the irreversible Confirm so an ambiguous Confirm
        // outcome is still probe-able via lookupStatus (ADR-016 §4).
        if (request.txnRef() != null) {
            txTokenByReference.put(request.txnRef(), verified.txTokenNo());
        }

        // Step 2 — submit-mpm (SendMN Confirm). merchantId stays null: the adapter uses the
        // SendMN merchant GUID it captured at verify-qr (the hub id is not a SendMN id).
        SendmnSubmitMpmResponse body = call(() -> restClient.post()
                .uri("/internal/scheme/sendmn/submit-mpm")
                .body(new SendmnSubmitMpmRequest(
                        verified.txTokenNo(), toMntAmount(request.payoutAmount()), null,
                        request.txnRef()))
                .retrieve()
                .body(SendmnSubmitMpmResponse.class), "submit-mpm");
        if (body == null) {
            throw new PaymentException("scheme-adapter-sendmn returned empty submit-mpm response");
        }
        // schemeApprovalCode ← canonical status (APPROVED / PENDING / UNKNOWN);
        // schemeTxnRef ← SendMN paymentNo when known, else the txTokenNo.
        String schemeTxnRef = body.paymentNo() != null && !body.paymentNo().isBlank()
                ? body.paymentNo() : body.txTokenNo();
        return new MpmSubmitResponse(body.status(), schemeTxnRef, Instant.now());
    }

    @Override
    public CpmSubmitResponse submitCpm(CpmSubmitRequest request) {
        // SendMN is merchant-presented (MPM static QR) only — no CPM contract exists.
        throw new PaymentException("SENDMN supports MPM only; submitCpm is not supported");
    }

    /**
     * SendMN Confirm is authorize+commit in one call and the scheme documents no cancel, so there is
     * nothing to call. T2-7: this now raises the structured
     * {@link SchemeOperationNotSupportedException} ({@code SCHEME_OPERATION_UNSUPPORTED}) so a SENDMN
     * refund gets an unambiguous answer instead of the ZeroPay decline the scheme-less router produced.
     * Reversing the KRW→MNT money movement is a manual/ops SendMN process (register item T2-6).
     */
    @Override
    public void cancelPayment(String schemeTxnRef, String reason) {
        throw new SchemeOperationNotSupportedException(SCHEME_CODE, "cancelPayment",
                "SENDMN Confirm is single-shot (authorize+commit); the scheme documents no cancel");
    }

    /**
     * Anti-double-charge status lookup (ADR-016 §4). Cheap first hop:
     * {@code GET /internal/scheme/sendmn/status/{txTokenNo}} via the remembered
     * reference→txTokenNo map. On a map miss (restart/eviction) falls back to the
     * adapter's durable {@code GET /internal/scheme/sendmn/status/by-reference/{reference}}.
     * See the class doc for the NOT_FOUND vs PENDING policy.
     */
    @Override
    public LookupStatus lookupStatus(String schemeId, String reference) {
        if (reference == null) {
            // Nothing to key either probe on — genuinely absent: safe to fail over.
            return LookupStatus.NOT_FOUND;
        }
        String txTokenNo = txTokenByReference.get(reference);
        if (txTokenNo == null) {
            // Map miss ≠ never submitted: this process may have restarted after Confirm.
            // Ask the adapter's durable by-reference index before concluding anything.
            return lookupStatusByReference(reference);
        }
        try {
            SendmnStatusResponse body = restClient.get()
                    .uri("/internal/scheme/sendmn/status/{txTokenNo}", txTokenNo)
                    .retrieve()
                    .body(SendmnStatusResponse.class);
            if (body == null || body.status() == null) {
                return LookupStatus.PENDING; // known reference, indeterminate answer
            }
            return mapStatus(body.status());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                // The adapter itself has no record of the token — nothing was persisted at
                // verify time, so no Confirm reached SendMN: safe to fail over.
                return LookupStatus.NOT_FOUND;
            }
            // Known reference but the probe failed: the Confirm may have landed. Reporting
            // NOT_FOUND here could double-charge, so hold at PENDING (do not retry).
            return LookupStatus.PENDING;
        } catch (RuntimeException ex) {
            return LookupStatus.PENDING;
        }
    }

    /**
     * Durable fallback probe keyed by our stable reference, which the adapter persists in
     * {@code smn_payments} at verify time (before Confirm). Only a true 404 — the adapter
     * never saw the reference, so no Confirm can have been sent — maps to
     * {@link LookupStatus#NOT_FOUND} (mirrors {@link NepalRestSchemeClient}); any other
     * failure holds {@code PENDING} because the Confirm may have landed (ADR-016).
     */
    private LookupStatus lookupStatusByReference(String reference) {
        try {
            SendmnStatusResponse body = restClient.get()
                    .uri("/internal/scheme/sendmn/status/by-reference/{reference}", reference)
                    .retrieve()
                    .body(SendmnStatusResponse.class);
            if (body == null || body.status() == null) {
                return LookupStatus.PENDING; // adapter knows the reference, answer indeterminate
            }
            return mapStatus(body.status());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return LookupStatus.NOT_FOUND;
            }
            return LookupStatus.PENDING;
        } catch (RuntimeException ex) {
            return LookupStatus.PENDING;
        }
    }

    /** Maps the SendMN adapter's canonical status vocabulary onto {@link LookupStatus}. */
    private static LookupStatus mapStatus(String status) {
        String s = status.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "APPROVED" -> LookupStatus.APPROVED;
            case "REJECTED" -> LookupStatus.REJECTED;
            // PENDING and UNKNOWN both mean "outcome not final — do NOT retry" (ADR-016:
            // the adapter never auto-fails; UNKNOWN is an unresolved, possibly-paid state).
            default -> LookupStatus.PENDING;
        };
    }

    /** MNT is Decimal(18,2) on the SendMN wire. */
    private static String toMntAmount(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Shared HTTP error mapping around the two submit-path calls. */
    private <T> T call(java.util.function.Supplier<T> httpCall, String step) {
        try {
            return httpCall.get();
        } catch (RestClientResponseException ex) {
            throw mapSchemeFailure(ex, step);
        } catch (ResourceAccessException ex) {
            throw new SchemeTimeoutException(schemeId);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "scheme-adapter-sendmn " + step + " failed: " + ex.getMessage(), ex);
        }
    }

    private RuntimeException mapSchemeFailure(RestClientResponseException ex, String step) {
        HttpStatusCode status = ex.getStatusCode();
        String body = ex.getResponseBodyAsString();
        // The adapter's ApiExceptionHandler maps validation rejects (bad QR, RES_CODE
        // rejects, settlement mismatch 307) to 400 VALIDATION_ERROR; treat 400 and 422
        // both as authoritative scheme declines.
        if (status.value() == HttpStatus.BAD_REQUEST.value()
                || status.value() == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
            return new SchemeDeclinedException(extractField(body, "code", "SENDMN_ERROR"),
                    extractField(body, "message", "SendMN scheme declined"));
        }
        if (status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()
                || status.value() == HttpStatus.GATEWAY_TIMEOUT.value()) {
            return new SchemeTimeoutException(schemeId);
        }
        return new PaymentException(
                "scheme-adapter-sendmn " + step + " call failed: " + status + " " + body, ex);
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

    // ---- wire formats (match scheme-adapter-sendmn's controller DTOs) ----

    record SendmnVerifyQrRequest(String qrPayload, String reference) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SendmnVerifyQrResponse(
            String txTokenNo,
            String merchantId,
            String merchantName,
            String localAmountMnt,
            String currency
    ) {}

    record SendmnSubmitMpmRequest(
            String txTokenNo,
            String localAmountMnt,
            String merchantId,
            String reference
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SendmnSubmitMpmResponse(
            String txTokenNo,
            String status,
            String paymentNo,
            String paymentReceiptNo,
            String fxUsdBuyRate,
            String settlementAmountUsd
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SendmnStatusResponse(
            String txTokenNo,
            String status,
            String paymentNo,
            String paymentReceiptNo
    ) {}
}
