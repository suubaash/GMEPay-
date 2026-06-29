package com.gme.pay.settlement.batch;

import com.gme.pay.events.EventPublisher;
import com.gme.pay.money.BookedAmount;
import com.gme.pay.settlement.booking.SettlementBookingService;
import com.gme.pay.settlement.builder.AbstractZeroPayFileBuilder;
import com.gme.pay.settlement.builder.BuildContext;
import com.gme.pay.settlement.builder.DetailBuildContext;
import com.gme.pay.settlement.builder.ZP0061RequestBuilder;
import com.gme.pay.settlement.builder.ZP0065PaymentDetailBuilder;
import com.gme.pay.settlement.builder.ZP0066RefundDetailBuilder;
import com.gme.pay.settlement.model.TransactionRecord;
import com.gme.pay.settlement.outbox.OutboxAppender;
import com.gme.pay.settlement.outbox.SettlementCompletedEvent;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import com.gme.pay.settlement.persistence.SettlementLineEntity;
import com.gme.pay.settlement.persistence.SettlementLineRepository;
import com.gme.pay.settlement.port.PartnerConfigPort;
import com.gme.pay.settlement.port.TransactionQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orchestrates one outbound settlement-file window run end-to-end, in a single transaction so the batch,
 * its lines, and the {@code settlement.completed} outbox row commit atomically:
 * <ol>
 *   <li>idempotent {@link SettlementBatchFactory#createOrGet} (no-op if the batch already advanced past PENDING);</li>
 *   <li>pull APPROVED transactions for the KST business date, group by <b>(merchant, settlement type)</b>;</li>
 *   <li>per group: resolve the partner's rounding mode, book the per-partner net under Addendum-001
 *       ({@link SettlementBookingService}), persist a line per txn;</li>
 *   <li>build the ZP0061/0063 file (checksum + record count), move PENDING → GENERATED;</li>
 *   <li>append {@link SettlementCompletedEvent} to the outbox.</li>
 * </ol>
 * On any failure the whole transaction rolls back atomically — no partial/stuck batch is left, and the
 * next run starts clean (the failure is logged; persisting an ERROR row for ops visibility in a separate
 * transaction is a follow-up).
 *
 * <p><b>Data-correctness (closed):</b>
 * <ul>
 *   <li><b>Merchant fee</b> — {@code merchantFeeRate} is snapshotted on the txn at creation (V005) and
 *       carried through the REST projection, so NET fee = Σ(payout × rate) is real (no longer 0).</li>
 *   <li><b>Window cutoff</b> — txns are filtered by their scheme {@code approvedAt} against the window's
 *       cutoff time ({@code settlement.morning-cutoff} / {@code settlement.afternoon-cutoff}, KST), so
 *       a txn approved after the morning cutoff rolls into the afternoon batch instead of being
 *       mis-settled in the morning file. A txn with no timestamp fails OPEN (is included).</li>
 *   <li><b>Refund clawback</b> — REFUNDED txns are netted out (net = gross − fee − refund) <em>only</em>
 *       when their original payment was already settled in a prior batch (a positive settlement_line for
 *       the txn exists) and not already clawed back (no negative line); a same-day approve→refund that
 *       was never paid out correctly nets to zero. Refunds are reported as refund_count/refund_amount.</li>
 * </ul>
 * Per-partner grouping keys off {@code merchantId} as the partner code. ZeroPay settles in KRW, so amounts
 * are booked at KRW scale under the partner's mode.
 *
 * <p><b>Remaining scope (documented, not faked):</b> the txn fetch is keyed by <em>creation</em> date, so
 * a refund of a payment created on a <em>prior</em> day (e.g. settled yesterday, refunded today) is not
 * picked up until a refund-date (rather than creation-date) query is added — a follow-up.
 */
@Service
public class SettlementBatchJobService {

    private static final Logger log = LoggerFactory.getLogger(SettlementBatchJobService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String SETTLE_CCY = "KRW";   // ZeroPay settles in KRW (scale 0)
    private static final char GROUP_SEP = '|';   // delimiter unlikely to appear in a merchant id

    private final TransactionQueryPort txnPort;
    private final PartnerConfigPort partnerConfigPort;
    private final SettlementBookingService booking;
    private final SettlementBatchFactory batchFactory;
    private final SettlementBatchRepository batchRepo;
    private final SettlementLineRepository lineRepo;
    private final EventPublisher outbox;

    /** Window cutoff times (KST). null = no cutoff for that window (include all). */
    private final LocalTime morningCutoff;
    private final LocalTime afternoonCutoff;

    public SettlementBatchJobService(TransactionQueryPort txnPort,
                                     PartnerConfigPort partnerConfigPort,
                                     SettlementBookingService booking,
                                     SettlementBatchFactory batchFactory,
                                     SettlementBatchRepository batchRepo,
                                     SettlementLineRepository lineRepo,
                                     @Qualifier(OutboxAppender.BEAN_NAME) EventPublisher outbox,
                                     @Value("${settlement.morning-cutoff:04:30}") String morningCutoff,
                                     @Value("${settlement.afternoon-cutoff:13:30}") String afternoonCutoff) {
        this.txnPort = txnPort;
        this.partnerConfigPort = partnerConfigPort;
        this.booking = booking;
        this.batchFactory = batchFactory;
        this.batchRepo = batchRepo;
        this.lineRepo = lineRepo;
        this.outbox = outbox;
        this.morningCutoff = parseCutoff(morningCutoff, "morning-cutoff");
        this.afternoonCutoff = parseCutoff(afternoonCutoff, "afternoon-cutoff");
    }

    /** @param fileType "ZP0061" (morning) or "ZP0063" (afternoon); @param window e.g. "MORNING"/"AFTERNOON". */
    @Transactional
    public SettlementBatchEntity runWindow(String fileType, String window) {
        requireRequestFile(fileType);
        LocalDate date = LocalDate.now(KST);
        SettlementBatchEntity batch = batchFactory.createOrGet(fileType, date, window);

        // Generate only for a fresh PENDING batch. A batch that already advanced (GENERATED and beyond)
        // is a no-op — never re-delete its lines or re-emit the event.
        if (!SettlementBatchStatus.PENDING.name().equals(batch.getStatus())) {
            log.info("settlement batch {} already at {} — idempotent no-op", batch.getBatchId(), batch.getStatus());
            return batch;
        }

        lineRepo.deleteByBatchId(batch.getBatchId());   // clean (re-)generation of this PENDING batch

        // Window cutoff: only settle txns whose scheme approval is at/before the window's cutoff time
        // (KST). A txn approved after the morning cutoff is left for the afternoon batch; a txn with no
        // approval timestamp fails OPEN (included) so we never silently drop settle-able volume.
        Instant cutoff = windowCutoff(date, window);

        List<TransactionRecord> txns = txnPort.findUnbatchedApproved(date).stream()
                .filter(TransactionRecord::isApproved)
                .filter(t -> withinCutoff(t, cutoff))
                .collect(Collectors.toList());

        // Refund clawback: a REFUNDED txn reduces the merchant's net ONLY if its original payment was
        // already settled in a prior batch (positive line exists) and has not already been clawed back
        // (no negative line). A same-day approve→refund that was never paid out has no prior line and
        // correctly nets to zero — subtracting it would claw back money the merchant never received.
        List<TransactionRecord> refunds = txnPort.findUnbatchedRefunded(date).stream()
                .filter(t -> withinCutoff(t, cutoff))
                .filter(this::isClawbackEligible)
                .collect(Collectors.toList());

        // Group both payments and refunds by (merchantId, settlementType) so a merchant with both domestic
        // NET and international GROSS txns yields one row per type — never a blended row.
        Map<String, List<TransactionRecord>> paymentsByKey = txns.stream()
                .collect(Collectors.groupingBy(SettlementBatchJobService::groupKey,
                        LinkedHashMap::new, Collectors.toList()));
        Map<String, List<TransactionRecord>> refundsByKey = refunds.stream()
                .collect(Collectors.groupingBy(SettlementBatchJobService::groupKey,
                        LinkedHashMap::new, Collectors.toList()));

        // Union of keys: payment groups first (stable ordering), then any refund-only merchants
        // (a prior-settled payment refunded in a window with no fresh payments → a negative claw-back row).
        LinkedHashSet<String> keys = new LinkedHashSet<>(paymentsByKey.keySet());
        keys.addAll(refundsByKey.keySet());

        List<BuildContext.MerchantRow> rows = new ArrayList<>();
        BigDecimal netTotal = BigDecimal.ZERO;
        BigDecimal feeTotal = BigDecimal.ZERO;
        BigDecimal residualTotal = BigDecimal.ZERO;

        for (String key : keys) {
            List<TransactionRecord> group = paymentsByKey.getOrDefault(key, List.of());
            List<TransactionRecord> refundGroup = refundsByKey.getOrDefault(key, List.of());
            // identity (merchantId, type) from whichever side is present (groups are homogeneous in type).
            TransactionRecord exemplar = !group.isEmpty() ? group.get(0) : refundGroup.get(0);
            String merchantId = exemplar.merchantId();
            char type = exemplar.settlementType();
            PartnerConfigPort.PartnerSettlementConfig cfg = partnerConfigPort.resolve(merchantId);

            // Round each payout to whole KRW ONCE, then sum (Σ round) — NOT round(Σ). This is the value
            // persisted per line and re-summed by the ZP0065/ZP0066 detail run, so the detail files tie out
            // to this summary exactly (round-then-sum == round-then-sum). KRW is integer by convention, so
            // for real data these are identical; the discipline makes the tie-out provable regardless.
            BigDecimal grossSum = group.stream()
                    .map(t -> krw(t.targetPayoutKrw())).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal refundSum = refundGroup.stream()
                    .map(t -> krw(t.targetPayoutKrw())).reduce(BigDecimal.ZERO, BigDecimal::add);
            int refundCount = refundGroup.size();

            BigDecimal feeSumPrecise = BigDecimal.ZERO;
            if (type == 'N') {
                for (TransactionRecord t : group) {
                    feeSumPrecise = feeSumPrecise.add(krw(t.targetPayoutKrw()).multiply(nz(t.merchantFeeRate())));
                }
            }
            // net = gross − fee − refund (NET); gross − refund (GROSS). Refunds claw back the merchant payout.
            BigDecimal precise = ((type == 'N') ? grossSum.subtract(feeSumPrecise) : grossSum).subtract(refundSum);
            BookedAmount booked = booking.book(SETTLE_CCY, cfg.mode(), precise, type);
            // merchant_fee_total keeps the file balanced (gross = net + fee + refund); it equals
            // trueFee + rounding residual. The residual is also carried to rounding_residual for the
            // (pending) REVENUE_ROUNDING post — wire only ONE before treating it as pure fee revenue.
            BigDecimal fileFee = (type == 'N')
                    ? grossSum.subtract(booked.booked()).subtract(refundSum)
                    : BigDecimal.ZERO;

            if (refundSum.signum() > 0 && booked.booked().signum() < 0) {
                log.warn("settlement row {}|{}: refunds {} exceed gross {} → negative net {} (claw-back); "
                                + "verify scheme handling of negative settlement",
                        merchantId, type, refundSum, grossSum, booked.booked());
            }

            // One line per payment (positive whole-KRW amount) and one per clawed-back refund (negative); the
            // negative line is also the cross-window idempotency marker read by isClawbackEligible. Each line
            // snapshots the fields the ZP0065/ZP0066 detail files need (merchant, scheme ref, approval instant,
            // fee rate, type) so the detail run is built from the line alone — the authoritative settled record.
            for (TransactionRecord t : group) {
                SettlementLineEntity line = new SettlementLineEntity(
                        batch.getBatchId(), t.txnRef(), krw(t.targetPayoutKrw()), SETTLE_CCY, false);
                line.setSettlementType(String.valueOf(type));
                line.setSettlementRoundingMode(booked.mode().name());
                snapshotDetailFields(line, t);
                lineRepo.save(line);
            }
            for (TransactionRecord t : refundGroup) {
                SettlementLineEntity line = new SettlementLineEntity(
                        batch.getBatchId(), t.txnRef(), krw(t.targetPayoutKrw()).negate(), SETTLE_CCY, false);
                line.setSettlementType(String.valueOf(type));
                line.setSettlementRoundingMode(booked.mode().name());
                snapshotDetailFields(line, t);
                lineRepo.save(line);
            }

            rows.add(new BuildContext.MerchantRow(merchantId, group.size(), grossSum,
                    refundCount, refundSum, fileFee, booked.booked(), booked.residual(), booked.mode(), type));
            netTotal = netTotal.add(booked.booked());
            feeTotal = feeTotal.add(fileFee);
            residualTotal = residualTotal.add(booked.residual());
        }

        BuildContext ctx = new BuildContext(date.format(DateTimeFormatter.BASIC_ISO_DATE), 1, rows);
        AbstractZeroPayFileBuilder.BuiltFile file = new ZP0061RequestBuilder(fileType).build(ctx);

        batch.setSettleCurrency(SETTLE_CCY);
        batch.setNetSettlementAmount(netTotal);
        batch.setMerchantFeeTotal(feeTotal);
        batch.setRoundingResidual(residualTotal);
        batch.setFileChecksum(file.checksum());
        batch.setRecordCount(file.recordCount());
        batch.setTotalAmount(netTotal);
        batch.setTotalCurrency(SETTLE_CCY);
        transition(batch, SettlementBatchStatus.GENERATED);
        batchRepo.save(batch);

        outbox.publish(new SettlementCompletedEvent(
                batch.getBatchId(), fileType, window, date, null,
                netTotal, SETTLE_CCY, txns.size() + refunds.size(), file.checksum()));

        log.info("settlement batch {} GENERATED: {} row(s), net={} KRW, residual={}, checksum={}",
                batch.getBatchId(), rows.size(), netTotal, residualTotal, file.checksum());
        return batch;
    }

    /**
     * Generate a per-transaction DETAIL file (~22:00 KST): {@code ZP0065} payment detail (one row per
     * APPROVED payment) or {@code ZP0066} refund detail (one row per refund), so ZeroPay can reconcile the
     * detail against the day's ZP0061/ZP0063 summary. Mirrors {@link #runWindow}'s atomic skeleton (idempotent
     * createOrGet → build → persist batch → outbox event, all in one transaction) but is a SEPARATE entry
     * point because detail emission is per-txn, not the per-merchant booking/grouping/clawback of runWindow.
     *
     * <p><b>Sourced from the day's request-batch lines for tie-out.</b> Rows come from the ZP0061/ZP0063
     * {@code settlement_lines}: positive (payment) lines → ZP0065, negative (claw-back) lines → ZP0066,
     * each tagged with its request batch id ({@code settlement_batch_ref}). This makes ZP0065 SUM(payout)
     * equal the ZP0061+ZP0063 gross and ZP0066 equal the refunds the aggregate actually clawed back.
     *
     * <p><b>Reads, never writes, {@code settlement_lines}.</b> Those signed rows are the aggregate path's
     * clawback idempotency markers ({@code existsByTxnRefAndAmount…}); a detail-written line would poison
     * them (a ZP0066 negative line would make a later {@link #runWindow} treat a refund as already-clawed
     * and silently drop it). The detail run persists only the batch row + file checksum + event.
     *
     * <p><b>NOT TRANSMIT-READY</b> — see the builder javadocs for the remaining IDD/data gates (van_fee,
     * txn_time, final widths). This generates a structurally valid, tie-out-correct file + batch + event;
     * transmission to a live ZeroPay endpoint is gated on those gaps closing.
     *
     * @param fileType {@code "ZP0065"} or {@code "ZP0066"}
     */
    @Transactional
    public SettlementBatchEntity runDetailWindow(String fileType) {
        requireDetailFile(fileType);
        LocalDate date = LocalDate.now(KST);
        String window = "DETAIL";
        SettlementBatchEntity batch = batchFactory.createOrGet(fileType, date, window);

        if (!SettlementBatchStatus.PENDING.name().equals(batch.getStatus())) {
            log.info("settlement detail batch {} already at {} — idempotent no-op",
                    batch.getBatchId(), batch.getStatus());
            return batch;
        }

        String yyyymmdd = date.format(DateTimeFormatter.BASIC_ISO_DATE);

        // Build detail rows ENTIRELY from the day's REQUEST-batch settlement_lines — the authoritative
        // record of what was settled: positive lines = payments (ZP0065), negative lines = claw-backs
        // (ZP0066), each tagged with its request batch ref. Each line carries the snapshot fields (V008)
        // the detail file needs, so there is NO re-fetch of the live txn — the detail ties out to the
        // summary by construction and is immune to later txn status/date changes (a settled payment later
        // REVERSED, or a claw-back of a prior-day payment, still emits correctly). These lines are only
        // READ — the detail run writes none (a detail line would poison the aggregate clawback markers).
        boolean payments = "ZP0065".equals(fileType);
        List<DetailBuildContext.DetailRow> rows = new ArrayList<>();
        for (SettlementBatchEntity req : requestBatchesForDate(date)) {
            for (SettlementLineEntity line : lineRepo.findByBatchId(req.getBatchId())) {
                int sign = line.getAmount() == null ? 0 : line.getAmount().signum();
                if (payments ? sign <= 0 : sign >= 0) {
                    continue;   // ZP0065 wants payment lines (>0); ZP0066 wants claw-back lines (<0)
                }
                rows.add(new DetailBuildContext.DetailRow(toDetailTxn(line), req.getBatchId()));
            }
        }

        DetailBuildContext ctx = new DetailBuildContext(yyyymmdd, 1, rows);
        AbstractZeroPayFileBuilder.BuiltFile file = payments
                ? new ZP0065PaymentDetailBuilder().build(ctx)
                : new ZP0066RefundDetailBuilder().build(ctx);

        // The detail total ties to the request file's gross_amount column (ZP0065) / refund_amount column
        // (ZP0066) — NOT to net_settlement_amount (which is gross − fee − refund). Store it as the batch's
        // total only; leave net_settlement_amount unset so it is never read as a net figure.
        batch.setSettleCurrency(SETTLE_CCY);
        batch.setFileChecksum(file.checksum());
        batch.setRecordCount(file.recordCount());
        batch.setTotalAmount(file.trailerTotal());
        batch.setTotalCurrency(SETTLE_CCY);
        transition(batch, SettlementBatchStatus.GENERATED);
        batchRepo.save(batch);

        outbox.publish(new SettlementCompletedEvent(
                batch.getBatchId(), fileType, window, date, null,
                file.trailerTotal(), SETTLE_CCY, file.recordCount(), file.checksum()));

        log.info("settlement detail batch {} GENERATED: {} row(s), total={} KRW, checksum={}",
                batch.getBatchId(), file.recordCount(), file.trailerTotal(), file.checksum());
        return batch;
    }

    private static void transition(SettlementBatchEntity batch, SettlementBatchStatus to) {
        SettlementBatchStatus from;
        try {
            from = SettlementBatchStatus.valueOf(batch.getStatus());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateTransitionException(
                    "settlement batch " + batch.getBatchId() + " has no/unknown status '" + batch.getStatus()
                            + "' — cannot transition to " + to);
        }
        if (!from.canMoveTo(to)) {
            throw new IllegalStateTransitionException(
                    "illegal settlement batch transition " + from + " -> " + to + " for " + batch.getBatchId());
        }
        batch.setStatus(to.name());
    }

    private static void requireRequestFile(String fileType) {
        if (!"ZP0061".equals(fileType) && !"ZP0063".equals(fileType)) {
            throw new IllegalArgumentException(
                    "runWindow handles ZP0061/ZP0063 request files only, got: " + fileType);
        }
    }

    private static void requireDetailFile(String fileType) {
        if (!"ZP0065".equals(fileType) && !"ZP0066".equals(fileType)) {
            throw new IllegalArgumentException(
                    "runDetailWindow handles ZP0065/ZP0066 detail files only, got: " + fileType);
        }
    }

    /** The day's outbound REQUEST batches (ZP0061 morning, ZP0063 afternoon) that exist — the detail
     *  files are sourced from their settlement_lines, so they must be generated before the detail window. */
    private List<SettlementBatchEntity> requestBatchesForDate(LocalDate date) {
        List<SettlementBatchEntity> batches = new ArrayList<>(2);
        batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow("ZP0061", date, "MORNING")
                .ifPresent(batches::add);
        batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow("ZP0063", date, "AFTERNOON")
                .ifPresent(batches::add);
        if (batches.isEmpty()) {
            log.warn("no ZP0061/ZP0063 request batch for {} — detail file will be empty (request files "
                    + "must be generated before the detail window)", date);
        }
        return batches;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Whole-KRW (scale 0, HALF_UP), null-safe. KRW has no sub-unit; rounding once per txn keeps the
     *  per-line value, the summary total, and the detail file all summing the SAME integer. */
    private static BigDecimal krw(BigDecimal v) {
        return nz(v).setScale(0, RoundingMode.HALF_UP);
    }

    /** Snapshot the fields the ZP0065/ZP0066 detail files need onto the line (V008), so the detail run
     *  builds from the line alone — independent of the txn's later status/date. */
    private static void snapshotDetailFields(SettlementLineEntity line, TransactionRecord t) {
        line.setMerchantId(t.merchantId());
        line.setSchemeRef(t.schemeRef());
        line.setApprovedAt(t.completedAt() == null ? null : t.completedAt().toInstant());
        line.setMerchantFeeRate(nz(t.merchantFeeRate()));
    }

    /** Reconstruct the minimal {@link TransactionRecord} a detail builder needs from a settled line's
     *  snapshot. Amount is the line's absolute KRW (the builders apply the sign convention themselves). */
    private static TransactionRecord toDetailTxn(SettlementLineEntity line) {
        char type = (line.getSettlementType() == null || line.getSettlementType().isEmpty())
                ? 'G' : line.getSettlementType().charAt(0);
        OffsetDateTime approvedAt = line.getApprovedAt() == null
                ? null : line.getApprovedAt().atOffset(ZoneOffset.UTC);
        String status = nz(line.getAmount()).signum() >= 0 ? "APPROVED" : "REFUNDED";
        return new TransactionRecord(
                null, line.getTxnRef(), line.getSchemeRef(), line.getMerchantId(),
                nz(line.getAmount()).abs(), type, nz(line.getMerchantFeeRate()),
                status, approvedAt, null);
    }

    /** (merchantId, settlementType) grouping key — one settlement row per merchant per type. */
    private static String groupKey(TransactionRecord t) {
        return t.merchantId() + GROUP_SEP + t.settlementType();
    }

    /** The upper-bound instant for a window's settle-able txns, or null if no cutoff is configured. */
    private Instant windowCutoff(LocalDate date, String window) {
        LocalTime t = "MORNING".equalsIgnoreCase(window) ? morningCutoff
                : "AFTERNOON".equalsIgnoreCase(window) ? afternoonCutoff
                : null;
        return t == null ? null : date.atTime(t).atZone(KST).toInstant();
    }

    /**
     * Include a txn when there is no cutoff, or it has no approval timestamp (fail OPEN — never silently
     * drop settle-able volume), or it was approved at/before the cutoff.
     */
    private static boolean withinCutoff(TransactionRecord t, Instant cutoff) {
        if (cutoff == null || t.completedAt() == null) {
            return true;
        }
        return !t.completedAt().toInstant().isAfter(cutoff);
    }

    /**
     * A refund is clawed back only if its original payment was already settled in a prior batch (a
     * positive settlement_line exists) and it has not already been clawed back (no negative line). This
     * makes same-day approve→refund (never paid) net to zero and keeps the claw-back idempotent across
     * the morning/afternoon windows.
     */
    private boolean isClawbackEligible(TransactionRecord refund) {
        String ref = refund.txnRef();
        if (!lineRepo.existsByTxnRefAndAmountGreaterThan(ref, BigDecimal.ZERO)) {
            log.debug("refund {} has no prior settled payment line — nets to zero, not clawed back", ref);
            return false;
        }
        if (lineRepo.existsByTxnRefAndAmountLessThan(ref, BigDecimal.ZERO)) {
            log.debug("refund {} already clawed back in a prior batch — skipping (idempotent)", ref);
            return false;
        }
        return true;
    }

    /** Parse an "HH:mm" cutoff; blank/null → null (window cutoff disabled); malformed → null (logged). */
    private static LocalTime parseCutoff(String hhmm, String name) {
        if (hhmm == null || hhmm.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(hhmm.trim());
        } catch (DateTimeParseException e) {
            log.warn("invalid settlement window {} '{}' — cutoff disabled for that window", name, hhmm);
            return null;
        }
    }
}
