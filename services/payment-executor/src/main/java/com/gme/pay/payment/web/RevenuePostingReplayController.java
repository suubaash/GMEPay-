package com.gme.pay.payment.web;

import com.gme.pay.payment.opsrun.LedgerOpsRunExecutor;
import com.gme.pay.payment.opsrun.LedgerOpsRunTrigger;
import com.gme.pay.payment.persistence.RevenuePostingFailureEntity;
import com.gme.pay.payment.persistence.RevenuePostingFailureRepository;
import com.gme.pay.payment.replay.RevenuePostingOutstandingQuery;
import com.gme.pay.payment.replay.RevenuePostingOutstandingView;
import com.gme.pay.payment.replay.RevenuePostingReplayService;
import com.gme.pay.payment.replay.RevenuePostingRequeueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Ops surface over the revenue-posting replay queue (gap <b>T2-5</b>).
 *
 * <pre>
 *   GET  /internal/ops/revenue-posting-failures            -- what is outstanding (aggregated headline)
 *   GET  /internal/ops/revenue-posting-failures/rows       -- the rows themselves, bounded
 *   POST /internal/ops/revenue-posting-failures/replay     -- run the sweep NOW
 *   POST /internal/ops/revenue-posting-failures/requeue    -- move POISON rows back to PENDING
 * </pre>
 *
 * <h2>Authorisation</h2>
 * <p>Mounted under {@code /internal/**}, which {@code SandboxSurfaceInternalAuthConfig} gates
 * <b>unconditionally</b> with the platform internal token ({@code X-Gme-Internal}) — including when no secret is
 * configured, in which case every caller is refused 401 (a blank configured secret can never equal a presented
 * one). Fail-closed: these rows carry transaction references and revenue amounts.
 *
 * <h2>Bounded by construction</h2>
 * <p>{@code /rows} clamps its limit and has no offset parameter, so no caller can walk the whole table — the
 * same shape as {@code OpsAlertQueryController}.
 */
@RestController
@RequestMapping("/internal/ops/revenue-posting-failures")
@Tag(name = "Revenue posting replay (internal)",
        description = "Postings that never reached revenue-ledger, and the job that drains them (gap T2-5)")
public class RevenuePostingReplayController {

    /** Hard ceiling on a single row query, whatever the caller asks for. */
    static final int MAX_ROWS = 500;

    /** Applied when the caller passes a non-positive limit. */
    static final int DEFAULT_ROWS = 50;

    private final RevenuePostingOutstandingQuery outstandingQuery;
    private final RevenuePostingReplayService replayService;
    private final RevenuePostingRequeueService requeueService;
    private final RevenuePostingFailureRepository repository;

    public RevenuePostingReplayController(RevenuePostingOutstandingQuery outstandingQuery,
                                          RevenuePostingReplayService replayService,
                                          RevenuePostingRequeueService requeueService,
                                          RevenuePostingFailureRepository repository) {
        this.outstandingQuery = outstandingQuery;
        this.replayService = replayService;
        this.requeueService = requeueService;
        this.repository = repository;
    }

    /** The headline: how much booked revenue is still absent from the ledger, and for how long. */
    @GetMapping
    @Operation(summary = "Revenue postings still missing from revenue-ledger (aggregated)")
    public RevenuePostingOutstandingView outstanding() {
        return outstandingQuery.outstanding();
    }

    /**
     * The rows, newest-updated first.
     *
     * @param status optional exact filter: {@code PENDING} | {@code REPLAYED} | {@code POISON} |
     *               {@code ABANDONED}
     * @param limit  page size; defaults to {@value #DEFAULT_ROWS}, hard-capped at {@value #MAX_ROWS}
     */
    @GetMapping("/rows")
    @Operation(summary = "Individual failed postings, bounded, newest first")
    public List<FailedPostingResponse> rows(@RequestParam(required = false) String status,
                                            @RequestParam(defaultValue = "50") int limit) {
        int capped = limit <= 0 ? DEFAULT_ROWS : Math.min(limit, MAX_ROWS);
        PageRequest page = PageRequest.of(0, capped);
        List<RevenuePostingFailureEntity> rows = (status == null || status.isBlank())
                ? repository.findAllByOrderByUpdatedAtDesc(page)
                : repository.findByStatusOrderByUpdatedAtDesc(status.trim().toUpperCase(), page);
        return rows.stream().map(FailedPostingResponse::from).toList();
    }

    /**
     * Run the replay sweep immediately, attributed to the caller.
     *
     * <p>Goes through exactly the same {@code LedgerOpsRunExecutor} wrapper as the scheduled run, so an
     * operator-triggered sweep is recorded in {@code ledger_ops_runs} and alerts on failure identically —
     * there is no "manual runs are invisible" second class.
     *
     * <p>Returns 200 with the sweep result on success and 500 with the failure on error. It does NOT throw:
     * the run is already recorded either way, and the operator needs to see which.
     */
    @PostMapping("/replay")
    @Operation(summary = "Run the replay sweep now (recorded and alerted like the scheduled run)")
    public ResponseEntity<?> replayNow(
            @RequestHeader(name = "X-Operator-Id", required = false) String operatorId) {
        LedgerOpsRunExecutor.RunResult<RevenuePostingReplayService.ReplaySweepResult> result =
                replayService.run(LedgerOpsRunTrigger.OPERATOR, operatorId);
        if (result.succeeded()) {
            return ResponseEntity.ok(result.value());
        }
        Throwable failure = result.failure();
        return ResponseEntity.internalServerError().body(java.util.Map.of(
                "error_code", "REPLAY_RUN_FAILED",
                "message", failure == null ? "replay run failed" : String.valueOf(failure),
                "ledgerOpsRunId", result.runId() == null ? "UNWRITTEN" : result.runId()));
    }

    /**
     * Move POISON rows back to PENDING with a fresh attempt budget, after the reason they were poisoned has
     * been fixed (gap T2-5 follow-up, opened by T3-12).
     *
     * <p><b>Why this endpoint exists.</b> {@code POISON} is terminal, and that was correct only as long as
     * the reason was a property of the row. Revenue-ledger's 406 content-negotiation defect made it a
     * property of the <em>server</em>: every {@code ROUNDING_RESIDUAL} and {@code REVERSAL_JOURNAL} posting
     * was poisoned on the first sweep for a bug that was later fixed, with no path back short of editing the
     * table in production. That is the immediate need this shape is cut to —
     * {@code {"postingTypes":["ROUNDING_RESIDUAL","REVERSAL_JOURNAL"],"reason":"..."}} — but it is general.
     *
     * <p><b>It does not replay.</b> Requeueing makes rows due; the sweep sends them. Keeping the two
     * separate means an operator can requeue, inspect what became PENDING, and only then trigger
     * {@code POST /replay} — rather than discovering the shape of a mass re-send after it has happened.
     *
     * <p>Returns 200 with the counts, 400 for a bad or absent selector, 500 if the audited run itself failed.
     */
    @PostMapping("/requeue")
    @Operation(summary = "Requeue POISON postings to PENDING (idempotent, audited, filtered)")
    public ResponseEntity<?> requeue(@RequestBody(required = false) RequeueRequest request,
                                     @RequestHeader(name = "X-Operator-Id", required = false) String operatorId) {
        RequeueRequest body = request == null ? RequeueRequest.EMPTY : request;
        LedgerOpsRunExecutor.RunResult<RevenuePostingRequeueService.RequeueResult> result;
        try {
            result = requeueService.requeue(body.postingTypes(), body.ids(), body.reason(), operatorId);
        } catch (RevenuePostingRequeueService.RequeueRejectedException rejected) {
            // A selector mistake is the CALLER's error, so it must not become a FAILED run row and a
            // CRITICAL ops alert — that would page someone for a typo.
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error_code", rejected.errorCode(),
                    "message", String.valueOf(rejected.getMessage())));
        }
        if (result.succeeded()) {
            return ResponseEntity.ok(result.value());
        }
        Throwable failure = result.failure();
        return ResponseEntity.internalServerError().body(java.util.Map.of(
                "error_code", "REQUEUE_RUN_FAILED",
                "message", failure == null ? "requeue run failed" : String.valueOf(failure),
                "ledgerOpsRunId", result.runId() == null ? "UNWRITTEN" : result.runId()));
    }

    /**
     * The requeue selectors.
     *
     * @param postingTypes posting types to requeue — the immediate need is
     *                     {@code ["ROUNDING_RESIDUAL","REVERSAL_JOURNAL"]}
     * @param ids          specific {@code revenue_posting_failures.id} values
     * @param reason       why, stamped onto each row's {@code last_error} and onto the audit row. Not
     *                     mandatory at the type level, because refusing the fix over a missing string would
     *                     be worse than recording "no reason given" — but the audit row is much less useful
     *                     without it.
     */
    public record RequeueRequest(List<String> postingTypes, List<Long> ids, String reason) {

        static final RequeueRequest EMPTY = new RequeueRequest(null, null, null);
    }

    /**
     * One failed posting. The stored request payload is deliberately NOT returned: it carries the full money
     * detail of the posting and the operator does not need it to act (the replay re-sends it), so exposing it
     * on a read surface would widen the blast radius of a leaked internal token for no operational gain.
     */
    public record FailedPostingResponse(
            Long id,
            String reference,
            String postingType,
            String status,
            int attempts,
            String lastError,
            Instant createdAt,
            Instant updatedAt,
            Instant lastAttemptAt,
            Instant nextAttemptAt,
            Instant replayedAt) {

        static FailedPostingResponse from(RevenuePostingFailureEntity e) {
            return new FailedPostingResponse(e.getId(), e.getReference(), e.getPostingType(), e.getStatus(),
                    e.getAttempts(), e.getLastError(), e.getCreatedAt(), e.getUpdatedAt(),
                    e.getLastAttemptAt(), e.getNextAttemptAt(), e.getReplayedAt());
        }
    }
}
