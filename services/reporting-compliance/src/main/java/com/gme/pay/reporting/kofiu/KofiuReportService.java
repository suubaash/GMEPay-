package com.gme.pay.reporting.kofiu;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Application service: builds daily KoFIU CTR and STR report batches.
 *
 * <h2>CTR logic (FTRA Article 4)</h2>
 * <p>For each end-user, sum all KRW-equivalent transaction amounts whose KST
 * date equals {@code reportDate}. If the aggregate meets or exceeds the partner's
 * {@code ctrThresholdKrw} (default KRW 10,000,000 from V029 regulatory config),
 * a {@link CtrReport} is emitted. Aggregation is per end-user, not per partner.
 *
 * <h2>STR logic (FTRA Article 5)</h2>
 * <p>For each transaction whose corridor has {@code str_enabled = TRUE} (V029_1),
 * a {@link StrReport} is emitted. STR is per-transaction; no aggregation.
 *
 * <h2>Threshold semantics</h2>
 * <p>A CTR is triggered when {@code totalAmountKrw >= ctrThresholdKrw}
 * (inclusive, using {@link BigDecimal#compareTo}).
 */
@Service
public class KofiuReportService {

    /** Statutory KoFIU CTR default — used when regulatory config is absent. */
    static final BigDecimal DEFAULT_CTR_THRESHOLD = new BigDecimal("10000000");

    private final KofiuTransactionPort txnPort;
    private final RegulatoryConfigPort regulatoryConfigPort;
    private final CorridorConfigPort corridorConfigPort;

    public KofiuReportService(
            KofiuTransactionPort txnPort,
            RegulatoryConfigPort regulatoryConfigPort,
            CorridorConfigPort corridorConfigPort) {
        this.txnPort = Objects.requireNonNull(txnPort, "txnPort");
        this.regulatoryConfigPort = Objects.requireNonNull(regulatoryConfigPort,
                "regulatoryConfigPort");
        this.corridorConfigPort = Objects.requireNonNull(corridorConfigPort,
                "corridorConfigPort");
    }

    /**
     * Builds the full CTR + STR batch for a single KST calendar day.
     *
     * @param reportDate the KST date to report on; must not be null
     * @return batch containing all triggered CTR and STR reports for that day
     */
    public KofiuReportBatch buildDailyBatch(LocalDate reportDate) {
        Objects.requireNonNull(reportDate, "reportDate must not be null");

        List<KofiuTransaction> transactions = txnPort.fetchForKofiu(reportDate, reportDate);

        List<CtrReport> ctrReports = buildCtrReports(transactions, reportDate);
        List<StrReport> strReports = buildStrReports(transactions, reportDate);

        return new KofiuReportBatch(reportDate, ctrReports, strReports);
    }

    // -------------------------------------------------------------------------
    // CTR aggregation
    // -------------------------------------------------------------------------

    /**
     * Aggregates transactions by (endUserId, partnerId) and emits a
     * {@link CtrReport} for each group whose total meets or exceeds the threshold.
     */
    private List<CtrReport> buildCtrReports(
            List<KofiuTransaction> transactions,
            LocalDate reportDate) {

        // Key: endUserId + ":" + partnerId
        Map<String, CtrAccumulator> accumulators = new LinkedHashMap<>();

        for (KofiuTransaction txn : transactions) {
            if (!reportDate.equals(txn.kstDate())) {
                // Guard: skip transactions not on this KST date
                continue;
            }
            String key = txn.getEndUserId() + ":" + txn.getPartnerId();
            accumulators
                    .computeIfAbsent(key, k -> new CtrAccumulator(
                            txn.getEndUserId(), txn.getPartnerId()))
                    .add(txn);
        }

        List<CtrReport> reports = new ArrayList<>();
        for (CtrAccumulator acc : accumulators.values()) {
            BigDecimal threshold = regulatoryConfigPort
                    .findCtrThreshold(acc.partnerId)
                    .orElse(DEFAULT_CTR_THRESHOLD);

            // Trigger when total >= threshold (inclusive, FTRA Article 4)
            if (acc.total.compareTo(threshold) >= 0) {
                reports.add(new CtrReport(
                        acc.endUserId,
                        acc.partnerId,
                        reportDate,
                        acc.total,
                        acc.count,
                        acc.txnIds));
            }
        }
        return reports;
    }

    /** Mutable accumulator for per-(endUser, partner) CTR aggregation. */
    private static final class CtrAccumulator {
        final String endUserId;
        final long partnerId;
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        final List<Long> txnIds = new ArrayList<>();

        CtrAccumulator(String endUserId, long partnerId) {
            this.endUserId = endUserId;
            this.partnerId = partnerId;
        }

        void add(KofiuTransaction txn) {
            total = total.add(txn.getCollectionAmountKrw());
            count++;
            txnIds.add(txn.getTxnId());
        }
    }

    // -------------------------------------------------------------------------
    // STR — per-corridor gating
    // -------------------------------------------------------------------------

    /**
     * Emits one {@link StrReport} per transaction whose corridor has
     * {@code str_enabled = TRUE} (V029_1).
     */
    private List<StrReport> buildStrReports(
            List<KofiuTransaction> transactions,
            LocalDate reportDate) {

        List<StrReport> reports = new ArrayList<>();
        for (KofiuTransaction txn : transactions) {
            if (!reportDate.equals(txn.kstDate())) {
                continue;
            }
            boolean strEnabled = corridorConfigPort.isStrEnabled(
                    txn.getPartnerId(), txn.getSrcCcy(), txn.getDstCcy());
            if (strEnabled) {
                reports.add(new StrReport(
                        txn.getTxnId(),
                        txn.getTxnRef(),
                        txn.getEndUserId(),
                        txn.getPartnerId(),
                        reportDate,
                        txn.getCollectionAmountKrw(),
                        txn.getSrcCcy(),
                        txn.getDstCcy()));
            }
        }
        return reports;
    }
}
