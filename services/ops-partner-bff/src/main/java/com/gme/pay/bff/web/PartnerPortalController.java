package com.gme.pay.bff.web;

import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.web.dto.PartnerOverview;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/**
 * Partner Self-Service Portal endpoints. Each method orchestrates 1-N calls to
 * backend services and returns a UI-shaped DTO scoped to the calling partner.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /v1/portal/{partnerId}/overview} — balance + recent activity counter + last settlement
 *   <li>{@code GET /v1/portal/{partnerId}/transactions} — paginated recent transactions
 *   <li>{@code GET /v1/portal/{partnerId}/balance} — prefunding balance view
 * </ul>
 */
@RestController
@RequestMapping("/v1/portal")
public class PartnerPortalController {

    /** Default number of transactions per portal page. */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** Hard cap to protect upstream from runaway page sizes. */
    static final int MAX_PAGE_SIZE = 100;

    private final TransactionMgmtClient transactions;
    private final PrefundingClient prefunding;
    private final SettlementClient settlement;

    public PartnerPortalController(
            TransactionMgmtClient transactions,
            PrefundingClient prefunding,
            SettlementClient settlement) {
        this.transactions = transactions;
        this.prefunding = prefunding;
        this.settlement = settlement;
    }

    @GetMapping("/{partnerId}/overview")
    public PartnerOverview overview(@PathVariable String partnerId) {
        PrefundingClient.BalanceView balance = prefunding.getBalance(partnerId);
        List<TransactionMgmtClient.TransactionSummary> recent =
                transactions.recent(partnerId, DEFAULT_PAGE_SIZE);
        List<SettlementClient.SettlementBatchSummary> batches =
                settlement.recent(partnerId, 1);

        LocalDate lastSettlementDate = batches.isEmpty()
                ? null
                : batches.get(0).settlementDate();

        return new PartnerOverview(partnerId, balance, recent.size(), lastSettlementDate);
    }

    @GetMapping("/{partnerId}/transactions")
    public List<TransactionMgmtClient.TransactionSummary> transactions(
            @PathVariable String partnerId,
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit) {
        int capped = Math.min(Math.max(1, limit), MAX_PAGE_SIZE);
        return transactions.recent(partnerId, capped);
    }

    @GetMapping("/{partnerId}/balance")
    public PrefundingClient.BalanceView balance(@PathVariable String partnerId) {
        PrefundingClient.BalanceView view = prefunding.getBalance(partnerId);
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no prefunding balance for partner " + partnerId);
        }
        return view;
    }
}
