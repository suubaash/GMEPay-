package com.gme.pay.bff.web;

import com.gme.pay.bff.client.OperatorActionAuditClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.client.TransactionMgmtClient.TransactionSummary;
import com.gme.pay.bff.web.dto.Page;
import com.gme.pay.rbac.RbacHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 360° transaction search proxy + the audited operator resolve action.
 *
 * <ul>
 *   <li>{@code GET  /v1/admin/transactions/search?q=&status=&partnerId=&page=&size=}
 *       — proxies transaction-mgmt's free-text + facet search, returning the mapped
 *       result rows for the Ops drill-down.</li>
 *   <li>{@code POST /v1/admin/transactions/{ref}/resolve} — durably writes an operator-action
 *       audit record (fail-closed) then delegates to transaction-mgmt's resolve. Fail-closed
 *       RBAC via {@link OpsRbacGuard}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/transactions")
public class OpsTransactionController {

    private static final int DEFAULT_SIZE = 20;

    private final TransactionMgmtClient transactions;
    private final OperatorActionAuditClient audit;
    private final OpsRbacGuard rbac;

    public OpsTransactionController(TransactionMgmtClient transactions, OperatorActionAuditClient audit,
                                    OpsRbacGuard rbac) {
        this.transactions = transactions;
        this.audit = audit;
        this.rbac = rbac;
    }

    /**
     * 360° search proxy — mapped result page for the Ops / customer-support drill-down.
     *
     * <p>CS support-read: this is a READ endpoint, so it is gated on {@code txn.view}
     * (fail-closed) rather than the dangerous {@code ops:operate}. A support agent can
     * search by {@code userRef} (the customer's / wallet id) or {@code reference} (the
     * partner's own reference), in addition to {@code q} (free-text on txnRef),
     * {@code status} and {@code partnerId}. {@code userRef}/{@code reference} are
     * forwarded to transaction-mgmt's search; the formerly-dropped {@code q} is still
     * forwarded (transaction-mgmt matches it against txnRef).
     */
    @GetMapping("/search")
    public Page<TransactionSummary> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String userRef,
            @RequestParam(required = false) String reference,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        rbac.requireTxnView(permissions);
        int safeSize = size <= 0 ? DEFAULT_SIZE : size;
        TransactionMgmtClient.Page<TransactionSummary> upstream = transactions.search(
                new TransactionMgmtClient.SearchQuery(
                        q, partnerId, status, userRef, reference, Math.max(0, page), safeSize));
        if (upstream == null) {
            return new Page<>(java.util.List.of(), Math.max(0, page), safeSize, 0L);
        }
        return new Page<>(upstream.content(), upstream.page(), upstream.size(), upstream.total());
    }

    /** Audited operator resolution of an UNCERTAIN / stuck transaction. */
    @PostMapping("/{ref}/resolve")
    public TransactionSummary resolve(
            @PathVariable String ref,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal,
            @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        rbac.requireOps(permissions);
        String resolution = OpsActionController.str(body, "resolution");
        if (resolution == null || resolution.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "resolution is required");
        }
        String actor = OpsActionController.actor(principal);
        String reason = OpsActionController.reason(body);
        audit.recordDurable("transaction.resolve", ref, actor, reason);
        TransactionSummary result = transactions.resolve(ref, resolution, actor, reason);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "transaction " + ref + " not found");
        }
        return result;
    }
}
