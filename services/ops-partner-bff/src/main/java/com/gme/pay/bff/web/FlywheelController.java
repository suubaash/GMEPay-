package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PlatformSettingsClient;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.RevenueLedgerClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.web.dto.FlywheelDashboard;
import com.gme.pay.bff.web.dto.PlatformSettingView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PartnerView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Growth-loop KPI dashboard — {@code GET /v1/admin/flywheel?from&to}. One
 * UI-shaped payload carrying the 7 loop metrics from
 * {@code docs/QR_HUB_GROWTH_FLYWHEEL.md} §5 so the Admin UI Flywheel page makes
 * one call, mirroring {@code /v1/admin/dashboard} and {@code /v1/admin/delivery/overview}.
 *
 * <p>Sources per metric:
 * <ul>
 *   <li>Network sides — config-registry partner views (LIVE status) + scheme list
 *       (ACTIVE status); acceptance points from the {@code flywheel.acceptance_points}
 *       platform setting.</li>
 *   <li>TPV — sums {@code prefundingDeductedUsd} over APPROVED transactions in the
 *       window via the paged transactions search, capped at {@link #MAX_TPV_PAGES}
 *       pages with an explicit {@code tpvTruncated} flag.</li>
 *   <li>Take rate — revenue-ledger range summary ÷ TPV.</li>
 *   <li>Prefunding turn — window TPV ÷ current total prefund balance.</li>
 *   <li>Activation — onboardedAt → first APPROVED instant per partner (median hours),
 *       same join as the Delivery overview.</li>
 *   <li>Adapter time-to-live / monthly active payers — ops-entered platform settings
 *       until those facts have system-of-record feeds.</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin")
public class FlywheelController {

    /** Ops-entered platform-setting keys feeding metrics without a system feed yet. */
    static final String SETTING_ACCEPTANCE_POINTS = "flywheel.acceptance_points";
    static final String SETTING_ADAPTER_TTL_DAYS = "flywheel.adapter_time_to_live_days";
    static final String SETTING_ACTIVE_PAYERS = "flywheel.monthly_active_payers";

    /** Page size for the TPV scan over APPROVED transactions. */
    static final int TPV_PAGE_SIZE = 200;

    /** Hard cap on TPV scan pages (× {@link #TPV_PAGE_SIZE} = max rows summed). */
    static final int MAX_TPV_PAGES = 25;

    /** Default reporting window when {@code from} is omitted. */
    static final int DEFAULT_WINDOW_DAYS = 30;

    private final ConfigRegistryClient configRegistry;
    private final TransactionMgmtClient transactions;
    private final PrefundingClient prefunding;
    private final RevenueLedgerClient revenue;
    private final PlatformSettingsClient platformSettings;

    public FlywheelController(
            ConfigRegistryClient configRegistry,
            TransactionMgmtClient transactions,
            PrefundingClient prefunding,
            RevenueLedgerClient revenue,
            PlatformSettingsClient platformSettings) {
        this.configRegistry = configRegistry;
        this.transactions = transactions;
        this.prefunding = prefunding;
        this.revenue = revenue;
        this.platformSettings = platformSettings;
    }

    @GetMapping("/flywheel")
    public FlywheelDashboard flywheel(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {

        Instant resolvedTo = to == null ? Instant.now() : to;
        Instant resolvedFrom = from == null
                ? resolvedTo.minus(DEFAULT_WINDOW_DAYS, ChronoUnit.DAYS)
                : from;
        LocalDate fromDate = LocalDate.ofInstant(resolvedFrom, ZoneOffset.UTC);
        LocalDate toDate = LocalDate.ofInstant(resolvedTo, ZoneOffset.UTC);

        List<PartnerView> partnerViews = configRegistry.listPartnerViews();
        List<ConfigRegistryClient.SchemeSummary> schemes = configRegistry.listSchemes();
        Map<String, BigDecimal> settings = numericSettings();

        FlywheelDashboard.Network network = network(partnerViews, schemes, settings);
        TpvScan tpv = scanTpv(fromDate, toDate);
        FlywheelDashboard.Volume volume = volume(tpv, fromDate, toDate);
        FlywheelDashboard.Capital capital = capital(tpv.sumUsd);
        FlywheelDashboard.LoopHealth loopHealth = loopHealth(partnerViews, tpv, settings);

        return new FlywheelDashboard(
                new FlywheelDashboard.Window(resolvedFrom, resolvedTo),
                network, volume, capital, loopHealth);
    }

    private FlywheelDashboard.Network network(
            List<PartnerView> partnerViews,
            List<ConfigRegistryClient.SchemeSummary> schemes,
            Map<String, BigDecimal> settings) {
        int liveWallets = (int) partnerViews.stream()
                .filter(pv -> pv.status() == PartnerStatus.LIVE)
                .count();
        int liveSchemes = (int) schemes.stream()
                .filter(s -> "ACTIVE".equalsIgnoreCase(s.status()))
                .count();
        return new FlywheelDashboard.Network(
                liveWallets, partnerViews.size(),
                liveSchemes, schemes.size(),
                settings.get(SETTING_ACCEPTANCE_POINTS));
    }

    /** Accumulated result of the paged APPROVED-transactions scan. */
    private record TpvScan(BigDecimal sumUsd, long scanned, long total, boolean truncated) {}

    private TpvScan scanTpv(LocalDate fromDate, LocalDate toDate) {
        BigDecimal sum = BigDecimal.ZERO;
        long scanned = 0;
        long total = 0;
        for (int page = 0; page < MAX_TPV_PAGES; page++) {
            TransactionMgmtClient.Page<TransactionMgmtClient.TransactionSummary> p =
                    transactions.list(new TransactionMgmtClient.Filter(
                            null, null, "APPROVED", fromDate, toDate, page, TPV_PAGE_SIZE));
            total = p.total();
            if (p.content().isEmpty()) {
                break;
            }
            for (TransactionMgmtClient.TransactionSummary t : p.content()) {
                if (t.prefundingDeductedUsd() != null) {
                    sum = sum.add(t.prefundingDeductedUsd());
                }
            }
            scanned += p.content().size();
            if (scanned >= total) {
                break;
            }
        }
        return new TpvScan(sum, scanned, total, scanned < total);
    }

    private FlywheelDashboard.Volume volume(TpvScan tpv, LocalDate fromDate, LocalDate toDate) {
        RevenueLedgerClient.RevenueSummary summary = revenue.summaryRange(fromDate, toDate);
        BigDecimal revenueUsd = summary == null ? null : summary.totalRevenueUsd();

        BigDecimal takeRatePct = null;
        if (revenueUsd != null && tpv.sumUsd.signum() > 0) {
            takeRatePct = revenueUsd
                    .multiply(new BigDecimal("100"))
                    .divide(tpv.sumUsd, 2, RoundingMode.HALF_UP);
        }
        BigDecimal tpvUsd = tpv.scanned == 0 && tpv.total == 0 ? null : tpv.sumUsd;
        return new FlywheelDashboard.Volume(
                tpvUsd, tpv.truncated, tpv.scanned, tpv.total, revenueUsd, takeRatePct);
    }

    private FlywheelDashboard.Capital capital(BigDecimal tpvUsd) {
        BigDecimal totalPrefund = BigDecimal.ZERO;
        boolean any = false;
        for (ConfigRegistryClient.PartnerSummary partner : configRegistry.listPartners()) {
            PrefundingClient.BalanceView balance = prefunding.getBalance(partner.partnerId());
            if (balance != null && balance.balance() != null) {
                totalPrefund = totalPrefund.add(balance.balance());
                any = true;
            }
        }
        if (!any) {
            return new FlywheelDashboard.Capital(null, null);
        }
        BigDecimal turnRatio = totalPrefund.signum() > 0
                ? tpvUsd.divide(totalPrefund, 2, RoundingMode.HALF_UP)
                : null;
        return new FlywheelDashboard.Capital(totalPrefund, turnRatio);
    }

    private FlywheelDashboard.LoopHealth loopHealth(
            List<PartnerView> partnerViews, TpvScan tpv, Map<String, BigDecimal> settings) {
        Map<String, Instant> firstApproved = transactions.firstApprovedByPartner();

        List<Long> activationHours = new ArrayList<>();
        int pending = 0;
        for (PartnerView pv : partnerViews) {
            Instant onboardedAt = pv.validFrom();
            Instant firstApprovedAt = firstApproved.get(pv.partnerCode());
            if (onboardedAt != null && firstApprovedAt != null) {
                activationHours.add(ChronoUnit.HOURS.between(onboardedAt, firstApprovedAt));
            } else {
                pending++;
            }
        }
        Long medianHours = median(activationHours);

        BigDecimal activePayers = settings.get(SETTING_ACTIVE_PAYERS);
        BigDecimal txnsPerPayer = null;
        if (activePayers != null && activePayers.signum() > 0 && tpv.total > 0) {
            txnsPerPayer = BigDecimal.valueOf(tpv.total)
                    .divide(activePayers, 1, RoundingMode.HALF_UP);
        }
        return new FlywheelDashboard.LoopHealth(
                medianHours,
                activationHours.size(),
                pending,
                settings.get(SETTING_ADAPTER_TTL_DAYS),
                activePayers,
                txnsPerPayer);
    }

    private static Long median(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    /**
     * The {@code flywheel.*} settings parsed as numbers. A missing key or a
     * non-numeric value yields no entry — the metric renders as "not measured"
     * rather than a fake zero.
     */
    private Map<String, BigDecimal> numericSettings() {
        Map<String, BigDecimal> parsed = new java.util.HashMap<>();
        List<PlatformSettingView> all = platformSettings.list();
        if (all == null) {
            return parsed;
        }
        for (PlatformSettingView setting : all) {
            if (setting.key() == null || !setting.key().startsWith("flywheel.")) {
                continue;
            }
            try {
                parsed.put(setting.key(), new BigDecimal(setting.value().trim()));
            } catch (RuntimeException ignored) {
                // unset or non-numeric — leave the metric null
            }
        }
        return parsed;
    }
}
