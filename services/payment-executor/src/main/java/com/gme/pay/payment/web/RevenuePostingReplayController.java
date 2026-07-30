package com.gme.pay.payment.web;

import com.gme.pay.payment.opsrun.LedgerOpsRunExecutor;
import com.gme.pay.payment.opsrun.LedgerOpsRunTrigger;
import com.gme.pay.payment.persistence.RevenuePostingFailureEntity;
import com.gme.pay.payment.persistence.RevenuePostingFailureRepository;
import com.gme.pay.payment.replay.RevenuePostingOutstandingQuery;
import com.gme.pay.payment.replay.RevenuePostingOutstandingView;
import com.gme.pay.payment.replay.RevenuePostingReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final RevenuePostingFailureRepository repository;

    public RevenuePostingReplayController(RevenuePostingOutstandingQuery outstandingQuery,
                                          RevenuePostingReplayService replayService,
                                          RevenuePostingFailureRepository repository) {
        this.outstandingQuery = outstandingQuery;
        this.replayService = replayService;
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
