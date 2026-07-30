package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.settlement.SettlementStatuses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Standalone-boot in-memory stub of {@link SettlementClient}.
 *
 * <h2>GAP T4-5: the fixture no longer says COMPLETED, or anything else that implies a transmission</h2>
 * Its three seeded batches used to carry {@code status="COMPLETED"} — a word absent from
 * settlement-reconciliation's own vocabulary, so it could only ever have been invented here. In stub
 * mode that fixture <em>is</em> what an operator sees, so it has to be honest in the same way the real
 * path is: statuses come from the real lifecycle vocabulary, and every batch carries
 * {@code NOT_TRANSMITTED_CHANNEL_UNAVAILABLE} with a reason, because no environment has a settlement
 * transmission channel. A stub that presented a sent settlement would reintroduce the exact defect the
 * service-side fix removed, one layer up.
 */
@Component
@ConditionalOnProperty(
        name = "gmepay.settlement-reconciliation.client",
        havingValue = "stub",
        matchIfMissing = true)
public class StubSettlementClient implements SettlementClient {

    /** The reason every stub row carries — the same fact the real service reports. */
    static final String NO_CHANNEL_REASON =
            "The BFF is in stub mode AND no settlement transmission channel exists in any environment: "
                    + "settlement files are generated and reconciled locally and are never sent to the "
                    + "scheme (scheme SFTP credentials + certification are externally gated).";

    private static final List<SettlementBatchSummary> STORE = List.of(
            // RECONCILED = the confirmation file tied out. It does NOT mean GMEPay+ sent anything.
            notTransmitted("ZP0061-20260608-MORNING", "partner_test_001",
                    LocalDate.of(2026, 6, 8), "USD", new BigDecimal("9875.42"), "RECONCILED"),
            // RECEIVED = a confirmation arrived but the batch still holds an open exception.
            notTransmitted("ZP0061-20260608-AFTERNOON", "partner_test_002",
                    LocalDate.of(2026, 6, 8), "KRW", new BigDecimal("12450000"), "RECEIVED"),
            // GENERATED = the file exists on disk and has gone precisely nowhere.
            notTransmitted("ZP0061-20260607-MORNING", "partner_test_001",
                    LocalDate.of(2026, 6, 7), "USD", new BigDecimal("11203.91"), "GENERATED"));

    /** Deterministic per-batch line set so the Admin drawer always has rows to render. */
    private static final Map<String, List<SettlementLine>> LINES = Map.of(
            "ZP0061-20260608-MORNING", List.of(
                    new SettlementLine("TXN-1001", new BigDecimal("125.50"), "USD", true),
                    new SettlementLine("TXN-1002", new BigDecimal("75.00"),  "USD", true),
                    new SettlementLine("TXN-1099", new BigDecimal("9.92"),   "USD", false)),
            "ZP0061-20260608-AFTERNOON", List.of(
                    new SettlementLine("TXN-1003", new BigDecimal("50000"), "KRW", true)),
            "ZP0061-20260607-MORNING", List.of(
                    new SettlementLine("TXN-0901", new BigDecimal("210.00"), "USD", true),
                    new SettlementLine("TXN-0902", new BigDecimal("310.00"), "USD", true)));

    private static SettlementBatchSummary notTransmitted(String batchId, String partnerId,
                                                         LocalDate date, String currency,
                                                         BigDecimal amount, String status) {
        return new SettlementBatchSummary(batchId, partnerId, date, currency, amount,
                SettlementStatuses.lifecycleFromUpstream(status),
                SettlementStatuses.NOT_TRANSMITTED_CHANNEL_UNAVAILABLE,
                NO_CHANNEL_REASON,
                null);   // never a send timestamp: nothing was sent
    }

    @Override
    public List<SettlementBatchSummary> recent(String partnerId, int limit) {
        return STORE.stream()
                .filter(b -> partnerId == null || partnerId.equals(b.partnerId()))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public List<SettlementBatchSummary> range(String partnerId, LocalDate from, LocalDate to, int limit) {
        return STORE.stream()
                .filter(b -> partnerId == null || partnerId.equals(b.partnerId()))
                .filter(b -> from == null || !b.settlementDate().isBefore(from))
                .filter(b -> to == null || !b.settlementDate().isAfter(to))
                .limit(limit <= 0 ? Long.MAX_VALUE : limit)
                .toList();
    }

    @Override
    public SettlementBatchDetail detail(String batchId) {
        SettlementBatchSummary batch = STORE.stream()
                .filter(b -> Objects.equals(b.batchId(), batchId))
                .findFirst()
                .orElse(null);
        if (batch == null) {
            return null;
        }
        List<SettlementLine> lines = LINES.getOrDefault(batchId, List.of());
        return new SettlementBatchDetail(batch, lines);
    }

    @Override
    public TransmissionChannel transmissionChannel() {
        return new TransmissionChannel(false,
                SettlementStatuses.NOT_TRANSMITTED_CHANNEL_UNAVAILABLE, NO_CHANNEL_REASON);
    }

    @Override
    public PartnerStatement statement(String partnerId, LocalDate from, LocalDate to, boolean includeLines) {
        List<StatementEntry> entries = new ArrayList<>();
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal clawed = BigDecimal.ZERO;
        int lineCount = 0;
        int openLines = 0;
        String currency = null;

        for (SettlementBatchSummary batch : range(partnerId, from, to, 0)) {
            List<SettlementLine> lines = LINES.getOrDefault(batch.batchId(), List.of());
            BigDecimal entryNet = BigDecimal.ZERO;
            BigDecimal entryPaid = BigDecimal.ZERO;
            BigDecimal entryClawed = BigDecimal.ZERO;
            int entryOpen = 0;
            for (SettlementLine l : lines) {
                entryNet = entryNet.add(l.amount());
                if (l.amount().signum() < 0) {
                    entryClawed = entryClawed.add(l.amount().abs());
                } else {
                    entryPaid = entryPaid.add(l.amount());
                }
                if (!l.matched()) {
                    entryOpen++;
                }
                if (currency == null) {
                    currency = l.currency();
                }
            }
            entries.add(new StatementEntry(batch, entryNet, entryPaid, entryClawed,
                    lines.size(), entryOpen, includeLines ? lines : List.of()));
            net = net.add(entryNet);
            paid = paid.add(entryPaid);
            clawed = clawed.add(entryClawed);
            lineCount += lines.size();
            openLines += entryOpen;
        }
        return new PartnerStatement(partnerId, from, to, currency, List.copyOf(entries),
                net, paid, clawed, lineCount, openLines,
                0,   // nothing in this fixture was transmitted, and it never will be
                transmissionChannel());
    }

    @Override
    public Integer openReconExceptions() {
        // Count the unmatched lines across all seeded batches (TXN-1099 is unmatched).
        return (int) LINES.values().stream()
                .flatMap(List::stream)
                .filter(l -> !l.matched())
                .count();
    }

    @Override
    public ReconRerunResult rerunRecon(String date, String actor, String reason) {
        long matched = LINES.values().stream().flatMap(List::stream).filter(SettlementLine::matched).count();
        long unmatched = LINES.values().stream().flatMap(List::stream).filter(l -> !l.matched()).count();
        return new ReconRerunResult("COMPLETED", (int) matched, (int) unmatched, "recon rerun accepted (stub)");
    }
}
