package com.gme.pay.settlement.web;

import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import com.gme.pay.settlement.persistence.SettlementLineEntity;
import com.gme.pay.settlement.persistence.SettlementLineRepository;
import com.gme.pay.settlement.transmission.SettlementTransmissionChannelRegistry;
import com.gme.pay.settlement.transmission.SettlementTransmissionChannelStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Read side of the <b>persisted</b> settlement tables (GAP T4-5).
 *
 * <p>Distinct from {@link SettlementService}, which recomputes per-merchant figures from unbatched
 * approved transactions for one date and touches neither table. Everything here comes from
 * {@code settlement_batches} / {@code settlement_lines}: per-batch detail, date-ranged lists, and the
 * partner-facing statement. Read-only — no method here mutates a batch, a line or a status. Partner
 * self-serve <em>writes</em> are an open product decision (T1-5) and are deliberately not here.
 */
@Service
@Transactional(readOnly = true)
public class SettlementBatchQueryService {

    /** Cap on a single statement/list window, so one call cannot stream a partner-decade into memory. */
    public static final int MAX_WINDOW_DAYS = 400;

    /** Window applied when a caller supplies neither {@code from} nor {@code to}. */
    public static final int DEFAULT_WINDOW_DAYS = 30;

    private final SettlementBatchRepository batchRepository;
    private final SettlementLineRepository lineRepository;
    private final SettlementTransmissionChannelRegistry channels;

    public SettlementBatchQueryService(SettlementBatchRepository batchRepository,
                                       SettlementLineRepository lineRepository,
                                       SettlementTransmissionChannelRegistry channels) {
        this.batchRepository = batchRepository;
        this.lineRepository = lineRepository;
        this.channels = channels;
    }

    /** The transmission channel board — whether any settlement file can be sent at all, and why not. */
    public SettlementTransmissionChannelStatus transmissionChannel() {
        return channels.status();
    }

    /**
     * Resolve a caller's optional {@code from}/{@code to} into a concrete, bounded window.
     * Either bound alone anchors the other; neither means the last {@link #DEFAULT_WINDOW_DAYS} days.
     *
     * @throws IllegalArgumentException when the window is inverted or wider than {@link #MAX_WINDOW_DAYS}
     */
    public static Window resolveWindow(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate end = to != null ? to : (from != null ? from.plusDays(DEFAULT_WINDOW_DAYS - 1L) : today);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from (" + start + ") is after to (" + end + ")");
        }
        long days = end.toEpochDay() - start.toEpochDay() + 1;
        if (days > MAX_WINDOW_DAYS) {
            throw new IllegalArgumentException(
                    "window of " + days + " days exceeds the " + MAX_WINDOW_DAYS + "-day maximum; "
                            + "request a narrower from/to");
        }
        return new Window(start, end);
    }

    /**
     * Persisted batches over a window, newest business date first.
     *
     * @param counterpartyId optional {@code settlement_batches.partner_id} filter (e.g. ZEROPAY).
     *                       This is the COUNTERPARTY, not a merchant — merchant scoping is
     *                       {@link #statement}.
     * @param status         optional {@code SettlementBatchStatus} filter (case-insensitive)
     * @param limit          &le;0 means unlimited within the window
     */
    public List<SettlementBatchSummaryResponse> batches(String counterpartyId,
                                                        Window window,
                                                        String status,
                                                        int limit) {
        List<SettlementBatchEntity> rows = counterpartyId == null || counterpartyId.isBlank()
                ? batchRepository.findByBusinessDateBetweenOrderByBusinessDateDescBatchIdAsc(
                        window.from(), window.to())
                : batchRepository.findByPartnerIdAndBusinessDateBetweenOrderByBusinessDateDescBatchIdAsc(
                        counterpartyId, window.from(), window.to());

        SettlementTransmissionChannelStatus channel = channels.status();
        return rows.stream()
                .filter(b -> status == null || status.isBlank()
                        || status.trim().equalsIgnoreCase(b.getStatus()))
                .limit(limit <= 0 ? Long.MAX_VALUE : limit)
                .map(b -> SettlementBatchSummaryResponse.from(b, channel))
                .toList();
    }

    /**
     * One persisted batch and its lines, or {@link Optional#empty()} when the id is unknown (the
     * controller maps that to 404). Unlike the old BFF behaviour this returns empty only for a genuinely
     * unknown id — never because the read surface does not exist.
     */
    public Optional<SettlementBatchDetailResponse> detail(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            return Optional.empty();
        }
        return batchRepository.findById(batchId).map(batch -> {
            List<SettlementBatchLineResponse> lines =
                    lineRepository.findByBatchIdOrderByIdAsc(batchId).stream()
                            .map(SettlementBatchLineResponse::from)
                            .toList();
            return SettlementBatchDetailResponse.of(
                    SettlementBatchSummaryResponse.from(batch, channels.status()), lines);
        });
    }

    /**
     * The partner-facing settlement statement for one merchant over a window.
     *
     * <p>Scoped by {@code settlement_lines.merchant_id}, because {@code settlement_batches.partner_id}
     * is the counterparty (ZEROPAY) and filtering on it would show one merchant every merchant's money.
     * Per-entry figures are the merchant's OWN lines summed — never the batch-level net, which spans
     * every merchant on the file.
     *
     * @param includeLines false for a summary-only statement (the list view); true for the drill-down
     */
    public PartnerSettlementStatementResponse statement(String merchantId,
                                                        Window window,
                                                        boolean includeLines) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new IllegalArgumentException("merchantId is required for a settlement statement");
        }
        SettlementTransmissionChannelStatus channel = channels.status();
        List<SettlementBatchEntity> batches =
                batchRepository.findForMerchantInWindow(merchantId, window.from(), window.to());

        List<PartnerSettlementStatementResponse.Entry> entries = new ArrayList<>();
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal clawed = BigDecimal.ZERO;
        int lineCount = 0;
        int openLines = 0;
        int transmitted = 0;
        String currency = null;

        for (SettlementBatchEntity batch : batches) {
            List<SettlementLineEntity> rows = lineRepository
                    .findByBatchIdAndMerchantIdOrderByIdAsc(batch.getBatchId(), merchantId);
            if (rows.isEmpty()) {
                continue;   // the EXISTS query said otherwise only if a line was deleted concurrently
            }
            BigDecimal entryNet = BigDecimal.ZERO;
            BigDecimal entryPaid = BigDecimal.ZERO;
            BigDecimal entryClawed = BigDecimal.ZERO;
            int entryOpen = 0;
            for (SettlementLineEntity l : rows) {
                BigDecimal amount = l.getAmount() == null ? BigDecimal.ZERO : l.getAmount();
                entryNet = entryNet.add(amount);
                if (amount.signum() < 0) {
                    entryClawed = entryClawed.add(amount.abs());
                } else {
                    entryPaid = entryPaid.add(amount);
                }
                if (!l.isMatched()) {
                    entryOpen++;
                }
                if (currency == null && l.getCurrency() != null) {
                    currency = l.getCurrency();
                }
            }
            if (batch.getTransmissionState().isSent()) {
                transmitted++;
            }
            entries.add(new PartnerSettlementStatementResponse.Entry(
                    SettlementBatchSummaryResponse.from(batch, channel),
                    entryNet, entryPaid, entryClawed, rows.size(), entryOpen,
                    includeLines ? rows.stream().map(SettlementBatchLineResponse::from).toList() : List.of()));

            net = net.add(entryNet);
            paid = paid.add(entryPaid);
            clawed = clawed.add(entryClawed);
            lineCount += rows.size();
            openLines += entryOpen;
        }

        return new PartnerSettlementStatementResponse(
                merchantId, window.from(), window.to(), currency,
                List.copyOf(entries), net, paid, clawed, lineCount, openLines, transmitted, channel);
    }

    /** Normalise a caller-supplied status filter, or null when absent. */
    public static String normaliseStatus(String status) {
        return status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
    }

    /** A resolved, bounded [from, to] business-date window. */
    public record Window(LocalDate from, LocalDate to) {}
}
