package com.gme.pay.settlement.web;

import com.gme.pay.settlement.transmission.SettlementTransmissionChannelStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Reads of the <b>persisted</b> settlement record (GAP T4-5 / CPO audit P13).
 *
 * <pre>
 * GET /v1/settlements/batches?counterpartyId=&amp;from=&amp;to=&amp;status=&amp;limit=  — date-RANGED batch list
 * GET /v1/settlements/batches/{batchId}                                 — per-batch detail + lines (404 if unknown)
 * GET /v1/settlements/statement?merchantId=&amp;from=&amp;to=&amp;includeLines= — the PARTNER-FACING statement
 * GET /v1/settlements/transmission-channel                              — can anything be sent at all?
 * </pre>
 *
 * <h2>Why these exist</h2>
 * The service's only settlement read used to be {@code GET /v1/settlements} (see
 * {@link SettlementController}), which <em>recomputes</em> per-merchant figures from unbatched approved
 * transactions for a single date and reads neither {@code settlement_batches} nor
 * {@code settlement_lines}. Downstream, {@code ops-partner-bff}'s {@code RestSettlementClient}
 * therefore had to synthesise a {@code batchId} of {@code merchantId-date-type}, hardcode
 * {@code status=COMPLETED}, return {@code null} for every per-batch detail request, and could offer no
 * date range at all. All four of those were consequences of this gap in the read surface, not BFF bugs.
 *
 * <h2>Read-only, deliberately</h2>
 * Nothing here mutates a batch. Operator re-runs live on {@code BatchRerunController} /
 * {@code ReconRerunController} with their own {@code operatorId} + {@code reason} discipline, and
 * partner self-serve writes are an open product decision (T1-5).
 */
@RestController
@RequestMapping("/v1/settlements")
public class SettlementBatchController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SettlementBatchQueryService queryService;

    public SettlementBatchController(SettlementBatchQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Persisted batches over a business-date window, newest first.
     *
     * @param counterpartyId optional {@code partner_id} (the counterparty, e.g. ZEROPAY) filter
     * @param from           window start; defaults so that the window is the last 30 days
     * @param to             window end; defaults to today (KST) or to {@code from} + 29 days
     * @param status         optional lifecycle filter (PENDING/GENERATED/TRANSMITTED/RECEIVED/
     *                       RECONCILED/ERROR), case-insensitive
     * @param limit          max rows; {@code 0} or less means every batch in the window
     */
    @GetMapping("/batches")
    public List<SettlementBatchSummaryResponse> batches(
            @RequestParam(required = false) String counterpartyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "0") int limit) {

        return queryService.batches(counterpartyId, window(from, to),
                SettlementBatchQueryService.normaliseStatus(status), limit);
    }

    /**
     * One persisted batch and its {@code settlement_lines}.
     *
     * @return 200 with the detail; <b>404 only when the batch id is genuinely unknown</b> — no longer
     *         because no such read surface exists
     */
    @GetMapping("/batches/{batchId}")
    public SettlementBatchDetailResponse detail(@PathVariable String batchId) {
        return queryService.detail(batchId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no settlement batch with id " + batchId));
    }

    /**
     * The partner-facing settlement statement: one merchant's settled position over a window, summed
     * from the persisted lines of real batches, with each entry carrying its batch's real status and
     * honest transmission state.
     *
     * @param merchantId   required — a statement with no subject is meaningless, so this is never
     *                     defaulted to "all merchants"
     * @param includeLines true (default) to include the per-transaction rows
     */
    @GetMapping("/statement")
    public PartnerSettlementStatementResponse statement(
            @RequestParam String merchantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "true") boolean includeLines) {

        try {
            return queryService.statement(merchantId, window(from, to), includeLines);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Whether this deployment can transmit a settlement file at all — and when it cannot, why.
     * Mirrors {@code GET /v1/reports/filing-channels} in reporting-compliance (T5-2) so operators read
     * one shape for "generated" versus "actually sent" across both lanes.
     */
    @GetMapping("/transmission-channel")
    public SettlementTransmissionChannelStatus transmissionChannel() {
        return queryService.transmissionChannel();
    }

    private SettlementBatchQueryService.Window window(LocalDate from, LocalDate to) {
        try {
            return SettlementBatchQueryService.resolveWindow(from, to, LocalDate.now(KST));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
