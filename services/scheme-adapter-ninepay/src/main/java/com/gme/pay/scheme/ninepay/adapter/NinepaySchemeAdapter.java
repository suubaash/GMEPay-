package com.gme.pay.scheme.ninepay.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.scheme.ninepay.client.NinepayApiClient;
import com.gme.pay.scheme.ninepay.client.NinepayErrorException;
import com.gme.pay.scheme.ninepay.client.NinepayTransportException;
import com.gme.pay.scheme.ninepay.dto.BalanceResponse;
import com.gme.pay.scheme.ninepay.dto.DecodeQrResponse;
import com.gme.pay.scheme.ninepay.dto.IpnAck;
import com.gme.pay.scheme.ninepay.dto.IpnRequest;
import com.gme.pay.scheme.ninepay.dto.PayoutRequest;
import com.gme.pay.scheme.ninepay.dto.PayoutResponse;
import com.gme.pay.scheme.ninepay.persistence.IpnRejectReason;
import com.gme.pay.scheme.ninepay.persistence.NpIpnEventEntity;
import com.gme.pay.scheme.ninepay.persistence.NpIpnEventRepository;
import com.gme.pay.scheme.ninepay.persistence.NpPayoutEntity;
import com.gme.pay.scheme.ninepay.persistence.NpPayoutRepository;
import com.gme.pay.scheme.ninepay.sign.NinepaySigner;
import com.gme.pay.scheme.ninepay.status.NinepayStatusMapper;
import com.gme.pay.scheme.ninepay.status.PayoutStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Translates the hub's canonical {@code /scheme/...} payout calls into the 9Pay
 * disbursement API and back, and applies inbound IPN pushes to the local payout registry.
 *
 * <p><b>Idempotency / anti-double-payout spine</b> (9Pay has NO cancel API):</p>
 * <ol>
 *   <li>The {@code np_payouts} row (UNIQUE {@code request_id}) is persisted BEFORE the
 *       wire call; a hub replay with the same {@code request_id} returns the stored state
 *       (refreshed by a lookup poll when non-final) instead of re-submitting.</li>
 *   <li>9Pay error <b>1062</b> (duplicate request_id) = the transfer already exists at
 *       9Pay → resolve via {@code transfer/info}, never resubmit.</li>
 *   <li>An ambiguous transport failure (timeout/5xx) → ALWAYS poll {@code transfer/info}
 *       with the original request_id first; only if 9Pay confirms "not exists"
 *       (1005/1021) is a resubmission with the same request_id safe — and even then this
 *       adapter leaves the retry decision to the hub (status {@code UNKNOWN}).</li>
 * </ol>
 */
@Service
public class NinepaySchemeAdapter {

    private static final Logger log = LoggerFactory.getLogger(NinepaySchemeAdapter.class);

    /** 9Pay minimum transfer amount, integer VND. */
    static final long MIN_AMOUNT_VND = 2_000L;
    /** 9Pay content rule: unaccented Vietnamese, letters+digits (spaces allowed), no special chars. */
    private static final Pattern CONTENT_PATTERN = Pattern.compile("^[A-Za-z0-9 ]{1,150}$");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final NinepayApiClient client;
    private final NinepaySigner signer;
    private final NpPayoutRepository payouts;
    private final NpIpnEventRepository ipnEvents;
    private final ObjectMapper mapper;
    /** T5-4 IPN replay / staleness / monotonicity gate. */
    private final NinepayIpnReplayGuard replayGuard;

    public NinepaySchemeAdapter(NinepayApiClient client, NinepaySigner signer,
                                NpPayoutRepository payouts, NpIpnEventRepository ipnEvents,
                                ObjectMapper mapper, NinepayIpnReplayGuard replayGuard) {
        this.client = client;
        this.signer = signer;
        this.payouts = payouts;
        this.ipnEvents = ipnEvents;
        this.mapper = mapper;
        this.replayGuard = Objects.requireNonNull(replayGuard);
    }

    // -------------------------------------------------------------------------
    // POST /scheme/payout
    // -------------------------------------------------------------------------

    /** Submits (or idempotently replays) a payout. See class Javadoc for the retry rules. */
    public PayoutResponse submitPayout(PayoutRequest req) {
        validate(req);

        // Idempotent replay: an existing row means this request_id was already driven to
        // 9Pay (or at least persisted pre-wire). Never re-submit; refresh if non-final.
        Optional<NpPayoutEntity> existing = payouts.findByRequestId(req.requestId());
        if (existing.isPresent()) {
            return refreshIfNonFinal(existing.get());
        }

        NpPayoutEntity payout = new NpPayoutEntity(
                req.requestId(), req.bankNo(), req.accountNo(), req.accountType(),
                req.accountName(), req.amountVnd(), req.content(), req.senderUid());
        try {
            payout = payouts.save(payout);
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate — another thread/instance won the unique request_id race.
            NpPayoutEntity winner = payouts.findByRequestId(req.requestId())
                    .orElseThrow(() -> new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                            "concurrent duplicate request_id " + req.requestId()));
            return refreshIfNonFinal(winner);
        }

        try {
            NinepayApiClient.TransferResult result = client.transfer(new NinepayApiClient.TransferCommand(
                    req.requestId(), req.bankNo(), req.accountNo(), req.accountType(),
                    req.accountName(), req.amountVnd(), req.content(),
                    req.senderName(), req.senderId(), req.recipientId(), req.transferPurpose(),
                    req.qrStr(), req.uCountry(), req.senderUid()));
            payout.recordSchemeAccept(result.transactionId(),
                    NinepayStatusMapper.fromSchemeStatus(result.status()),
                    result.fee(), result.transferAmount(), result.createdAt(), result.message());
            return toResponse(payouts.save(payout), null);

        } catch (NinepayErrorException e) {
            if (e.isDuplicateRequestId()) {
                // 1062: 9Pay already holds this request_id (e.g. a previous attempt whose
                // response we lost) — the transfer exists; adopt its state via lookup.
                log.warn("9Pay 1062 duplicate request_id {} — polling transfer/info", req.requestId());
                return pollAndApply(payout, null);
            }
            if (e.isNotFound()) {
                // Defensive: not expected on submit; treat as ambiguous.
                return markUnknown(payout, e.getMessage());
            }
            // Definitive business rejection (1008 params, 1024 balance, 1041/1042 account,
            // 2071-2073 limits, ...): the transfer was NOT created. Mark FAILED.
            log.warn("9Pay rejected payout {}: {}", req.requestId(), e.getMessage());
            payout.recordStatus(PayoutStatus.FAILED);
            payout.recordIpn(null, PayoutStatus.FAILED, null, null, null, e.getMessage());
            return toResponse(payouts.save(payout), e.code());

        } catch (NinepayTransportException e) {
            // AMBIGUOUS: 9Pay may have executed the transfer. Poll before anything else;
            // never resubmit from here.
            log.warn("9Pay transport failure for payout {} — polling transfer/info before any retry: {}",
                    req.requestId(), e.getMessage());
            return pollAndApply(payout, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // GET /scheme/payout/{requestId}
    // -------------------------------------------------------------------------

    /** Returns the payout state, refreshing non-final rows via a transfer/info poll. */
    public PayoutResponse getPayout(String requestId) {
        NpPayoutEntity payout = payouts.findByRequestId(requestId)
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND,
                        "no 9Pay payout with request_id " + requestId));
        return refreshIfNonFinal(payout);
    }

    // -------------------------------------------------------------------------
    // GET /scheme/balance
    // -------------------------------------------------------------------------

    /** Reads the prefunded 9Pay balance. */
    public BalanceResponse balance() {
        try {
            NinepayApiClient.BalanceResult result = client.balance(newLookupRequestId());
            return new BalanceResponse(result.responseTime(), result.available().stream()
                    .map(a -> new BalanceResponse.Amount(a.unit(), a.value()))
                    .toList());
        } catch (NinepayErrorException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "9Pay balance inquiry rejected: " + e.getMessage());
        } catch (NinepayTransportException e) {
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE, "9Pay balance inquiry failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // POST /scheme/decode-qr
    // -------------------------------------------------------------------------

    /** Decodes a VIETQR/VNPAY QR payload via 9Pay. */
    public DecodeQrResponse decodeQr(String qr) {
        if (qr == null || qr.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "decode-qr: qr is required");
        }
        if (qr.length() > 1000) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "decode-qr: qr exceeds 1000 chars");
        }
        try {
            NinepayApiClient.DecodedQr decoded = client.decodeQr(newLookupRequestId(), qr);
            return new DecodeQrResponse(decoded.type(), decoded.bankNo(), decoded.accountNumber(),
                    decoded.amountVnd(), decoded.accountName(), decoded.city(),
                    decoded.description(), decoded.service());
        } catch (NinepayErrorException e) {
            // 1106 = invalid/unsupported QR — a caller-input problem.
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "9Pay decode-qr rejected: " + e.getMessage());
        } catch (NinepayTransportException e) {
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE, "9Pay decode-qr failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // POST /scheme/ipn
    // -------------------------------------------------------------------------

    /**
     * Applies an inbound 9Pay IPN push. The event is persisted verbatim BEFORE any payout
     * mutation — including signature failures (persisted with {@code signature_valid=false},
     * then rejected with 400 so 9Pay redelivers) and unknown request_ids (audited, ACKed).
     *
     * <p>Code semantics: 000 → SUCCESS; 004 → still PENDING (bank retryable); 008 → HELD
     * pending merchant confirmation (ops path); <b>009 → REVERSED</b> — the bank reversed a
     * payment after the fact, flipping an already-SUCCESS payout; the persisted event row is
     * the reversal record and {@code reversed_at} is stamped on the payout.</p>
     *
     * <p><b>Replay protection (gap T5-4).</b> A valid signature is no longer sufficient to
     * mutate the payout: {@link NinepayIpnReplayGuard} must also agree that this is a NEW
     * event ({@code (request_id, trans_id, code)} not yet applied), that its signed
     * {@code created_at} is neither stale nor beyond the acceptance window, and that it does
     * not walk the payout backwards. A withheld event is still recorded (with its
     * {@code reject_reason}) and still ACKed — 9Pay must not keep retrying an event we
     * already have. The genuine 000 → 009 reversal sequence is explicitly preserved; see
     * the guard's Javadoc for why each rule is shaped the way it is.</p>
     */
    public IpnAck handleIpn(String rawBody) {
        IpnRequest ipn = parseIpn(rawBody);
        boolean signatureValid = verifyIpnSignature(ipn);

        // Audit first — deliberately not in one transaction with the payout update, so the
        // event survives a rejection below.
        NpIpnEventEntity event = new NpIpnEventEntity(
                ipn.requestId() == null ? "" : ipn.requestId(),
                ipn.transId(), ipn.code(), ipn.status(), rawBody, signatureValid);
        event.setSchemeCreatedAt(ipn.createdAt());

        if (!signatureValid) {
            event.markRejected(IpnRejectReason.SIGNATURE_INVALID);
            ipnEvents.save(event);
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "9Pay IPN signature failed verification for request_id " + ipn.requestId());
        }

        Optional<NpPayoutEntity> found = ipn.requestId() == null
                ? Optional.empty()
                : payouts.findByRequestId(ipn.requestId());
        if (found.isEmpty()) {
            log.warn("9Pay IPN for unknown request_id {} (code {}) — audited, no payout updated",
                    ipn.requestId(), ipn.code());
            event.markRejected(IpnRejectReason.UNKNOWN_REQUEST_ID);
            ipnEvents.save(event);
            return new IpnAck("RECEIVED", null);
        }

        NpPayoutEntity payout = found.get();
        PayoutStatus mapped = NinepayStatusMapper.fromIpn(ipn.code(), ipn.status());

        // T5-4: replay / staleness / monotonicity gate, BEFORE any mutation.
        String eventKey = replayGuard.eventKey(ipn.requestId(), ipn.transId(), ipn.code());
        Optional<IpnRejectReason> reject =
                replayGuard.evaluate(ipn, eventKey, mapped, payout.getStatus());
        if (reject.isPresent()) {
            event.markRejected(reject.get());
            ipnEvents.save(event);
            // ACK with the payout's CURRENT status: nothing changed, and 9Pay must not
            // interpret this as a delivery failure worth retrying.
            return new IpnAck("RECEIVED", payout.getStatus().name());
        }

        if (mapped == PayoutStatus.REVERSED && payout.getStatus() != PayoutStatus.SUCCESS) {
            log.warn("9Pay IPN 009 reversal for {} arrived while status={} (expected SUCCESS)",
                    ipn.requestId(), payout.getStatus());
        }

        // Claim the event identity. The UNIQUE constraint on event_key (V002) is the real
        // guard under concurrency: if a racing redelivery claimed it first this save fails
        // and we treat the push as the duplicate it is, leaving the payout untouched.
        event.markApplied(eventKey);
        try {
            ipnEvents.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            log.warn("9Pay IPN concurrent replay ignored: request_id={} trans_id={} code={} was "
                    + "applied by another delivery", ipn.requestId(), ipn.transId(), ipn.code());
            return new IpnAck("RECEIVED", payout.getStatus().name());
        }

        payout.recordIpn(ipn.code(), mapped, ipn.transId(), ipn.fee(), ipn.transferAmount(),
                ipn.message());
        payouts.save(payout);
        return new IpnAck("RECEIVED", mapped.name());
    }

    // -------------------------------------------------------------------------
    // internals
    // -------------------------------------------------------------------------

    private void validate(PayoutRequest req) {
        if (req.requestId() == null || req.requestId().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "payout: requestId is required");
        }
        if (req.requestId().length() > 50) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "payout: requestId exceeds 50 chars");
        }
        if (req.bankNo() == null || req.bankNo().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "payout: bankNo is required");
        }
        if (req.accountNo() == null || req.accountNo().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "payout: accountNo is required");
        }
        if (req.accountType() != 0 && req.accountType() != 1) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "payout: accountType must be 0 (account) or 1 (card)");
        }
        if (req.accountName() == null || req.accountName().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "payout: accountName is required");
        }
        if (req.amountVnd() < MIN_AMOUNT_VND) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "payout: amountVnd must be an integer >= " + MIN_AMOUNT_VND + " VND (got "
                            + req.amountVnd() + ")");
        }
        if (req.content() == null || !CONTENT_PATTERN.matcher(req.content()).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "payout: content must be 1-150 unaccented letters/digits/spaces "
                            + "(9Pay rejects special characters)");
        }
    }

    /** Replay/read path: non-final rows are refreshed by a lookup poll; final rows returned as-is. */
    private PayoutResponse refreshIfNonFinal(NpPayoutEntity payout) {
        if (payout.getStatus().isFinal() || payout.getStatus() == PayoutStatus.HELD) {
            // HELD resolves via IPN/ops, not via transfer/info polling.
            return toResponse(payout, null);
        }
        return pollAndApply(payout, null);
    }

    /**
     * The timeout/duplicate resolution gate: polls {@code transfer/info} by the ORIGINAL
     * request_id and applies the result. "Not exists" (1005/1021) or a failed poll leaves
     * the row {@code UNKNOWN}/{@code SUBMITTED} — the hub decides about retries; this
     * adapter never resubmits.
     */
    private PayoutResponse pollAndApply(NpPayoutEntity payout, String ambiguityReason) {
        try {
            NinepayApiClient.TransferInfo info =
                    client.transferInfoByRequestId(newLookupRequestId(), payout.getRequestId());
            payout.recordSchemeAccept(info.transactionId(),
                    NinepayStatusMapper.fromSchemeStatus(info.status()),
                    null, info.transferAmount(), info.createdAt(), payout.getSchemeMessage());
            return toResponse(payouts.save(payout), null);
        } catch (NinepayErrorException e) {
            if (e.isNotFound()) {
                // 9Pay confirms it never accepted this request_id. Safe for the HUB to
                // retry with the same request_id; we surface UNKNOWN + keep the row.
                log.warn("9Pay transfer/info: request_id {} not found — payout unconfirmed{}",
                        payout.getRequestId(),
                        ambiguityReason == null ? "" : " (submit ambiguity: " + ambiguityReason + ")");
                return markUnknown(payout, "9Pay has no transaction for this request_id");
            }
            return markUnknown(payout, e.getMessage());
        } catch (NinepayTransportException e) {
            // Prolonged outage: cannot confirm either way (spec says escalate to 9Pay ops).
            return markUnknown(payout, e.getMessage());
        }
    }

    private PayoutResponse markUnknown(NpPayoutEntity payout, String reason) {
        log.warn("9Pay payout {} left UNKNOWN: {}", payout.getRequestId(), reason);
        payout.recordStatus(PayoutStatus.UNKNOWN);
        return toResponse(payouts.save(payout), null);
    }

    private PayoutResponse toResponse(NpPayoutEntity payout, String errorCode) {
        return new PayoutResponse(
                payout.getRequestId(),
                payout.getTransactionId(),
                payout.getStatus().name(),
                payout.getAmountVnd().longValueExact(),
                payout.getFeeVnd() == null ? null : payout.getFeeVnd().longValueExact(),
                payout.getTransferAmountVnd() == null ? null : payout.getTransferAmountVnd().longValueExact(),
                payout.getSchemeMessage(),
                errorCode);
    }

    private IpnRequest parseIpn(String rawBody) {
        try {
            IpnRequest ipn = mapper.readValue(rawBody, IpnRequest.class);
            if (ipn == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "IPN: empty body");
            }
            return ipn;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "IPN: unparseable body: " + e.getMessage());
        }
    }

    /**
     * Verifies the IPN signature over the documented signed string
     * ({@code message}/{@code approved_at}/{@code code} excluded). A missing/unloadable
     * 9Pay public key counts as NOT verified (never silently trust).
     */
    private boolean verifyIpnSignature(IpnRequest ipn) {
        String canonical = NinepaySigner.canonical(
                ipn.requestId(), ipn.partnerId(), ipn.transId(), ipn.requestAmount(), ipn.fee(),
                ipn.transferAmount(), ipn.type(), ipn.status(), ipn.createdAt());
        try {
            return signer.verify(canonical, ipn.signature());
        } catch (Exception e) {
            log.error("9Pay IPN signature verification unavailable: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Fresh unique request_id for lookup/balance calls (every 9Pay request needs its own),
     * following the recommended {@code PartnerID + 9P + YYYYMMDD + UniqueId} format,
     * truncated to the 50-char limit.
     */
    private String newLookupRequestId() {
        String id = client.partnerId() + "9P"
                + LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(YYYYMMDD)
                + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return id.length() <= 50 ? id : id.substring(0, 50);
    }
}
