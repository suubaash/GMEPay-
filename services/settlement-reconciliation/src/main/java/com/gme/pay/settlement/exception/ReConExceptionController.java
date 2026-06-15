package com.gme.pay.settlement.exception;

import com.gme.pay.settlement.recon.MatchStatus;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Exception management API (UC-04-03).
 *
 * <pre>
 * GET  /v1/settlement/exceptions                        — list, filterable
 * POST /v1/settlement/exceptions/{id}/resolve           — resolve with note + action
 * POST /v1/settlement/exceptions/{id}/re-run            — request a diff re-run
 * </pre>
 *
 * <p>All money amounts in responses are BigDecimal-as-string per MONEY_CONVENTION.md.
 */
@RestController
@RequestMapping("/v1/settlement/exceptions")
public class ReConExceptionController {

    private final ReConExceptionService service;

    public ReConExceptionController(ReConExceptionService service) {
        this.service = service;
    }

    /**
     * List recon exceptions.
     *
     * @param batchId         (optional) filter by settlement batch id
     * @param exceptionStatus (optional) filter by ops status: OPEN, RESOLVED, RE_RUN
     * @param matchStatus     (optional) filter by recon match type: DISCREPANCY, MISSING_SCHEME, MISSING_INTERNAL
     * @return list of exception DTOs
     */
    @GetMapping
    public List<ReconExceptionResponse> listExceptions(
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) ExceptionStatus exceptionStatus,
            @RequestParam(required = false) MatchStatus matchStatus) {

        return service.listExceptions(batchId, exceptionStatus, matchStatus);
    }

    /**
     * Resolve a specific exception.
     *
     * <p>Body: {@code { "operatorId": "ops@gmeremit.com", "note": "...", "resolutionAction": "MANUAL_OVERRIDE" }}
     *
     * @param id      exception row PK
     * @param request resolution details
     * @return updated exception DTO (200) or 404 if not found
     */
    @PostMapping("/{id}/resolve")
    public ResponseEntity<ReconExceptionResponse> resolve(
            @PathVariable Long id,
            @Valid @RequestBody ResolveExceptionRequest request) {

        try {
            ReconExceptionResponse response = service.resolve(id, request);
            return ResponseEntity.ok(response);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Request a recon re-run for the batch that contains this exception.
     *
     * <p>Body: {@code { "operatorId": "ops@gmeremit.com" }}
     *
     * @param id      exception row PK
     * @param request re-run request (operatorId required)
     * @return updated exception DTO (200) or 404 if not found
     */
    @PostMapping("/{id}/re-run")
    public ResponseEntity<ReconExceptionResponse> requestReRun(
            @PathVariable Long id,
            @RequestBody ReRunRequest request) {

        try {
            ReconExceptionResponse response = service.requestReRun(id, request.operatorId());
            return ResponseEntity.ok(response);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Simple request record for the re-run endpoint.
     *
     * <p>Field name {@code operatorId} is the canonical contract for the admin-ui.
     */
    public record ReRunRequest(String operatorId) {}
}
