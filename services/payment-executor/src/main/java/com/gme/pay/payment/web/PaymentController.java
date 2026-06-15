package com.gme.pay.payment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.payment.domain.PartnerType;
import com.gme.pay.payment.domain.PaymentOrchestrator;
import com.gme.pay.payment.domain.PaymentOrchestrator.CancelResult;
import com.gme.pay.payment.domain.PaymentOrchestrator.CpmPaymentCommand;
import com.gme.pay.payment.domain.PaymentOrchestrator.MpmPaymentCommand;
import com.gme.pay.payment.domain.PaymentOrchestrator.PaymentResult;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.persistence.IdempotencyRecordEntity;
import com.gme.pay.payment.persistence.IdempotencyRecordRepository;
import com.gme.pay.payment.web.dto.CancelPaymentRequest;
import com.gme.pay.payment.web.dto.CancelPaymentResponse;
import com.gme.pay.payment.web.dto.CpmGenerateRequest;
import com.gme.pay.payment.web.dto.CpmGenerateResponse;
import com.gme.pay.payment.web.dto.MpmPaymentRequest;
import com.gme.pay.payment.web.dto.MpmPaymentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;

/**
 * REST controller exposing the Payment Executor API surface (API-05).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /v1/payments          — Fixed MPM payment execution
 *   <li>POST /v1/payments/cpm/generate — CPM QR token generation
 *   <li>POST /v1/payments/{id}/cancel  — Same-day cancellation
 * </ul>
 */
@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    private static final long IDEMPOTENCY_RETENTION_HOURS = 24L;

    private final PaymentOrchestrator orchestrator;
    private final PartnerConfigClient partnerConfigClient;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    /**
     * Constructor injection. {@code partnerConfigClient} resolves the partner type from
     * config-registry (replacing the old X-Partner-Type header stub); the idempotency
     * repository + object mapper back the {@code Idempotency-Key} replay on POST /v1/payments.
     */
    public PaymentController(PaymentOrchestrator orchestrator,
                             PartnerConfigClient partnerConfigClient,
                             IdempotencyRecordRepository idempotencyRepository,
                             ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.partnerConfigClient = partnerConfigClient;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * POST /v1/payments — execute a Fixed MPM payment.
     *
     * <p>Idempotency (API-05 §3.4): when an {@code Idempotency-Key} header is present, a
     * retried request for the same (partner, key) replays the recorded response instead of
     * re-executing the payment.
     *
     * <p>Partner type is resolved from config-registry by the partner code ({@code X-Partner-Code}
     * header — the gateway sets it from the authenticated partner). When no code is supplied or
     * config-registry cannot resolve it, we fall back to the {@code X-Partner-Type} header so
     * dev/test callers keep working. The numeric {@code X-Partner-Id} continues to drive
     * transaction-mgmt + prefunding.
     */
    @PostMapping
    public ResponseEntity<MpmPaymentResponse> executeMpmPayment(
            @RequestBody MpmPaymentRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Partner-Id", defaultValue = "1") long partnerId,
            @RequestHeader(value = "X-Partner-Code", required = false) String partnerCode,
            @RequestHeader(value = "X-Partner-Type", defaultValue = "OVERSEAS") String partnerTypeHeader) {

        req.validate();

        // Idempotency replay: a recorded outcome for (partner, key) is returned verbatim.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyRecordEntity> existing =
                    idempotencyRepository.findByPartnerIdAndIdempotencyKey(partnerId, idempotencyKey);
            if (existing.isPresent() && existing.get().getResponseBody() != null) {
                log.info("idempotent replay for partner={} key={}", partnerId, idempotencyKey);
                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(readSnapshot(existing.get().getResponseBody()));
            }
        }

        PartnerType partnerType = resolvePartnerType(partnerCode, partnerTypeHeader);

        MpmPaymentCommand cmd = new MpmPaymentCommand(
                partnerId,
                req.quoteId(),
                req.merchantQr(),
                req.schemeId(),
                req.direction(),
                req.customerRef(),
                req.partnerTxnRef(),
                partnerCode
        );

        PaymentResult result = orchestrator.executeMpm(cmd, partnerType);
        MpmPaymentResponse response = toResponse(result);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            recordIdempotencyOutcome(partnerId, idempotencyKey, req, result, response);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /v1/payments/cpm/generate — execute a CPM (Consumer-Presented Mode) payment.
     *
     * <p>For CPM the customer presents a token-QR on their device; the merchant's terminal
     * scans it and POSTs here to authorise and capture. This delegates to the orchestrator
     * which calls the scheme-adapter-zeropay /cpm endpoint.</p>
     *
     * <p>The {@code X-Partner-Type} header controls prefunding: OVERSEAS deducts from
     * the prefunding pool, LOCAL (default for CPM) does not.</p>
     */
    @PostMapping("/cpm/generate")
    public ResponseEntity<CpmGenerateResponse> generateCpmToken(
            @RequestBody CpmGenerateRequest req,
            @RequestHeader(value = "X-Partner-Id", defaultValue = "1") long partnerId,
            @RequestHeader(value = "X-Partner-Type", defaultValue = "LOCAL") String partnerTypeHeader) {

        req.validate();

        PartnerType partnerType = PartnerType.valueOf(partnerTypeHeader.toUpperCase());

        // For CPM the collectionAmount == payoutAmount (KRW domestic default)
        BigDecimal collectionAmount = new BigDecimal(req.collectionAmount());

        CpmPaymentCommand cmd = new CpmPaymentCommand(
                partnerId,
                req.partnerTxnRef(),
                req.schemeId(),
                req.quoteId(),           // quoteId field re-used as the cpmToken for CPM
                "UNKNOWN",               // merchantId unknown until scheme decode
                collectionAmount,
                req.collectionCurrency(),
                collectionAmount,
                req.collectionCurrency(),
                null                     // no USD prefunding amount for LOCAL
        );

        PaymentResult result = orchestrator.executeCpm(cmd, partnerType);

        CpmGenerateResponse response = new CpmGenerateResponse(
                result.paymentId(),
                result.schemeTxnId(),     // schemeTxnRef returned as the "qr_token" for CPM
                result.approvedAt(),
                req.schemeId()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /v1/payments/{id}/cancel — cancel a same-day approved payment.
     *
     * <p>Only APPROVED or PENDING payments on the same calendar day (KST) may be cancelled.
     * For OVERSEAS partners the prefunding deduction is reversed.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<CancelPaymentResponse> cancelPayment(
            @PathVariable("id") String paymentId,
            @RequestBody(required = false) CancelPaymentRequest req,
            @RequestHeader(value = "X-Partner-Id", defaultValue = "1") long partnerId,
            @RequestHeader(value = "X-Partner-Type", defaultValue = "OVERSEAS") String partnerTypeHeader,
            @RequestHeader(value = "X-Txn-Ref", required = false) String txnRef,
            @RequestHeader(value = "X-Scheme-Txn-Ref", required = false) String schemeTxnRef) {

        PartnerType partnerType = PartnerType.valueOf(partnerTypeHeader.toUpperCase());
        String reason = (req != null && req.reason() != null) ? req.reason() : "PARTNER_INITIATED";
        String resolvedTxnRef = txnRef != null ? txnRef : paymentId;
        String resolvedSchemeTxnRef = schemeTxnRef != null ? schemeTxnRef : paymentId;

        CancelResult result = orchestrator.cancelPayment(
                paymentId, resolvedSchemeTxnRef, partnerType, partnerId, resolvedTxnRef, reason);

        return ResponseEntity.ok(new CancelPaymentResponse(
                result.paymentId(),
                "cancelled",
                result.cancelledAt(),
                result.prefundReturnedUsd()
        ));
    }

    // ---- partner-type resolution ----

    /**
     * Resolve the partner type from config-registry by partner code; fall back to the
     * {@code X-Partner-Type} header when no code is supplied or the lookup fails (fail-open
     * so a config-registry outage degrades to the header rather than rejecting payments).
     */
    private PartnerType resolvePartnerType(String partnerCode, String headerFallback) {
        if (partnerCode != null && !partnerCode.isBlank()) {
            try {
                PartnerConfigClient.PartnerConfigView cfg = partnerConfigClient.loadPartner(partnerCode);
                if (cfg != null && cfg.type() != null && !cfg.type().isBlank()) {
                    return PartnerType.valueOf(cfg.type().toUpperCase());
                }
            } catch (RuntimeException e) {
                log.warn("partner-type resolution from config-registry failed for code={}; "
                        + "falling back to X-Partner-Type header: {}", partnerCode, e.getMessage());
            }
        }
        return PartnerType.valueOf(headerFallback.toUpperCase());
    }

    // ---- idempotency ----

    private void recordIdempotencyOutcome(long partnerId, String idempotencyKey,
                                          MpmPaymentRequest req, PaymentResult result,
                                          MpmPaymentResponse response) {
        try {
            String requestHash = sha256Hex(objectMapper.writeValueAsString(req));
            IdempotencyRecordEntity record = idempotencyRepository
                    .findByPartnerIdAndIdempotencyKey(partnerId, idempotencyKey)
                    .orElseGet(() -> new IdempotencyRecordEntity(
                            partnerId, idempotencyKey, requestHash, Instant.now()));
            record.setTxnRef(result.paymentId());
            record.recordOutcome(result.status(), objectMapper.writeValueAsString(response));
            record.setExpiresAt(Instant.now().plus(IDEMPOTENCY_RETENTION_HOURS, ChronoUnit.HOURS));
            idempotencyRepository.save(record);
        } catch (Exception e) {
            // The payment already executed; a failure to persist the replay snapshot must not
            // fail the response. The DB unique constraint still guards against double-execution
            // on a genuinely concurrent retry.
            log.warn("failed to record idempotency outcome for partner={} key={}: {}",
                    partnerId, idempotencyKey, e.getMessage());
        }
    }

    private MpmPaymentResponse readSnapshot(String body) {
        try {
            return objectMapper.readValue(body, MpmPaymentResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt idempotency response snapshot", e);
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ---- mapping ----

    private static MpmPaymentResponse toResponse(PaymentResult r) {
        return new MpmPaymentResponse(
                r.paymentId(),
                r.status().name().toLowerCase(),
                r.schemeTxnId(),
                r.merchantName(),
                r.merchantId(),
                r.targetPayout(),
                r.payoutCurrency(),
                r.offerRate(),
                r.collectionAmount(),
                r.collectionCurrency(),
                r.serviceCharge(),
                r.serviceChargeCurrency(),
                r.prefundDeductedUsd(),
                r.partnerTxnRef(),
                r.createdAt(),
                r.approvedAt()
        );
    }
}
