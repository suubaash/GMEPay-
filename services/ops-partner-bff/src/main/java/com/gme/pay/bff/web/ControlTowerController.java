package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.OpsControlClient;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.SystemHealthClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.client.WebhookOpsClient;
import com.gme.pay.bff.web.dto.ControlTowerView;
import com.gme.pay.contracts.BalanceView;
import com.gme.pay.contracts.OperationalStatusView;
import com.gme.pay.contracts.PartnerView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ops control-tower — the single composed situational view for the on-call operator.
 * Fans out to the gated upstream REST clients and folds their answers into one
 * {@link ControlTowerView}. Read-only.
 *
 * <p><b>Degrade, never 500.</b> Every section is composed inside its own try/catch: a
 * section whose upstream is unavailable is emitted as "unknown" (null counts /
 * {@code UNKNOWN} status) and its name is appended to {@code degradedSections}. The
 * endpoint returns 200 with a partial tower rather than failing the whole page because
 * one backend is down.
 *
 * <p>Sections: in-flight txn count, UNCERTAIN/aged count, webhook backlog (PENDING+DLQ),
 * per-partner float headroom + the lowest/at-risk partner, scheme/partner health rollup,
 * open reconciliation exceptions, and the current {@link OperationalStatusView}.
 */
@RestController
@RequestMapping("/v1/admin/ops")
public class ControlTowerController {

    private static final Logger log = LoggerFactory.getLogger(ControlTowerController.class);

    /** States considered "in flight" (not yet terminal). */
    private static final List<String> IN_FLIGHT_STATES = List.of("AUTHORIZED", "PENDING", "PROCESSING");
    /** States needing operator attention. */
    private static final List<String> ATTENTION_STATES = List.of("UNCERTAIN");

    private final TransactionMgmtClient transactions;
    private final WebhookOpsClient webhooks;
    private final PrefundingClient prefunding;
    private final SystemHealthClient systemHealth;
    private final SettlementClient settlements;
    private final ConfigRegistryClient configRegistry;
    private final OpsControlClient opsControl;

    public ControlTowerController(TransactionMgmtClient transactions,
                                  WebhookOpsClient webhooks,
                                  PrefundingClient prefunding,
                                  SystemHealthClient systemHealth,
                                  SettlementClient settlements,
                                  ConfigRegistryClient configRegistry,
                                  OpsControlClient opsControl) {
        this.transactions = transactions;
        this.webhooks = webhooks;
        this.prefunding = prefunding;
        this.systemHealth = systemHealth;
        this.settlements = settlements;
        this.configRegistry = configRegistry;
        this.opsControl = opsControl;
    }

    @GetMapping("/control-tower")
    public ControlTowerView controlTower() {
        List<String> degraded = new ArrayList<>();

        ControlTowerView.InFlight inFlight = inFlight(degraded);
        ControlTowerView.WebhookBacklog backlog = webhookBacklog(degraded);
        ControlTowerView.FloatHeadroom headroom = floatHeadroom(degraded);
        ControlTowerView.Health health = health(degraded);
        Integer reconExceptions = reconExceptions(degraded);
        OperationalStatusView status = operationalStatus(degraded);

        return new ControlTowerView(inFlight, backlog, headroom, health, reconExceptions, status, degraded);
    }

    private ControlTowerView.InFlight inFlight(List<String> degraded) {
        try {
            long inFlightCount = 0;
            for (String s : IN_FLIGHT_STATES) {
                inFlightCount += countByStatus(s);
            }
            long attention = 0;
            for (String s : ATTENTION_STATES) {
                attention += countByStatus(s);
            }
            return new ControlTowerView.InFlight((int) inFlightCount, (int) attention);
        } catch (Exception e) {
            log.debug("control-tower: in-flight section unavailable ({})", e.toString());
            degraded.add("inFlight");
            return new ControlTowerView.InFlight(null, null);
        }
    }

    private long countByStatus(String status) {
        TransactionMgmtClient.Page<TransactionMgmtClient.TransactionSummary> page =
                transactions.search(new TransactionMgmtClient.SearchQuery(null, null, status, 0, 1));
        return page == null ? 0 : page.total();
    }

    private ControlTowerView.WebhookBacklog webhookBacklog(List<String> degraded) {
        try {
            WebhookOpsClient.WebhookBacklog b = webhooks.backlog();
            if (b == null || b.unknown()) {
                degraded.add("webhookBacklog");
                return new ControlTowerView.WebhookBacklog(null, null, null);
            }
            return new ControlTowerView.WebhookBacklog(b.pending(), b.dlq(), b.total());
        } catch (Exception e) {
            log.debug("control-tower: webhook backlog section unavailable ({})", e.toString());
            degraded.add("webhookBacklog");
            return new ControlTowerView.WebhookBacklog(null, null, null);
        }
    }

    private ControlTowerView.FloatHeadroom floatHeadroom(List<String> degraded) {
        try {
            List<PartnerView> partners = configRegistry.listPartnerViews();
            if (partners == null) {
                partners = List.of();
            }
            List<ControlTowerView.PartnerFloat> floats = new ArrayList<>();
            for (PartnerView p : partners) {
                String code = p.partnerCode();
                try {
                    BalanceView bal = prefunding.getAdminBalance(code);
                    if (bal == null) {
                        continue;
                    }
                    boolean atRisk = bal.balance() != null && bal.threshold() != null
                            && bal.balance().compareTo(bal.threshold()) <= 0;
                    floats.add(new ControlTowerView.PartnerFloat(
                            code, bal.currency(), bal.balance(), bal.threshold(),
                            bal.pctOfThreshold(), atRisk));
                } catch (Exception perPartner) {
                    log.debug("control-tower: balance unavailable for {} ({})", code, perPartner.toString());
                }
            }
            ControlTowerView.PartnerFloat lowest = floats.stream()
                    .filter(f -> f.pctOfThreshold() != null)
                    .min(Comparator.comparing(ControlTowerView.PartnerFloat::pctOfThreshold))
                    .orElse(null);
            return new ControlTowerView.FloatHeadroom(floats, lowest);
        } catch (Exception e) {
            log.debug("control-tower: float headroom section unavailable ({})", e.toString());
            degraded.add("floatHeadroom");
            return new ControlTowerView.FloatHeadroom(List.of(), null);
        }
    }

    private ControlTowerView.Health health(List<String> degraded) {
        try {
            SystemHealthClient.SystemHealth snap = systemHealth.check();
            if (snap == null || snap.services() == null) {
                degraded.add("health");
                return new ControlTowerView.Health(null, null, null, null, List.of());
            }
            int up = 0;
            int down = 0;
            int deg = 0;
            List<String> unhealthy = new ArrayList<>();
            for (SystemHealthClient.ServiceHealth s : snap.services()) {
                switch (s.status() == null ? "" : s.status()) {
                    case "UP" -> up++;
                    case "DOWN" -> {
                        down++;
                        unhealthy.add(s.name());
                    }
                    case "DEGRADED" -> {
                        deg++;
                        unhealthy.add(s.name());
                    }
                    default -> { /* UNKNOWN: neither healthy nor counted as down */ }
                }
            }
            return new ControlTowerView.Health(snap.services().size(), up, down, deg, unhealthy);
        } catch (Exception e) {
            log.debug("control-tower: health section unavailable ({})", e.toString());
            degraded.add("health");
            return new ControlTowerView.Health(null, null, null, null, List.of());
        }
    }

    private Integer reconExceptions(List<String> degraded) {
        try {
            Integer open = settlements.openReconExceptions();
            if (open == null) {
                degraded.add("openReconExceptions");
            }
            return open;
        } catch (Exception e) {
            log.debug("control-tower: recon exceptions section unavailable ({})", e.toString());
            degraded.add("openReconExceptions");
            return null;
        }
    }

    private OperationalStatusView operationalStatus(List<String> degraded) {
        try {
            OperationalStatusView v = opsControl.operationalStatus();
            return v == null ? OperationalStatusView.allClear() : v;
        } catch (Exception e) {
            log.debug("control-tower: operational-status section unavailable ({})", e.toString());
            degraded.add("operationalStatus");
            return OperationalStatusView.allClear();
        }
    }
}
